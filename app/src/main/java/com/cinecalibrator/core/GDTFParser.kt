package com.cinecalibrator.core

import android.content.Context
import android.net.Uri
import org.w3c.dom.Element
import timber.log.Timber
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * GDTFParser — DIN SPEC 15800 / GDTF 1.2
 *
 * Parses the description.xml from a .gdtf ZIP archive.
 * Key addition: distinguishes Dimmer/Intensity channels from colour channels
 * so ScanEngine can keep the dimmer at 255 while cycling colour emitters.
 *
 * GDTF attribute names used by major manufacturers:
 *   Dimmer         — master intensity (ETC, Robe, GLP, etc.)
 *   Intensity      — alternate name for same
 *   ColorAdd_R/G/B/W/WW/CW/A/M/C/Y  — additive colour channels
 *   ColorRGB_Red/Green/Blue          — alternate naming convention
 */
class GDTFParser(private val context: Context) {

    data class GDTFEmitter(
        val name: String,
        val color: String?,
        val dominantWavelength: Float?,
        val diodeCIEx: Float?,
        val diodeCIEy: Float?,
        val dmxChannel: Int?,
        val dmxChannelFine: Int?
    )

    data class GDTFChannel(
        val name: String,
        val geometry: String,
        val attribute: String,
        val dmxAddress: Int,
        val coarseResolution: Int,
        val defaultValue: Int,
        val highlightValue: Int,
        val isDimmer: Boolean = false,
        val isColor: Boolean = false,
        val isControl: Boolean = false
    )

    data class GDTFMode(
        val name: String,
        val channels: List<GDTFChannel>
    )

    data class GDTFFixture(
        val manufacturer: String,
        val name: String,
        val shortName: String,
        val modes: List<GDTFMode>,
        val emitters: List<GDTFEmitter>,
        val thumbnailBase64: String? = null
    ) {
        /** All channels that control colour output (excludes dimmer and control channels) */
        fun colorChannels(mode: GDTFMode = modes.firstOrNull() ?: GDTFMode("", emptyList())): List<GDTFChannel> =
            mode.channels.filter { it.isColor }

        /** The master dimmer/intensity channel, if present */
        fun dimmerChannel(mode: GDTFMode = modes.firstOrNull() ?: GDTFMode("", emptyList())): GDTFChannel? =
            mode.channels.firstOrNull { it.isDimmer }

        fun diodeNames(): List<String> =
            if (emitters.isNotEmpty()) emitters.map { it.name }
            else colorChannels().map { humaniseName(it.attribute) }

        private fun humaniseName(attr: String): String =
            attr.removePrefix("ColorAdd_")
                .removePrefix("ColorRGB_")
                .removePrefix("Color_")
                .replace('_', ' ')
    }

    companion object {
        /**
         * Human-readable display name for a GDTF attribute string.
         * Covers the camelCase control attributes that title-casing gets wrong,
         * plus the colour prefixes that are stripped for colour channels.
         */
        val ATTRIBUTE_DISPLAY_NAMES: Map<String, String> = mapOf(
            "Dimmer"                to "Dimmer",
            "Intensity"             to "Intensity",
            "StrobeModeShutter"     to "Strobe",
            "StrobeMode"            to "Strobe",
            "ShutterStrobe"         to "Strobe",
            "DimmerCurve"           to "Dim Curve",
            "DimmingCurve"          to "Dim Curve",
            "Fans"                  to "Fan",
            "FanMode"               to "Fan Mode",
            "Pan"                   to "Pan",
            "Tilt"                  to "Tilt",
            "PanRotate"             to "Pan Rotate",
            "TiltRotate"            to "Tilt Rotate",
            "Focus"                 to "Focus",
            "Zoom"                  to "Zoom",
            "Iris"                  to "Iris",
            "Frost"                 to "Frost",
            "Prism"                 to "Prism",
            "Gobo"                  to "Gobo",
            "GoboIndex"             to "Gobo Index",
            "GoboWheelSpin"         to "Gobo Spin",
            "CTO"                   to "CTO",
            "CTB"                   to "CTB",
            "CTC"                   to "CTC",
            "ColorMacro"            to "Color Macro",
            "ColorEffects"          to "Effects",
            "Effects"               to "Effects",
            "EffectsRate"           to "FX Rate",
            "EffectsSync"           to "FX Sync",
        )

        fun attributeDisplayName(attribute: String): String {
            ATTRIBUTE_DISPLAY_NAMES[attribute]?.let { return it }
            // Strip colour prefixes for colour channels
            val stripped = attribute
                .removePrefix("ColorAdd_")
                .removePrefix("ColorRGB_")
                .removePrefix("ColorSub_")
                .removePrefix("Color_")
            if (stripped != attribute) return stripped.replace('_', ' ')
            // Unknown camelCase — insert spaces before capitals
            return attribute.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
        }
    }

