package com.sekonic.c800

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Parses raw binary payloads from the Sekonic C-800.
 * All offsets are "stripped" (RESP_HEADER_LEN already removed by caller).
 */
internal object PayloadParser {

    // ── NR payload (live measurement result) ─────────────────────────────────

    fun parseNr(payload: ByteArray): MeasurementResult {
        // NR has no ASCII text header — call MR parser then clear header-only fields
        return parseMr(payload).copy(
            presetName   = "",
            lbIndex      = 0f,
            ccIndex      = 0f,
            cameraFilter = "",
            lightFilter  = "",
            lbFilterRec  = "",
            ccFilter1    = "",
            ccFilter2    = "",
        )
    }

    // ── MR payload (stored memory record) ────────────────────────────────────

    fun parseMr(payload: ByteArray): MeasurementResult {
        if (payload.size < SekonicProtocol.VALID_PAYLOAD_SIZE) {
            // Still try to parse what we have
        }

        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)

        fun f32(off: Int): Float = if (off + 4 <= payload.size)
            buf.getFloat(off) else 0f

        fun f64(off: Int): Double = if (off + 8 <= payload.size)
            buf.getDouble(off) else 0.0

        fun str(off: Int, len: Int): String = if (off + len <= payload.size)
            String(payload, off, len, Charsets.US_ASCII)
                .trimEnd('\u0000').trim()
        else ""

        // ── Text header fields (MR only) ─────────────────────────────────────
        val measMode = if (payload.size > 0 && payload[0] == '1'.code.toByte())
            MeasurementResult.MeasMode.FLASH
        else
            MeasurementResult.MeasMode.AMBIENT

        val presetName   = str(SekonicProtocol.OFF_PRESET_NAME, 16)
        val lbIndex      = f32(SekonicProtocol.OFF_LB_INDEX)
        val ccIndex      = f32(SekonicProtocol.OFF_CC_INDEX)
        val cameraFilter = str(SekonicProtocol.OFF_CAMERA_FILTER, 3)
        val lightFilter  = str(SekonicProtocol.OFF_LIGHT_FILTER, 44)
        val lbFilterRec  = str(SekonicProtocol.OFF_LB_FILTER_REC, 48)
        val lbShift      = f32(SekonicProtocol.OFF_LB_SHIFT)
        val ccFilter1    = str(SekonicProtocol.OFF_CC_FILTER_1, 5)
        val ccFilter2    = str(SekonicProtocol.OFF_CC_FILTER_2, 42)

        // ── Core numeric fields ───────────────────────────────────────────────
        val cct      = f32(SekonicProtocol.OFF_CCT)
        val deltaUv  = f32(SekonicProtocol.OFF_DELTA_UV)
        val lux      = f32(SekonicProtocol.OFF_LUX)
        val fc       = f32(SekonicProtocol.OFF_FOOT_CANDLE)

        // Tristimulus XYZ — stored as float64
        val tristX   = f64(SekonicProtocol.OFF_TRIST_X)
        val tristY   = f64(SekonicProtocol.OFF_TRIST_Y)
        val tristZ   = f64(SekonicProtocol.OFF_TRIST_Z)

        // CIE 1931 xy
        val x = f32(SekonicProtocol.OFF_CIE_X)
        val y = f32(SekonicProtocol.OFF_CIE_Y)
        val z = if (x > 0f && y > 0f) 1f - x - y else 0f

        // CIE 1976 u'v' — derive from xy for reliability
        val denom = 12f * y - 2f * x + 3f
        val uPrime = if (denom != 0f) 4f * x / denom else 0f
        val vPrime = if (denom != 0f) 9f * y / denom else 0f

        // Dominant wavelength & excitation purity
        val dwl  = f32(SekonicProtocol.OFF_DWL)
        val excit = f32(SekonicProtocol.OFF_EXCITATION)

        // CRI
        val criRa = f32(SekonicProtocol.OFF_CRI_RA)
        val criR  = List(15) { i -> f32(SekonicProtocol.OFF_CRI_R1 + i * 5) }

        // SPD 5nm (381 = 380 + 80×5)
        val spd5 = FloatArray(81) { i -> f32(SekonicProtocol.OFF_SPD_5NM + i * 4) }
        val peak5 = spd5.indices.maxByOrNull { spd5[it] }?.let { 380 + it * 5 } ?: 0

