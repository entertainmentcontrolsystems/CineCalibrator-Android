package com.cinecalibrator.core

import android.os.Build
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PlanckianSweepEngine
 *
 * Closed-loop calibration sweep along the Planckian (black body) locus.
 *
 * For each CCT target, the engine:
 *  1. Computes an initial DMX recipe from the measured primary chromaticities
 *     using a simple colorimetric matrix solve.
 *  2. Sends that DMX, waits for the fixture to settle, measures with C-800.
 *  3. Adjusts the recipe to move toward the target CCT and reduce |Duv|.
 *  4. Iterates (up to MAX_ITERATIONS) until convergence.
 *  5. Records the best result.
 *
 * The output is a .pct.json file that MSxy (and any other program) can read
 * to map CCT requests to accurate DMX recipes without relying on the linear
 * colour matrix that EOS uses.
 *
 * Requires C-800 to be connected — the closed-loop feedback is the whole point.
 */
class PlanckianSweepEngine(
    private val dmxClient: DMXOverIPClient,
    private val sekonicSource: SekonicMeasurementSource,
    private val scanResult: ColorScience.ScanResult,
    private val config: ScanEngine.ScanConfig,
) {
    // ── Default CCT target series ─────────────────────────────────────────────
    // Every 100K from 1700K to 10000K — 84 points covering the full range
    // expected by broadcast and cinema workflows.
    val DEFAULT_CCT_TARGETS = (1700..10000 step 100).toList()

    private val MAX_ITERATIONS = 8
    private val CONVERGENCE_DUV = 0.008  // stop when |Duv| < this
    private val CONVERGENCE_CCT_DELTA = 80 // stop when |ΔCCT| < this K

    sealed class SweepProgress {
        data class Started(val totalTargets: Int, val estimatedMinutes: Int) : SweepProgress()
        data class TargetStarted(val targetCCT: Int, val index: Int, val total: Int) : SweepProgress()
        data class Iteration(
            val targetCCT: Int,
            val iteration: Int,
            val measuredCCT: Float,
            val measuredDuv: Float,
            val measuredCriRa: Float = 0f
        ) : SweepProgress()
        data class TargetComplete(val entry: PlanckianEntry) : SweepProgress()
        data class Completed(val table: PlanckianTable) : SweepProgress()
        data class Error(val message: String) : SweepProgress()
        object Cancelled : SweepProgress()
    }

    data class PlanckianEntry(
        val targetCCT: Int,
        val measuredCCT: Float,
        val measuredDuv: Float,
        val measuredCriRa: Float,
        /** CRI R1–R15 from the converged measurement */
        val measuredCriR: List<Float> = emptyList(),
        /** TM-30-18 Fidelity index (requires C-800 ME command) */
        val measuredTm30Rf: Float = 0f,
        /** TM-30-18 Gamut index */
        val measuredTm30Rg: Float = 0f,
        /** TLCI-2012 */
        val measuredTlci: Float = 0f,
        /** SSI tungsten reference */
        val measuredSsiT: Float = 0f,
        /** SSI daylight reference */
        val measuredSsiD: Float = 0f,
        val measuredX: Float,
        val measuredY: Float,
        val measuredLux: Float,
        /** Peak wavelength from SPD */
        val peakWavelength: Int = 0,
        val iterations: Int,
        val dmx: Map<String, Int>
    )

    data class PlanckianTable(
        val version: String = "1.0",
        val fixture: String,
        val manufacturer: String,
        val scanDate: String,
        val deviceModel: String,
        val channels: List<String>,
        val dimmerChannel: String,
        val targetDimmerDmx: Int,
        val entries: List<PlanckianEntry>
    )

    private val _progress = MutableSharedFlow<SweepProgress>(extraBufferCapacity = 64)
    val progress: SharedFlow<SweepProgress> = _progress

    private var sweepJob: Job? = null

    fun startSweep(
        cctTargets: List<Int> = DEFAULT_CCT_TARGETS,
        dimmerDmx: Int = 255,
        scope: CoroutineScope
    ) {
        sweepJob = scope.launch(Dispatchers.IO) {
            try {
                runSweep(cctTargets, dimmerDmx)
            } catch (e: CancellationException) {
                _progress.emit(SweepProgress.Cancelled)
                blackout()
            } catch (e: Exception) {
                _progress.emit(SweepProgress.Error("Sweep failed: ${e.message}"))
                blackout()
                Timber.e(e, "Planckian sweep error")
            }
        }
    }

    fun cancel() = sweepJob?.cancel()

    // ── Core sweep loop ───────────────────────────────────────────────────────

    private suspend fun runSweep(cctTargets: List<Int>, dimmerDmx: Int) {
        require(sekonicSource.isConnected()) { "C-800 must be connected for Planckian sweep" }

        _progress.emit(SweepProgress.Started(
            totalTargets = cctTargets.size,
            // ~4 iterations avg × 2× settle per shot, plus C-800 measure time (~3s)
            estimatedMinutes = (cctTargets.size * 4 * (config.settleTimeMs * 2 + 3000) / 60000).toInt().coerceAtLeast(1)
        ))
        blackout()
        delay(config.settleTimeMs)

        val diodes = config.diodeChannels
        val primaries = scanResult.measurements
        val channelNames = diodes.map { it.name }
        val entries = mutableListOf<PlanckianEntry>()

        cctTargets.forEachIndexed { idx, targetCCT ->
            currentCoroutineContext().ensureActive()
            _progress.emit(SweepProgress.TargetStarted(targetCCT, idx, cctTargets.size))

            // Start from a colorimetric matrix estimate
            var dmxRecipe = initialRecipe(targetCCT, dimmerDmx, primaries, diodes)
            var bestEntry: PlanckianEntry? = null
            var bestDuvAbs = Double.MAX_VALUE

            for (iteration in 1..MAX_ITERATIONS) {
                currentCoroutineContext().ensureActive()

                // Send DMX
                val dmxMap = buildDmxMap(dmxRecipe, dimmerDmx, diodes)
                dmxClient.setChannels(dmxMap)
                delay(config.settleTimeMs * 2)  // extra settle for blended readings

                // Measure
                val raw = sekonicSource.measureDiode(idx * MAX_ITERATIONS + iteration, "CCT${targetCCT}K it${iteration}")
                val sekonicRaw = sekonicSource.lastResult.value ?: continue

                val measuredCCT = sekonicRaw.cct
                val measuredDuv = sekonicRaw.deltaUv

                _progress.emit(SweepProgress.Iteration(
                    targetCCT, iteration, measuredCCT, measuredDuv, sekonicRaw.criRa))
                Timber.d("PCT [$targetCCT K] it$iteration: CCT=${measuredCCT}K Duv=${measuredDuv} Ra=${sekonicRaw.criRa}")

                // Record if best so far (lowest |Duv| wins)
                val duvAbs = Math.abs(measuredDuv.toDouble())
                val cctDelta = Math.abs((measuredCCT - targetCCT).toDouble())
                if (duvAbs < bestDuvAbs || bestEntry == null) {
                    bestDuvAbs = duvAbs
                    bestEntry = PlanckianEntry(
                        targetCCT      = targetCCT,
                        measuredCCT    = measuredCCT,
                        measuredDuv    = measuredDuv,
                        measuredCriRa  = sekonicRaw.criRa,
                        measuredCriR   = sekonicRaw.criR,
                        measuredTm30Rf = sekonicRaw.tm30Rf,
                        measuredTm30Rg = sekonicRaw.tm30Rg,
                        measuredTlci   = sekonicRaw.tlci,
                        measuredSsiT   = sekonicRaw.ssiT,
                        measuredSsiD   = sekonicRaw.ssiD,
                        measuredX      = sekonicRaw.cie1931x,
                        measuredY      = sekonicRaw.cie1931y,
                        measuredLux    = sekonicRaw.lux,
                        peakWavelength = sekonicRaw.peakWavelength,
                        iterations     = iteration,
                        dmx            = dmxRecipe.toMap()
                    )
                }

                // Converged?
                if (duvAbs < CONVERGENCE_DUV && cctDelta < CONVERGENCE_CCT_DELTA) {
                    Timber.d("PCT [$targetCCT K] converged at iteration $iteration")
                    break
                }

                // Adjust recipe toward target
                dmxRecipe = adjustRecipe(
                    dmxRecipe, diodes, primaries,
                    targetCCT, measuredCCT, measuredDuv, dimmerDmx
                )
            }

            bestEntry?.let {
                entries.add(it)
                _progress.emit(SweepProgress.TargetComplete(it))
            }

            blackout()
            delay(300)
        }

        val table = PlanckianTable(
            fixture         = scanResult.fixtureName,
            manufacturer    = scanResult.fixtureManufacturer,
            scanDate        = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
            deviceModel     = "${Build.MANUFACTURER} ${Build.MODEL}",
            channels        = channelNames,
            dimmerChannel   = config.dimmerChannel?.let {
                diodes.firstOrNull { d -> d.dmxChannel == it }?.name ?: "Dimmer"
            } ?: "Dimmer",
            targetDimmerDmx = 255,
            entries         = entries.sortedBy { it.targetCCT }
        )

        blackout()
        _progress.emit(SweepProgress.Completed(table))
    }

    // ── Initial recipe from colorimetric matrix ───────────────────────────────

    /**
     * Compute starting DMX values from measured primary chromaticities.
     * Uses a greedy warm-bias strategy: for warm CCTs, prefer warm emitters
     * (DR, R, RY) and explicitly set cool/green emitters to 0.
     * This avoids the linear solver's tendency to mix in Lime for warm whites.
     */
    private fun initialRecipe(
        targetCCT: Int,
        dimmerDmx: Int,
        primaries: List<ColorScience.DiodeMeasurement>,
        diodes: List<ScanEngine.DiodeChannel>
    ): MutableMap<String, Int> {
        val recipe = mutableMapOf<String, Int>()
        diodes.forEach { recipe[it.name] = 0 }

        // Target xy from Planckian locus
        val (tx, ty) = cctToXY(targetCCT.toDouble())

        // Classify emitters
        val warmEmitters = primaries.filter { m ->
            val name = m.diodeName.lowercase()
            name.contains("dr") || name.contains("deepred") ||
            name.contains("r") && !name.contains("ry") ||
            name.contains("ry") || name.contains("amber") || name.contains("a")
        }
        val coolEmitters = primaries.filter { m ->
            val name = m.diodeName.lowercase()
            name.contains("b") || name.contains("blue") ||
            name.contains("indigo") || name == "i" ||
            name.contains("uv")
        }
        val greenEmitters = primaries.filter { m ->
            val name = m.diodeName.lowercase()
            name.contains("g") && !name.contains("gy") ||
            name.contains("c") || name.contains("cyan")
        }
        val limeEmitters = primaries.filter { m ->
            val name = m.diodeName.lowercase()
            name.contains("gy") || name.contains("lime") || name.contains("l")
        }

        when {
            targetCCT <= 2000 -> {
                // Candlelight / firelight — deep reds only, everything else off
                val deepRed = primaries.filter { m ->
                    m.diodeName.lowercase().let { it.contains("dr") || it.contains("deepred") }
                }
                val warmRest = warmEmitters.filter { m ->
                    !m.diodeName.lowercase().let { it.contains("dr") || it.contains("deepred") }
                }
                deepRed.forEach { recipe[it.diodeName] = (dimmerDmx * 0.90).toInt() }
                warmRest.forEach { recipe[it.diodeName] = (dimmerDmx * 0.30).toInt() }
                coolEmitters.forEach { recipe[it.diodeName] = 0 }
                limeEmitters.forEach { recipe[it.diodeName] = 0 }
                greenEmitters.forEach { recipe[it.diodeName] = 0 }
            }
            targetCCT <= 3200 -> {
                // Tungsten / very warm — warm channels only, no green/lime at all
                warmEmitters.forEach { recipe[it.diodeName] = (dimmerDmx * 0.85).toInt() }
                coolEmitters.forEach { recipe[it.diodeName] = (dimmerDmx * 0.02).toInt() }
                limeEmitters.forEach { recipe[it.diodeName] = 0 }
                greenEmitters.forEach { recipe[it.diodeName] = 0 }
            }
            targetCCT <= 4500 -> {
                // Mid-range — warm dominant, small blue
                warmEmitters.forEach { recipe[it.diodeName] = (dimmerDmx * 0.65).toInt() }
                coolEmitters.forEach { recipe[it.diodeName] = (dimmerDmx * 0.12).toInt() }
                limeEmitters.forEach { recipe[it.diodeName] = 0 }  // still no lime
                greenEmitters.forEach { recipe[it.diodeName] = (dimmerDmx * 0.05).toInt() }
            }
            targetCCT <= 5600 -> {
                // Daylight — balance warm and cool
                warmEmitters.forEach { recipe[it.diodeName] = (dimmerDmx * 0.45).toInt() }
                coolEmitters.forEach { recipe[it.diodeName] = (dimmerDmx * 0.30).toInt() }
                limeEmitters.forEach { recipe[it.diodeName] = (dimmerDmx * 0.05).toInt() }
                greenEmitters.forEach { recipe[it.diodeName] = (dimmerDmx * 0.15).toInt() }
            }
            else -> {
                // Cool/HMI — blue-leaning
                warmEmitters.forEach { recipe[it.diodeName] = (dimmerDmx * 0.25).toInt() }
                coolEmitters.forEach { recipe[it.diodeName] = (dimmerDmx * 0.50).toInt() }
                limeEmitters.forEach { recipe[it.diodeName] = (dimmerDmx * 0.10).toInt() }
                greenEmitters.forEach { recipe[it.diodeName] = (dimmerDmx * 0.20).toInt() }
            }
        }

        return recipe.mapValues { (_, v) -> v.coerceIn(0, 255) }.toMutableMap()
    }

    /**
     * Adjust the DMX recipe based on measured CCT and Duv error.
     *
     * CCT too warm  → reduce warm channels, increase cool
     * CCT too cool  → increase warm channels, reduce cool
     * Duv positive  → too green — reduce green/lime channels
     * Duv negative  → too magenta — allow small increase in green/lime
     */
    private fun adjustRecipe(
        current: Map<String, Int>,
        diodes: List<ScanEngine.DiodeChannel>,
        primaries: List<ColorScience.DiodeMeasurement>,
        targetCCT: Int,
        measuredCCT: Float,
        measuredDuv: Float,
        dimmerDmx: Int
    ): MutableMap<String, Int> {
        val recipe = current.toMutableMap()

        val cctError = measuredCCT - targetCCT   // positive = too warm, need more blue
        val stepSize = (dimmerDmx * 0.12).toInt().coerceAtLeast(8)

        val warmNames = setOf("dr","r","ry","a","amber","red","deepred","warm")
        val coolNames = setOf("b","blue","i","indigo","uv")
        val greenNames = setOf("g","green","c","cyan")
        val limeNames = setOf("gy","l","lime","greenyellow")

        fun classify(name: String): String {
            val n = name.lowercase().replace(" ","")
            return when {
                limeNames.any { n.contains(it) } -> "lime"
                greenNames.any { n.contains(it) } -> "green"
                coolNames.any { n.contains(it) } -> "cool"
                warmNames.any { n.contains(it) } -> "warm"
                else -> "neutral"
            }
        }

        diodes.forEach { diode ->
            val cls = classify(diode.name)
            val cur = recipe[diode.name] ?: 0
            val adjustment = when {
                // CCT correction
                cctError > CONVERGENCE_CCT_DELTA && cls == "cool" -> +stepSize
                cctError > CONVERGENCE_CCT_DELTA && cls == "warm" -> -stepSize
                cctError < -CONVERGENCE_CCT_DELTA && cls == "warm" -> +stepSize
                cctError < -CONVERGENCE_CCT_DELTA && cls == "cool" -> -stepSize
                // Duv (green-magenta) correction
                measuredDuv > CONVERGENCE_DUV && cls == "lime"  -> -(stepSize * 2)  // aggressive lime reduction
                measuredDuv > CONVERGENCE_DUV && cls == "green" -> -stepSize
                measuredDuv < -CONVERGENCE_DUV && cls == "cool" -> +(stepSize / 2)
                else -> 0
            }
            recipe[diode.name] = (cur + adjustment).coerceIn(0, 255)
        }

        return recipe
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildDmxMap(
        recipe: Map<String, Int>,
        dimmerDmx: Int,
        diodes: List<ScanEngine.DiodeChannel>
    ): Map<Int, Int> {
        val map = mutableMapOf<Int, Int>()
        config.dimmerChannel?.let { map[it] = dimmerDmx }
        diodes.forEach { d -> map[d.dmxChannel] = recipe[d.name] ?: 0 }
        // Hold control channels (StrobeModeShutter, DimmerCurve, Fans) at their
        // original defaults — NOT zero. Zero closes the shutter.
        config.controlChannels.forEach { map[it.dmxChannel] = it.defaultValue }
        return map
    }

    private suspend fun blackout() {
        val map = mutableMapOf<Int, Int>()
        config.dimmerChannel?.let { map[it] = 0 }
        config.diodeChannels.forEach { map[it.dmxChannel] = 0 }
        // Keep control channels at default even during blackout
        config.controlChannels.forEach { map[it.dmxChannel] = it.defaultValue }
        dmxClient.setChannels(map)
    }

    /**
     * CIE xy from CCT for 1667K – 25000K.
     * 1667–2856K: McCamy approximation via CIE 1960 UCS
     * 2856K+:     Kang et al. 2002 polynomial
     */
    private fun cctToXY(cct: Double): Pair<Double, Double> {
        if (cct < 2856.0) {
            // Use Planckian locus directly via CIE 1960 u,v → xy
            // Simplified McCamy: u = 0.860117757 + ... (valid 1667–25000K)
            val u = (0.860117757 + 1.54118254e-4 * cct + 1.28641212e-7 * cct * cct) /
                    (1.0 + 8.42420235e-4 * cct + 7.08145163e-7 * cct * cct)
            val v = (0.317398726 + 4.22806245e-5 * cct + 4.20481691e-8 * cct * cct) /
                    (1.0 - 2.89741816e-5 * cct + 1.61456053e-7 * cct * cct)
            // CIE 1960 u,v → CIE 1931 x,y
            val x = 3.0 * u / (2.0 * u - 8.0 * v + 4.0)
            val y = 2.0 * v / (2.0 * u - 8.0 * v + 4.0)
            return Pair(x, y)
        }
        // Kang et al. 2002 (2856K – 25000K)
        val x = when {
            cct <= 4000 ->
                -0.2661239e9/(cct*cct*cct) - 0.2343589e6/(cct*cct) + 0.8776956e3/cct + 0.179910
            else ->
                -3.0258469e9/(cct*cct*cct) + 2.1070379e6/(cct*cct) + 0.2226347e3/cct + 0.240390
        }
        val y = when {
            cct <= 2222 ->
                -1.1063814*x*x*x - 1.34811020*x*x + 2.18555832*x - 0.20219683
            cct <= 4000 ->
                -0.9549476*x*x*x - 1.37418593*x*x + 2.09137015*x - 0.16748867
            else ->
                3.0817580*x*x*x - 5.87338670*x*x + 3.75112997*x - 0.37001483
        }
        return Pair(x, y)
    }

    // ── JSON serialisation ────────────────────────────────────────────────────

    fun tableToJson(table: PlanckianTable): String =
        GsonBuilder().setPrettyPrinting().create().toJson(table)

    fun tableFromJson(json: String): PlanckianTable =
        Gson().fromJson(json, PlanckianTable::class.java)
}
