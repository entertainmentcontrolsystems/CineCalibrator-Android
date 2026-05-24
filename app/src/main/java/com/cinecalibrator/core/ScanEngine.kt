package com.cinecalibrator.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.util.UUID

/**
 * ScanEngine
 *
 * Orchestrates the full calibration scan:
 *
 *  Phase 1 – Individual emitter primaries
 *    For each color diode:
 *      • Set dimmer to 255, all color channels to 0, then bring one diode to 255
 *      • Wait settle time (default 2s for LED driver stabilisation)
 *      • Drain stale camera frames, average N frames, record CIE xy + luminance
 *
 *  Phase 2 – Multi-level sweep (LUT data)
 *    Sweep each diode through configurable DMX levels with dimmer at 255.
 *    Longer settle + frame drain between each level.
 *
 *  Phase 3 – Color volume scan
 *    Sample the fixture's blended output at random DMX combinations
 *    to characterise the full reachable color volume.
 */
class ScanEngine(
    private val cameraManager: CameraManager,
    private val dmxClient: DMXOverIPClient,
    private val sekonicSource: SekonicMeasurementSource? = null,
    private val colorScience: ColorScience = ColorScience
) {
    data class ScanConfig(
        val fixtureName: String,
        val manufacturer: String,
        /** Color emitter channels only — dimmer is handled separately via dimmerChannel */
        val diodeChannels: List<DiodeChannel>,
        /** DMX channel number (1-indexed) for the master dimmer / intensity, or null if none */
        val dimmerChannel: Int? = null,
        /** Non-colour, non-dimmer channels — held at their default value during scans */
        val controlChannels: List<ControlChannel> = emptyList(),
        val dmxUniverse: Int = 1,
        /** Settle time in ms — bumped from 500 to 2000 for LED driver stabilisation */
        val settleTimeMs: Long = 2000,
        /** Frames averaged per sample (after stale-frame discard) */
        val framesPerSample: Int = 15,
        val roiCenterX: Float = 0.5f,
        val roiCenterY: Float = 0.5f,
        val runMultiLevelScan: Boolean = true,
        val multiLevelSteps: List<Int> = listOf(64, 128, 192, 255),
        val runColorVolumeScan: Boolean = true,
        /** How many random blend samples to take for the color volume */
        val colorVolumeSamples: Int = 150
    )

    data class ControlChannel(
        val name: String,
        val dmxChannel: Int,
        val defaultValue: Int = 0
    )

    data class DiodeChannel(
        val name: String,
        val dmxChannel: Int,
        val fineChannel: Int? = null
    )

    sealed class ScanProgress {
        data class Started(val totalDiodes: Int, val source: String) : ScanProgress()
        object ExposureLocking : ScanProgress()
        object ExposureLocked : ScanProgress()
        data class ScanningDiode(
            val diodeName: String, val index: Int, val total: Int,
            val source: String = "Camera"
        ) : ScanProgress()
        data class DiodeComplete(
            val measurement: ColorScience.DiodeMeasurement,
            val source: String = "Camera"
        ) : ScanProgress()
        data class Warning(val message: String) : ScanProgress()
        data class MultiLevelProgress(
            val diodeName: String, val level: Int, val levelIndex: Int, val totalLevels: Int
        ) : ScanProgress()
        data class ColorVolumeProgress(
            val sample: Int,
            val total: Int,
            val lastX: Double? = null,
            val lastY: Double? = null,
            val lastCCT: Float? = null
        ) : ScanProgress()
        data class Completed(val result: ColorScience.ScanResult) : ScanProgress()
        data class Error(val message: String, val cause: Throwable? = null) : ScanProgress()
        object Cancelled : ScanProgress()
    }

    data class LUTSamplePoint(
        val diodeName: String,
        val dmxLevel: Int,
        val x: Double,
        val y: Double,
        val luminance: Double
    )

    /** A single color-volume sample: the DMX mix that produced the measured xy + Y */
    data class ColorVolumeSample(
        val dmxLevels: Map<String, Int>,   // diodeName -> dmxValue 0-255
        val x: Double,
        val y: Double,
        val luminance: Double
    )

    private var scanJob: Job? = null
    private val _progress = MutableSharedFlow<ScanProgress>(replay = 1)
    val progress: SharedFlow<ScanProgress> = _progress

    // Partial scan recovery state
    private var partialMeasurements: List<ColorScience.DiodeMeasurement> = emptyList()
    val hasPartialScan: Boolean get() = partialMeasurements.isNotEmpty()

    val lutSamples = mutableListOf<LUTSamplePoint>()
    val colorVolumeSamples = mutableListOf<ColorVolumeSample>()

    // ─── Public API ──────────────────────────────────────────────────────────────

    fun startScan(config: ScanConfig, scope: CoroutineScope) {
        scanJob?.cancel()
        if (!hasPartialScan) {
            lutSamples.clear()
            colorVolumeSamples.clear()
        }
        scanJob = scope.launch {
            try {
                runScan(config)
                // Completed successfully — clear partial state
                partialMeasurements = emptyList()
            } catch (e: CancellationException) {
                _progress.emit(ScanProgress.Cancelled)
                blackout(config)
            } catch (e: Exception) {
                _progress.emit(ScanProgress.Error("Scan failed: ${e.message}", e))
                blackout(config)
                Timber.e(e, "Scan error")
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
    }

    /** Resume from a partial scan if available. Call before [startScan]. */
    fun resumePartialScan(): List<ColorScience.DiodeMeasurement> {
        return partialMeasurements.also {
            partialMeasurements = emptyList()
        }
    }

    // ─── Phase 1: Individual emitter primaries ────────────────────────────────────

    private suspend fun runScan(config: ScanConfig) {
        val sourceName = if (sekonicSource?.isConnected() == true &&
            sekonicSource.activeMode != SekonicMeasurementSource.Mode.CAMERA_ONLY)
            "Sekonic C-800" else "Camera"

        _progress.emit(ScanProgress.Started(config.diodeChannels.size, sourceName))
        blackout(config)
        delay(config.settleTimeMs)

        // ── Exposure lock phase — skip when spectrometer handles measurements ─────
        val usingSpectrometer = sekonicSource?.isConnected() == true &&
            sekonicSource.activeMode != SekonicMeasurementSource.Mode.CAMERA_ONLY
        if (!usingSpectrometer) {
            _progress.emit(ScanProgress.ExposureLocking)
            lockExposureOnReferenceColour(config)
            _progress.emit(ScanProgress.ExposureLocked)
        }

        val measurements = mutableListOf<ColorScience.DiodeMeasurement>()

        config.diodeChannels.forEachIndexed { index, diode ->
            currentCoroutineContext().ensureActive()
            _progress.emit(ScanProgress.ScanningDiode(
                diode.name, index, config.diodeChannels.size, sourceName
            ))

            val dmxMap = buildBaseDMX(config)
            dmxMap[diode.dmxChannel] = 255
            dmxClient.setChannels(dmxMap)
            delay(config.settleTimeMs)

            // Drain stale camera frames that were captured during the transition
            cameraManager.drainFrameBuffer()

            val measurement = if (usingSpectrometer) {
                // ── Spectrometer path ────────────────────────────────────────────────
                // Extra settle for LED driver full stabilization + C-800 trigger lag.
                // The C-800 shutter fires on command, but the LED output needs
                // to be fully settled first. Doubling settle here gives 4s total
                // with the default 2s config.
                delay(config.settleTimeMs)
                try {
                    val m = sekonicSource!!.measureDiode(index, diode.name)
                    if (m.fluxRelative < 0.001) {
                        _progress.emit(ScanProgress.Warning(
                            "${diode.name}: C-800 reading very low — " +
                            "ensure meter dome faces the fixture and is within 1–2 m"))
                    }
                    m
                } catch (e: Exception) {
                    Timber.e(e, "Sekonic measurement failed for ${diode.name}, falling back to camera")
                    cameraMeasurement(index, diode, config)
                }
            } else {
                // ── Camera path ──────────────────────────────────────────────────────
                cameraMeasurement(index, diode, config)
            }

            if (measurement != null) {
                measurements.add(measurement)
                _progress.emit(ScanProgress.DiodeComplete(measurement, sourceName))
                // Save partial progress for crash recovery
                partialMeasurements = measurements.toList()
            }

            blackout(config); delay(100)
        }

        // Phase 2: multi-level sweep
        if (config.runMultiLevelScan && measurements.isNotEmpty()) {
            runMultiLevelScan(config)
        }

        // Phase 3: color volume
        if (config.runColorVolumeScan && config.diodeChannels.size >= 2) {
            runColorVolumeScan(config)
        }

        // Recompute gamut coverage with all primaries known
        val primaryXY = measurements.map { ColorScience.CIExy(it.x, it.y) }
        val gamutProfile = CameraCalibration.getActiveGamutProfile()

        // Cross-normalise flux so the brightest emitter = 1.0
        val maxFlux = measurements.maxOfOrNull { it.fluxRelative }
            ?.takeIf { it > 0.0 } ?: 1.0

        val finalMeasurements = measurements.map { m ->
            val normFlux = (m.fluxRelative / maxFlux).coerceIn(0.001, 1.0)
            val withGamut = m.copy(
                fluxRelative = normFlux,
                gamutCoverage = ColorScience.ALL_REFERENCE_GAMUTS.associate { ref ->
                    ref.name to ColorScience.calculateGamutCoveragePercent(primaryXY, ref)
                }
            )
            if (gamutProfile != null) {
                val result = gamutProfile.assess(CameraCalibration.CIExy(m.x, m.y))
                withGamut.copy(
                    measurementConfidence = result.confidence,
                    confidenceNote = result.cctNote
                )
            } else withGamut
        }

        blackout(config)
        cameraManager.unlockExposure()

        _progress.emit(ScanProgress.Completed(
            ColorScience.ScanResult(
                sessionId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                fixtureName = config.fixtureName,
                fixtureManufacturer = config.manufacturer,
                measurements = finalMeasurements
            )
        ))
    }

    // ─── Phase 2: Multi-level sweep for LUT data ─────────────────────────────────

    /**
     * Sweep each diode through multiple DMX levels, measuring CIE xy at each step.
     *
     * Fixed from original:
     *  - Settle time now uses config value (default 2s instead of 0.5s)
     *  - Camera frames are drained from the buffer after settle before sampling
     *  - Sekonic path uses the correct [diodeIndex] not a cumulative counter
     *  - Camera sampling uses 15 frames (config.framesPerSample) not hardcoded 8
     */
    private suspend fun runMultiLevelScan(config: ScanConfig) {
        val usingSpectrometer = sekonicSource?.isConnected() == true &&
            sekonicSource.activeMode != SekonicMeasurementSource.Mode.CAMERA_ONLY

        config.diodeChannels.forEachIndexed { diodeIndex, diode ->
            config.multiLevelSteps.forEachIndexed { levelIdx, level ->
                currentCoroutineContext().ensureActive()
                _progress.emit(ScanProgress.MultiLevelProgress(
                    diode.name, level, levelIdx, config.multiLevelSteps.size
                ))
                val dmxMap = buildBaseDMX(config)
                dmxMap[diode.dmxChannel] = level
                dmxClient.setChannels(dmxMap)
                delay(config.settleTimeMs)

                // Drain stale frames captured during DMX transition before sampling
                cameraManager.drainFrameBuffer()

                if (usingSpectrometer) {
                    try {
                        // Extra settle for C-800: LED drivers + spectrometer trigger
                        delay(config.settleTimeMs)
                        // FIXED: pass the actual diode index, NOT a cumulative counter
                        val m = sekonicSource!!.measureDiode(diodeIndex, diode.name)
                        lutSamples.add(LUTSamplePoint(
                            diode.name, level, m.x, m.y, m.tristY
                        ))
                    } catch (_: Exception) {
                        val sample = cameraManager.sampleROIAveraged(
                            config.roiCenterX, config.roiCenterY,
                            frames = config.framesPerSample,
                            discardFrames = 0  // already drained above
                        )
                        if (sample != null) {
                            val (linR, linG, linB) = ColorScience.pixel8bitToLinearRGB(
                                sample.avgR, sample.avgG, sample.avgB
                            )
                            val (X, Y, Z) = ColorScience.linearRGBtoXYZ(linR, linG, linB)
                            val xy = ColorScience.xyzToCIExy(X, Y, Z)
                            lutSamples.add(LUTSamplePoint(
                                diode.name, level, xy.x, xy.y, Y
                            ))
                        }
                    }
                } else {
                    // Camera path: drain already done above, use full frame count
                    val sample = cameraManager.sampleROIAveraged(
                        config.roiCenterX, config.roiCenterY,
                        frames = config.framesPerSample,
                        discardFrames = 0  // already drained
                    )
                    if (sample != null) {
                        val (linR, linG, linB) = ColorScience.pixel8bitToLinearRGB(
                            sample.avgR, sample.avgG, sample.avgB
                        )
                        val (X, Y, Z) = ColorScience.linearRGBtoXYZ(linR, linG, linB)
                        val xy = ColorScience.xyzToCIExy(X, Y, Z)
                        lutSamples.add(LUTSamplePoint(
                            diode.name, level, xy.x, xy.y, Y
                        ))
                    }
                }
            }
        }
    }

    // ─── Phase 3: Color volume scan ───────────────────────────────────────────────

    /**
     * Generate stratified random DMX blends and measure CIE xy for each.
     * This characterises the full gamut volume the fixture can produce.
     */
    private suspend fun runColorVolumeScan(config: ScanConfig) {
        val totalSamples = config.colorVolumeSamples
        val rng = java.util.Random(42L)
        val usingSpectrometer = sekonicSource?.isConnected() == true &&
            sekonicSource.activeMode != SekonicMeasurementSource.Mode.CAMERA_ONLY

        repeat(totalSamples) { sampleIdx ->
            currentCoroutineContext().ensureActive()

            val dmxMap = buildBaseDMX(config)
            val diodeLevels = mutableMapOf<String, Int>()
            config.diodeChannels.forEachIndexed { i, diode ->
                val level = when {
                    // One diode gets a "lead" level for this sample to ensure
                    // we cover the full range, not just mid-grey blends
                    i == sampleIdx % config.diodeChannels.size -> 128 + rng.nextInt(128)
                    else -> rng.nextInt(256)
                }
                dmxMap[diode.dmxChannel] = level
                diodeLevels[diode.name] = level
            }
            dmxClient.setChannels(dmxMap)
            delay(config.settleTimeMs)

            // Drain transition frames before sampling
            cameraManager.drainFrameBuffer()

            if (usingSpectrometer) {
                delay(config.settleTimeMs) // extra settle for C-800
                try {
                    val raw = sekonicSource!!.measureDiode(
                        sampleIdx % config.diodeChannels.size,
                        "blend ${sampleIdx + 1}"
                    )
                    val xy = ColorScience.CIExy(raw.x, raw.y)
                    if (raw.fluxRelative > 0.001) {
                        colorVolumeSamples.add(ColorVolumeSample(
                            diodeLevels.toMap(), xy.x, xy.y, raw.tristY
                        ))
                    }
                    _progress.emit(ScanProgress.ColorVolumeProgress(
                        sample = sampleIdx + 1,
                        total = totalSamples,
                        lastX = raw.x,
                        lastY = raw.y,
                        lastCCT = if (raw.cct != null && raw.cct < 49000.0)
                            raw.cct.toFloat() else null
                    ))
                } catch (e: Exception) {
                    Timber.w("C-800 color volume sample ${sampleIdx + 1} failed: ${e.message}")
                    addCameraColorVolumeSample(config, diodeLevels)
                    _progress.emit(ScanProgress.ColorVolumeProgress(
                        sampleIdx + 1, totalSamples
                    ))
                }
            } else {
                addCameraColorVolumeSample(config, diodeLevels)
                _progress.emit(ScanProgress.ColorVolumeProgress(
                    sampleIdx + 1, totalSamples
                ))
            }
        }
    }

    private suspend fun addCameraColorVolumeSample(
        config: ScanConfig,
        diodeLevels: Map<String, Int>
    ) {
        val sample = cameraManager.sampleROIAveraged(
            config.roiCenterX, config.roiCenterY,
            frames = config.framesPerSample,
            discardFrames = 0  // already drained in caller
        )
        if (sample != null && !sample.isClipped) {
            val (linR, linG, linB) = ColorScience.pixel8bitToLinearRGB(
                sample.avgR, sample.avgG, sample.avgB
            )
            val (X, Y, Z) = ColorScience.linearRGBtoXYZ(linR, linG, linB)
            val xy = ColorScience.xyzToCIExy(X, Y, Z)
            if (Y > 0.01) {
                colorVolumeSamples.add(ColorVolumeSample(
                    diodeLevels.toMap(), xy.x, xy.y, Y
                ))
            }
        }
    }

    private suspend fun cameraMeasurement(
        index: Int,
        diode: DiodeChannel,
        config: ScanConfig
    ): ColorScience.DiodeMeasurement? {
        val sample = cameraManager.sampleROIAveraged(
            config.roiCenterX, config.roiCenterY, config.framesPerSample
        ) ?: return null
        if (sample.isClipped) Timber.w("Clipping on diode ${diode.name}")
        return ColorScience.buildDiodeMeasurement(
            index, diode.name,
            listOf(Triple(sample.avgR, sample.avgG, sample.avgB))
        )
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Display a bright reference colour, wait for AE/AWB to converge, then lock.
     * Priority: green (most luminous) → white → amber → first available diode.
     */
    private suspend fun lockExposureOnReferenceColour(config: ScanConfig) {
        val dmxMap = buildBaseDMX(config)
        val brightDiode = config.diodeChannels.firstOrNull {
            it.name.lowercase().contains("green") || it.name.lowercase() == "g"
        } ?: config.diodeChannels.firstOrNull {
            it.name.lowercase().contains("white")
        } ?: config.diodeChannels.firstOrNull {
            it.name.lowercase().contains("amber")
        } ?: config.diodeChannels.firstOrNull()

        if (brightDiode != null) {
            dmxMap[brightDiode.dmxChannel] = 255
        } else {
            config.diodeChannels.forEach { dmxMap[it.dmxChannel] = 255 }
        }
        dmxClient.setChannels(dmxMap)

        // Let AE/AWB converge for 3 seconds (was 2s — increased for slower cameras)
        cameraManager.lockExposureForCalibration(settleMs = 3000)

        // Back to black before measuring individual diodes
        blackout(config)
        delay(300)
    }

    private fun buildBaseDMX(config: ScanConfig): MutableMap<Int, Int> {
        val map = mutableMapOf<Int, Int>()
        config.diodeChannels.forEach { map[it.dmxChannel] = 0 }
        config.dimmerChannel?.let { map[it] = 255 }
        config.controlChannels.forEach { map[it.dmxChannel] = it.defaultValue }
        return map
    }

    private suspend fun blackout(config: ScanConfig) {
        val map = mutableMapOf<Int, Int>()
        config.diodeChannels.forEach { map[it.dmxChannel] = 0 }
        config.dimmerChannel?.let { map[it] = 0 }
        config.controlChannels.forEach { map[it.dmxChannel] = it.defaultValue }
        dmxClient.setChannels(map)
    }

    // ─── CSV exports ─────────────────────────────────────────────────────────────

    fun lutSamplesToCSV(): String {
        val sb = StringBuilder()
        sb.appendLine("Diode,DMX_Level,CIE_x,CIE_y,Y_Luminance")
        lutSamples.forEach { s ->
            sb.appendLine("${s.diodeName},${s.dmxLevel}," +
                "${"%.6f".format(s.x)},${"%.6f".format(s.y)},${"%.6f".format(s.luminance)}")
        }
        return sb.toString()
    }

    fun colorVolumeToCSV(): String {
        if (colorVolumeSamples.isEmpty()) return "No color volume data\n"
        val headers = colorVolumeSamples.first().dmxLevels.keys.joinToString(",")
        val sb = StringBuilder()
        sb.appendLine("$headers,CIE_x,CIE_y,Y_Luminance")
        colorVolumeSamples.forEach { s ->
            val levels = s.dmxLevels.values.joinToString(",")
            sb.appendLine("$levels,${"%.6f".format(s.x)}," +
                "${"%.6f".format(s.y)},${"%.6f".format(s.luminance)}")
        }
        return sb.toString()
    }
}
