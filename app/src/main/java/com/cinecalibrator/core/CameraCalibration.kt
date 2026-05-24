package com.cinecalibrator.core

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import timber.log.Timber
import java.io.File
import kotlin.math.*

/**
 * CameraCalibration
 *
 * ── The Core Problem ──────────────────────────────────────────────────────────
 * A phone camera cannot accurately measure highly saturated primary colours
 * (e.g. deep red x=0.71) because they fall outside the camera sensor's gamut.
 * Clipping, colour channel crosstalk, and sensor spectral sensitivity all
 * distort the measurement before we ever read a pixel value.
 *
 * ── The Solution: Desaturation Path Calibration ──────────────────────────────
 * CIE XYZ mixing is linear. If we know:
 *   P = primary chromaticity (manufacturer data, e.g. Fos/4 Red x=0.698, y=0.300)
 *   W = D65 white (x=0.3127, y=0.3290)
 *   Y_P, Y_W = luminances of each channel at each DMX level
 *
 * Then the predicted xy of a blend at step t ∈ [0..1] is:
 *   X_mix = x_P * Y_P * (1-t) + x_W * Y_W * t     (in XYZ space)
 *   Y_mix = y_P * Y_P * (1-t) + y_W * Y_W * t
 *   xy_mix = (X_mix, Y_mix) / (X_mix + Y_mix + Z_mix)
 *
 * We drive the fixture from pure primary → blended with white across ~16 steps.
 * The camera CAN accurately measure intermediate (less saturated) colours even
 * if it cannot measure the pure primary accurately.
 *
 * We collect (camera_measured_xy, physically_predicted_xy) pairs for each step,
 * use only the reliable ones (stable, non-clipping), and solve for the CCM.
 *
 * This gives us accurate calibration because:
 *  - At the white end the camera is accurate and measurements are stable
 *  - At the primary end the physics anchors the predicted values
 *  - In the middle we get good gamut spread + reliable measurements
 *  - The CCM is solved from many pairs spread across the gamut
 */
object CameraCalibration {

    // ─── White Reference ──────────────────────────────────────────────────────────

    // D65 white point
    val D65 = CIExy(0.3127, 0.3290)

    data class CIExy(val x: Double, val y: Double)

    // ─── Known Reference Fixtures ─────────────────────────────────────────────────

    data class ReferenceEmitter(
        val name: String,
        val cieX: Double,
        val cieY: Double,
        val fluxFc: Double?        // fc at 1m full output — used for luminance weighting
    ) {
        // Derived: luminance weight (proportional to Y in XYZ)
        // If fc not known, treat as equal weight
        val luminanceWeight: Double get() = fluxFc ?: 1.0
    }

    data class ReferenceFixture(
        val manufacturer: String,
        val model: String,
        val description: String,
        val emitters: List<ReferenceEmitter>,
        val measurementConditions: String
    )

