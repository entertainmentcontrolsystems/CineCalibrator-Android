package com.cinecalibrator.core

import kotlin.math.*

/**
 * CameraGamutProfile
 *
 * Derived from a desaturation-path calibration scan, this object encodes the
 * region of CIE xy space where a specific phone camera makes reliable
 * colorimetric measurements.
 *
 * ── How it's built ────────────────────────────────────────────────────────────
 * During calibration, each DesatStep has a measured_xy and a predicted_xy, and
 * a flag indicating whether the measurement was reliable.  We take the convex
 * hull of all RELIABLE measured points.  Any subsequent measurement that falls
 * inside that hull can be reported with confidence; measurements outside it are
 * extrapolations and should be flagged.
 *
 * ── CCT / Duv special case ────────────────────────────────────────────────────
 * The Planckian (blackbody) locus runs from roughly (0.652, 0.345) at 1667 K
 * to (0.312, 0.329) at 6500 K.  This arc passes through the CENTRE of the CIE
 * diagram — well inside the reliable zone for virtually every phone camera.
 * So even a camera that cannot accurately measure saturated primaries CAN give
 * reliable CCT and Duv readings for white and near-white sources.
 *
 * We explicitly check whether a measured xy is within a configurable "CCT belt"
 * around the Planckian locus and, if so, declare CCT/Duv reliable regardless of
 * whether the point is within the convex hull.
 *
 * ── Confidence levels ─────────────────────────────────────────────────────────
 * FULL        — inside hull, low residual error during calibration
 * CCT_ONLY    — outside hull but within the Planckian belt; CCT/Duv trustworthy
 * MARGINAL    — within 0.03 Δxy of the hull boundary
 * UNRELIABLE  — outside hull, outside Planckian belt
 */
