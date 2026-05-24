package com.cinecalibrator.core

import kotlin.math.*

/**
 * CineCalibrator Color Science Engine
 *
 * Handles all colorimetric calculations:
 * - Camera RGB → CIE XYZ conversion
 * - CIE XYZ → CIE xy chromaticity
 * - Luminance / flux estimation
 * - Gamut comparison (Rec.709, Rec.2020, ACES AP0/AP1)
 * - CCT (Correlated Color Temperature) via Robertson method
 * - Delta-E color difference
 */
object ColorScience {

    // ─── Camera RAW Linear RGB → CIE XYZ (D65) ─────────────────────────────────
    // sRGB primaries matrix (IEC 61966-2-1)
    private val SRGB_TO_XYZ_D65 = arrayOf(
        doubleArrayOf(0.4124564, 0.3575761, 0.1804375),
        doubleArrayOf(0.2126729, 0.7151522, 0.0721750),
        doubleArrayOf(0.0193339, 0.1191920, 0.9503041)
    )

    // Rec.709 uses same primaries as sRGB
    private val REC709_TO_XYZ_D65 = SRGB_TO_XYZ_D65

    // Rec.2020 primaries (ITU-R BT.2020)
    private val REC2020_TO_XYZ_D65 = arrayOf(
        doubleArrayOf(0.6369580, 0.1446169, 0.1688810),
        doubleArrayOf(0.2627002, 0.6779981, 0.0593017),
        doubleArrayOf(0.0000000, 0.0280727, 1.0609851)
    )

    // ACES AP0 primaries (SMPTE ST 2065-1)
    private val ACES_AP0_TO_XYZ = arrayOf(
        doubleArrayOf(0.9525523959, 0.0000000000, 0.0000936786),
        doubleArrayOf(0.3439664498, 0.7281660966, -0.0721325464),
        doubleArrayOf(0.0000000000, 0.0000000000, 1.0088251844)
    )

    // ACES AP1 primaries (ACEScg working space)
    private val ACES_AP1_TO_XYZ = arrayOf(
        doubleArrayOf(0.6624541811, 0.1340042065, 0.1561876870),
        doubleArrayOf(0.2722287168, 0.6740817658, 0.0536895174),
        doubleArrayOf(-0.0055746495, 0.0040607335, 1.0103391003)
    )

    // ─── Gamut Definition ───────────────────────────────────────────────────────

    data class CIExy(val x: Double, val y: Double) {
        override fun toString() = "x=%.4f, y=%.4f".format(x, y)
    }

    data class Gamut(
        val name: String,
        val red: CIExy,
        val green: CIExy,
        val blue: CIExy,
        val whitePoint: CIExy
    )

    val REC709_GAMUT = Gamut(
        name = "Rec.709",
        red = CIExy(0.6400, 0.3300),
        green = CIExy(0.3000, 0.6000),
        blue = CIExy(0.1500, 0.0600),
        whitePoint = CIExy(0.3127, 0.3290)  // D65
    )

    val REC2020_GAMUT = Gamut(
        name = "Rec.2020",
        red = CIExy(0.7080, 0.2920),
        green = CIExy(0.1700, 0.7970),
        blue = CIExy(0.1310, 0.0460),
        whitePoint = CIExy(0.3127, 0.3290)  // D65
    )

    val ACES_AP0_GAMUT = Gamut(
        name = "ACES AP0",
        red = CIExy(0.7347, 0.2653),
        green = CIExy(0.0000, 1.0000),
        blue = CIExy(0.0001, -0.0770),
        whitePoint = CIExy(0.32168, 0.33767)  // ACES white
    )

    val ACES_AP1_GAMUT = Gamut(
        name = "ACES AP1 (ACEScg)",
        red = CIExy(0.7130, 0.2930),
        green = CIExy(0.1650, 0.8300),
        blue = CIExy(0.1280, 0.0440),
        whitePoint = CIExy(0.32168, 0.33767)  // ACES white
    )

    val ALL_REFERENCE_GAMUTS = listOf(REC709_GAMUT, REC2020_GAMUT, ACES_AP0_GAMUT, ACES_AP1_GAMUT)

    // ─── Data Models ────────────────────────────────────────────────────────────

    data class DiodeMeasurement(
        val diodeIndex: Int,
        val diodeName: String,
        val rawR: Double,
        val rawG: Double,
        val rawB: Double,
        val tristX: Double,           // CIE XYZ tristimulus X
        val tristY: Double,           // CIE XYZ tristimulus Y (relative luminance)
        val tristZ: Double,           // CIE XYZ tristimulus Z
        val x: Double,                // CIE 1931 chromaticity x
        val y: Double,                // CIE 1931 chromaticity y
        val fluxRelative: Double,     // tristY normalised to 0-1
        val cct: Double?,             // Correlated Color Temperature (K), null if out of range
        val duv: Double,              // Distance from Planckian locus
        val gamutCoverage: Map<String, Double>,  // % inside each reference gamut
        val measurementConfidence: CameraGamutProfile.Confidence = CameraGamutProfile.Confidence.FULL,
        val confidenceNote: String = ""
    )