    val KNOWN_REFERENCES: List<ReferenceFixture> = listOf(

        ReferenceFixture(
            manufacturer = "ETC",
            model = "Fos/4 Panel 8-Light",
            description = "ETC Fos/4 PL8 — 8-channel phosphor LED. Manufacturer photometric data.",
            measurementConditions = "1m, full output per channel, dark room",
            emitters = listOf(
                // Actual ETC photometric xy (manufacturer data) + fc at 1m
                // GDTF short names in parentheses
                ReferenceEmitter("DeepRed", 0.7134, 0.2798, 169.0),   // DR
                ReferenceEmitter("Red",     0.6984, 0.3001, 350.0),   // R
                ReferenceEmitter("Amber",   0.5747, 0.4241, 771.0),   // A / RY
                ReferenceEmitter("Cyan",    0.0717, 0.5173, 700.0),   // C
                ReferenceEmitter("Indigo",  0.1559, 0.0227, 119.0),   // I
                ReferenceEmitter("Blue",    0.1232, 0.0906, 256.0),   // B
                ReferenceEmitter("Lime",    0.4190, 0.5529, 2260.0),  // L / GY
                ReferenceEmitter("Green",   0.1922, 0.7314, 960.0)    // G
            )
        ),

        ReferenceFixture(
            manufacturer = "Arri",
            model = "SkyPanel S60-C",
            description = "Large-format LED softlight. Arri photometric documentation.",
            measurementConditions = "Standard mode, 1m, dark room",
            emitters = listOf(
                ReferenceEmitter("Red",       0.6640, 0.3060, null),
                ReferenceEmitter("Green",     0.2100, 0.6920, null),
                ReferenceEmitter("Blue",      0.1460, 0.0380, null),
                ReferenceEmitter("WarmWhite", 0.4290, 0.4050, null),
                ReferenceEmitter("CoolWhite", 0.2840, 0.2960, null)
            )
        ),

        ReferenceFixture(
            manufacturer = "Creamsource",
            model = "Vortex8",
            description = "8-channel RGBWW broadcast LED. Creamsource specification sheet.",
            measurementConditions = "Full output per channel, 1m, dark room",
            emitters = listOf(
                ReferenceEmitter("Red",       0.6720, 0.3130, null),
                ReferenceEmitter("Green",     0.1850, 0.7210, null),
                ReferenceEmitter("Blue",      0.1520, 0.0290, null),
                ReferenceEmitter("WarmWhite", 0.4480, 0.4060, null),
                ReferenceEmitter("CoolWhite", 0.3040, 0.3090, null),
                ReferenceEmitter("Amber",     0.5720, 0.4180, null),
                ReferenceEmitter("Cyan",      0.1360, 0.3420, null),
                ReferenceEmitter("Mint",      0.2430, 0.5360, null)
            )
        ),

        ReferenceFixture(
            manufacturer = "Generic",
            model = "D65 White Reference",
            description = "Single white channel or known D65 source. Minimal calibration.",
            measurementConditions = "Full white output, 1m, dark room",
            emitters = listOf(
                ReferenceEmitter("White", 0.3127, 0.3290, null)
            )
        )
    )

    // ─── Desaturation path data ────────────────────────────────────────────────────

    /**
     * One step along a primary→white desaturation ramp.
     * @param primaryLevel   DMX level of the primary channel (0-255)
     * @param whiteLevel     DMX level of a white/neutral channel (0-255)
     * @param predictedXY    Physically predicted CIE xy for this blend (from linear mixing law)
     * @param measuredXY     Camera's measured CIE xy
     * @param isReliable     True if measurement is stable and non-clipping
     */
    data class DesatStep(
        val primaryName: String,
        val primaryLevel: Int,
        val whiteLevel: Int,
        val predictedXY: CIExy,
        val measuredXY: CIExy,
        val measuredLuminance: Double,
        val isReliable: Boolean
    )

    // ─── Calibration Matrix ────────────────────────────────────────────────────────

    data class CalibrationMatrix(
        val referenceName: String,
        val matrix: Array<DoubleArray>,
        val residualError: Double,
        val sampleCount: Int,
        val method: String = "desaturation_path",
        val timestamp: Long = System.currentTimeMillis()
    ) {
        override fun equals(other: Any?) = other is CalibrationMatrix && referenceName == other.referenceName
        override fun hashCode() = referenceName.hashCode()
    }

    data class CalibrationState(
        val isCalibrated: Boolean,
        val matrix: CalibrationMatrix?,
        val gamutProfile: CameraGamutProfile?,
        val description: String
    )

    private var activeCalibration: CalibrationMatrix? = null
    private var activeGamutProfile: CameraGamutProfile? = null

    fun getCalibrationState(): CalibrationState {
        val cal = activeCalibration
        return if (cal != null) {
            CalibrationState(true, cal, activeGamutProfile,
                "Calibrated to ${cal.referenceName} — " +
                "avg error ${"%.4f".format(cal.residualError)} Δxy " +
                "(${cal.sampleCount} samples)")
        } else {
            CalibrationState(false, null, null, "Not calibrated — results are raw camera values")
        }
    }

