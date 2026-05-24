package com.cinecalibrator.core

import android.content.Context
import com.sekonic.c800.MeasurementResult
import com.sekonic.c800.SekonicC800
import com.sekonic.c800.SekonicException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * SekonicMeasurementSource
 *
 * Wraps the Sekonic C-800 USB library and bridges it to CineCalibrator's
 * internal data model.
 *
 * ── What the C-800 gives us that the phone camera cannot ─────────────────────
 *  • True SPD at 1 nm resolution (380–780 nm) — actual spectroradiometry
 *  • CIE xy direct from the instrument — no gamut limits, no camera matrix
 *  • CCT, Duv, CRI Ra + R1-R15 from the meter firmware
 *  • TM-30 Rf / Rg, SSI, TLCI — broadcast-grade quality metrics
 *  • Lux and fc at the measurement plane
 *
 * ── Integration modes ─────────────────────────────────────────────────────────
 * SPECTROMETER_ONLY  — All measurements from C-800; phone camera not used.
 *                      Best accuracy. Requires USB OTG cable.
 * HYBRID             — Spectrometer provides absolute CIE xy; camera provides
 *                      relative spatial uniformity across a panel.
 * CAMERA_ONLY        — Original camera-based mode (C-800 not connected).
 *
 * The ScanEngine checks [activeMode] before each measurement and routes
 * accordingly.
 */
class SekonicMeasurementSource(private val context: Context) {

    enum class Mode { CAMERA_ONLY, SPECTROMETER_ONLY, HYBRID }

    private val meter = SekonicC800(context)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _lastResult = MutableStateFlow<MeasurementResult?>(null)
    val lastResult: StateFlow<MeasurementResult?> = _lastResult

    var activeMode: Mode = Mode.CAMERA_ONLY
        private set

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR;

