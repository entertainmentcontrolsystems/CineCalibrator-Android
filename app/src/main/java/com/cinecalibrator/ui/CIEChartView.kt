package com.cinecalibrator.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.cinecalibrator.core.CameraCalibration
import com.cinecalibrator.core.ColorScience
import kotlin.math.min

/**
 * CIEChartView
 *
 * Custom View that renders:
 *   - CIE 1931 spectral locus (horseshoe outline)
 *   - Planckian / black-body locus
 *   - Reference gamut triangles (Rec.709, Rec.2020, ACES AP1) as dashed outlines
 *   - Measured diode points as filled circles with labels
 *   - D65 white point marker
 */
class CIEChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var measurements: List<ColorScience.DiodeMeasurement> = emptyList()

    private var cameraGamutHull: List<CameraCalibration.CIExy> = emptyList()

    fun setMeasurements(data: List<ColorScience.DiodeMeasurement>) {
        measurements = data
        // Pick up the active camera gamut profile hull each time measurements are set
        cameraGamutHull = CameraCalibration.getActiveGamutProfile()?.hullPoints ?: emptyList()
        invalidate()
    }

    // ─── Paints ────────────────────────────────────────────────────────────────

    private val bgPaint = Paint().apply { color = Color.parseColor("#111111") }
    private val axisPaint = Paint().apply {
        color = Color.parseColor("#444444"); strokeWidth = 1f; style = Paint.Style.STROKE
    }
    private val axisLabelPaint = Paint().apply {
        color = Color.parseColor("#777777"); textSize = 22f; isAntiAlias = true
    }
    private val horseshoePaint = Paint().apply {
        color = Color.parseColor("#555555"); strokeWidth = 2f; style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val diodePaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val diodeBorderPaint = Paint().apply {
        color = Color.WHITE; strokeWidth = 2f; style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val diodeLabelPaint = Paint().apply {
        color = Color.parseColor("#EEEEEE"); textSize = 24f; isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }
    private val whitePtPaint = Paint().apply {
        color = Color.parseColor("#FFFFFF"); style = Paint.Style.FILL; isAntiAlias = true
    }

    private fun gamutPaint(color: Int) = Paint().apply {
        this.color = color; strokeWidth = 2f; style = Paint.Style.STROKE; isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(12f, 6f), 0f)
    }

    // ─── Spectral Locus ────────────────────────────────────────────────────────

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

    // ─── Draw ──────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val pad = 60f
        val size = min(width, height).toFloat()
        val left = pad; val top = pad
        val chartW = size - pad * 2
        val chartH = size - pad * 2
        val bottom = top + chartH

        // Background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Helper: CIE xy → canvas pixel
        fun cx(x: Float) = left + x * chartW
        fun cy(y: Float) = bottom - y * chartH

        // Grid lines
        val gridPaint = Paint().apply { color = Color.parseColor("#222222"); strokeWidth = 0.5f }
        for (i in 0..10) {
            val v = i / 10f
            canvas.drawLine(cx(v), top, cx(v), bottom, gridPaint)
            canvas.drawLine(left, cy(v), left + chartW, cy(v), gridPaint)
        }

        // Axes
        canvas.drawLine(left, bottom, left + chartW, bottom, axisPaint)
        canvas.drawLine(left, top, left, bottom, axisPaint)

        // Axis labels
        for (i in 0..10) {
            val v = i / 10f
            canvas.drawText("%.1f".format(v), cx(v) - 10f, bottom + 36f, axisLabelPaint)
            canvas.drawText("%.1f".format(v), left - 48f, cy(v) + 8f, axisLabelPaint)
        }

        // Axis titles
        val xTitlePaint = Paint().apply {
            color = Color.parseColor("#AAAAAA"); textSize = 26f; isAntiAlias = true
        }
        canvas.drawText("x", left + chartW / 2, bottom + 54f, xTitlePaint)
        canvas.save()
        canvas.rotate(-90f, left - 54f, top + chartH / 2)
        canvas.drawText("y", left - 54f, top + chartH / 2, xTitlePaint)
        canvas.restore()

        // Spectral locus horseshoe
        val horseshoePath = Path()
        spectralLocus.forEachIndexed { i, (x, y) ->
            if (i == 0) horseshoePath.moveTo(cx(x), cy(y)) else horseshoePath.lineTo(cx(x), cy(y))
        }
        horseshoePath.close()
        canvas.drawPath(horseshoePath, horseshoePaint)

        // Reference gamut triangles
        drawGamut(canvas, ColorScience.REC709_GAMUT, Color.parseColor("#3399FF"), ::cx, ::cy)
        drawGamut(canvas, ColorScience.REC2020_GAMUT, Color.parseColor("#33FF99"), ::cx, ::cy)
        drawGamut(canvas, ColorScience.ACES_AP1_GAMUT, Color.parseColor("#FFCC00"), ::cx, ::cy)

        // D65 white point
        val d65x = 0.3127f; val d65y = 0.3290f
        canvas.drawCircle(cx(d65x), cy(d65y), 6f, whitePtPaint)

        // Camera gamut hull — filled polygon showing the phone's reliable measurement zone
        if (cameraGamutHull.size >= 3) {
            val hullPath = Path()
            cameraGamutHull.forEachIndexed { i, pt ->
                if (i == 0) hullPath.moveTo(cx(pt.x.toFloat()), cy(pt.y.toFloat()))
                else hullPath.lineTo(cx(pt.x.toFloat()), cy(pt.y.toFloat()))
            }
            hullPath.close()
            // Translucent fill
            canvas.drawPath(hullPath, Paint().apply {
                color = Color.argb(28, 100, 200, 255)
                style = Paint.Style.FILL
            })
            // Solid border
            canvas.drawPath(hullPath, Paint().apply {
                color = Color.argb(180, 100, 200, 255)
                strokeWidth = 2f; style = Paint.Style.STROKE; isAntiAlias = true
                pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
            })
            // Label
            val hullCx = cameraGamutHull.map { it.x }.average().toFloat()
            val hullCy = cameraGamutHull.map { it.y }.average().toFloat()
            canvas.drawText("Camera gamut", cx(hullCx) - 50f, cy(hullCy),
                Paint().apply { color = Color.argb(160, 100, 200, 255)
                    textSize = 20f; isAntiAlias = true })
        }

        // Measured diodes — ring colour indicates confidence
        measurements.forEach { m ->
            val px = cx(m.x.toFloat()); val py = cy(m.y.toFloat())
            diodePaint.color = approximateColor(m.x, m.y)
            canvas.drawCircle(px, py, 14f, diodePaint)
            val ringColor = when (m.measurementConfidence) {
                com.cinecalibrator.core.CameraGamutProfile.Confidence.FULL       -> Color.WHITE
                com.cinecalibrator.core.CameraGamutProfile.Confidence.CCT_ONLY   -> Color.parseColor("#FF8C42")
                com.cinecalibrator.core.CameraGamutProfile.Confidence.MARGINAL   -> Color.parseColor("#FFCC00")
                com.cinecalibrator.core.CameraGamutProfile.Confidence.UNRELIABLE -> Color.parseColor("#CC3333")
            }
            diodeBorderPaint.color = ringColor
            canvas.drawCircle(px, py, 14f, diodeBorderPaint)
            canvas.drawText(m.diodeName.take(8), px + 18f, py + 8f, diodeLabelPaint)
        }

        // Legend
        val legendY = bottom + 80f
        drawLegend(canvas, left, legendY)
    }

    private fun drawGamut(
        canvas: Canvas,
        gamut: ColorScience.Gamut,
        color: Int,
        cx: (Float) -> Float,
        cy: (Float) -> Float
    ) {
        val path = Path()
        val pts = listOf(gamut.red, gamut.green, gamut.blue)
        pts.forEachIndexed { i, pt ->
            if (i == 0) path.moveTo(cx(pt.x.toFloat()), cy(pt.y.toFloat()))
            else path.lineTo(cx(pt.x.toFloat()), cy(pt.y.toFloat()))
        }
        path.close()
        canvas.drawPath(path, gamutPaint(color))
    }

    private fun drawLegend(canvas: Canvas, x: Float, y: Float) {
        val items = mutableListOf(
            Color.parseColor("#3399FF") to "Rec.709",
            Color.parseColor("#33FF99") to "Rec.2020",
            Color.parseColor("#FFCC00") to "ACES AP1",
            Color.WHITE to "Measured"
        )
        if (cameraGamutHull.size >= 3) {
            items.add(Color.argb(180, 100, 200, 255) to "Camera gamut")
        }
        var curX = x
        val boxPaint = Paint().apply { style = Paint.Style.FILL }
        val labelPaint = Paint().apply { color = Color.parseColor("#CCCCCC"); textSize = 22f; isAntiAlias = true }
        items.forEach { (color, label) ->
            boxPaint.color = color
            canvas.drawRect(curX, y - 18f, curX + 24f, y + 2f, boxPaint)
            canvas.drawText(label, curX + 30f, y, labelPaint)
            curX += labelPaint.measureText(label) + 60f
        }
    }

    private fun approximateColor(x: Double, y: Double): Int {
        val r = (x * 2.2).coerceIn(0.0, 1.0)
        val g = (y * 1.8).coerceIn(0.0, 1.0)
        val b = ((1.0 - x - y) * 2.0).coerceIn(0.0, 1.0)
        return Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(size, size)  // Square
    }
}
