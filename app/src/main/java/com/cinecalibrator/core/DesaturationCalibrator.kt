package com.cinecalibrator.core

import kotlinx.coroutines.*
import timber.log.Timber

/**
 * DesaturationCalibrator
 *
 * Implements the desaturation-path camera calibration strategy.
 *
 * For each primary emitter:
 *   1. Start at full saturation (primary 255, white 0)
 *   2. Step through ~16 blend points toward D65 white
 *      (primary decreases, white channel increases)
 *   3. At each step:
 *      - Set DMX levels via the provided sendDMX callback
 *      - Wait for light to stabilise
 *      - Sample the camera (averaged frames)
 *      - Compute physically-predicted xy using linear XYZ mixing law
 *      - Record (measured, predicted) pair
 *   4. Filter out unreliable steps (clipping, jumpy)
 *   5. Solve CCM from reliable pairs
 *
 * The "white" reference comes from a channel combination that produces ~D65.
 * For the Fos/4 PL8: using Lime + Cyan + Amber blended to D65 works well.
 * Fallback: use all channels simultaneously at equal levels.
 */
class DesaturationCalibrator(
    private val cameraManager: CameraManager,
    private val dmxClient: DMXOverIPClient
) {

    companion object {
        /** Number of steps along each primary→white desaturation path */
        const val DESAT_STEPS = 16
        /** Minimum white fraction before we consider measurements reliable */
        const val RELIABILITY_THRESHOLD = 0.15   // lowered from 0.25 — peak DMX cap prevents clipping
    }

    data class CalibConfig(
        val reference: CameraCalibration.ReferenceFixture,
        /** Map of emitter name → DMX channel (1-indexed) */
        val channelMap: Map<String, Int>,
        /** DMX channel for master dimmer, or null */
        val dimmerChannel: Int?,
        val dmxUniverse: Int = 1,
        val settleTimeMs: Long = 400,
        val framesPerSample: Int = 12,
        val roiCenterX: Float = 0.5f,
        val roiCenterY: Float = 0.5f,
        /**
         * Which channels to use as the "white" reference during desaturation.
         * If empty, all channels are used at equal levels.
         * For Fos/4: ["Lime", "Cyan", "Amber"] approximates D65.
         */
        val whiteChannelNames: List<String> = emptyList()
    )

    sealed class CalibProgress {
        data class StartingPrimary(val name: String, val index: Int, val total: Int) : CalibProgress()
        data class StepProgress(val primaryName: String, val step: Int, val totalSteps: Int,
                                val whiteFrac: Double, val predictedXY: CameraCalibration.CIExy,
                                val measuredXY: CameraCalibration.CIExy?,
                                val isReliable: Boolean) : CalibProgress()
        /** Emitted during the per-emitter exposure ramp before the desat sweep starts */
        data class RampProgress(val emitterName: String, val safePeakDmx: Int) : CalibProgress()
        data class PrimaryComplete(val name: String, val reliableSteps: Int) : CalibProgress()
        data class CalibrationComplete(val cal: CameraCalibration.CalibrationMatrix) : CalibProgress()
        /**
         * Not enough reliable samples to compute a CCM, but we still have
         * the raw measured data. The UI uses [allSteps] to visualise how far the
         * camera is from the reference — showing WHY calibration failed.
         */
        data class InsufficientData(
            val totalCollected: Int,
            val reliableCount: Int,
            val allSteps: List<CameraCalibration.DesatStep>,
            val message: String
        ) : CalibProgress()
        data class Error(val message: String) : CalibProgress()
    }

    private val allSteps = mutableListOf<CameraCalibration.DesatStep>()

    /**
     * Run the full desaturation calibration scan.
     * Reports progress via [onProgress] callbacks on the calling coroutine.
     */
    suspend fun runCalibration(
        config: CalibConfig,
        onProgress: suspend (CalibProgress) -> Unit
    ): CameraCalibration.CalibrationMatrix? {
        allSteps.clear()

        // Determine white channel levels
        val whiteChannels = determineWhiteChannels(config)
        val whiteYWeight = estimateWhiteYWeight(config, whiteChannels)

        val emitters = config.reference.emitters
        var totalSamples = 0

        emitters.forEachIndexed { idx, emitter ->
            val primaryCh = config.channelMap[emitter.name]
                ?: config.channelMap.entries.firstOrNull { (k, _) ->
                    matchesName(k, emitter.name)
                }?.value

            if (primaryCh == null) {
                Timber.w("No DMX channel found for emitter ${emitter.name} — skipping")
                return@forEachIndexed
            }

            onProgress(CalibProgress.StartingPrimary(emitter.name, idx, emitters.size))

            val primarySteps = scanPrimaryDesaturation(
                emitter = emitter,
                primaryChannel = primaryCh,
                whiteChannels = whiteChannels,
                whiteYWeight = whiteYWeight,
                config = config,
                isFirstEmitter = (idx == 0),
                onProgress = onProgress
            )

            allSteps.addAll(primarySteps)
            totalSamples += primarySteps.count { it.isReliable }
            onProgress(CalibProgress.PrimaryComplete(emitter.name, primarySteps.count { it.isReliable }))
        }

        // Blackout after scan
        blackout(config)

        if (totalSamples < 3) {
            onProgress(CalibProgress.InsufficientData(
                totalCollected = allSteps.size,
                reliableCount  = totalSamples,
                allSteps       = allSteps.toList(),
                message        = "Only $totalSamples reliable samples — showing camera vs reference deviation below."
            ))
            return null
        }

        val cal = CameraCalibration.computeCalibrationFromDesatSteps(
            allSteps,
            "${config.reference.manufacturer} ${config.reference.model}"
        )

        if (cal == null) {
            onProgress(CalibProgress.Error("Matrix solve failed — collinear data points. Try a different fixture or more steps."))
            return null
        }

        onProgress(CalibProgress.CalibrationComplete(cal))
        return cal
    }

    // ─── Scan one primary along desaturation path ─────────────────────────────────

    private suspend fun scanPrimaryDesaturation(
        emitter: CameraCalibration.ReferenceEmitter,
        primaryChannel: Int,
        whiteChannels: Map<Int, Double>,  // ch → relative level weight
        whiteYWeight: Double,
        config: CalibConfig,
        isFirstEmitter: Boolean = false,
        onProgress: suspend (CalibProgress) -> Unit
    ): List<CameraCalibration.DesatStep> {

        val steps = mutableListOf<CameraCalibration.DesatStep>()

        // For very bright emitters, starting at full 255 blows out the camera immediately
        // and all early steps are discarded as clipping. Cap the peak DMX level inversely
        // proportional to the emitter's luminance relative to a moderate reference (300 fc).
        // e.g. Lime at 2260 fc → peak ≈ 255 * (300/2260)^0.5 ≈ 90
        //      Red  at  350 fc → peak ≈ 255 * (300/350)^0.5 ≈ 236
        //      Blue at  256 fc → peak = 255 (below reference, no cap)
        // ── Measure actual non-clipping peak DMX for this emitter ────────────
        // Ramp DMX from low to high in coarse steps and find the last level before
        // the camera clips. This is more accurate than the sqrt(300fc/emitter_fc)
        // formula and adapts to the actual camera and ambient conditions.
        val peakDmx = measureSafePeakDmx(
            primaryChannel = primaryChannel,
            config = config,
            emitterName = emitter.name
        )
        Timber.d("${emitter.name}: measured safe peakDmx=$peakDmx")
        onProgress(CalibProgress.RampProgress(emitter.name, peakDmx))

        for (stepIdx in 0 until DESAT_STEPS) {
            currentCoroutineContext().ensureActive()

            val t = stepIdx.toDouble() / (DESAT_STEPS - 1).toDouble()
            val primaryFrac = 1.0 - t * 0.85  // goes from 1.0 down to 0.15
            val whiteFrac   = t                 // goes from 0.0 up to 1.0

            // Apply peak cap so bright emitters don't immediately clip the camera
            val primaryDmx = (primaryFrac * peakDmx).toInt().coerceIn(0, 255)

            val dmxMap = mutableMapOf<Int, Int>()
            config.dimmerChannel?.let { dmxMap[it] = 255 }
            config.channelMap.values.forEach { ch -> dmxMap[ch] = 0 }
            dmxMap[primaryChannel] = primaryDmx
            whiteChannels.forEach { (ch, weight) ->
                if (ch != primaryChannel) {
                    dmxMap[ch] = (whiteFrac * weight * 255).toInt().coerceIn(0, 255)
                }
            }

            dmxClient.setChannels(dmxMap)

            // The very first step of the very first emitter fires in a dark room.
            // The camera AE needs extra time to converge from darkness before we
            // take any readings. 4× settle on step 0, 2× on step 1, normal after.
            val actualSettle = when {
                isFirstEmitter && stepIdx == 0 -> {
                    Timber.d("First step in dark room — extended settle ${config.settleTimeMs * 4}ms")
                    config.settleTimeMs * 4
                }
                isFirstEmitter && stepIdx == 1 -> config.settleTimeMs * 2
                else -> config.settleTimeMs
            }
            delay(actualSettle)

            // Sample camera
            val sample = cameraManager.sampleROIAveraged(
                config.roiCenterX, config.roiCenterY, config.framesPerSample
            )

            if (sample == null) {
                Timber.w("No camera sample at step $stepIdx for ${emitter.name}")
                continue
            }

            // Camera measured xy
            val (linR, linG, linB) = ColorScience.pixel8bitToLinearRGB(sample.avgR, sample.avgG, sample.avgB)
            val (X, Y, Z) = ColorScience.linearRGBtoXYZ(linR, linG, linB)
            val measuredXY = CameraCalibration.CIExy(
                ColorScience.xyzToCIExy(X, Y, Z).x,
                ColorScience.xyzToCIExy(X, Y, Z).y
            )
            val measuredY = Y

            // Physically predicted xy via linear XYZ mixing
            val predictedXY = CameraCalibration.predictBlendXY(
                primary = emitter,
                primaryFrac = primaryFrac,
                whiteFrac = whiteFrac,
                whiteYWeight = whiteYWeight
            )

            // Reliability: not clipping AND white fraction is above threshold
            val isReliable = !sample.isClipped && whiteFrac >= RELIABILITY_THRESHOLD && Y > 0.001

            val step = CameraCalibration.DesatStep(
                primaryName  = emitter.name,
                primaryLevel = primaryDmx,
                whiteLevel   = (whiteFrac * 255).toInt(),
                predictedXY  = predictedXY,
                measuredXY   = measuredXY,
                measuredLuminance = measuredY,
                isReliable   = isReliable
            )
            steps.add(step)

            onProgress(CalibProgress.StepProgress(
                primaryName = emitter.name,
                step = stepIdx,
                totalSteps = DESAT_STEPS,
                whiteFrac = whiteFrac,
                predictedXY = predictedXY,
                measuredXY = measuredXY,
                isReliable = isReliable
            ))

            Timber.d("[${emitter.name} step $stepIdx t=${"%.2f".format(t)}] " +
                "pred=(%.4f,%.4f) meas=(%.4f,%.4f) reliable=$isReliable".format(
                predictedXY.x, predictedXY.y, measuredXY.x, measuredXY.y))
        }

        return steps
    }

    // ─── White channel detection ──────────────────────────────────────────────────

    /**
     * Determine which channels to use as the "white" reference.
     * Returns a map of channel→relative weight.
     *
     * Strategy:
     *  1. If config specifies white channel names, use those
     *  2. Otherwise, find channels whose combined xy is near D65
     *  3. Fallback: use all channels at equal weight
     */
    private fun determineWhiteChannels(config: CalibConfig): Map<Int, Double> {
        if (config.whiteChannelNames.isNotEmpty()) {
            val result = mutableMapOf<Int, Double>()
            config.whiteChannelNames.forEach { name ->
                val ch = config.channelMap.entries.firstOrNull { (k, _) -> matchesName(k, name) }?.value
                if (ch != null) result[ch] = 1.0
            }
            if (result.isNotEmpty()) return result
        }

        // Try to find "white", "warmwhite", "coolwhite" channels
        val whitishNames = listOf("white", "warmwhite", "coolwhite", "ww", "cw", "w")
        val whiteChans = config.channelMap.entries
            .filter { (k, _) -> whitishNames.any { k.lowercase().replace(" ", "").contains(it) } }
            .associate { (_, v) -> v to 1.0 }

        if (whiteChans.isNotEmpty()) return whiteChans

        // Fallback: all channels at equal weight
        return config.channelMap.values.associateWith { 1.0 }
    }

    /**
     * Estimate the luminance weight of the white combination.
     * Uses the sum of luminance weights of selected white channels.
     */
    private fun estimateWhiteYWeight(config: CalibConfig, whiteChannels: Map<Int, Double>): Double {
        if (whiteChannels.isEmpty()) return 1.0
        // Find emitters corresponding to these channels and sum their fc values
        val chToEmitter = buildChannelToEmitterMap(config)
        return whiteChannels.keys.sumOf { ch ->
            chToEmitter[ch]?.luminanceWeight ?: 1.0
        }.coerceAtLeast(1.0)
    }

    private fun buildChannelToEmitterMap(config: CalibConfig): Map<Int, CameraCalibration.ReferenceEmitter> {
        return config.reference.emitters.mapNotNull { emitter ->
            val ch = config.channelMap.entries
                .firstOrNull { (k, _) -> matchesName(k, emitter.name) }?.value
            if (ch != null) ch to emitter else null
        }.toMap()
    }

    /**
     * Ramp a single emitter from dim to full and find the highest DMX level
     * where the camera doesn't clip. Returns that as the safe peak for the
     * desaturation sweep.
     *
     * Ramp steps: 20, 40, 60, 80, 100, 120, 140, 160, 180, 200, 220, 240, 255
     * Stops at the first clipping detection and backs off one step.
     * The result is cached per emitter name so it's only measured once per session.
     */
    fun getSafePeakDmxMap(): Map<String, Int> = safePeakDmxCache.toMap()

    private val safePeakDmxCache = mutableMapOf<String, Int>()

    private suspend fun measureSafePeakDmx(
        primaryChannel: Int,
        config: CalibConfig,
        emitterName: String
    ): Int {
        safePeakDmxCache[emitterName]?.let { return it }

        val rampLevels = listOf(20, 40, 60, 80, 100, 120, 140, 160, 180, 200, 220, 240, 255)
        var safePeak = 60  // conservative fallback

        for (level in rampLevels) {
            val dmxMap = mutableMapOf<Int, Int>()
            config.dimmerChannel?.let { dmxMap[it] = 255 }
            config.channelMap.values.forEach { dmxMap[it] = 0 }
            dmxMap[primaryChannel] = level
            dmxClient.setChannels(dmxMap)
            delay(config.settleTimeMs)

            val sample = cameraManager.sampleROIAveraged(
                config.roiCenterX, config.roiCenterY, 4
            ) ?: continue

            if (sample.isClipped) {
                Timber.d("$emitterName: clipping detected at DMX $level — safe peak = $safePeak")
                break
            }
            safePeak = level
        }

        // Blackout between emitters
        val dmxMap = mutableMapOf<Int, Int>()
        config.dimmerChannel?.let { dmxMap[it] = 255 }
        config.channelMap.values.forEach { dmxMap[it] = 0 }
        dmxClient.setChannels(dmxMap)
        delay(config.settleTimeMs)

        Timber.i("$emitterName: safe peak DMX = $safePeak (camera exposure ramp)")
        safePeakDmxCache[emitterName] = safePeak
        return safePeak
    }

    private suspend fun blackout(config: CalibConfig) {
        val map = mutableMapOf<Int, Int>()
        config.channelMap.values.forEach { map[it] = 0 }
        config.dimmerChannel?.let { map[it] = 0 }
        dmxClient.setChannels(map)
    }

    // ─── Name matching ────────────────────────────────────────────────────────────

    private val abbrevMap = mapOf(
        "dr" to listOf("deepred"), "r" to listOf("red"),
        "ry" to listOf("amber"), "a" to listOf("amber"),
        "gy" to listOf("lime"), "l" to listOf("lime"),
        "g" to listOf("green"), "c" to listOf("cyan"),
        "b" to listOf("blue"), "i" to listOf("indigo"),
        "deepred" to listOf("dr"), "red" to listOf("r"),
        "amber" to listOf("a","ry"), "cyan" to listOf("c"),
        "indigo" to listOf("i"), "blue" to listOf("b"),
        "lime" to listOf("l","gy"), "green" to listOf("g"),
        "white" to listOf("w"), "warmwhite" to listOf("ww"),
        "coolwhite" to listOf("cw")
    )

    fun matchesName(channelName: String, emitterName: String): Boolean {
        fun norm(s: String) = s.lowercase().replace(" ","").replace("_","")
        val c = norm(channelName)
        val e = norm(emitterName)
        return c == e || c.contains(e) || e.contains(c) ||
               abbrevMap[c]?.any { norm(it) == e } == true ||
               abbrevMap[e]?.any { norm(it) == c } == true
    }

    /** Build the channel map from a ScanConfig + reference fixture */
    fun buildChannelMap(
        config: ScanEngine.ScanConfig,
        reference: CameraCalibration.ReferenceFixture
    ): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        // Match each reference emitter to a scan channel
        reference.emitters.forEach { emitter ->
            val match = config.diodeChannels.firstOrNull { dc ->
                matchesName(dc.name, emitter.name)
            }
            if (match != null) result[emitter.name] = match.dmxChannel
        }
        // Also include all scan channels by their own names (for white detection)
        config.diodeChannels.forEach { dc ->
            if (dc.name !in result.values.map { _ -> dc.name }) {
                result[dc.name] = dc.dmxChannel
            }
        }
        return result
    }

    /** Collect all steps for diagnostics / export */
    fun getAllSteps(): List<CameraCalibration.DesatStep> = allSteps.toList()
}
