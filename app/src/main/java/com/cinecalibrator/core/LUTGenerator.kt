package com.cinecalibrator.core

import android.content.Context
import timber.log.Timber
import java.io.File
import kotlin.math.*

/**
 * LUT Generator
 *
 * Generates a 3D LUT (.cube format - Adobe/DaVinci Resolve compatible) from
 * measured diode calibration data.
 *
 * The LUT answers: "When I grade a pixel as (r,g,b) in the target colorspace,
 * what (r,g,b) will the fixture actually produce?"
 *
 * Two strategies, auto-selected:
 *
 *   Strategy A — Color-Volume Interpolation (preferred)
 *     Uses the measured XYZ from blended multi-channel DMX mixes (color volume
 *     samples) as ground truth. For each LUT grid point, finds the K nearest
 *     samples by target-XYZ proximity, weights by inverse distance, and
 *     computes the output. Works for ANY number of emitters (RGB, RGBW, RGBACL,
 *     etc.) because it uses actual measurements, not a simplified 3-emitter model.
 *
 *   Strategy B — 3×3 Primary Matrix (fallback)
 *     Used when no color volume data is available. Builds a matrix from R,G,B
 *     diode primaries only. Accurate only for pure-RGB fixtures.
 */
class LUTGenerator(private val context: Context) {

    enum class TargetColorspace {
        REC709, REC2020, ACES_AP0, ACES_AP1_ACESCG
    }

    data class LUTData(
        val fixtureName: String,
        val targetColorspace: TargetColorspace,
        val size: Int,                   // LUT cube size (e.g. 33 = 33³ entries)
        val entries: Array<FloatArray>,  // [index][R,G,B] each 0..1
        val inputMin: FloatArray,
        val inputMax: FloatArray,
        val strategy: String,            // "color_volume" or "primary_matrix"
        val notes: String
    ) {
        override fun equals(other: Any?): Boolean =
            other is LUTData && other.fixtureName == fixtureName &&
            other.targetColorspace == targetColorspace && other.size == size
        override fun hashCode(): Int =
            31 * (31 * fixtureName.hashCode() + targetColorspace.hashCode()) + size
    }

    // ─── Target colorspace XYZ matrices ──────────────────────────────────────────

    private val XYZ_TO_REC709 = arrayOf(
        floatArrayOf(3.2404542f, -1.5371385f, -0.4985314f),
        floatArrayOf(-0.9692660f, 1.8760108f, 0.0415560f),
        floatArrayOf(0.0556434f, -0.2040259f, 1.0572252f)
    )

    private val XYZ_TO_REC2020 = arrayOf(
        floatArrayOf(1.7166512f, -0.3556708f, -0.2533663f),
        floatArrayOf(-0.6666844f, 1.6164812f, 0.0157685f),
        floatArrayOf(0.0176399f, -0.0427706f, 0.9421031f)
    )

    private val XYZ_TO_ACES_AP0 = arrayOf(
        floatArrayOf(1.0498110175f, 0.0000000000f, -0.0000974845f),
        floatArrayOf(-0.4959030231f, 1.3733130458f, 0.0982400361f),
        floatArrayOf(0.0000000000f, 0.0000000000f, 0.9912520182f)
    )

    private val XYZ_TO_ACES_AP1 = arrayOf(
        floatArrayOf(1.6410234f, -0.3248033f, -0.2364247f),
        floatArrayOf(-0.6636629f, 1.6153316f, 0.0167563f),
        floatArrayOf(0.0117219f, -0.0082844f, 0.9883948f)
    )

    // ─── Public API — Strategy A (color volume) or B (matrix fallback) ───────────