    // ─── Attribute classification helpers ────────────────────────────────────────

    private fun isDimmerAttribute(attr: String): Boolean {
        val up = attr.uppercase()
        return up == "DIMMER" || up == "INTENSITY" || up == "MASTERINTENSITY" ||
               up.startsWith("DIM") && up.length <= 7
    }

    private fun isColorAttribute(attr: String): Boolean {
        val up = attr.uppercase()
        return up.startsWith("COLORADD") ||
               up.startsWith("COLORRGB") ||
               up.startsWith("COLORSUB") ||
               up.startsWith("COLOR_R") || up.startsWith("COLOR_G") || up.startsWith("COLOR_B") ||
               up.startsWith("CTO") || up.startsWith("CTB") || up.startsWith("CTC") ||
               up.startsWith("HUE") || up.startsWith("SATURATION") ||
               up == "RED" || up == "GREEN" || up == "BLUE" ||
               up == "WHITE" || up == "AMBER" || up == "LIME" || up == "CYAN" ||
               up == "MAGENTA" || up == "MINT" || up == "UV" || up == "INDIGO" ||
               up.startsWith("WARMWHITE") || up.startsWith("COOLWHITE")
    }

    private fun isControlAttribute(attr: String): Boolean {
        // Anything that is neither a colour channel nor the master dimmer is a control channel.
        // This is the correct semantics: colour and dimmer get special treatment in the scan
        // engine; everything else (strobe, fan, curve, pan/tilt, gobo, etc.) is treated as
        // a passthrough control channel — driven at its default value during scans.
        return !isDimmerAttribute(attr) && !isColorAttribute(attr)
    }

    // ─── Public API ──────────────────────────────────────────────────────────────

    fun parseFromUri(uri: Uri): GDTFFixture? = try {
        context.contentResolver.openInputStream(uri)?.use { parseFromStream(it) }
    } catch (e: Exception) {
        Timber.e(e, "Failed to parse GDTF from URI: $uri"); null
    }