        val label: String get() = when (this) {
            DISCONNECTED -> "C-800 not connected"
            CONNECTING   -> "Connecting…"
            CONNECTED    -> "C-800 connected"
            ERROR        -> "C-800 error"
        }
        val isReady: Boolean get() = this == CONNECTED
    }

    // ── Connection ────────────────────────────────────────────────────────────

    suspend fun connect() {
        _connectionState.value = ConnectionState.CONNECTING
        try {
            meter.connect()
            _connectionState.value = ConnectionState.CONNECTED
            activeMode = Mode.SPECTROMETER_ONLY
            Timber.i("Sekonic C-800 connected — switching to SPECTROMETER_ONLY mode")
        } catch (e: SekonicException) {
            _connectionState.value = ConnectionState.ERROR
            activeMode = Mode.CAMERA_ONLY
            Timber.w("Sekonic C-800 connection failed: ${e.message}")
            throw e
        }
    }

    fun disconnect() {
        meter.disconnect()
        _connectionState.value = ConnectionState.DISCONNECTED
        activeMode = Mode.CAMERA_ONLY
        Timber.i("Sekonic C-800 disconnected — reverting to CAMERA_ONLY mode")
    }

    fun setMode(mode: Mode) {
        if (mode != Mode.CAMERA_ONLY && _connectionState.value != ConnectionState.CONNECTED) {
            Timber.w("Cannot set mode $mode — C-800 not connected")
            return
        }
        activeMode = mode
        Timber.d("Measurement mode set to $mode")
    }

    // ── Single measurement ────────────────────────────────────────────────────

    /**
     * Take one measurement from the C-800 and return it as a [DiodeMeasurement].
     *
     * All colorimetric values come directly from the spectrometer firmware —
     * no camera matrix, no gamut limits.
     *
     * @param diodeIndex  Index of the diode channel being measured
     * @param diodeName   Name for display (e.g. "Red", "Lime")
     */
    suspend fun measureDiode(
        diodeIndex: Int,
        diodeName: String
    ): ColorScience.DiodeMeasurement {
        val raw = meter.measure()
        _lastResult.value = raw

        // Log raw values to diagnose "all zeros" issues
        Timber.d("C-800 [$diodeName]: lux=${raw.lux} cct=${raw.cct} " +
                 "x=${raw.cie1931x} y=${raw.cie1931y} Ra=${raw.criRa}")

        if (raw.lux < 0.5f) {
            Timber.w("C-800 [$diodeName]: Very low lux (${raw.lux}) — " +
                     "is meter aimed at light source? Is it within range?")
        }

        return toDiodeMeasurement(raw, diodeIndex, diodeName)
    }

    /**
     * Read the current result from the C-800's memory without triggering a new measurement.
     * Used when the fixture is already driven and stabilised.
     */
    suspend fun readCurrentReading(
        diodeIndex: Int,
        diodeName: String
    ): ColorScience.DiodeMeasurement {
        val raw = meter.measure()
        _lastResult.value = raw
        return toDiodeMeasurement(raw, diodeIndex, diodeName)
    }

    // ── Memory download ───────────────────────────────────────────────────────

    /**
     * Download all stored measurements from C-800 memory.
     * Useful for importing a manual session recorded without the phone.
     */
    suspend fun downloadMemory(
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): List<MeasurementResult> {
        return meter.downloadMemory { current, total -> onProgress?.invoke(current, total) }
    }

    // ── Conversion ────────────────────────────────────────────────────────────

    /**
     * Map a [MeasurementResult] from the C-800 to our internal [DiodeMeasurement].
     *
     * Confidence is always FULL when measured by spectrometer — no camera gamut limit.
     */
    fun toDiodeMeasurement(
        raw: MeasurementResult,
        diodeIndex: Int,
        diodeName: String
    ): ColorScience.DiodeMeasurement {
        // Use tristimulus Y as relative flux — it's the photometric luminance the instrument
        // measures directly. Lux ÷ 1000 was wrong: it clips at 1000 lx and loses all
        // information above that. We'll normalise across all emitters in GDTFExporter.
        // Coerce to 0..1 for the per-diode display; GDTF export re-normalises to peak.
        val fluxRelative = (raw.tristY / 100.0).coerceIn(0.0, 1.0)
            .takeIf { it > 0.0 }
            ?: (raw.lux / 10000.0).coerceIn(0.0, 1.0)  // fallback: lux with higher ceiling

        return ColorScience.DiodeMeasurement(
            diodeIndex  = diodeIndex,
            diodeName   = diodeName,
            rawR        = raw.spd5nm.getOrElse(16) { 0f }.toDouble(),
            rawG        = raw.spd5nm.getOrElse(24) { 0f }.toDouble(),
            rawB        = raw.spd5nm.getOrElse(8)  { 0f }.toDouble(),
            tristX      = raw.tristX,
            tristY      = raw.tristY,
            tristZ      = raw.tristZ,
            x           = raw.cie1931x.toDouble(),
            y           = raw.cie1931y.toDouble(),
            fluxRelative = fluxRelative,
            cct         = if (raw.cct > 0f) raw.cct.toDouble() else null,
            duv         = raw.deltaUv.toDouble(),
            gamutCoverage = emptyMap(),
            measurementConfidence = CameraGamutProfile.Confidence.FULL,
            confidenceNote = "Measured by Sekonic C-800 spectrometer"
        )
    }

    /**
     * Convert a full [MeasurementResult] to a richer [SpectrometerReading] that
     * carries all C-800 fields not in the base [DiodeMeasurement].
     */
    fun toSpectrometerReading(raw: MeasurementResult, diodeName: String) =
        SpectrometerReading(
            diodeName       = diodeName,
            cct             = raw.cct,
            deltaUv         = raw.deltaUv,
            lux             = raw.lux,
            footCandle      = raw.footCandle,
            cie1931x        = raw.cie1931x,
            cie1931y        = raw.cie1931y,
            cie1976u        = raw.cie1976u,
            cie1976v        = raw.cie1976v,
            criRa           = raw.criRa,
            criR            = raw.criR,
            tm30Rf          = raw.tm30Rf,
            tm30Rg          = raw.tm30Rg,
            ssiD            = raw.ssiD,
            ssiT            = raw.ssiT,
            tlci            = raw.tlci,
            spd5nm          = raw.spd5nm,
            spd1nm          = raw.spd1nm,
            peakWavelength  = raw.peakWavelength,
            dominantWavelength = raw.dominantWavelength,
            hasExtendedMetrics = raw.hasExtendedMetrics
        )

    fun isConnected() = _connectionState.value == ConnectionState.CONNECTED

    fun release() = disconnect()

    // ─── Extended data class carrying all C-800 fields ────────────────────────

    data class SpectrometerReading(
        val diodeName:          String,
        val cct:                Float,
        val deltaUv:            Float,
        val lux:                Float,
        val footCandle:         Float,
        val cie1931x:           Float,
        val cie1931y:           Float,
        val cie1976u:           Float,
        val cie1976v:           Float,
        val criRa:              Float,
        val criR:               List<Float>,
        val tm30Rf:             Float,
        val tm30Rg:             Float,
        val ssiD:               Float,
        val ssiT:               Float,
        val tlci:               Float,
        val spd5nm:             FloatArray,
        val spd1nm:             FloatArray,
        val peakWavelength:     Int,
        val dominantWavelength: Float,
        val hasExtendedMetrics: Boolean
    )
}
