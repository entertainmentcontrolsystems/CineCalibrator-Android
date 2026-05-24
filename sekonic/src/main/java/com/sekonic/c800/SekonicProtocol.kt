package com.sekonic.c800

/**
 * Confirmed USB protocol constants for the Sekonic C-800 spectrometer.
 *
 * Protocol reverse-engineered from:
 *   - USBPcap/Wireshark captures (6 sessions, 38+ measurements)
 *   - Official Sekonic CSV exports (20 measurements, all fields cross-validated)
 *   - skreader-go open-source Go implementation (same SkCommIo.dll transport)
 *   - C-7000 SDK Reference Manual (same protocol family)
 */
internal object SekonicProtocol {

    // ── USB Device Identity ──────────────────────────────────────────────────
    const val VENDOR_ID  = 0x0A41   // Sekonic Corporation — confirmed from device descriptor
    const val PRODUCT_ID = 0x7003   // C-800 / C-800-U — confirmed from device descriptor

    // ── USB Endpoints ────────────────────────────────────────────────────────
    const val EP_OUT = 0x02   // Bulk OUT host→device
    const val EP_IN  = 0x81   // Bulk IN  device→host

    // ── Wire Format ──────────────────────────────────────────────────────────
    // Commands:  raw ASCII bytes, no length prefix, no framing
    // ACK:       exactly 2 bytes {0x06, 0x30} — always a SEPARATE USB transfer
    // Response:  [CMD_ECHO 2B][FLAGS 2B][SEP 0x40 1B][PAYLOAD...]
    val ACK = byteArrayOf(0x06, 0x30)
    const val RESP_HEADER_LEN = 5       // bytes to skip to reach payload
    const val READ_TIMEOUT_MS = 5_000
    const val READ_BUF_SIZE   = 65_508  // max bulk IN size (observed for DG002,1)

    // ── Commands (confirmed from skreader-go const.go + pcap) ────────────────
    const val CMD_GET_STATUS          = "ST"     // Get device status (bit-decoded flags)
    const val CMD_GET_MODEL           = "MN"     // Get model name → "C-800"
    const val CMD_GET_FIRMWARE        = "FV"     // Get firmware version
    const val CMD_RECONNECT           = "RT2"    // Reconnect / init handshake
    const val CMD_REMOTE_ON           = "RT1"    // SetRemoteMode ON  (enables RM0)
    const val CMD_REMOTE_OFF          = "RT0"    // SetRemoteMode OFF
    const val CMD_START_MEASURING     = "RM0"    // StartMeasuring    (fires shutter)
    const val CMD_GET_RESULT          = "NR"     // GetMeasurementResult (current, no index)
    const val CMD_GET_MEMORY_INFO     = "MI"     // Memory info → "max,used,titles"
    const val CMD_GET_MEMORY_RECORD   = "MR%04d" // Fetch stored record n → 2380-byte payload
    const val CMD_GET_EXTENDED        = "ME%04d,01" // Extended metrics (TM-30/SSI/TLCI/Hue)
    const val CMD_SA_READ             = "SAr"    // SA mode read
    const val CMD_FT_READ             = "FTr"    // FT mode read
    const val CMD_IU_READ             = "IUr"    // Illumination unit read

    // ── Status bit decode (from skreader-go device.go State()) ───────────────
    // ST response bytes: data[0]=S data[1]=T data[2]=st1 data[3]=st2 data[4]=key
    const val STATUS_BIT_ERROR        = 0x10   // st1 & 0x10 → hardware error
    const val STATUS_BIT_BUSY         = 0x01   // st1 & 0x01 → device busy
    const val STATUS_BIT_IDLE_OUT     = 0x08   // st1 & 0x08 → idle (out of measurement)
    const val STATUS_BIT_REMOTE       = 0x02   // st1 & 0x02 → remote mode active
    const val STATUS_BIT_MEASURING    = 0x08   // st2 & 0x08 → busy: measuring
    const val RING_MASK               = 0x60
    const val RING_SHIFT              = 5
    const val BUTTON_MASK             = 0x1F
    const val RING_LOW                = 2       // correct position for measurement

    // ── Payload offsets (stripped — after RESP_HEADER_LEN is removed) ─────────
    // Confirmed from: measurement.go (Go impl) + pcap + 20 CSV cross-validations
    // These apply to BOTH NR (live) and MR (memory) response payloads.

    const val OFF_CCT           = 45    // float32 BE  Kelvin
    const val OFF_DELTA_UV      = 50    // float32 BE  Δuv
    const val OFF_LUX           = 266   // float32 BE  Illuminance lx
    const val OFF_FOOT_CANDLE   = 271   // float32 BE  Illuminance fc
    const val OFF_TRIST_X       = 276   // float64 BE  Tristimulus X (8 bytes)
    const val OFF_TRIST_Y       = 285   // float64 BE  Tristimulus Y
    const val OFF_TRIST_Z       = 294   // float64 BE  Tristimulus Z
    const val OFF_CIE_X         = 303   // float32 BE  CIE1931 x
    const val OFF_CIE_Y         = 308   // float32 BE  CIE1931 y
    const val OFF_CIE76_U       = 323   // float32 BE  CIE1976 u'
    const val OFF_CIE76_V       = 328   // float32 BE  CIE1976 v'
    const val OFF_DWL           = 333   // float32 BE  Dominant wavelength nm
    const val OFF_EXCITATION    = 338   // float32 BE  Excitation purity %
    const val OFF_CRI_RA        = 343   // float32 BE  CRI Ra
    const val OFF_CRI_R1        = 348   // float32 BE  CRI R1 (step = 5 bytes per Ri)
    const val OFF_SPD_5NM       = 423   // float32 BE  SPD[380nm] (81 × 4 bytes, 380–780nm)
    const val OFF_SPD_1NM       = 748   // float32 BE  SPD[380nm] @1nm (401 × 4 bytes)
    const val OFF_PPFD          = 2371  // float32 BE  PPFD

    // MR-only offsets (ASCII text header, not present in NR)
    const val OFF_MEAS_MODE     = 0     // byte '0'=ambient '1'=flash
    const val OFF_PRESET_NAME   = 2     // 16 bytes null-padded ASCII
    const val OFF_LB_INDEX      = 31    // float32 BE  LB index MK⁻¹
    const val OFF_CC_INDEX      = 38    // float32 BE  CC index
    const val OFF_CAMERA_FILTER = 65    // 3 bytes ASCII
    const val OFF_LIGHT_FILTER  = 69    // 44 bytes null-padded ASCII
    const val OFF_LB_FILTER_REC = 114   // 48 bytes null-padded ASCII
    const val OFF_LB_SHIFT      = 163   // float32 BE
    const val OFF_CC_FILTER_1   = 168   // 5 bytes ASCII
    const val OFF_CC_FILTER_2   = 174   // 42 bytes null-padded ASCII

    const val VALID_PAYLOAD_SIZE = 2380 // confirmed from skreader-go MeasurementDataValidSize

    // ── ME (extended metrics) payload parsing ─────────────────────────────────
    // Confirmed from pcap6: 5 records × all fields validated against official CSVs
    // Payload is comma-delimited BE float32; fields located via null-float anchor.
    // Fields [0] and [1] are always Rf and Rg (first two 4-byte comma-separated fields).
    // Tail structure (worked backwards from null-float anchor 0x00000000):
    //   ..., SSIt, SSId, '1', SSI1, '1', SSI2, TLCI, '0', 0x00000000, Hue, Sat, '0', '00', trailing
    val ME_ZERO_ANCHOR = byteArrayOf(0, 0, 0, 0, 0x2c.toByte()) // null float + comma
}