class CameraGamutProfile private constructor(
    val deviceModel: String,
    val referenceFixture: String,
    /** Convex hull of reliable calibration measurement points, in CIE xy */
    val hullPoints: List<CameraCalibration.CIExy>,
    /** Average Δxy residual across all reliable calibration samples */
    val avgResidualDxy: Double,
    /** Max Δxy residual across reliable calibration samples */
    val maxResidualDxy: Double,
    /** Number of reliable samples used to build the hull */
    val sampleCount: Int,
    /** How far (Δxy) from the Planckian locus a measurement can be and still get CCT_ONLY confidence */
    val planckianBeltRadius: Double = 0.06
) {

    // ── Confidence Assessment ──────────────────────────────────────────────────

    enum class Confidence {
        /** Fully within camera's measured gamut — all colorimetric values reliable */
        FULL,
        /** Near or slightly outside hull but within the Planckian locus belt — CCT/Duv reliable */
        CCT_ONLY,
        /** Marginal — within ~0.03 Δxy of hull edge — use with caution */
        MARGINAL,
        /** Outside camera gamut and not near Planckian locus — values unreliable */
        UNRELIABLE;

        val label: String get() = when (this) {
            FULL       -> "✓ Reliable"
            CCT_ONLY   -> "⚠ CCT/Duv only"
            MARGINAL   -> "⚠ Marginal"
            UNRELIABLE -> "✗ Outside camera gamut"
        }
        val isCCTReliable: Boolean  get() = this == FULL || this == CCT_ONLY || this == MARGINAL
        val isXYReliable: Boolean   get() = this == FULL || this == MARGINAL
    }

    data class ConfidenceResult(
        val confidence: Confidence,
        /** Δxy distance from hull boundary; negative = inside, positive = outside */
        val distanceFromHull: Double,
        /** Δxy distance from nearest point on Planckian locus */
        val distanceFromPlanckian: Double,
        /** Estimated CCT reliability note */
        val cctNote: String
    )

    fun assess(xy: CameraCalibration.CIExy): ConfidenceResult {
        val insideDist = signedDistanceFromHull(xy)      // negative = inside
        val planckDist = distanceFromPlanckianLocus(xy)

        val confidence = when {
            insideDist <= 0.0                    -> Confidence.FULL
            planckDist <= planckianBeltRadius    -> Confidence.CCT_ONLY
            insideDist <= 0.03                   -> Confidence.MARGINAL
            else                                 -> Confidence.UNRELIABLE
        }

        val cctNote = when (confidence) {
            Confidence.FULL     -> "CCT and Duv reliable (within camera gamut)"
            Confidence.CCT_ONLY -> "CCT/Duv reliable — xy falls on Planckian locus " +
                                   "even though outside camera's primary gamut"
            Confidence.MARGINAL -> "CCT/Duv probably reliable; xy accuracy marginal"
            Confidence.UNRELIABLE -> "CCT/Duv unreliable — outside camera gamut and " +
                                     "not near Planckian locus"
        }
        return ConfidenceResult(confidence, insideDist, planckDist, cctNote)
    }

    // ── Convex Hull Geometry ───────────────────────────────────────────────────

    /**
     * Signed distance from the convex hull boundary.
     * Negative = inside hull, positive = outside.
     */
    private fun signedDistanceFromHull(p: CameraCalibration.CIExy): Double {
        if (hullPoints.size < 3) return 0.0  // degenerate — assume inside

        var maxSignedDist = Double.NEGATIVE_INFINITY
        val n = hullPoints.size
        for (i in 0 until n) {
            val a = hullPoints[i]
            val b = hullPoints[(i + 1) % n]
            // Outward normal of edge a→b
            val ex = b.x - a.x
            val ey = b.y - a.y
            val len = sqrt(ex * ex + ey * ey)
            if (len < 1e-10) continue
            val nx = ey / len   // perpendicular (outward for CCW hull)
            val ny = -ex / len
            val dist = nx * (p.x - a.x) + ny * (p.y - a.y)
            if (dist > maxSignedDist) maxSignedDist = dist
        }
        return maxSignedDist
    }

    // ── Planckian Locus Distance ───────────────────────────────────────────────

    /**
     * Minimum Δxy distance from the given point to the Planckian (blackbody) locus.
     * We sample the locus at 50K intervals from 1667K to 10000K.
     */
    private fun distanceFromPlanckianLocus(p: CameraCalibration.CIExy): Double {
        var minDist = Double.MAX_VALUE
        var cct = 1667.0
        while (cct <= 10000.0) {
            val (u, v) = planckianUV(cct)
            // Convert CIE 1960 uv → xy
            val px = 3.0 * u / (2.0 * u - 8.0 * v + 4.0)
            val py = 2.0 * v / (2.0 * u - 8.0 * v + 4.0)
            val d = sqrt((p.x - px).pow(2) + (p.y - py).pow(2))
            if (d < minDist) minDist = d
            cct += 50.0
        }
        return minDist
    }

    private fun planckianUV(cct: Double): Pair<Double, Double> {
        // Judd / CIE 1960 UCS Planckian chromaticity
        val t = cct
        val u = (0.860117757 + 1.54118254e-4 * t + 1.28641212e-7 * t * t) /
                (1.0 + 8.42420235e-4 * t + 7.08145163e-7 * t * t)
        val v = (0.317398726 + 4.22806245e-5 * t + 4.20481691e-8 * t * t) /
                (1.0 - 2.89741816e-5 * t + 1.61456053e-7 * t * t)
        return u to v
    }

    // ── Hull area (for display) ────────────────────────────────────────────────

    /** Area of the convex hull polygon in CIE xy units */
    val hullArea: Double by lazy {
        if (hullPoints.size < 3) return@lazy 0.0
        var area = 0.0
        val n = hullPoints.size
        for (i in 0 until n) {
            val a = hullPoints[i]; val b = hullPoints[(i + 1) % n]
            area += a.x * b.y - b.x * a.y
        }
        abs(area) / 2.0
    }

    /** Percentage of the visible gamut (horseshoe area ≈ 0.199) covered by camera */
    val visibleGamutCoveragePercent: Double get() = (hullArea / 0.199 * 100.0).coerceIn(0.0, 100.0)

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {

        fun fromDesatSteps(
            steps: List<CameraCalibration.DesatStep>,
            deviceModel: String,
            referenceFixture: String
        ): CameraGamutProfile? {
            val reliable = steps.filter { it.isReliable }
            if (reliable.size < 3) return null

            // Build convex hull from reliable measured points
            val points = reliable.map { it.measuredXY }
            val hull = convexHull(points)

            val deltas = reliable.map { s ->
                sqrt((s.measuredXY.x - s.predictedXY.x).pow(2) +
                     (s.measuredXY.y - s.predictedXY.y).pow(2))
            }

            return CameraGamutProfile(
                deviceModel      = deviceModel,
                referenceFixture = referenceFixture,
                hullPoints       = hull,
                avgResidualDxy   = deltas.average(),
                maxResidualDxy   = deltas.max(),
                sampleCount      = reliable.size
            )
        }

        // Graham scan convex hull
        private fun convexHull(pts: List<CameraCalibration.CIExy>): List<CameraCalibration.CIExy> {
            if (pts.size < 3) return pts
            val sorted = pts.sortedWith(compareBy({ it.x }, { it.y }))

            fun cross(o: CameraCalibration.CIExy, a: CameraCalibration.CIExy, b: CameraCalibration.CIExy) =
                (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

            val lower = mutableListOf<CameraCalibration.CIExy>()
            for (p in sorted) {
                while (lower.size >= 2 && cross(lower[lower.size-2], lower[lower.size-1], p) <= 0)
                    lower.removeAt(lower.size - 1)
                lower.add(p)
            }
            val upper = mutableListOf<CameraCalibration.CIExy>()
            for (p in sorted.reversed()) {
                while (upper.size >= 2 && cross(upper[upper.size-2], upper[upper.size-1], p) <= 0)
                    upper.removeAt(upper.size - 1)
                upper.add(p)
            }
            lower.removeAt(lower.size - 1)
            upper.removeAt(upper.size - 1)
            return lower + upper
        }
    }
}