    fun getActiveGamutProfile(): CameraGamutProfile? = activeGamutProfile

    fun clearCalibration() {
        activeCalibration = null
        activeGamutProfile = null
    }

    // ─── Core: predict xy along desaturation path ─────────────────────────────────

    /**
     * Given a known primary emitter and D65 white, predict the CIE xy
     * of their blend at a given ratio using the linear XYZ mixing law.
     *
     * @param primary      Known emitter (xy + luminance weight)
     * @param primaryFrac  Fractional output of primary [0..1]
     * @param whiteFrac    Fractional output of white [0..1]
     * @param whiteYWeight Luminance weight of the white channel
     */
    fun predictBlendXY(
        primary: ReferenceEmitter,
        primaryFrac: Double,
        whiteFrac: Double,
        whiteYWeight: Double = 1.0
    ): CIExy {
        val yP = primary.luminanceWeight * primaryFrac
        val yW = whiteYWeight * whiteFrac

        // Convert xy + Y → XYZ for both components
        val xyzP = xyYtoXYZ(primary.cieX, primary.cieY, yP)
        val xyzW = xyYtoXYZ(D65.x, D65.y, yW)

        // Sum in XYZ space (linear)
        val X = xyzP[0] + xyzW[0]
        val Y = xyzP[1] + xyzW[1]
        val Z = xyzP[2] + xyzW[2]

        val sum = X + Y + Z
        if (sum < 1e-10) return D65
        return CIExy(X / sum, Y / sum)
    }

    /**
     * Find the desaturation steps that are "camera-reliable" —
     * far enough from the saturated extreme that the camera can measure accurately.
     *
     * Reliability criteria:
     *  - Not clipping (no channel >= 250)
     *  - Consecutive measurements are stable (Δxy between steps is smooth)
     *  - xy is not too close to the sensor gamut boundary (heuristic)
     *
     * In practice: steps where whiteFrac >= 0.3 are usually reliable.
     */
    fun filterReliableSteps(steps: List<DesatStep>): List<DesatStep> {
        if (steps.size < 3) return steps.filter { it.isReliable }

        // Mark steps as unreliable if:
        // 1. They are clipping
        // 2. The measured xy jumps erratically compared to its neighbours
        val result = steps.toMutableList()
        for (i in 1 until steps.size - 1) {
            if (!steps[i].isReliable) continue
            val prev = steps[i - 1]
            val curr = steps[i]
            val next = steps[i + 1]

            // Expected smooth progression: Δxy between consecutive steps should be similar
            val d1 = hypot(curr.measuredXY.x - prev.measuredXY.x, curr.measuredXY.y - prev.measuredXY.y)
            val d2 = hypot(next.measuredXY.x - curr.measuredXY.x, next.measuredXY.y - curr.measuredXY.y)

            // If this step is > 3x larger than its neighbours, it's a camera error
            if (d1 > 0.01 && d2 > 0.01) {
                val ratio = maxOf(d1, d2) / minOf(d1, d2)
                if (ratio > 4.0) {
                    result[i] = steps[i].copy(isReliable = false)
                    Timber.w("Step ${i} marked unreliable: jump ratio ${"%.2f".format(ratio)}")
                }
            }
        }
        return result.filter { it.isReliable }
    }

    // ─── CCM Computation ─────────────────────────────────────────────────────────