    fun parseFromStream(stream: InputStream): GDTFFixture? {
        val zip = ZipInputStream(stream)
        var xmlContent: ByteArray? = null
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name.equals("description.xml", ignoreCase = true) ||
                entry.name.endsWith("/description.xml")) {
                xmlContent = zip.readBytes(); break
            }
            entry = zip.nextEntry
        }
        zip.close()
        return xmlContent?.let { parseDescriptionXML(it) }
    }

    // ─── XML Parsing ──────────────────────────────────────────────────────────────

    private fun parseDescriptionXML(xmlBytes: ByteArray): GDTFFixture? {
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
            val doc = factory.newDocumentBuilder().parse(xmlBytes.inputStream())
            doc.documentElement.normalize()
            val root = doc.documentElement
            val fixtureType = root.getElementsByTagName("FixtureType").item(0) as? Element
                ?: return null

            GDTFFixture(
                manufacturer = fixtureType.getAttribute("Manufacturer").ifEmpty { "Unknown" },
                name = fixtureType.getAttribute("Name").ifEmpty { "Unknown Fixture" },
                shortName = fixtureType.getAttribute("ShortName").let { it.ifEmpty { fixtureType.getAttribute("Name") } },
                modes = parseModes(fixtureType),
                emitters = parseEmitters(fixtureType)
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse description.xml")
            null
        }
    }

    private fun parseEmitters(fixtureType: Element): List<GDTFEmitter> {
        val list = mutableListOf<GDTFEmitter>()
        val emitters = fixtureType.getElementsByTagName("Emitter")
        for (i in 0 until emitters.length) {
            val e = emitters.item(i) as? Element ?: continue
            list.add(GDTFEmitter(
                name = e.getAttribute("Name").ifEmpty { "Emitter $i" },
                color = e.getAttribute("Color").ifEmpty { null },
                dominantWavelength = e.getAttribute("DominantWaveLength").toFloatOrNull(),
                diodeCIEx = null, diodeCIEy = null, dmxChannel = null, dmxChannelFine = null
            ))
        }
        return list
    }

    private fun parseModes(fixtureType: Element): List<GDTFMode> {
        val list = mutableListOf<GDTFMode>()
        val dmxModes = fixtureType.getElementsByTagName("DMXMode")
        for (i in 0 until dmxModes.length) {
            val modeEl = dmxModes.item(i) as? Element ?: continue
            list.add(GDTFMode(
                name = modeEl.getAttribute("Name").ifEmpty { "Mode ${i + 1}" },
                channels = parseChannels(modeEl)
            ))
        }
        return list
    }

    private fun parseChannels(modeEl: Element): List<GDTFChannel> {
        val list = mutableListOf<GDTFChannel>()
        val channels = modeEl.getElementsByTagName("DMXChannel")
        var autoAddress = 1
        for (i in 0 until channels.length) {
            val ch = channels.item(i) as? Element ?: continue
            val logicalChannels = ch.getElementsByTagName("LogicalChannel")
            val logicalEl = if (logicalChannels.length > 0) logicalChannels.item(0) as? Element else null
            val attribute = logicalEl?.getAttribute("Attribute")?.ifEmpty { "Unknown" } ?: "Unknown"
            val geometry = ch.getAttribute("Geometry").ifEmpty { "Body" }

            // Read DMX address from Offset attribute (1-indexed in GDTF)
            val offsetStr = ch.getAttribute("Offset")
            val dmxAddress = if (offsetStr.isNotEmpty()) {
                // Offset can be "3" or "3,4" (coarse,fine) — take the first value
                offsetStr.split(",").firstOrNull()?.trim()?.toIntOrNull() ?: autoAddress
            } else autoAddress
            autoAddress = dmxAddress + 1

            // Read Highlight from DMXChannel, Default from the first ChannelFunction
            val highlight = ch.getAttribute("Highlight").let { h ->
                if (h.isNotEmpty()) parseDmxValue(h) else 255
            }
            val channelFunctions = ch.getElementsByTagName("ChannelFunction")
            val default = if (channelFunctions.length > 0) {
                val cf = channelFunctions.item(0) as? Element
                cf?.getAttribute("Default")?.let { d ->
                    if (d.isNotEmpty()) parseDmxValue(d) else 0
                } ?: 0
            } else 0

            list.add(GDTFChannel(
                name = attribute,
                geometry = geometry,
                attribute = attribute,
                dmxAddress = dmxAddress,
                coarseResolution = 8,
                defaultValue = default,
                highlightValue = highlight,
                isDimmer = isDimmerAttribute(attribute),
                isColor = isColorAttribute(attribute),
                isControl = isControlAttribute(attribute)
            ))
        }
        return list
    }

    /**
     * Parse a GDTF DMX value string.
     * Format is either plain integer ("128") or fractional ("128/1", "255/1").
     * Returns the coarse 8-bit value (0–255).
     */
    private fun parseDmxValue(s: String): Int {
        val parts = s.split("/")
        return parts[0].trim().toIntOrNull()?.coerceIn(0, 255) ?: 0
    }

    // ─── Built-in templates ──────────────────────────────────────────────────────

    fun commonTemplates(): List<GDTFFixture> = listOf(
        makeTemplate("Generic", "RGB LED",
            dimmer = 1,
            colors = listOf("Red" to 2, "Green" to 3, "Blue" to 4)),
        makeTemplate("Generic", "RGBA LED",
            dimmer = 1,
            colors = listOf("Red" to 2, "Green" to 3, "Blue" to 4, "Amber" to 5)),
        makeTemplate("Generic", "RGBW LED",
            dimmer = 1,
            colors = listOf("Red" to 2, "Green" to 3, "Blue" to 4, "White" to 5)),
        makeTemplate("Generic", "RGBWW LED",
            dimmer = 1,
            colors = listOf("Red" to 2, "Green" to 3, "Blue" to 4, "White" to 5, "WarmWhite" to 6)),
        makeTemplate("Generic", "RGBWA LED",
            dimmer = 1,
            colors = listOf("Red" to 2, "Green" to 3, "Blue" to 4, "White" to 5, "Amber" to 6)),
        makeTemplate("Generic", "RGBAM LED",
            dimmer = 1,
            colors = listOf("Red" to 2, "Green" to 3, "Blue" to 4, "Amber" to 5, "Mint" to 6)),
        makeTemplate("Generic", "Bi-Color Tunable",
            dimmer = 1,
            colors = listOf("WarmWhite" to 2, "CoolWhite" to 3)),
        makeTemplate("ETC", "Fos/4 Panel (simplified)",
            dimmer = 1,
            colors = listOf("Red" to 2, "RedOrange" to 3, "Amber" to 4, "Green" to 5,
                            "Cyan" to 6, "Blue" to 7, "Indigo" to 8, "Mint" to 9)),
    )

    private fun makeTemplate(
        manufacturer: String,
        name: String,
        dimmer: Int?,
        colors: List<Pair<String, Int>>
    ): GDTFFixture {
        val channels = mutableListOf<GDTFChannel>()
        dimmer?.let {
            channels.add(GDTFChannel("Dimmer", "Body", "Dimmer", it, 8, 0, 255,
                isDimmer = true, isColor = false, isControl = false))
        }
        colors.forEach { (attr, addr) ->
            channels.add(GDTFChannel(attr, "Body", "ColorAdd_$attr", addr, 8, 0, 255,
                isDimmer = false, isColor = true, isControl = false))
        }
        val emitters = colors.map { (n, _) ->
            GDTFEmitter(n, n, null, null, null, null, null)
        }
        return GDTFFixture(manufacturer, name, name,
            listOf(GDTFMode("Default", channels)), emitters)
    }
}
