package com.cinecalibrator.core

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * GDTFExporter — GDTF 1.2 (DIN SPEC 15800) compliant output.
 *
 * Structure matches the mandatory GDTF 1.2 child-element order:
 *   AttributeDefinitions → Wheels → PhysicalDescriptions → Models →
 *   Geometries → DMXModes → Revisions → Emitters
 *
 * Without AttributeDefinitions and Geometries, EOS (and most other GDTF
 * consumers) crash on import because they cannot resolve attribute or
 * geometry references.
 */
class GDTFExporter(private val context: Context) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    // Standard GDTF 1.2 identity matrix for the Body geometry position
    private val IDENTITY_MATRIX =
        "{1.0000,0.0000,0.0000,0.0000}" +
        "{0.0000,1.0000,0.0000,0.0000}" +
        "{0.0000,0.0000,1.0000,0.0000}" +
        "{0.0000,0.0000,0.0000,1.0000}"

    fun export(
        scanResult: ColorScience.ScanResult,
        originalFixture: GDTFParser.GDTFFixture? = null,
        selectedMode: GDTFParser.GDTFMode? = null,
        fluxOverrides: Map<String, Double> = emptyMap()
    ): File {
        val xml = buildDescriptionXML(scanResult, originalFixture, selectedMode, fluxOverrides)
        val safeName = "${scanResult.fixtureManufacturer}_${scanResult.fixtureName}"
            .replace("[^A-Za-z0-9_\\-]".toRegex(), "_")
        val timestamp = timeFormat.format(Date())
        val file = File(context.cacheDir, "CineCalibrator_${safeName}_${timestamp}.gdtf")

        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("device_description.xml"))
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        Timber.d("GDTF exported: ${file.absolutePath} (${file.length()} bytes)")
        return file
    }

    private fun buildDescriptionXML(
        result: ColorScience.ScanResult,
        original: GDTFParser.GDTFFixture?,
        mode: GDTFParser.GDTFMode?,
        fluxOverrides: Map<String, Double> = emptyMap()
    ): String {
        // ── Flux normalisation ────────────────────────────────────────────────
        fun rawFlux(m: ColorScience.DiodeMeasurement): Double =
            fluxOverrides[m.diodeName]
                ?: fluxOverrides.entries.firstOrNull { (k, _) ->
                    k.lowercase().replace(" ", "") == m.diodeName.lowercase().replace(" ", "")
                }?.value
                ?: m.fluxRelative

        val maxRaw = result.measurements.maxOfOrNull { rawFlux(it) }?.takeIf { it > 0.0 } ?: 1.0
        fun normFlux(m: ColorScience.DiodeMeasurement) = (rawFlux(m) / maxRaw).coerceIn(0.001, 1.0)

        Timber.d("GDTF flux: peak=$maxRaw  source=${if (fluxOverrides.isNotEmpty()) "override" else "tristY"}")

        // ── Collect channels ──────────────────────────────────────────────────
        val channels = if (original != null && mode != null) mode.channels else emptyList()

        // All unique attribute names used (for AttributeDefinitions)
        val colorAttrs = result.measurements.map { "ColorAdd_${it.diodeName.replace(" ", "")}" }
        val allAttrs = (listOf("Dimmer") + colorAttrs +
            channels.filter { it.attribute != "Dimmer" }.map { it.attribute }).distinct()

        val shortName = original?.shortName ?: result.fixtureName.take(20)
        val longName  = "${result.fixtureName} [CineCalibrated]"
        val mfr       = result.fixtureManufacturer.ifEmpty { original?.manufacturer ?: "Unknown" }
        val modeNameStr = mode?.name ?: "CineCalibrator"
        val scanDate  = dateFormat.format(Date(result.timestamp))
        val avgCCT    = result.measurements.mapNotNull { m ->
            m.cct?.takeIf { it < 49000.0 && m.duv < 0.5 }
        }.average().let { if (it.isNaN()) null else it }
        val cov709    = result.measurements.mapNotNull { it.gamutCoverage["Rec.709"] }.average()
            .let { if (it.isNaN()) null else it }

        val desc = buildString {
            append("CineCalibrator $scanDate | ${result.measurements.size} emitters")
            avgCCT?.let { append(" | avgCCT ${it.toInt()}K") }
            cov709?.let { append(" | Rec709 ${String.format("%.1f", it)}%") }
        }

        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<GDTF DataVersion="1.2">""")
        sb.appendLine("""  <FixtureType""")
        sb.appendLine("""    Name="${escapeXml(longName)}"""")
        sb.appendLine("""    ShortName="${escapeXml(shortName)}"""")
        sb.appendLine("""    LongName="${escapeXml(longName)}"""")
        sb.appendLine("""    Manufacturer="${escapeXml(mfr)}"""")
        sb.appendLine("""    Description="${escapeXml(desc)}"""")
        sb.appendLine("""    FixtureTypeID="{${UUID.randomUUID()}}"""")
        sb.appendLine("""    RefFT="">""")
        sb.appendLine()

        // ── 1. AttributeDefinitions (REQUIRED — EOS crashes without this) ─────
        sb.appendLine("""    <AttributeDefinitions>""")
        sb.appendLine("""      <ActivationGroups/>""")
        sb.appendLine("""      <FeatureGroups>""")
        sb.appendLine("""        <FeatureGroup Name="Dimmer" Pretty="Dimmer">""")
        sb.appendLine("""          <Feature Name="Dimmer"/>""")
        sb.appendLine("""        </FeatureGroup>""")
        sb.appendLine("""        <FeatureGroup Name="Color" Pretty="Color">""")
        sb.appendLine("""          <Feature Name="Color"/>""")
        sb.appendLine("""        </FeatureGroup>""")
        // Always include Control group — catches DimmerCurve, StrobeModeShutter, Fans, etc.
        sb.appendLine("""        <FeatureGroup Name="Control" Pretty="Control">""")
        sb.appendLine("""          <Feature Name="Control"/>""")
        sb.appendLine("""        </FeatureGroup>""")
        sb.appendLine("""      </FeatureGroups>""")
        sb.appendLine("""      <Attributes>""")

        // Dimmer
        sb.appendLine("""        <Attribute Name="Dimmer" Pretty="Dimmer" ActivationGroup="" Feature="Dimmer.Dimmer" PhysicalUnit="LuminousIntensity"/>""")

        // Colour channels with measured CIE xy
        result.measurements.forEach { m ->
            val attr = gdtfColorAttr(m.diodeName)
            val wl = ciexyToApproxWavelength(m.x, m.y)
            val flux = normFlux(m)
            sb.appendLine("""        <Attribute Name="${escapeXml(attr)}" Pretty="${escapeXml(m.diodeName)}" ActivationGroup="" Feature="Color.Color" PhysicalUnit="None" Color="${String.format("%.4f %.4f %.4f", m.x, m.y, flux)}"${if (wl != null) """ DominantWaveLength="$wl"""" else ""}/>""")
        }

        // Control channels from original fixture — all non-colour, non-dimmer channels
        channels.filter { it.isControl }.forEach { ch ->
            sb.appendLine("""        <Attribute Name="${escapeXml(ch.attribute)}" Pretty="${escapeXml(GDTFParser.attributeDisplayName(ch.attribute))}" ActivationGroup="" Feature="Control.Control" PhysicalUnit="None"/>""")
        }

        sb.appendLine("""      </Attributes>""")
        sb.appendLine("""    </AttributeDefinitions>""")
        sb.appendLine()

        // ── 2. Wheels (REQUIRED — empty is fine) ─────────────────────────────
        sb.appendLine("""    <Wheels/>""")
        sb.appendLine()

        // ── 3. PhysicalDescriptions (REQUIRED — empty is fine) ───────────────
        sb.appendLine("""    <PhysicalDescriptions/>""")
        sb.appendLine()

        // ── 4. Models (REQUIRED — empty is fine) ─────────────────────────────
        sb.appendLine("""    <Models/>""")
        sb.appendLine()

        // ── 5. Geometries (REQUIRED — DMXChannel Geometry attr must resolve) ─
        sb.appendLine("""    <Geometries>""")
        sb.appendLine("""      <Geometry Type="General" Name="Body" Model="" Position="$IDENTITY_MATRIX"/>""")
        sb.appendLine("""    </Geometries>""")
        sb.appendLine()

        // ── 6. DMXModes ───────────────────────────────────────────────────────
        sb.appendLine("""    <DMXModes>""")
        sb.appendLine("""      <DMXMode Name="${escapeXml(modeNameStr)}" Geometry="Body">""")
        sb.appendLine("""        <DMXChannels>""")

        if (channels.isNotEmpty()) {
            // Use channels from original GDTF — preserves all 12 channels including controls
            channels.forEach { ch ->
                val emitterName = findEmitterForChannel(ch.attribute, result.measurements)
                val emitterAttr = if (emitterName != null) """ EmitterSpectrum="${escapeXml(emitterName)}"""" else ""
                // Use original Default and Highlight — critical for control channels:
                // DimmerCurve default might be 0 (linear), StrobeModeShutter default is 255 (open), etc.
                val defaultVal = "${ch.defaultValue}/1"
                val highlightVal = ch.highlightValue
                sb.appendLine("""          <DMXChannel DMXBreak="1" Offset="${ch.dmxAddress}" Highlight="$highlightVal" Geometry="Body">""")
                sb.appendLine("""            <LogicalChannel Attribute="${escapeXml(ch.attribute)}" Snap="No" Master="${if (ch.isDimmer) "None" else if (ch.isColor) "Grand" else "None"}">""")
                sb.appendLine("""              <ChannelFunction Attribute="${escapeXml(ch.attribute)}"$emitterAttr OriginalAttribute="" DMXFrom="0/1" Default="$defaultVal" PhysicalFrom="0" PhysicalTo="1" RealFade="0" RealAcceleration="0" WheelSlotIndex="0"/>""")
                sb.appendLine("""            </LogicalChannel>""")
                sb.appendLine("""          </DMXChannel>""")
            }
        } else {
            // Synthesise channels from measurement data
            sb.appendLine("""          <DMXChannel DMXBreak="1" Offset="1" Highlight="255" Geometry="Body">""")
            sb.appendLine("""            <LogicalChannel Attribute="Dimmer" Snap="No" Master="None">""")
            sb.appendLine("""              <ChannelFunction Attribute="Dimmer" OriginalAttribute="" DMXFrom="0/1" Default="255/1" PhysicalFrom="0" PhysicalTo="1" RealFade="0" RealAcceleration="0" WheelSlotIndex="0"/>""")
            sb.appendLine("""            </LogicalChannel>""")
            sb.appendLine("""          </DMXChannel>""")
            result.measurements.forEachIndexed { i, m ->
                val attr = gdtfColorAttr(m.diodeName)
                sb.appendLine("""          <DMXChannel DMXBreak="1" Offset="${i + 2}" Highlight="255" Geometry="Body">""")
                sb.appendLine("""            <LogicalChannel Attribute="${escapeXml(attr)}" Snap="No" Master="Grand">""")
                sb.appendLine("""              <ChannelFunction Attribute="${escapeXml(attr)}" EmitterSpectrum="${escapeXml(m.diodeName)}" OriginalAttribute="" DMXFrom="0/1" Default="0/1" PhysicalFrom="0" PhysicalTo="1" RealFade="0" RealAcceleration="0" WheelSlotIndex="0"/>""")
                sb.appendLine("""            </LogicalChannel>""")
                sb.appendLine("""          </DMXChannel>""")
            }
        }

        sb.appendLine("""        </DMXChannels>""")
        sb.appendLine("""        <Relations/>""")
        sb.appendLine("""      </DMXMode>""")
        sb.appendLine("""    </DMXModes>""")
        sb.appendLine()

        // ── 7. Revisions (REQUIRED — one entry minimum recommended) ──────────
        sb.appendLine("""    <Revisions>""")
        sb.appendLine("""      <Revision Text="CineCalibrator scan $scanDate" Date="${dateFormat.format(Date())}T00:00:00" UserName="CineCalibrator"/>""")
        sb.appendLine("""    </Revisions>""")
        sb.appendLine()

        // ── 8. Emitters (with measured CIE xy) ───────────────────────────────
        sb.appendLine("""    <Emitters>""")
        result.measurements.forEach { m ->
            val flux = normFlux(m)
            val wl   = ciexyToApproxWavelength(m.x, m.y)
            sb.append("""      <Emitter Name="${escapeXml(m.diodeName)}" Color="${String.format("%.4f %.4f %.4f", m.x, m.y, flux)}"""")
            if (wl != null) sb.append(""" DominantWaveLength="$wl"""")
            sb.append(""" DiodePitch="1"""")
            // Embed measurement notes in Description (the spec-sanctioned free text field)
            val emitterDesc = buildString {
                append("x=${String.format("%.6f", m.x)} y=${String.format("%.6f", m.y)}")
                append(" flux=${String.format("%.4f", flux)}")
                m.cct?.takeIf { it < 49000.0 }?.let { append(" CCT=${it.toInt()}K") }
                append(" Duv=${String.format("%.4f", m.duv)}")
            }
            sb.appendLine(""" Description="${escapeXml(emitterDesc)}"/>""")
        }
        sb.appendLine("""    </Emitters>""")
        sb.appendLine()

        sb.appendLine("""  </FixtureType>""")
        sb.appendLine("""</GDTF>""")
        return sb.toString()
    }

    /**
     * Maps common diode/emitter names to the closest GDTF 1.2 standard ColorAdd attribute suffix.
     * EOS, grandMA, and Capture all have built-in handling for these names.
     * Unknown names still work but produce an "Unknown parameter type" warning.
     *
     * GDTF 1.2 standard ColorAdd suffixes (DIN SPEC 15800 Annex A):
     *   R, G, B, W, WW, CW, A (amber), L (lime/green-yellow), RY, UV
     */
    private val DIODE_TO_GDTF_ATTR = mapOf(
        // Deep reds / reds
        "dr"         to "ColorAdd_R",   // Deep Red → closest standard is R
        "deepred"    to "ColorAdd_R",
        "r"          to "ColorAdd_R",
        "red"        to "ColorAdd_R",
        // Red-Yellows / Ambers
        "ry"         to "ColorAdd_RY",
        "redyellow"  to "ColorAdd_RY",
        "a"          to "ColorAdd_A",
        "amber"      to "ColorAdd_A",
        "gy"         to "ColorAdd_A",   // Green-Yellow lime → A is closest warm
        "greenyellow" to "ColorAdd_L",
        // Greens
        "g"          to "ColorAdd_G",
        "green"      to "ColorAdd_G",
        // Cyans / Blues
        "c"          to "ColorAdd_B",   // Cyan → closest is B (no standard Cyan suffix)
        "cyan"       to "ColorAdd_B",
        "b"          to "ColorAdd_B",
        "blue"       to "ColorAdd_B",
        // Indigo / UV / Violet
        "i"          to "ColorAdd_UV",  // Indigo → UV is the standard near-violet suffix
        "indigo"     to "ColorAdd_UV",
        "uv"         to "ColorAdd_UV",
        "violet"     to "ColorAdd_UV",
        // Whites
        "w"          to "ColorAdd_W",
        "white"      to "ColorAdd_W",
        "ww"         to "ColorAdd_WW",
        "warmwhite"  to "ColorAdd_WW",
        "cw"         to "ColorAdd_CW",
        "coolwhite"  to "ColorAdd_CW",
        // Lime (GDTF has L suffix since 1.1)
        "l"          to "ColorAdd_L",
        "lime"       to "ColorAdd_L",
    )

    /** Returns the best GDTF standard ColorAdd attribute name for a diode. */
    private fun gdtfColorAttr(diodeName: String): String {
        val key = diodeName.lowercase().replace(" ", "").replace("_", "")
        return DIODE_TO_GDTF_ATTR[key] ?: "ColorAdd_${diodeName.replace(" ", "")}"
    }

    private fun findEmitterForChannel(
        attribute: String,
        measurements: List<ColorScience.DiodeMeasurement>
    ): String? {
        val isColorAttr = attribute.startsWith("ColorAdd_") ||
                          attribute.startsWith("ColorRGB_") ||
                          attribute.startsWith("Color_")
        if (!isColorAttr) return null
        val stripped = attribute
            .removePrefix("ColorAdd_").removePrefix("ColorRGB_").removePrefix("Color_")
            .lowercase().replace(" ", "").replace("_", "")
        fun norm(s: String) = s.lowercase().replace(" ", "").replace("_", "")
        return measurements.firstOrNull { m -> norm(m.diodeName) == stripped }?.diodeName
    }

    private fun ciexyToApproxWavelength(x: Double, y: Double): Int? {
        val dx = x - 0.3127; val dy = y - 0.3290
        if (Math.hypot(dx, dy) < 0.08) return null
        return when (val angle = Math.toDegrees(Math.atan2(dy, dx))) {
            in 20.0..70.0     -> 565
            in 70.0..120.0    -> 540
            in 120.0..160.0   -> 490
            in -180.0..-120.0 -> 450
            in -120.0..-60.0  -> 465
            in -60.0..0.0     -> 600
            in 0.0..20.0      -> 590
            else -> if (angle > 160.0 || angle < -160.0) 620 else null
        }
    }

    private fun escapeXml(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
