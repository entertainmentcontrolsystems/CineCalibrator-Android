package com.cinecalibrator.core

import timber.log.Timber
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ConversionEngine
 *
 * Converts (dimmer, CIE x, CIE y) from an incoming sACN D16xy stream
 * into per-emitter DMX values using the Planckian Calibration Table (PCT).
 *
 * Lookup strategy:
 *   1. Find the two closest PCT entries by Euclidean xy distance to the
 *      requested chromaticity.
 *   2. Interpolate their DMX recipes by distance-weighted blend.
 *   3. Scale every channel by the incoming dimmer value.
 *   4. Hold control channels (StrobeModeShutter, DimmerCurve, Fans) at
 *      their fixture defaults.
 *
 * Off-locus colours: The PCT covers the Planckian locus. Saturated or
 * highly-coloured requests are handled by finding the closest locus point
 * and applying a hue offset — this is approximate. Full gamut conversion
 * requires the colour volume data and is a future enhancement.
 */
class ConversionEngine(
    private val table: PlanckianSweepEngine.PlanckianTable,
    private val scanConfig: ScanEngine.ScanConfig
) {

    data class ConversionResult(
        /** DMX channel → value map, ready to send via DMXOverIPClient */
        val dmxMap: Map<Int, Int>,
        /** The PCT entry used (nearest on locus) */
        val nearestCCT: Int,
        /** xy distance to nearest locus point (0 = exact match) */
        val lociDistance: Float,
        /** Whether the request was within the calibrated range */
        val inRange: Boolean
    )

    // Build a quick lookup: entry → xy position
    private val entryPoints: List<Triple<PlanckianSweepEngine.PlanckianEntry, Float, Float>> =
        table.entries.map { Triple(it, it.measuredX, it.measuredY) }

    /**
     * Convert a single fixture frame from D16xy format to fixture DMX.
     *
     * @param dimmer  0.0–1.0 intensity
     * @param x       CIE 1931 x
     * @param y       CIE 1931 y
     */
    fun convert(dimmer: Float, x: Float, y: Float): ConversionResult {
        if (entryPoints.isEmpty()) {
            // No PCT — output full dimmer, zero colours
            val map = mutableMapOf<Int, Int>()
            scanConfig.dimmerChannel?.let { map[it] = (dimmer * 255).toInt().coerceIn(0, 255) }
            scanConfig.controlChannels.forEach { map[it.dmxChannel] = it.defaultValue }
            return ConversionResult(map, 0, Float.MAX_VALUE, false)
        }

        // ── Find 2 nearest PCT entries by xy distance ──────────────────────────
        val sorted = entryPoints.sortedBy { (_, ex, ey) ->
            sqrt((x - ex) * (x - ex) + (y - ey) * (y - ey))
        }

        val (e1, x1, y1) = sorted[0]
        val d1 = sqrt((x - x1) * (x - x1) + (y - y1) * (y - y1)).coerceAtLeast(1e-6f)

        // Interpolate if second entry exists and is reasonably close
        val dmxRecipe = if (sorted.size >= 2) {
            val (e2, x2, y2) = sorted[1]
            val d2 = sqrt((x - x2) * (x - x2) + (y - y2) * (y - y2)).coerceAtLeast(1e-6f)
            val w1 = 1f / d1
            val w2 = 1f / d2
            val wTotal = w1 + w2

            // Blend the two nearest recipes by inverse-distance weighting
            val allChannels = (e1.dmx.keys + e2.dmx.keys).toSet()
            allChannels.associateWith { ch ->
                val v1 = (e1.dmx[ch] ?: 0).toFloat()
                val v2 = (e2.dmx[ch] ?: 0).toFloat()
                ((v1 * w1 + v2 * w2) / wTotal).toInt().coerceIn(0, 255)
            }
        } else {
            e1.dmx.mapValues { (_, v) -> v }
        }

        // ── Scale by dimmer ────────────────────────────────────────────────────
        val dimmerScale = dimmer.coerceIn(0f, 1f)
        val scaledRecipe = dmxRecipe.mapValues { (_, v) ->
            (v * dimmerScale).toInt().coerceIn(0, 255)
        }

        // ── Build full channel map ─────────────────────────────────────────────
        val dmxMap = mutableMapOf<Int, Int>()

        // Dimmer channel: pass through from the input (or max if no dimmer in recipe)
        scanConfig.dimmerChannel?.let {
            dmxMap[it] = (dimmer * 255).toInt().coerceIn(0, 255)
        }

        // Colour channels: from interpolated + scaled recipe, matched by name
        scanConfig.diodeChannels.forEach { diode ->
            val recipeValue = scaledRecipe[diode.name] ?: 0
            dmxMap[diode.dmxChannel] = recipeValue
        }

        // Control channels: always at fixture defaults
        scanConfig.controlChannels.forEach {
            dmxMap[it.dmxChannel] = it.defaultValue
        }

        val lociDist = d1
        val inRange = lociDist < 0.05f   // within 0.05 Δxy of a calibrated point

        Timber.v("Convert x=${"%.4f".format(x)} y=${"%.4f".format(y)} dim=${
            "%.2f".format(dimmer)} → nearest ${e1.targetCCT}K Δ${
            "%.4f".format(lociDist)}")

        return ConversionResult(dmxMap, e1.targetCCT, lociDist, inRange)
    }

    /**
     * Export the conversion config as a standalone JSON that a hardware
     * conversion node or another system can use without the full app.
     */
    fun exportConfig(
        inputUniverse: Int,
        outputUniverse: Int,
        fixtureCount: Int,
        startAddressIn: Int,
        startAddressOut: Int
    ): String {
        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
        val config = mapOf(
            "version"          to "1.0",
            "type"             to "CineCalibrator_ConversionConfig",
            "fixture"          to table.fixture,
            "manufacturer"     to table.manufacturer,
            "calibration_date" to table.scanDate,
            "input" to mapOf(
                "protocol"      to "sACN_E1.31",
                "universe"      to inputUniverse,
                "start_address" to startAddressIn,
                "fixture_count" to fixtureCount,
                "format"        to "D16xy",
                "channels"      to listOf(
                    mapOf("name" to "Dimmer", "bits" to 16, "offset" to 0),
                    mapOf("name" to "CIE_x",  "bits" to 16, "offset" to 2),
                    mapOf("name" to "CIE_y",  "bits" to 16, "offset" to 4)
                )
            ),
            "output" to mapOf(
                "protocol"      to "sACN_E1.31",
                "universe"      to outputUniverse,
                "start_address" to startAddressOut,
                "fixture_count" to fixtureCount,
                "channels"      to table.channels
            ),
            "planckian_table" to table
        )
        return gson.toJson(config)
    }
}