    /**
     * Generate a 3D LUT from measured data.
     *
     * Auto-selects strategy: color-volume interpolation if [volumeSamples] is
     * non-empty, otherwise falls back to the legacy 3×3 primary matrix.
     *
     * @param measurements     Phase 1 diode primaries (each at 100% output)
     * @param volumeSamples    Phase 3 color volume samples: (DMX mix → measured XYZ)
     * @param fixtureName      Fixture identifier for the .cube header
     * @param target           Target output color space
     * @param size             LUT grid size per axis (17, 33, or 65 recommended)
     * @param kNearest         Number of nearest samples for interpolation (6–12 good)
     */
    fun generateLUT(
        measurements: List<ColorScience.DiodeMeasurement>,
        volumeSamples: List<ScanEngine.ColorVolumeSample>,
        fixtureName: String,
        target: TargetColorspace = TargetColorspace.REC709,
        size: Int = 33,
        kNearest: Int = 8
    ): LUTData {
        val targetMatrix = getTargetMatrix(target)
        val totalEntries = size * size * size
        val entries = Array(totalEntries) { FloatArray(3) }

        // ── Strategy A: Inverse-distance interpolation from color volume ─────────
        if (volumeSamples.isNotEmpty() && volumeSamples.size >= 6) {
            Timber.d("LUT: using color-volume interpolation (${volumeSamples.size} samples, k=$kNearest)")

            // Build index: for each sample, pre-compute XYZ for fast lookup
            data class IndexedSample(
                val X: Double, val Y: Double, val Z: Double,
                val targetR: Float, val targetG: Float, val targetB: Float
            )
            val indexed = volumeSamples.map { s ->
                val XYZ = floatArrayOf(s.x.toFloat(), s.y.toFloat(), s.luminance.toFloat())
                val rgb = matMul3x3(targetMatrix, XYZ)
                IndexedSample(
                    X = s.x, Y = s.y, Z = s.luminance,
                    targetR = rgb[0].coerceIn(0f, 1f),
                    targetG = rgb[1].coerceIn(0f, 1f),
                    targetB = rgb[2].coerceIn(0f, 1f)
                )
            }

            var idx = 0
            for (b in 0 until size) {
                for (g in 0 until size) {
                    for (r in 0 until size) {
                        val rNorm = r.toFloat() / (size - 1)
                        val gNorm = g.toFloat() / (size - 1)
                        val bNorm = b.toFloat() / (size - 1)

                        // Target color (r,g,b) → XYZ
                        // We invert the target matrix to get what XYZ this RGB represents
                        val targetXYZ = targetRGBtoXYZ(rNorm, gNorm, bNorm, target)
                        val (tx, ty, tz) = Triple(targetXYZ[0], targetXYZ[1], targetXYZ[2])

                        // Find K nearest samples by XYZ distance (CIE76 ΔE in XYZ)
                        // Weight by inverse distance; if a sample is extremely close,
                        // give it dominant weight
                        data class ScoredSample(val sample: IndexedSample, val dist: Double)
                        val scored = indexed.map { s ->
                            val dx = s.X - tx; val dy = s.Y - ty; val dz = s.Z - tz
                            ScoredSample(s, sqrt(dx * dx + dy * dy + dz * dz))
                        }.sortedBy { it.dist }

                        val k = minOf(kNearest, scored.size)
                        val nearest = scored.take(k)

                        // Inverse-distance weights with epsilon to avoid div-by-zero
                        val eps = 1e-6
                        val weights = nearest.map {
                            1.0 / (it.dist + eps)
                        }
                        val weightSum = weights.sum()

                        var outR = 0f; var outG = 0f; var outB = 0f
                        for (i in nearest.indices) {
                            val w = (weights[i] / weightSum).toFloat()
                            outR += nearest[i].sample.targetR * w
                            outG += nearest[i].sample.targetG * w
                            outB += nearest[i].sample.targetB * w
                        }

                        entries[idx][0] = outR.coerceIn(0f, 1f)
                        entries[idx][1] = outG.coerceIn(0f, 1f)
                        entries[idx][2] = outB.coerceIn(0f, 1f)
                        idx++
                    }
                }
            }

            return LUTData(
                fixtureName = fixtureName,
                targetColorspace = target,
                size = size,
                entries = entries,
                inputMin = floatArrayOf(0f, 0f, 0f),
                inputMax = floatArrayOf(1f, 1f, 1f),
                strategy = "color_volume",
                notes = "Color-volume interpolation — ${volumeSamples.size} samples, k=$kNearest"
            )
        }

        // ── Strategy B: Legacy 3×3 primary matrix (fallback) ─────────────────────
        Timber.d("LUT: using primary-matrix fallback (${measurements.size} primaries)")

        val fixtureMatrix = buildFixtureMatrix(measurements)

        var idx = 0
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    val rNorm = r.toFloat() / (size - 1)
                    val gNorm = g.toFloat() / (size - 1)
                    val bNorm = b.toFloat() / (size - 1)

                    val xyz = matMul3x3(fixtureMatrix, floatArrayOf(rNorm, gNorm, bNorm))
                    val rgb = matMul3x3(targetMatrix, xyz)

                    entries[idx][0] = rgb[0].coerceIn(0f, 1f)
                    entries[idx][1] = rgb[1].coerceIn(0f, 1f)
                    entries[idx][2] = rgb[2].coerceIn(0f, 1f)
                    idx++
                }
            }
        }

        return LUTData(
            fixtureName = fixtureName,
            targetColorspace = target,
            size = size,
            entries = entries,
            inputMin = floatArrayOf(0f, 0f, 0f),
            inputMax = floatArrayOf(1f, 1f, 1f),
            strategy = "primary_matrix",
            notes = "3×3 primary-matrix fallback — ${measurements.size} diodes. " +
                    "Run a color-volume scan for multi-emitter fixtures."
        )
    }

    // ─── Target RGB → XYZ (inverse of the target matrix) ─────────────────────────

    private fun targetRGBtoXYZ(
        r: Float, g: Float, b: Float, target: TargetColorspace
    ): FloatArray {
        // Apply inverse of the XYZ→target matrix
        val inv = when (target) {
            TargetColorspace.REC709 -> REC709_TO_XYZ
            TargetColorspace.REC2020 -> REC2020_TO_XYZ
            TargetColorspace.ACES_AP0 -> ACES_AP0_TO_XYZ
            TargetColorspace.ACES_AP1_ACESCG -> ACES_AP1_TO_XYZ
        }
        return matMul3x3(inv, floatArrayOf(r, g, b))
    }

    // ─── Standard forward matrices (target RGB ← XYZ) ────────────────────────────

    // sRGB/Rec.709 primaries → XYZ
    private val REC709_TO_XYZ = arrayOf(
        floatArrayOf(0.4124564f, 0.3575761f, 0.1804375f),
        floatArrayOf(0.2126729f, 0.7151522f, 0.0721750f),
        floatArrayOf(0.0193339f, 0.1191920f, 0.9503041f)
    )

    // Rec.2020 primaries → XYZ
    private val REC2020_TO_XYZ = arrayOf(
        floatArrayOf(0.6369580f, 0.1446169f, 0.1688810f),
        floatArrayOf(0.2627002f, 0.6779981f, 0.0593017f),
        floatArrayOf(0.0000000f, 0.0280727f, 1.0609851f)
    )

    // ACES AP0 primaries → XYZ
    private val ACES_AP0_TO_XYZ = arrayOf(
        floatArrayOf(0.9525523959f, 0.0000000000f, 0.0000936786f),
        floatArrayOf(0.3439664498f, 0.7281660966f, -0.0721325464f),
        floatArrayOf(0.0000000000f, 0.0000000000f, 1.0088251844f)
    )

    // ACES AP1 primaries → XYZ
    private val ACES_AP1_TO_XYZ = arrayOf(
        floatArrayOf(0.6624541811f, 0.1340042065f, 0.1561876870f),
        floatArrayOf(0.2722287168f, 0.6740817658f, 0.0536895174f),
        floatArrayOf(-0.0055746495f, 0.0040607335f, 1.0103391003f)
    )

    // ─── Legacy: 3×3 fixture matrix from R,G,B primaries ─────────────────────────

    private fun buildFixtureMatrix(
        measurements: List<ColorScience.DiodeMeasurement>
    ): Array<FloatArray> {
        if (measurements.isEmpty()) return identityMatrix()

        val rDiode = measurements.firstOrNull {
            it.diodeName.contains("red", true) || it.diodeName.equals("r", true)
        } ?: measurements.firstOrNull()

        val gDiode = measurements.firstOrNull {
            it.diodeName.contains("green", true) || it.diodeName.equals("g", true)
        } ?: measurements.getOrNull(1) ?: measurements.firstOrNull()

        val bDiode = measurements.firstOrNull {
            it.diodeName.contains("blue", true) || it.diodeName.equals("b", true)
        } ?: measurements.getOrNull(2) ?: measurements.firstOrNull()

        if (rDiode == null || gDiode == null || bDiode == null) return identityMatrix()

        return arrayOf(
            floatArrayOf(
                rDiode.tristX.toFloat(), gDiode.tristX.toFloat(), bDiode.tristX.toFloat()
            ),
            floatArrayOf(
                rDiode.tristY.toFloat(), gDiode.tristY.toFloat(), bDiode.tristY.toFloat()
            ),
            floatArrayOf(
                rDiode.tristZ.toFloat(), gDiode.tristZ.toFloat(), bDiode.tristZ.toFloat()
            )
        )
    }

    // ─── .cube File Export ───────────────────────────────────────────────────────

    fun toCubeFormat(lut: LUTData): String {
        val sb = StringBuilder()
        sb.appendLine("# CineCalibrator LUT Export")
        sb.appendLine("# Fixture: ${lut.fixtureName}")
        sb.appendLine("# Target: ${lut.targetColorspace.name}")
        sb.appendLine("# Strategy: ${lut.strategy}")
        sb.appendLine("# Generated: ${
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())
        }")
        sb.appendLine("# ${lut.notes}")
        sb.appendLine()
        sb.appendLine("TITLE \"${lut.fixtureName}_${lut.targetColorspace.name}\"")
        sb.appendLine()
        sb.appendLine("LUT_3D_SIZE ${lut.size}")
        sb.appendLine()
        sb.appendLine(
            "DOMAIN_MIN ${lut.inputMin[0]} ${lut.inputMin[1]} ${lut.inputMin[2]}"
        )
        sb.appendLine(
            "DOMAIN_MAX ${lut.inputMax[0]} ${lut.inputMax[1]} ${lut.inputMax[2]}"
        )
        sb.appendLine()

        for (entry in lut.entries) {
            sb.appendLine("%.6f %.6f %.6f".format(entry[0], entry[1], entry[2]))
        }
        return sb.toString()
    }

    fun exportCubeFile(lut: LUTData): File {
        val safeName = lut.fixtureName.replace("[^A-Za-z0-9_\\-]".toRegex(), "_")
        val file = File(
            context.cacheDir,
            "${safeName}_${lut.targetColorspace.name}.cube"
        )
        file.writeText(toCubeFormat(lut))
        Timber.d("Wrote LUT to ${file.absolutePath} (${file.length()} bytes)")
        return file
    }

    // ─── Math Helpers ────────────────────────────────────────────────────────────

    private fun matMul3x3(m: Array<FloatArray>, v: FloatArray): FloatArray {
        return floatArrayOf(
            m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
            m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
            m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]
        )
    }

    private fun identityMatrix() = arrayOf(
        floatArrayOf(1f, 0f, 0f),
        floatArrayOf(0f, 1f, 0f),
        floatArrayOf(0f, 0f, 1f)
    )

    private fun getTargetMatrix(target: TargetColorspace) = when (target) {
        TargetColorspace.REC709 -> XYZ_TO_REC709
        TargetColorspace.REC2020 -> XYZ_TO_REC2020
        TargetColorspace.ACES_AP0 -> XYZ_TO_ACES_AP0
        TargetColorspace.ACES_AP1_ACESCG -> XYZ_TO_ACES_AP1
    }

    fun listSavedLUTs(): List<File> {
        return context.cacheDir.listFiles()
            ?.filter { it.extension == "cube" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
}