    data class ScanResult(
        val sessionId: String,
        val timestamp: Long,
        val fixtureName: String,
        val fixtureManufacturer: String,
        val measurements: List<DiodeMeasurement>,
        val notes: String = ""
    )

    // ─── Primary Conversion Functions ───────────────────────────────────────────

    /**
     * Convert linear (gamma-decoded) camera RGB [0..1] to CIE XYZ using sRGB primaries.
     * For best accuracy, use RAW camera data and apply camera-specific color matrix.
     */
    fun linearRGBtoXYZ(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
        val X = SRGB_TO_XYZ_D65[0][0] * r + SRGB_TO_XYZ_D65[0][1] * g + SRGB_TO_XYZ_D65[0][2] * b
        val Y = SRGB_TO_XYZ_D65[1][0] * r + SRGB_TO_XYZ_D65[1][1] * g + SRGB_TO_XYZ_D65[1][2] * b
        val Z = SRGB_TO_XYZ_D65[2][0] * r + SRGB_TO_XYZ_D65[2][1] * g + SRGB_TO_XYZ_D65[2][2] * b
        return Triple(X, Y, Z)
    }

    /**
     * Decode sRGB gamma (IEC 61966-2-1).
     */
    fun sRGBtoLinear(v: Double): Double {
        return if (v <= 0.04045) v / 12.92
        else ((v + 0.055) / 1.055).pow(2.4)
    }

    /**
     * Convert 8-bit sRGB [0..255] pixel to linear RGB [0..1]
     */
    fun pixel8bitToLinearRGB(r: Int, g: Int, b: Int): Triple<Double, Double, Double> {
        return Triple(
            sRGBtoLinear(r / 255.0),
            sRGBtoLinear(g / 255.0),
            sRGBtoLinear(b / 255.0)
        )
    }

    /**
     * XYZ → CIE 1931 xy chromaticity
     */
    fun xyzToCIExy(X: Double, Y: Double, Z: Double): CIExy {
        val sum = X + Y + Z
        if (sum < 1e-10) return CIExy(0.3127, 0.3290)  // return D65 if black
        return CIExy(X / sum, Y / sum)
    }

    /**
     * Full pipeline: sRGB pixel → CIE xy + Y luminance
     */
    fun pixelToCIExy(r: Int, g: Int, b: Int): Triple<CIExy, Double, Double> {
        val (linR, linG, linB) = pixel8bitToLinearRGB(r, g, b)
        val (X, Y, Z) = linearRGBtoXYZ(linR, linG, linB)
        val xy = xyzToCIExy(X, Y, Z)
        return Triple(xy, Y, X + Y + Z)
    }

    // ─── CCT (Robertson Method) ──────────────────────────────────────────────────

    private data class UVIsotherm(val u: Double, val t: Double, val reciprocal: Double)

    private val ROBERTSON_TABLE = listOf(
        UVIsotherm(0.18006, 0.26352, -0.24341),
        UVIsotherm(0.18066, 0.26589, -0.25479),
        UVIsotherm(0.18133, 0.26846, -0.26876),
        UVIsotherm(0.18208, 0.27119, -0.28539),
        UVIsotherm(0.18293, 0.27407, -0.30470),
        UVIsotherm(0.18388, 0.27709, -0.32675),
        UVIsotherm(0.18494, 0.28021, -0.35156),
        UVIsotherm(0.18611, 0.28342, -0.37915),
        UVIsotherm(0.18740, 0.28668, -0.40955),
        UVIsotherm(0.18880, 0.28997, -0.44278),
        UVIsotherm(0.19032, 0.29326, -0.47888),
        UVIsotherm(0.19462, 0.30141, -0.58204),
        UVIsotherm(0.19962, 0.30921, -0.70471),
        UVIsotherm(0.20525, 0.31647, -0.84901),
        UVIsotherm(0.21142, 0.32312, -1.0182),
        UVIsotherm(0.21807, 0.32909, -1.2168),
        UVIsotherm(0.22511, 0.33439, -1.4512),
        UVIsotherm(0.23247, 0.33904, -1.7298),
        UVIsotherm(0.24010, 0.34308, -2.0637),
        UVIsotherm(0.24792, 0.34655, -2.4681),
        UVIsotherm(0.25591, 0.34951, -2.9641),
        UVIsotherm(0.26400, 0.35200, -3.5814),
        UVIsotherm(0.27218, 0.35407, -4.3633),
        UVIsotherm(0.28039, 0.35577, -5.3762),
        UVIsotherm(0.28863, 0.35714, -6.7262),
        UVIsotherm(0.29685, 0.35823, -8.5955),
        UVIsotherm(0.30505, 0.35907, -11.324),
        UVIsotherm(0.31320, 0.35968, -15.628),
        UVIsotherm(0.32129, 0.36011, -23.325),
        UVIsotherm(0.32931, 0.36038, -40.770),
        UVIsotherm(0.33724, 0.36051, -116.45)
    )