    /**
     * Compute the 3×3 CCM from collected desaturation path samples.
     *
     * Each sample is a (camera_measured_XYZ, physically_predicted_XYZ) pair.
     * We solve M such that M * camera_XYZ ≈ physical_XYZ for all samples.
     *
     * The "physical_XYZ" values come from the known manufacturer primaries
     * mixed with known D65 white via linear XYZ mixing — so we never need
     * the camera to accurately measure pure saturated primaries.
     */
    fun computeCalibrationFromDesatSteps(
        steps: List<DesatStep>,
        referenceName: String
    ): CalibrationMatrix? {
        val reliable = filterReliableSteps(steps)
        Timber.d("Calibration: ${steps.size} total steps, ${reliable.size} reliable")

        if (reliable.size < 4) {
            Timber.w("Not enough reliable samples: ${reliable.size}")
            return null
        }

        // Convert xy → XYZ (use predicted luminance as Y)
        val camXYZ  = reliable.map { s -> xyYtoXYZ(s.measuredXY.x, s.measuredXY.y, s.measuredLuminance) }
        val refXYZ  = reliable.map { s -> xyYtoXYZ(s.predictedXY.x, s.predictedXY.y, s.measuredLuminance) }

        val ccm = solveLeastSquares3x3(camXYZ, refXYZ) ?: return null

        // Compute residual error in xy space
        var totalErr = 0.0
        camXYZ.forEachIndexed { i, cam ->
            val corrected = matMul(ccm, cam)
            val corrXY = xyzToCIExy(corrected[0], corrected[1], corrected[2])
            totalErr += hypot(corrXY.x - reliable[i].predictedXY.x, corrXY.y - reliable[i].predictedXY.y)
        }
        val avgErr = totalErr / reliable.size

        val cal = CalibrationMatrix(
            referenceName = referenceName,
            matrix = ccm,
            residualError = avgErr,
            sampleCount = reliable.size
        )
        activeCalibration = cal

        // Build gamut profile from reliable steps so we can assess measurement confidence later
        activeGamutProfile = CameraGamutProfile.fromDesatSteps(
            reliable,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            referenceFixture = referenceName
        )

        Timber.d("CCM computed: avg Δxy=${"%.4f".format(avgErr)} from ${reliable.size} samples; " +
                 "hull area=${"%.4f".format(activeGamutProfile?.hullArea ?: 0.0)}")
        return cal
    }

    /**
     * Legacy: compute CCM directly from matched (measured, reference) xy pairs.
     * Used as a fallback if desaturation path is not available.
     */
    fun computeCalibrationMatrix(
        measured: List<CIExy>,
        reference: List<CIExy>,
        luminances: List<Double>,
        referenceName: String
    ): CalibrationMatrix? {
        if (measured.size < 3) return null
        val camXYZ = measured.zip(luminances).map { (xy, Y) -> xyYtoXYZ(xy.x, xy.y, Y) }
        val refXYZ = reference.zip(luminances).map { (xy, Y) -> xyYtoXYZ(xy.x, xy.y, Y) }
        val ccm = solveLeastSquares3x3(camXYZ, refXYZ) ?: return null

        var totalErr = 0.0
        camXYZ.forEachIndexed { i, cam ->
            val corr = matMul(ccm, cam)
            val xy = xyzToCIExy(corr[0], corr[1], corr[2])
            totalErr += hypot(xy.x - reference[i].x, xy.y - reference[i].y)
        }
        val cal = CalibrationMatrix(referenceName, ccm, totalErr / measured.size,
            measured.size, "direct_match")
        activeCalibration = cal
        return cal
    }

    // ─── Apply calibration ────────────────────────────────────────────────────────

    /**
     * Apply the active CCM to a raw camera measurement.
     * Returns unchanged values if no calibration is active.
     */
    fun applyCalibration(xy: ColorScience.CIExy, Y: Double): Pair<ColorScience.CIExy, Double> {
        val cal = activeCalibration ?: return Pair(xy, Y)
        val xyz = xyYtoXYZ(xy.x, xy.y, Y)
        val corrected = matMul(cal.matrix, xyz)
        val corrXY = xyzToCIExy(corrected[0], corrected[1], corrected[2])
        // Convert back to ColorScience.CIExy for the caller
        return Pair(ColorScience.CIExy(corrXY.x, corrXY.y), corrected[1].coerceAtLeast(0.0))
    }

    // ─── Math ─────────────────────────────────────────────────────────────────────

