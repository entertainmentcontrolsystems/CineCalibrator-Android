package com.cinecalibrator.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.cinecalibrator.core.ColorScience
import kotlin.math.sqrt

/**
 * CIExyPickerView
 *
 * Interactive CIE 1931 chromaticity diagram that lets the user tap/drag
 * to select a target xy point. Emits the selected xy via [onXYChanged].
 * Draws the spectral locus, Planckian locus, and a crosshair at the
 * current selection.
 */
class CIExyPickerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var onXYChanged: ((x: Float, y: Float) -> Unit)? = null

    private var selectedX = 0.3127f
    private var selectedY = 0.3290f

    fun setXY(x: Float, y: Float) {
        selectedX = x.coerceIn(0f, 1f)
        selectedY = y.coerceIn(0f, 1f)
        invalidate()
    }

    fun getCIEX() = selectedX
    fun getCIEY() = selectedY

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

    // Planckian locus points (CCT → xy via Kang 2002)
    private val planckianLocus: List<Pair<Float, Float>> by lazy {
        (1700..10000 step 200).map { cct ->
            val c = cct.toDouble()
            val x = when {
                c < 2856 -> {
                    val u = (0.860117757 + 1.54118254e-4*c + 1.28641212e-7*c*c) /
                            (1.0 + 8.42420235e-4*c + 7.08145163e-7*c*c)
                    val v = (0.317398726 + 4.22806245e-5*c + 4.20481691e-8*c*c) /
                            (1.0 - 2.89741816e-5*c + 1.61456053e-7*c*c)
                    (3.0*u / (2.0*u - 8.0*v + 4.0)).toFloat()
                }
                c <= 4000 -> (-0.2661239e9/(c*c*c) - 0.2343589e6/(c*c) + 0.8776956e3/c + 0.179910).toFloat()
                else      -> (-3.0258469e9/(c*c*c) + 2.1070379e6/(c*c) + 0.2226347e3/c + 0.240390).toFloat()
            }
            val y = when {
                c < 2856 -> {
                    val u = (0.860117757 + 1.54118254e-4*c + 1.28641212e-7*c*c) /
                            (1.0 + 8.42420235e-4*c + 7.08145163e-7*c*c)
                    val v = (0.317398726 + 4.22806245e-5*c + 4.20481691e-8*c*c) /
                            (1.0 - 2.89741816e-5*c + 1.61456053e-7*c*c)
                    (2.0*v / (2.0*u - 8.0*v + 4.0)).toFloat()
                }
                c <= 2222 -> (-1.1063814*x*x*x - 1.34811020*x*x + 2.18555832*x - 0.20219683).toFloat()
                c <= 4000 -> (-0.9549476*x*x*x - 1.37418593*x*x + 2.09137015*x - 0.16748867).toFloat()
                else      -> (3.0817580*x*x*x - 5.87338670*x*x + 3.75112997*x - 0.37001483).toFloat()
            }
            x to y
        }
    }

    private val pad = 24f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#111111"))

        val w = width.toFloat() - pad * 2
        val h = height.toFloat() - pad * 2

        fun cx(x: Float) = pad + x * w
        fun cy(y: Float) = pad + (1f - y) * h * 0.9f  // y=0 at bottom

        // ── Background colour wash ────────────────────────────────────────────
        // Simple gradient fill inside spectral locus
        val locusPaint = Paint().apply { isAntiAlias = true }
        val locusPath = Path()
        spectralLocus.forEachIndexed { i, (x, y) ->
            if (i == 0) locusPath.moveTo(cx(x), cy(y)) else locusPath.lineTo(cx(x), cy(y))
        }
        locusPath.close()

        // Fill with a radial sweep of colours
        canvas.save()
        canvas.clipPath(locusPath)
        for (py in 0..height step 4) {
            for (px in 0..width step 4) {
                val nx = (px - pad) / w
                val ny = 1f - (py - pad) / (h * 0.9f)
                if (nx < 0 || nx > 1 || ny < 0 || ny > 1) continue
                val r = (nx * 2.2f).coerceIn(0f, 1f)
                val g = (ny * 1.9f).coerceIn(0f, 1f)
                val b = ((1f - nx - ny) * 2.1f).coerceIn(0f, 1f)
                val dotPaint = Paint().apply { color = Color.argb(120, (r*255).toInt(), (g*255).toInt(), (b*255).toInt()) }
                canvas.drawRect(px.toFloat(), py.toFloat(), px+4f, py+4f, dotPaint)
            }
        }
        canvas.restore()

        // ── Spectral locus outline ────────────────────────────────────────────
        val locusBorder = Paint().apply {
            color = Color.parseColor("#888888"); strokeWidth = 1.5f
            style = Paint.Style.STROKE; isAntiAlias = true
        }
        canvas.drawPath(locusPath, locusBorder)

        // ── Planckian locus ───────────────────────────────────────────────────
        val planckPaint = Paint().apply {
            color = Color.parseColor("#FFFFFF"); strokeWidth = 1.5f
            style = Paint.Style.STROKE; isAntiAlias = true; alpha = 180
        }
        val planckPath = Path()
        planckianLocus.forEachIndexed { i, (x, y) ->
            if (i == 0) planckPath.moveTo(cx(x), cy(y)) else planckPath.lineTo(cx(x), cy(y))
        }
        canvas.drawPath(planckPath, planckPaint)

        // ── Selected point crosshair ──────────────────────────────────────────
        val sx = cx(selectedX); val sy = cy(selectedY)
        val crossPaint = Paint().apply {
            color = Color.WHITE; strokeWidth = 2f; isAntiAlias = true
        }
        canvas.drawLine(sx - 14f, sy, sx + 14f, sy, crossPaint)
        canvas.drawLine(sx, sy - 14f, sx, sy + 14f, crossPaint)
        canvas.drawCircle(sx, sy, 6f, Paint().apply {
            color = Color.WHITE; style = Paint.Style.STROKE
            strokeWidth = 2f; isAntiAlias = true
        })

        // ── D65 marker ────────────────────────────────────────────────────────
        canvas.drawCircle(cx(0.3127f), cy(0.3290f), 4f,
            Paint().apply { color = Color.parseColor("#AAAAAA")
                style = Paint.Style.FILL; isAntiAlias = true })

        // ── Axis labels ───────────────────────────────────────────────────────
        val axPaint = Paint().apply { color = Color.parseColor("#666666")
            textSize = 18f; isAntiAlias = true }
        canvas.drawText("x →", cx(0.78f), cy(-0.04f), axPaint)
        canvas.drawText("y ↑", cx(-0.01f), cy(0.85f), axPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val w = width.toFloat() - pad * 2
            val h = height.toFloat() - pad * 2
            selectedX = ((event.x - pad) / w).coerceIn(0f, 1f)
            selectedY = (1f - (event.y - pad) / (h * 0.9f)).coerceIn(0f, 1f)
            invalidate()
            onXYChanged?.invoke(selectedX, selectedY)
            return true
        }
        return super.onTouchEvent(event)
    }
}