    private val CCT_MRD = listOf(
        1000, 1100, 1200, 1300, 1400, 1500, 1600, 1700, 1800, 1900,
        2000, 2100, 2200, 2300, 2400, 2500, 2600, 2700, 2800, 2900,
        3000, 3100, 3200, 3300, 3400, 3500, 3600, 3700, 3800, 3900, 4000
    )

    /**
     * Estimate CCT using Robertson's method.
     * Returns null if the color is too far from the Planckian locus (duv > 0.05).
     */
    fun cctFromXYZ(X: Double, Y: Double, Z: Double): Pair<Double?, Double> {
        if (Y < 1e-10) return Pair(null, 0.0)
        // Convert to CIE 1960 UCS
        val denom = X + 15.0 * Y + 3.0 * Z
        if (denom < 1e-10) return Pair(null, 0.0)
        val u = 4.0 * X / denom
        val v = 6.0 * Y / denom

        var last = 0.0
        for (i in ROBERTSON_TABLE.indices) {
            val ri = ROBERTSON_TABLE[i]
            val di = (v - ri.t) - ri.reciprocal * (u - ri.u)
            if (i > 0 && last * di < 0) {
                // Interpolate
                val f = last / (last - di)
                val cct = CCT_MRD[i - 1] + f * (CCT_MRD[i] - CCT_MRD[i - 1])
                // Compute duv
                val vpPlanck = ROBERTSON_TABLE[i - 1].t + f * (ri.t - ROBERTSON_TABLE[i - 1].t)
                val upPlanck = ROBERTSON_TABLE[i - 1].u + f * (ri.u - ROBERTSON_TABLE[i - 1].u)
                val duv = sqrt((u - upPlanck).pow(2) + (v - vpPlanck).pow(2))
                return if (duv > 0.05) Pair(null, duv) else Pair(cct, duv)
            }
            last = di
        }
        return Pair(null, 0.0)
    }

    // ─── Gamut Coverage ──────────────────────────────────────────────────────────

    /**
     * Determine if a CIExy point is inside a given gamut triangle.
     * Uses barycentric coordinates.
     */
    fun isInsideGamut(point: CIExy, gamut: Gamut): Boolean {
        return isInsideTriangle(point, gamut.red, gamut.green, gamut.blue)
    }

    private fun sign(p1: CIExy, p2: CIExy, p3: CIExy): Double {
        return (p1.x - p3.x) * (p2.y - p3.y) - (p2.x - p3.x) * (p1.y - p3.y)
    }

    private fun isInsideTriangle(pt: CIExy, v1: CIExy, v2: CIExy, v3: CIExy): Boolean {
        val d1 = sign(pt, v1, v2)
        val d2 = sign(pt, v2, v3)
        val d3 = sign(pt, v3, v1)
        val hasNeg = (d1 < 0) || (d2 < 0) || (d3 < 0)
        val hasPos = (d1 > 0) || (d2 > 0) || (d3 > 0)
        return !(hasNeg && hasPos)
    }

    /**
     * Calculate what fraction of the measured gamut falls inside a reference gamut.
     * Takes a list of diode CIExy points (typically R, G, B primaries of the fixture),
     * builds their convex hull, and calculates triangle intersection area ratio.
     */
    fun calculateGamutCoveragePercent(
        measuredPrimaries: List<CIExy>,
        referenceGamut: Gamut
    ): Double {
        if (measuredPrimaries.size < 3) return 0.0

        // Build convex hull of ALL measured primaries — using only first 3 was the bug
        val hull = convexHull(measuredPrimaries)
        if (hull.size < 3) return 0.0

        val refVertices = listOf(referenceGamut.red, referenceGamut.green, referenceGamut.blue)
        val refArea = polygonArea(refVertices)
        if (refArea < 1e-10) return 0.0

        // Clip fixture hull against reference triangle using Sutherland-Hodgman
        val intersectionArea = polygonClipArea(hull, refVertices)
        return (intersectionArea / refArea * 100.0).coerceIn(0.0, 100.0)
    }