    fun xyYtoXYZ(x: Double, y: Double, Y: Double): DoubleArray {
        if (y < 1e-10) return doubleArrayOf(0.0, Y, 0.0)
        return doubleArrayOf(x * Y / y, Y, (1.0 - x - y) * Y / y)
    }

    /** Local helper returning CameraCalibration.CIExy — avoids cross-type dependency */
    private fun xyzToCIExy(X: Double, Y: Double, Z: Double): CIExy {
        val sum = X + Y + Z
        if (sum < 1e-10) return D65
        return CIExy(X / sum, Y / sum)
    }

    fun matMul(m: Array<DoubleArray>, v: DoubleArray): DoubleArray = doubleArrayOf(
        m[0][0]*v[0] + m[0][1]*v[1] + m[0][2]*v[2],
        m[1][0]*v[0] + m[1][1]*v[1] + m[1][2]*v[2],
        m[2][0]*v[0] + m[2][1]*v[1] + m[2][2]*v[2]
    )

    private fun solveLeastSquares3x3(
        src: List<DoubleArray>, dst: List<DoubleArray>
    ): Array<DoubleArray>? {
        val n = src.size
        val sST = Array(3) { DoubleArray(3) }
        val dST = Array(3) { DoubleArray(3) }
        for (k in 0 until n) {
            for (i in 0..2) for (j in 0..2) {
                sST[i][j] += src[k][i] * src[k][j]
                dST[i][j] += dst[k][i] * src[k][j]
            }
        }
        val inv = invert3x3(sST) ?: return null
        val M = Array(3) { DoubleArray(3) }
        for (i in 0..2) for (j in 0..2) for (k in 0..2) M[i][j] += dST[i][k] * inv[k][j]
        return M
    }

    private fun invert3x3(m: Array<DoubleArray>): Array<DoubleArray>? {
        val det = m[0][0]*(m[1][1]*m[2][2]-m[1][2]*m[2][1]) -
                  m[0][1]*(m[1][0]*m[2][2]-m[1][2]*m[2][0]) +
                  m[0][2]*(m[1][0]*m[2][1]-m[1][1]*m[2][0])
        if (abs(det) < 1e-10) return null
        val inv = Array(3) { DoubleArray(3) }
        inv[0][0] = (m[1][1]*m[2][2] - m[1][2]*m[2][1]) / det
        inv[0][1] = (m[0][2]*m[2][1] - m[0][1]*m[2][2]) / det
        inv[0][2] = (m[0][1]*m[1][2] - m[0][2]*m[1][1]) / det
        inv[1][0] = (m[1][2]*m[2][0] - m[1][0]*m[2][2]) / det
        inv[1][1] = (m[0][0]*m[2][2] - m[0][2]*m[2][0]) / det
        inv[1][2] = (m[0][2]*m[1][0] - m[0][0]*m[1][2]) / det
        inv[2][0] = (m[1][0]*m[2][1] - m[1][1]*m[2][0]) / det
        inv[2][1] = (m[0][1]*m[2][0] - m[0][0]*m[2][1]) / det
        inv[2][2] = (m[0][0]*m[1][1] - m[0][1]*m[1][0]) / det
        return inv
    }

    // ─── Persistence ─────────────────────────────────────────────────────────────

    fun save(context: Context) {
        activeCalibration?.let { cal ->
            try {
                File(context.filesDir, "camera_calibration.json")
                    .writeText(Gson().toJson(cal))
            } catch (e: Exception) { Timber.e(e, "Save calibration failed") }
        }
    }

    fun load(context: Context) {
        try {
            val f = File(context.filesDir, "camera_calibration.json")
            if (f.exists()) {
                activeCalibration = Gson().fromJson(f.readText(), CalibrationMatrix::class.java)
                Timber.d("Loaded calibration: ${activeCalibration?.referenceName}")
            }
        } catch (e: Exception) { Timber.e(e, "Load calibration failed") }
    }
}