        // SPD 1nm
        val spd1 = FloatArray(401) { i ->
            val off = SekonicProtocol.OFF_SPD_1NM + i * 4
            if (off + 4 <= payload.size) f32(off) else 0f
        }

        // PPFD
        val ppfd = f32(SekonicProtocol.OFF_PPFD)

        return MeasurementResult(
            measMode           = measMode,
            presetName         = presetName,
            cct                = cct,
            deltaUv            = deltaUv,
            lux                = lux,
            footCandle         = fc,
            tristX             = tristX,
            tristY             = tristY,
            tristZ             = tristZ,
            cie1931x           = x,
            cie1931y           = y,
            cie1931z           = z,
            cie1976u           = uPrime,
            cie1976v           = vPrime,
            dominantWavelength = dwl,
            excitationPurity   = excit,
            lbIndex            = lbIndex,
            ccIndex            = ccIndex,
            cameraFilter       = cameraFilter,
            lightFilter        = lightFilter,
            lbFilterRec        = lbFilterRec,
            ccFilter1          = ccFilter1,
            ccFilter2          = ccFilter2,
            criRa              = criRa,
            criR               = criR,
            spd5nm             = spd5,
            spd1nm             = spd1,
            peakWavelength     = peak5,
            ppfd               = ppfd,
        )
    }

    // ── ME payload (extended metrics) ────────────────────────────────────────

    /**
     * Parse ME{n},01 response and merge extended fields into an existing result.
     *
     * The ME payload is comma-delimited BE float32.
     * Field indices vary because 0x2c bytes can appear inside float values,
     * making simple comma-splitting unreliable.
     *
     * Parsing strategy: locate the null-float anchor (0x00000000,) and
     * work backwards for SSI/TLCI, forwards for Hue/Sat.
     * Rf and Rg are always the first two 4-byte comma-separated fields.
     *
     * Confirmed from pcap6: 5 records all fields validated against official CSVs.
     */
    fun mergeMe(mePayload: ByteArray, base: MeasurementResult): MeasurementResult {
        if (mePayload.size < 20) return base

        fun bf(off: Int): Float {
            if (off + 4 > mePayload.size) return 0f
            return ByteBuffer.wrap(mePayload, off, 4)
                .order(ByteOrder.BIG_ENDIAN).getFloat()
        }

        // Rf and Rg — first two 4-byte fields (always at bytes 0 and 5)
        val rf = bf(0)
        val rg = bf(5)

        // Locate null-float anchor: 0x00 0x00 0x00 0x00 0x2c
        val anchor = SekonicProtocol.ME_ZERO_ANCHOR
        var anchorPos = -1
        outer@ for (i in 0..mePayload.size - anchor.size) {
            for (j in anchor.indices) {
                if (mePayload[i + j] != anchor[j]) continue@outer
            }
            anchorPos = i
            break
        }
        if (anchorPos < 0) return base.copy(tm30Rf = rf, tm30Rg = rg)

        // Verify the '0' separator before the anchor
        if (anchorPos < 3 ||
            mePayload[anchorPos - 1] != 0x2c.toByte() ||
            mePayload[anchorPos - 2] != 0x30.toByte()) {
            return base.copy(tm30Rf = rf, tm30Rg = rg)
        }

        // Work backwards from anchor:
        //  [anchorPos-7 .. anchorPos-4] = TLCI
        //  [anchorPos-12.. anchorPos-9] = SSI2
        //  [anchorPos-19.. anchorPos-16]= SSI1  (preceded by '1' separator)
        //  [anchorPos-26.. anchorPos-23]= SSId  (preceded by '1' separator)
        //  [anchorPos-31.. anchorPos-28]= SSIt
        val tlci  = bf(anchorPos - 7)
        val ssi2  = bf(anchorPos - 12)
        val ssi1  = bf(anchorPos - 19)
        val ssiD  = bf(anchorPos - 26)
        val ssiT  = bf(anchorPos - 31)

        // Work forwards from anchor:
        //  [anchorPos+5 .. anchorPos+8] = Hue
        //  [anchorPos+10.. anchorPos+13]= Saturation
        val hue = bf(anchorPos + 5)
        val sat = bf(anchorPos + 10)

        return base.copy(
            tm30Rf     = rf,
            tm30Rg     = rg,
            ssiT       = ssiT,
            ssiD       = ssiD,
            ssi1       = ssi1,
            ssi2       = ssi2,
            tlci       = tlci,
            hue        = hue,
            saturation = sat,
        )
    }
}
