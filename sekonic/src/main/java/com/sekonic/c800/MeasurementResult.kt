package com.sekonic.c800

/**
 * Complete measurement result from the Sekonic C-800.
 * Core fields (CCT, lux, CRI, SPD) come from the NR/MR command.
 * Extended fields (TM-30, SSI, TLCI, Hue/Sat) come from the ME command.
 */
data class MeasurementResult(
    val timestamp:    Long   = System.currentTimeMillis(),
    val presetName:   String = "",          // Memory title (MR only; empty for live NR)
    val measMode:     MeasMode = MeasMode.AMBIENT,

    // ── Color Temperature ────────────────────────────────────────────────────
    val cct:          Float  = 0f,          // Correlated Color Temperature [K]
    val deltaUv:      Float  = 0f,          // Δuv deviation from Planckian locus
    val targetCct:    Float  = 0f,          // User-set target [K]

    // ── Illuminance ──────────────────────────────────────────────────────────
    val lux:          Float  = 0f,          // [lx]
    val footCandle:   Float  = 0f,          // [fc]

    // ── Tristimulus ──────────────────────────────────────────────────────────
    val tristX:       Double = 0.0,         // CIE XYZ — X
    val tristY:       Double = 0.0,         //           Y
    val tristZ:       Double = 0.0,         //           Z

    // ── Chromaticity ─────────────────────────────────────────────────────────
    val cie1931x:     Float  = 0f,          // CIE 1931 x
    val cie1931y:     Float  = 0f,          // CIE 1931 y
    val cie1931z:     Float  = 0f,          // CIE 1931 z = 1 - x - y
    val cie1976u:     Float  = 0f,          // CIE 1976 u'
    val cie1976v:     Float  = 0f,          // CIE 1976 v'

    // ── Dominant Wavelength ──────────────────────────────────────────────────
    val dominantWavelength: Float = 0f,     // [nm]
    val excitationPurity:   Float = 0f,     // [%]

    // ── White Balance (MR only) ───────────────────────────────────────────────
    val lbIndex:      Float  = 0f,          // LB shift [MK⁻¹]
    val ccIndex:      Float  = 0f,          // CC index
    val cameraFilter: String = "",
    val lightFilter:  String = "",
    val lbFilterRec:  String = "",
    val ccFilter1:    String = "",
    val ccFilter2:    String = "",

    // ── CRI ──────────────────────────────────────────────────────────────────
    val criRa:        Float  = 0f,          // Ra general
    val criR:         List<Float> = List(15) { 0f },  // R1-R15

    // ── TM-30-18 (from ME command) ────────────────────────────────────────────
    val tm30Rf:       Float  = 0f,          // Fidelity index
    val tm30Rg:       Float  = 0f,          // Gamut index

    // ── SSI (from ME command) ─────────────────────────────────────────────────
    val ssiT:         Float  = 0f,          // Tungsten reference
    val ssiD:         Float  = 0f,          // Daylight reference
    val ssi1:         Float  = 0f,          // Custom reference 1
    val ssi2:         Float  = 0f,          // Custom reference 2

    // ── TLCI (from ME command) ────────────────────────────────────────────────
    val tlci:         Float  = 0f,
    val tlmf:         Float  = 0f,

    // ── Hue / Saturation (from ME command) ───────────────────────────────────
    val hue:          Float  = 0f,          // [°]
    val saturation:   Float  = 0f,          // [%]

    // ── Spectral Data ─────────────────────────────────────────────────────────
    val spd5nm:       FloatArray = FloatArray(81),   // 380–780 nm @ 5 nm
    val spd1nm:       FloatArray = FloatArray(401),  // 380–780 nm @ 1 nm
    val peakWavelength: Int = 0,                     // [nm]
    val ppfd:         Float = 0f,           // Photosynthetic Photon Flux Density
) {
    enum class MeasMode { AMBIENT, FLASH }

    /** Wavelengths for the 5nm SPD array (380, 385, ... 780 nm). */
    val wavelengths5nm: IntArray get() = IntArray(81) { 380 + it * 5 }

    /** True if TM-30/SSI/TLCI were populated from the ME command. */
    val hasExtendedMetrics: Boolean get() = tm30Rf > 0f || tlci > 0f

    override fun toString(): String =
        "CCT=${cct.toInt()}K Δuv=${"%.4f".format(deltaUv)} " +
        "lux=${"%.1f".format(lux)} Ra=${"%.1f".format(criRa)} " +
        "x=${"%.4f".format(cie1931x)} y=${"%.4f".format(cie1931y)}"
}
