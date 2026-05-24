package com.cinecalibrator.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.cinecalibrator.core.CameraCalibration
import com.cinecalibrator.core.ColorScience
import kotlin.math.hypot
import kotlin.math.min

/**
 * CameraDeviationView
 *
 * CIE 1931 xy chromaticity diagram showing camera measurement deviation
 * from manufacturer reference values.
 *
 * Rendering is intentionally consistent with CIEChartView:
 *   - Same spectral locus point set
 *   - Same axis labels (x horizontal, y vertical, 0.0–1.0)
 *   - Same reference gamut triangles (dashed, same colours)
 *   - Same D65 white point marker
 *   - Title: "CIE 1931 xy — Camera Deviation"
 *
 * Additional elements specific to calibration:
 *   ● Filled dot   = manufacturer reference xy
 *   ○ Hollow ring  = camera measured xy
 *   → Arrow        = direction and magnitude of error (Δxy)
 *   Summary text below: avg / max Δxy + verdict
 */
class CameraDeviationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var steps: List<CameraCalibration.DesatStep> = emptyList()
    private var reference: CameraCalibration.ReferenceFixture? = null

    fun setData(
        allSteps: List<CameraCalibration.DesatStep>,
        ref: CameraCalibration.ReferenceFixture
    ) {
        steps = allSteps
        reference = ref
        invalidate()
    }

    // ── Spectral locus — same point set as CIEChartView ───────────────────────
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

    private fun xyToDisplayColor(x: Double, y: Double): Int {
        val r = (x * 2.4).coerceIn(0.0, 1.0)
        val g = (y * 1.9).coerceIn(0.0, 1.0)
        val b = ((1.0 - x - y) * 2.1).coerceIn(0.0, 1.0)
        return Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val pad = 52f
        val titleH = 32f
        val chartSize = min(width.toFloat(), (height - titleH - 120f)) - pad * 2
        val left = pad
        val top = titleH + pad
        val bottom = top + chartSize

        canvas.drawColor(Color.parseColor("#111111"))

        fun cx(x: Float) = left + x * chartSize
        fun cy(y: Float) = bottom - y * chartSize

        // ── Title ─────────────────────────────────────────────────────────────
        val titlePaint = Paint().apply {
            color = Color.parseColor("#AAAAAA"); textSize = 22f
            isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("CIE 1931 xy — Camera Deviation", left, titleH + 4f, titlePaint)

        // ── Grid ──────────────────────────────────────────────────────────────
        val gridPaint = Paint().apply { color = Color.parseColor("#1E1E1E"); strokeWidth = 0.5f }
        for (i in 0..10) {
            val v = i / 10f
            canvas.drawLine(cx(v), top, cx(v), bottom, gridPaint)
            canvas.drawLine(left, cy(v), left + chartSize, cy(v), gridPaint)
        }

        // ── Axes ──────────────────────────────────────────────────────────────
        val axisPaint = Paint().apply { color = Color.parseColor("#444444"); strokeWidth = 1f }
        canvas.drawLine(left, bottom, left + chartSize, bottom, axisPaint)
        canvas.drawLine(left, top, left, bottom, axisPaint)

        // Axis labels — same style as CIEChartView
        val axisLabelPaint = Paint().apply {
            color = Color.parseColor("#777777"); textSize = 20f; isAntiAlias = true
        }
        val axisTitlePaint = Paint().apply {
            color = Color.parseColor("#AAAAAA"); textSize = 22f; isAntiAlias = true
        }
        for (i in 0..10) {
            val v = i / 10f
            canvas.drawText("%.1f".format(v), cx(v) - 10f, bottom + 28f, axisLabelPaint)
            canvas.drawText("%.1f".format(v), left - 42f, cy(v) + 6f, axisLabelPaint)
        }
        canvas.drawText("x", left + chartSize / 2, bottom + 46f, axisTitlePaint)
        canvas.save()
        canvas.rotate(-90f, left - 46f, top + chartSize / 2)
        canvas.drawText("y", left - 46f, top + chartSize / 2, axisTitlePaint)
        canvas.restore()

        // ── Spectral locus ────────────────────────────────────────────────────
        val horseshoePaint = Paint().apply {
            color = Color.parseColor("#555555"); strokeWidth = 1.5f
            style = Paint.Style.STROKE; isAntiAlias = true
        }
        val path = Path()
        spectralLocus.forEachIndexed { i, (x, y) ->
            if (i == 0) path.moveTo(cx(x), cy(y)) else path.lineTo(cx(x), cy(y))
        }
        path.close()
        canvas.drawPath(path, horseshoePaint)

        // ── Reference gamut triangles — same colours as CIEChartView ─────────
        fun drawGamut(gamut: com.cinecalibrator.core.ColorScience.Gamut, color: Int) {
            val gPath = Path()
            listOf(gamut.red, gamut.green, gamut.blue).forEachIndexed { i, pt ->
                if (i == 0) gPath.moveTo(cx(pt.x.toFloat()), cy(pt.y.toFloat()))
                else gPath.lineTo(cx(pt.x.toFloat()), cy(pt.y.toFloat()))
            }
            gPath.close()
            canvas.drawPath(gPath, Paint().apply {
                this.color = color; strokeWidth = 1.5f; style = Paint.Style.STROKE
                isAntiAlias = true
                pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
            })
        }
        drawGamut(ColorScience.REC709_GAMUT,  Color.parseColor("#3399FF"))
        drawGamut(ColorScience.REC2020_GAMUT, Color.parseColor("#33FF99"))
        drawGamut(ColorScience.ACES_AP1_GAMUT, Color.parseColor("#FFCC00"))

        // ── D65 white point ───────────────────────────────────────────────────
        canvas.drawCircle(cx(0.3127f), cy(0.3290f), 5f,
            Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true })

        val ref = reference ?: return

        // ── Per-primary deviation arrows ──────────────────────────────────────
        val perPrimary = steps.groupBy { it.primaryName }

        var totalDelta = 0.0
        var maxDelta = 0.0
        var pairCount = 0

        ref.emitters.forEach { emitter ->
            val pSteps = perPrimary[emitter.name] ?: return@forEach
            // Use the step closest to 50% white for a representative mid-gamut reading
            val midStep = pSteps.minByOrNull { s -> kotlin.math.abs(s.whiteLevel - 128) }
                ?: return@forEach

            val predXY = midStep.predictedXY
            val measXY = midStep.measuredXY
            val delta = hypot(measXY.x - predXY.x, measXY.y - predXY.y)
            totalDelta += delta
            if (delta > maxDelta) maxDelta = delta
            pairCount++

            val colour = xyToDisplayColor(predXY.x, predXY.y)

            // Reference dot (filled)
            canvas.drawCircle(cx(predXY.x.toFloat()), cy(predXY.y.toFloat()), 10f,
                Paint().apply { color = colour; style = Paint.Style.FILL; isAntiAlias = true })
            // Border
            canvas.drawCircle(cx(predXY.x.toFloat()), cy(predXY.y.toFloat()), 10f,
                Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE
                    strokeWidth = 1.5f; isAntiAlias = true })

            // Measured dot (hollow ring)
            canvas.drawCircle(cx(measXY.x.toFloat()), cy(measXY.y.toFloat()), 8f,
                Paint().apply { color = colour; style = Paint.Style.STROKE
                    strokeWidth = 2.5f; isAntiAlias = true })

            // Arrow from reference → measured
            canvas.drawLine(
                cx(predXY.x.toFloat()), cy(predXY.y.toFloat()),
                cx(measXY.x.toFloat()), cy(measXY.y.toFloat()),
                Paint().apply {
                    color = Color.argb(180, Color.red(colour), Color.green(colour), Color.blue(colour))
                    strokeWidth = 1.5f; isAntiAlias = true
                    pathEffect = null
                }
            )

            // Emitter name at reference
            canvas.drawText(emitter.name.take(6),
                cx(predXY.x.toFloat()) + 13f, cy(predXY.y.toFloat()) + 5f,
                Paint().apply { color = Color.WHITE; textSize = 18f; isAntiAlias = true
                    typeface = Typeface.DEFAULT_BOLD })

            // Δxy label at measured point
            canvas.drawText("Δ${"%.3f".format(delta)}",
                cx(measXY.x.toFloat()) + 10f, cy(measXY.y.toFloat()) - 8f,
                Paint().apply { color = Color.parseColor("#CCCCCC"); textSize = 16f; isAntiAlias = true })
        }

        // ── Summary ───────────────────────────────────────────────────────────
        if (pairCount > 0) {
            val avgDelta = totalDelta / pairCount
            val (verdict, verdictColor) = when {
                avgDelta < 0.010 -> "✓ Excellent — camera suitable for colorimetry" to Color.parseColor("#33AA66")
                avgDelta < 0.025 -> "⚠ Moderate error — calibration recommended"   to Color.parseColor("#FFAA00")
                avgDelta < 0.050 -> "⚠ High error — calibrate before use"           to Color.parseColor("#FF6600")
                else             -> "✗ Very high error — camera may be unsuitable"  to Color.parseColor("#CC3333")
            }
            val sp = Paint().apply { textSize = 20f; isAntiAlias = true }
            var sy = bottom + 50f
            for ((text, tColor) in listOf(
                "● Reference  ○ Measured  → Error" to Color.parseColor("#888888"),
                "Avg Δxy: ${"%.4f".format(avgDelta)}   Max Δxy: ${"%.4f".format(maxDelta)}" to Color.parseColor("#AAAAAA"),
                verdict to verdictColor
            )) {
                sp.color = tColor
                canvas.drawText(text, left, sy, sp)
                sy += 26f
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 1.45f).toInt())
    }

    private val CameraCalibration.DesatStep.whiteLevel: Int
        get() = 255 - primaryLevel
}
