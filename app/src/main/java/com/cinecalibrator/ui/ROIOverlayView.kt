package com.cinecalibrator.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * ROIOverlayView
 *
 * Transparent overlay drawn on top of the camera preview.
 * Renders:
 *   - A darkened vignette outside the measurement zone
 *   - A center crosshair with tick marks (aims the user at the light)
 *   - Corner brackets marking the ROI boundary
 *   - A small label: "ROI" and the current normalized center
 *
 * The ROI is centered by default (0.5, 0.5) but can be repositioned
 * by calling setROICenter() if the user taps to reposition.
 */
class ROIOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var roiCenterX = 0.5f
    var roiCenterY = 0.5f
    var roiRadiusFraction = 0.08f   // ROI radius as fraction of min(width, height)

    private val crosshairPaint = Paint().apply {
        color = Color.parseColor("#00C9FF")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val bracketPaint = Paint().apply {
        color = Color.parseColor("#00C9FF")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = null
    }

    private val dimPaint = Paint().apply {
        color = Color.parseColor("#88000000")
        style = Paint.Style.FILL
    }

    private val centerDotPaint = Paint().apply {
        color = Color.parseColor("#00C9FF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val labelPaint = Paint().apply {
        color = Color.parseColor("#00C9FF")
        textSize = 28f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }

    private val warningPaint = Paint().apply {
        color = Color.parseColor("#FF4444")
        textSize = 26f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    var showClipWarning = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val cx = roiCenterX * width
        val cy = roiCenterY * height
        val radius = roiRadiusFraction * minOf(width, height)
        val bracketLen = radius * 0.6f
        val crossLen = radius * 1.8f

        // Vignette: dim everything outside the ROI circle
        canvas.save()
        val clipPath = Path().apply {
            addCircle(cx, cy, radius * 2.5f, Path.Direction.CCW)
            fillType = Path.FillType.INVERSE_WINDING
        }
        canvas.clipPath(clipPath)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.restore()

        // Crosshair lines
        // Horizontal
        canvas.drawLine(cx - crossLen, cy, cx - radius * 0.3f, cy, crosshairPaint)
        canvas.drawLine(cx + radius * 0.3f, cy, cx + crossLen, cy, crosshairPaint)
        // Vertical
        canvas.drawLine(cx, cy - crossLen, cx, cy - radius * 0.3f, crosshairPaint)
        canvas.drawLine(cx, cy + radius * 0.3f, cx, cy + crossLen, crosshairPaint)

        // Center dot
        canvas.drawCircle(cx, cy, 5f, centerDotPaint)

        // ROI circle (dashed)
        val dashPaint = Paint(crosshairPaint).apply {
            pathEffect = DashPathEffect(floatArrayOf(16f, 8f), 0f)
            alpha = 180
        }
        canvas.drawCircle(cx, cy, radius, dashPaint)

        // Corner brackets (outer box)
        val bx = cx - radius * 1.4f
        val by = cy - radius * 1.4f
        val bx2 = cx + radius * 1.4f
        val by2 = cy + radius * 1.4f

        // Top-left
        canvas.drawLine(bx, by, bx + bracketLen, by, bracketPaint)
        canvas.drawLine(bx, by, bx, by + bracketLen, bracketPaint)
        // Top-right
        canvas.drawLine(bx2, by, bx2 - bracketLen, by, bracketPaint)
        canvas.drawLine(bx2, by, bx2, by + bracketLen, bracketPaint)
        // Bottom-left
        canvas.drawLine(bx, by2, bx + bracketLen, by2, bracketPaint)
        canvas.drawLine(bx, by2, bx, by2 - bracketLen, bracketPaint)
        // Bottom-right
        canvas.drawLine(bx2, by2, bx2 - bracketLen, by2, bracketPaint)
        canvas.drawLine(bx2, by2, bx2, by2 - bracketLen, bracketPaint)

        // ROI label
        canvas.drawText("ROI", cx - 24f, by2 + 36f, labelPaint)

        // Clip warning
        if (showClipWarning) {
            canvas.drawText("⚠ CLIPPING", cx - 80f, by - 20f, warningPaint)
        }
    }

    fun setROICenter(nx: Float, ny: Float) {
        roiCenterX = nx
        roiCenterY = ny
        invalidate()
    }

    fun setClipWarning(show: Boolean) {
        showClipWarning = show
        invalidate()
    }
}