    /** Sutherland-Hodgman: clip subject polygon against convex clip polygon, return area */
    private fun polygonClipArea(subject: List<CIExy>, clip: List<CIExy>): Double {
        var poly = subject.toList()
        for (i in clip.indices) {
            if (poly.isEmpty()) return 0.0
            val a = clip[i]
            val b = clip[(i + 1) % clip.size]
            poly = sutherlandHodgmanClip(poly, a, b)
        }
        return polygonArea(poly)
    }

    /** Graham scan convex hull — ensures we use all primaries, not just first 3 */
    private fun convexHull(pts: List<CIExy>): List<CIExy> {
        if (pts.size < 3) return pts
        val sorted = pts.sortedWith(compareBy({ it.x }, { it.y }))
        fun cross(o: CIExy, a: CIExy, b: CIExy) =
            (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = mutableListOf<CIExy>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size-2], lower[lower.size-1], p) <= 0)
                lower.removeAt(lower.size - 1)
            lower.add(p)
        }
        val upper = mutableListOf<CIExy>()
        for (p in sorted.reversed()) {
            while (upper.size >= 2 && cross(upper[upper.size-2], upper[upper.size-1], p) <= 0)
                upper.removeAt(upper.size - 1)
            upper.add(p)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    private fun sutherlandHodgmanClip(
        polygon: List<CIExy>,
        edgeA: CIExy,
        edgeB: CIExy
    ): List<CIExy> {
        val output = mutableListOf<CIExy>()
        for (i in polygon.indices) {
            val current = polygon[i]
            val previous = polygon[(i - 1 + polygon.size) % polygon.size]
            val currInside = isLeftOf(edgeA, edgeB, current)
            val prevInside = isLeftOf(edgeA, edgeB, previous)

            if (currInside) {
                if (!prevInside) output.add(lineIntersect(previous, current, edgeA, edgeB))
                output.add(current)
            } else if (prevInside) {
                output.add(lineIntersect(previous, current, edgeA, edgeB))
            }
        }
        return output
    }

    private fun isLeftOf(a: CIExy, b: CIExy, p: CIExy): Boolean {
        return ((b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)) >= 0
    }

    private fun lineIntersect(p1: CIExy, p2: CIExy, p3: CIExy, p4: CIExy): CIExy {
        val denom = (p1.x - p2.x) * (p3.y - p4.y) - (p1.y - p2.y) * (p3.x - p4.x)
        if (abs(denom) < 1e-10) return p1
        val t = ((p1.x - p3.x) * (p3.y - p4.y) - (p1.y - p3.y) * (p3.x - p4.x)) / denom
        return CIExy(p1.x + t * (p2.x - p1.x), p1.y + t * (p2.y - p1.y))
    }

    private fun polygonArea(points: List<CIExy>): Double {
        var area = 0.0
        val n = points.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += points[i].x * points[j].y
            area -= points[j].x * points[i].y
        }
        return abs(area) / 2.0
    }

    // ─── Full Diode Measurement ───────────────────────────────────────────────────

    fun buildDiodeMeasurement(
        index: Int,
        name: String,
        pixels: List<Triple<Int, Int, Int>>,  // list of (R,G,B) samples from ROI
        measuredPrimaries: List<CIExy>? = null
    ): DiodeMeasurement {
        if (pixels.isEmpty()) {
            return DiodeMeasurement(
                index, name, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.3127, 0.3290,
                0.0, null, 0.0, emptyMap()
            )
        }

        // Average the ROI pixels
        val avgR = pixels.map { it.first }.average()
        val avgG = pixels.map { it.second }.average()
        val avgB = pixels.map { it.third }.average()

        val (linR, linG, linB) = pixel8bitToLinearRGB(avgR.toInt(), avgG.toInt(), avgB.toInt())
        val (X, Y, Z) = linearRGBtoXYZ(linR, linG, linB)
        val xy = xyzToCIExy(X, Y, Z)
        val (cct, duv) = cctFromXYZ(X, Y, Z)

        val gamutCoverage = mutableMapOf<String, Double>()
        if (measuredPrimaries != null && measuredPrimaries.size >= 3) {
            ALL_REFERENCE_GAMUTS.forEach { ref ->
                gamutCoverage[ref.name] = calculateGamutCoveragePercent(measuredPrimaries, ref)
            }
        }

        return DiodeMeasurement(
            diodeIndex = index,
            diodeName = name,
            rawR = avgR, rawG = avgG, rawB = avgB,
            tristX = X, tristY = Y, tristZ = Z,
            x = xy.x, y = xy.y,
            fluxRelative = Y.coerceIn(0.0, 1.0),
            cct = cct,
            duv = duv,
            gamutCoverage = gamutCoverage
        )
    }
}
