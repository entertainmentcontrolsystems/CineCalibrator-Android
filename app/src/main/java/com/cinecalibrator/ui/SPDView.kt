package com.cinecalibrator.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * SPDView — Spectral Power Distribution bar chart.
 *
 * Renders 81 values at 5 nm steps (380–780 nm) as colour-coded vertical bars.
 * Each bar is tinted by the approximate perceived colour of that wavelength.
 * Matches the visual style of CIEChartView (dark background, consistent typography).
 */
class SPDView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var spd: FloatArray = FloatArray(81)
    private var highlightChannel: String? = null   // optional diode name label

    fun setSPD(data: FloatArray, channelName: String? = null) {
        spd = if (data.size == 81) data else FloatArray(81).also {
            data.copyInto(it, endIndex = minOf(data.size, 81))
        }
        highlightChannel = channelName
        invalidate()
    }

    // Approximate sRGB colour for a wavelength in nm
    private fun wavelengthToColor(nm: Int): Int {
        return when {
            nm < 380 -> Color.parseColor("#6600CC")
            nm < 420 -> {
                val t = (nm - 380) / 40f
                Color.rgb((80 * t).toInt(), 0, (200 + 55 * (1-t)).toInt())
            }
            nm < 450 -> Color.rgb(0, 0, 255)
            nm < 490 -> {
                val t = (nm - 450) / 40f
                Color.rgb(0, (255 * t).toInt(), 255)
            }
            nm < 510 -> {
                val t = (nm - 490) / 20f
                Color.rgb(0, 255, (255 * (1-t)).toInt())
            }
            nm < 560 -> {
                val t = (nm - 510) / 50f
                Color.rgb((255 * t).toInt(), 255, 0)
            }
            nm < 590 -> Color.rgb(255, 255, 0)
            nm < 620 -> {
                val t = (nm - 590) / 30f
                Color.rgb(255, (255 * (1-t)).toInt(), 0)
            }
            nm < 700 -> Color.rgb(255, 0, 0)
            nm < 780 -> {
                val t = (nm - 700) / 80f
                Color.rgb((255 * (1-t * 0.4f)).toInt(), 0, 0)
            }
            else -> Color.parseColor("#440000")
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        canvas.drawColor(Color.parseColor("#111111"))

        val padL = 36f; val padR = 12f; val padT = 8f; val padB = 28f
        val chartW = width - padL - padR
        val chartH = height - padT - padB

        val peak = spd.maxOrNull()?.takeIf { it > 0f } ?: 1f
        val barW = chartW / 81f
        val barPaint = Paint().apply { style = Paint.Style.FILL }

        // Bars
        for (i in 0 until 81) {
            val nm = 380 + i * 5
            val normalized = spd[i] / peak
            val barH = normalized * chartH
            val x = padL + i * barW
            val y = padT + chartH - barH
            barPaint.color = wavelengthToColor(nm)
            barPaint.alpha = 220
            canvas.drawRect(x, y, x + barW - 0.5f, padT + chartH, barPaint)
        }

        // Axis
        val axisPaint = Paint().apply {
            color = Color.parseColor("#444444"); strokeWidth = 1f
        }
        canvas.drawLine(padL, padT + chartH, padL + chartW, padT + chartH, axisPaint)
        canvas.drawLine(padL, padT, padL, padT + chartH, axisPaint)

        // X-axis labels at 100 nm intervals
        val labelPaint = Paint().apply {
            color = Color.parseColor("#777777"); textSize = 18f
            isAntiAlias = true; textAlign = Paint.Align.CENTER
        }
        for (nm in listOf(400, 500, 600, 700)) {
            val i = (nm - 380) / 5
            val x = padL + (i + 0.5f) * barW
            canvas.drawText("${nm}nm", x, height - 4f, labelPaint)
            canvas.drawLine(x, padT + chartH, x, padT + chartH + 4f, axisPaint)
        }

        // Y-axis label
        val yPaint = Paint().apply {
            color = Color.parseColor("#666666"); textSize = 16f; isAntiAlias = true
        }
        canvas.save()
        canvas.rotate(-90f, 12f, padT + chartH / 2)
        canvas.drawText("SPD", 12f, padT + chartH / 2 + 5f, yPaint)
        canvas.restore()

        // Optional channel name
        highlightChannel?.let { name ->
            canvas.drawText(name, padL + chartW - 4f, padT + 18f,
                Paint().apply {
                    color = Color.parseColor("#AAAAAA"); textSize = 18f
                    isAntiAlias = true; textAlign = Paint.Align.RIGHT
                    typeface = Typeface.DEFAULT_BOLD
                })
        }
    }
}
