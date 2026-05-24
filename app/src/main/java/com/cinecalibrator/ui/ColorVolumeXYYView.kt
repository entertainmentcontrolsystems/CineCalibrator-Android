package com.cinecalibrator.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.cinecalibrator.core.ColorScience
import com.cinecalibrator.core.ScanEngine
import kotlin.math.*

/**
 * ColorVolumeXYYView — CIE xyY oblique axonometric projection.
 * ALL internal arithmetic is Float to avoid Double/Float widening.
 */
class ColorVolumeXYYView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var samples: List<ScanEngine.ColorVolumeSample> = emptyList()
    private var measurements: List<ColorScience.DiodeMeasurement> = emptyList()

    fun setData(
        colorVolumeSamples: List<ScanEngine.ColorVolumeSample>,
        diodeMeasurements: List<ColorScience.DiodeMeasurement>
    ) {
        samples = colorVolumeSamples
        measurements = diodeMeasurements
        invalidate()
    }

    private val spectralLocus = listOf(
        0.1741f to 0.0050f, 0.1738f to 0.0139f, 0.1736f to 0.0433f,
        0.1733f to 0.0899f, 0.1724f to 0.1390f, 0.1566f to 0.1770f,
        0.1295f to 0.2455f, 0.0992f to 0.3227f, 0.0669f to 0.3994f,
        0.0369f to 0.4997f, 0.0143f to 0.5877f, 0.0090f to 0.6341f,
        0.0138f to 0.6568f, 0.0236f to 0.6692f, 0.0536f to 0.7082f,
        0.1102f to 0.7438f, 0.1650f to 0.7752f, 0.2296f to 0.7938f,
        0.3016f to 0.6924f, 0.3731f to 0.6067f, 0.4441f to 0.5030f,
        0.5125f to 0.4183f, 0.5752f to 0.3449f, 0.6270f to 0.2892f,
        0.6658f to 0.2374f, 0.7079f to 0.2920f, 0.7348f to 0.2653f
    )

    // Approximate display colour from CIE xy (Float inputs)
    private fun xyColor(x: Float, y: Float): Int = Color.rgb(
        ((x * 2.4f).coerceIn(0f, 1f) * 255f).toInt(),
        ((y * 1.9f).coerceIn(0f, 1f) * 255f).toInt(),
        (((1f - x - y) * 2.1f).coerceIn(0f, 1f) * 255f).toInt()
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        canvas.drawColor(Color.parseColor("#111111"))

        val titleH  = 34f
        val pad     = 48f
        val legendH = 70f
        val chartW  = width.toFloat() - pad * 2f
        val chartH  = height.toFloat() - titleH - pad * 2f - legendH

        val baseFrac  = 0.55f
        val baseW     = chartW
        val baseH     = chartH * baseFrac
        val baseLeft  = pad
        val baseBottom = titleH + pad + chartH
        val baseTop   = baseBottom - baseH

        // ── Projection: all Float ─────────────────────────────────────────────
        fun bx(x: Float): Float = baseLeft + x * baseW
        fun by(y: Float): Float = baseBottom - y * baseH * 0.65f

        val yAxisScale = chartH * 0.38f
        val yAxisAngle = (PI.toFloat() / 2.2f)
        val yStepX: Float = cos(yAxisAngle) * yAxisScale
        val yStepY: Float = sin(yAxisAngle) * yAxisScale

        fun sx(x: Float, y: Float, Y: Float): Float = bx(x) + Y * yStepX
        fun sy(x: Float, y: Float, Y: Float): Float = by(y) - Y * yStepY

        val maxY: Float = (measurements.maxOfOrNull { it.fluxRelative }?.toFloat() ?: 1f).coerceAtLeast(0.01f)

        // Scatter samples may be in a different luminance scale than the cross-normalised
        // primary fluxRelative (camera Y is 0.001–0.05, fluxRelative max is 1.0).
        // Normalise scatter cloud to its own peak, then scale to fit within the pillar space.
        val maxSampleLum = samples.maxOfOrNull { it.luminance.toFloat() }?.coerceAtLeast(0.0001f) ?: 1f

        // ── Title ─────────────────────────────────────────────────────────────
        canvas.drawText("CIE xyY Color Volume",
            baseLeft, titleH,
            Paint().apply { color = Color.parseColor("#AAAAAA"); textSize = 22f
                isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD })

        // ── Axis labels ───────────────────────────────────────────────────────
        val axLbl = Paint().apply { color = Color.parseColor("#777777")
            textSize = 18f; isAntiAlias = true }

        canvas.drawText("x →",      bx(0.85f), by(-0.08f), axLbl)
        canvas.drawText("y (depth)", bx(-0.04f), by(0.6f),  axLbl)

        val yTopX = sx(0f, 0f, 1f)
        val yTopY = sy(0f, 0f, 1f)
        canvas.save()
        canvas.rotate(-90f + Math.toDegrees(yAxisAngle.toDouble()).toFloat(),
            yTopX, yTopY + 12f)
        canvas.drawText("Y (luminance) ↑", yTopX - 60f, yTopY + 12f, axLbl)
        canvas.restore()

        // ── Y axis ────────────────────────────────────────────────────────────
        val yAxisPaint = Paint().apply {
            color = Color.parseColor("#555555"); strokeWidth = 1.5f
            pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f)
        }
        canvas.drawLine(
            sx(0.3127f, 0.3290f, 0f), sy(0.3127f, 0.3290f, 0f),
            sx(0.3127f, 0.3290f, 1f), sy(0.3127f, 0.3290f, 1f),
            yAxisPaint
        )
        for (t in 0..4) {
            val yF = t / 4f
            val tx = sx(0.3127f, 0.3290f, yF)
            val ty = sy(0.3127f, 0.3290f, yF)
            canvas.drawLine(tx - 4f, ty, tx + 4f, ty, yAxisPaint)
            canvas.drawText("%.2f".format(yF), tx + 8f, ty + 5f, axLbl)
        }

        // ── Base horseshoe ────────────────────────────────────────────────────
        val horsePaint = Paint().apply { color = Color.parseColor("#444444")
            strokeWidth = 1.5f; style = Paint.Style.STROKE; isAntiAlias = true }
        val hPath = Path()
        spectralLocus.forEachIndexed { i, (x, y) ->
            if (i == 0) hPath.moveTo(bx(x), by(y)) else hPath.lineTo(bx(x), by(y))
        }
        hPath.close()
        canvas.drawPath(hPath, horsePaint)

        // ── Base gamut triangles ──────────────────────────────────────────────
        fun drawBaseGamut(gamut: ColorScience.Gamut, color: Int) {
            val gPath = Path()
            listOf(gamut.red, gamut.green, gamut.blue).forEachIndexed { i, pt ->
                val px = bx(pt.x.toFloat()); val py = by(pt.y.toFloat())
                if (i == 0) gPath.moveTo(px, py) else gPath.lineTo(px, py)
            }
            gPath.close()
            canvas.drawPath(gPath, Paint().apply {
                this.color = color; strokeWidth = 1f; style = Paint.Style.STROKE
                isAntiAlias = true; alpha = 100
                pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f)
            })
        }
        drawBaseGamut(ColorScience.REC709_GAMUT,   Color.parseColor("#3399FF"))
        drawBaseGamut(ColorScience.REC2020_GAMUT,  Color.parseColor("#33FF99"))
        drawBaseGamut(ColorScience.ACES_AP1_GAMUT, Color.parseColor("#FFCC00"))

        // ── D65 base marker ───────────────────────────────────────────────────
        canvas.drawCircle(bx(0.3127f), by(0.3290f), 4f,
            Paint().apply { color = Color.parseColor("#888888")
                style = Paint.Style.FILL; isAntiAlias = true })

        // ── Color volume scatter ──────────────────────────────────────────────
        if (samples.isNotEmpty()) {
            val dotPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
            // Sort back-to-front for painter's algorithm
            val sorted = samples.sortedWith(compareBy({ it.y }, { -it.luminance }))
            sorted.forEach { s ->
                // Normalise to scatter's own peak, then scale to 80% of chart height
                // so blend cloud sits inside the primary pillar envelope
                val yNorm = ((s.luminance.toFloat() / maxSampleLum) * 0.8f).coerceIn(0f, 1f)
                val px = sx(s.x.toFloat(), s.y.toFloat(), yNorm)
                val py = sy(s.x.toFloat(), s.y.toFloat(), yNorm)
                dotPaint.color = Color.argb(160,
                    ((s.x * 2.4).coerceIn(0.0, 1.0) * 255).toInt(),
                    ((s.y * 1.9).coerceIn(0.0, 1.0) * 255).toInt(),
                    (((1.0 - s.x - s.y) * 2.1).coerceIn(0.0, 1.0) * 255).toInt()
                )
                canvas.drawCircle(px, py, 5f, dotPaint)
            }
        }

        // ── Primary pillars ───────────────────────────────────────────────────
        measurements.forEach { m ->
            val xF   = m.x.toFloat()
            val yF   = m.y.toFloat()
            val yNorm = (m.fluxRelative.toFloat() / maxY).coerceIn(0f, 1f)
            val bxP = bx(xF); val byP = by(yF)
            val topX = sx(xF, yF, yNorm)
            val topY = sy(xF, yF, yNorm)
            val col  = xyColor(xF, yF)

            canvas.drawLine(bxP, byP, topX, topY,
                Paint().apply { color = Color.argb(160, Color.red(col),
                    Color.green(col), Color.blue(col)); strokeWidth = 1.5f })
            canvas.drawCircle(bxP, byP, 5f,
                Paint().apply { color = col; style = Paint.Style.FILL; isAntiAlias = true })
            canvas.drawCircle(topX, topY, 7f,
                Paint().apply { color = col; style = Paint.Style.FILL; isAntiAlias = true })
            canvas.drawCircle(topX, topY, 7f,
                Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE
                    strokeWidth = 1.5f; isAntiAlias = true })
            canvas.drawText(m.diodeName.take(5), topX + 9f, topY - 4f,
                Paint().apply { color = Color.WHITE; textSize = 18f
                    isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD })
        }

        // ── Legend ────────────────────────────────────────────────────────────
        val lp = Paint().apply { textSize = 17f; isAntiAlias = true }
        var lx = baseLeft; val ly = baseBottom + 28f
        for ((text, col) in listOf(
            "● Primaries" to Color.parseColor("#DDDDDD"),
            "· Color volume" to Color.parseColor("#888888"),
            "— Rec.709"   to Color.parseColor("#3399FF"),
            "— Rec.2020"  to Color.parseColor("#33FF99"),
            "— ACES AP1"  to Color.parseColor("#FFCC00")
        )) {
            lp.color = col
            canvas.drawText(text, lx, ly, lp)
            lx += lp.measureText(text) + 16f
        }
        if (samples.isNotEmpty()) {
            lp.color = Color.parseColor("#666666")
            canvas.drawText("${samples.size} blend pts  ·  Y normalised to peak emitter flux",
                baseLeft, ly + 24f, lp)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 1.5f).toInt())
    }
}
