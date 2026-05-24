package com.cinecalibrator.core

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * ExportManager
 *
 * Handles PDF and CSV export of calibration results.
 *
 * PDF layout:
 *   Page 1: Cover — fixture name, date, summary
 *   Page 2: CIE 1931 chromaticity diagram (rendered as vector paths) + gamut overlays
 *   Page 3: Per-diode table (CIE xy, flux, CCT, gamut coverage)
 *   Page 4: LUT summary and metadata
 *
 * CSV contains one row per diode measurement with all numeric fields.
 */
class ExportManager(private val context: Context) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fileDate = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    // ─── PDF Export ───────────────────────────────────────────────────────────────

    fun exportPDF(
        result: ColorScience.ScanResult,
        colorVolumeSamples: List<ScanEngine.ColorVolumeSample> = emptyList()
    ): File {
        val doc = PdfDocument()
        drawCoverPage(doc, result)
        drawCIEDiagramPage(doc, result)
        drawMeasurementTablePage(doc, result)
        drawColorVolumePage(doc, result, colorVolumeSamples)

        val safeName = result.fixtureName.replace("[^A-Za-z0-9_\\-]".toRegex(), "_")
        val file = File(context.cacheDir, "CineCalibrator_${safeName}_${fileDate.format(Date())}.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        Timber.d("PDF exported: ${file.absolutePath}")
        return file
    }

    private fun drawCoverPage(doc: PdfDocument, result: ColorScience.ScanResult) {
        val info = PdfDocument.PageInfo.Builder(595, 842, 1).create()  // A4
        val page = doc.startPage(info)
        val canvas = page.canvas

        val bgPaint = Paint().apply { color = Color.parseColor("#0D0D0D") }
        canvas.drawRect(0f, 0f, 595f, 842f, bgPaint)

        // Accent stripe
        val accentPaint = Paint().apply { color = Color.parseColor("#00C9FF") }
        canvas.drawRect(0f, 0f, 6f, 842f, accentPaint)

        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val subtitlePaint = Paint().apply {
            color = Color.parseColor("#AAAAAA")
            textSize = 14f
            isAntiAlias = true
        }
        val bodyPaint = Paint().apply {
            color = Color.parseColor("#DDDDDD")
            textSize = 12f
            isAntiAlias = true
        }

        canvas.drawText("CineCalibrator", 40f, 80f, titlePaint)
        canvas.drawText("Cinema Light Calibration Report", 40f, 110f, subtitlePaint)

        // Divider
        val divPaint = Paint().apply { color = Color.parseColor("#333333"); strokeWidth = 1f }
        canvas.drawLine(40f, 125f, 555f, 125f, divPaint)

        canvas.drawText("Fixture:", 40f, 180f, subtitlePaint)
        titlePaint.textSize = 22f
        canvas.drawText(result.fixtureName, 40f, 210f, titlePaint)

        canvas.drawText("Manufacturer:", 40f, 250f, subtitlePaint)
        canvas.drawText(result.fixtureManufacturer, 40f, 270f, bodyPaint)

        canvas.drawText("Scan Date:", 40f, 310f, subtitlePaint)
        canvas.drawText(dateFormat.format(Date(result.timestamp)), 40f, 330f, bodyPaint)

        canvas.drawText("Diodes Measured:", 40f, 370f, subtitlePaint)
        canvas.drawText("${result.measurements.size}", 40f, 390f, bodyPaint)

        if (result.notes.isNotEmpty()) {
            canvas.drawText("Notes:", 40f, 430f, subtitlePaint)
            canvas.drawText(result.notes, 40f, 450f, bodyPaint)
        }

        // Summary statistics
        val avgCCT = result.measurements.mapNotNull { it.cct }.average()
        val maxFlux = result.measurements.maxOfOrNull { it.fluxRelative } ?: 0.0

        canvas.drawText("Summary", 40f, 520f, titlePaint.apply { textSize = 16f })
        canvas.drawLine(40f, 530f, 555f, 530f, divPaint)
        bodyPaint.textSize = 11f
        canvas.drawText("Average CCT: ${"%.0f".format(avgCCT)} K", 40f, 555f, bodyPaint)
        canvas.drawText("Peak Relative Flux: ${"%.3f".format(maxFlux)}", 40f, 575f, bodyPaint)

        // Gamut coverage table
        val refs = ColorScience.ALL_REFERENCE_GAMUTS
        canvas.drawText("Gamut Coverage (RGB primaries):", 40f, 615f, subtitlePaint)
        refs.forEachIndexed { i, ref ->
            val avg = result.measurements
                .mapNotNull { it.gamutCoverage[ref.name] }
                .average()
            if (!avg.isNaN()) {
                canvas.drawText("  ${ref.name}: ${"%.1f".format(avg)}%", 40f, 640f + i * 20f, bodyPaint)
            }
        }

        // Footer
        val footerPaint = Paint().apply { color = Color.parseColor("#555555"); textSize = 9f; isAntiAlias = true }
        canvas.drawText("Generated by CineCalibrator  •  Page 1", 40f, 820f, footerPaint)

        doc.finishPage(page)
    }

    private fun drawCIEDiagramPage(doc: PdfDocument, result: ColorScience.ScanResult) {
        val info = PdfDocument.PageInfo.Builder(595, 842, 2).create()
        val page = doc.startPage(info)
        val canvas = page.canvas

        val bgPaint = Paint().apply { color = Color.parseColor("#0D0D0D") }
        canvas.drawRect(0f, 0f, 595f, 842f, bgPaint)

        val titlePaint = Paint().apply {
            color = Color.WHITE; textSize = 16f
            typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true
        }
        canvas.drawText("CIE 1931 Chromaticity Diagram", 40f, 50f, titlePaint)

        // Diagram area
        val left = 60f; val top = 70f; val right = 535f; val bottom = 600f
        val w = right - left; val h = bottom - top

        // CIE diagram background (approximated horseshoe outline)
        drawCIEHorseshoe(canvas, left, top, w, h)

        // Reference gamut triangles
        drawGamutTriangle(canvas, ColorScience.REC709_GAMUT, left, top, w, h,
            Color.parseColor("#3399FF"), "Rec.709")
        drawGamutTriangle(canvas, ColorScience.REC2020_GAMUT, left, top, w, h,
            Color.parseColor("#33FF99"), "Rec.2020")
        drawGamutTriangle(canvas, ColorScience.ACES_AP1_GAMUT, left, top, w, h,
            Color.parseColor("#FFCC00"), "ACES AP1")

        // Measured diode points
        val diodePaint = Paint().apply { color = Color.WHITE; strokeWidth = 6f; style = Paint.Style.FILL; isAntiAlias = true }
        val labelPaint = Paint().apply { color = Color.parseColor("#CCCCCC"); textSize = 9f; isAntiAlias = true }

        result.measurements.forEach { m ->
            val px = left + (m.x * w).toFloat()
            val py = bottom - (m.y * h).toFloat()
            diodePaint.color = xyToApproxColor(m.x, m.y)
            canvas.drawCircle(px, py, 5f, diodePaint)
            canvas.drawText(m.diodeName, px + 7f, py + 4f, labelPaint)
        }

        // Axes
        val axisPaint = Paint().apply { color = Color.parseColor("#555555"); strokeWidth = 1f }
        canvas.drawLine(left, bottom, right, bottom, axisPaint)
        canvas.drawLine(left, top, left, bottom, axisPaint)

        val axisLabelPaint = Paint().apply { color = Color.parseColor("#888888"); textSize = 10f; isAntiAlias = true }
        for (v in 0..10) {
            val xv = v / 10f
            val px = left + xv * w
            canvas.drawLine(px, bottom, px, bottom + 4f, axisPaint)
            canvas.drawText("%.1f".format(xv), px - 6f, bottom + 14f, axisLabelPaint)
            val py = bottom - xv * h
            canvas.drawLine(left - 4f, py, left, py, axisPaint)
            canvas.drawText("%.1f".format(xv), left - 24f, py + 4f, axisLabelPaint)
        }
        canvas.drawText("x →", right - 20f, bottom + 25f, axisLabelPaint)
        canvas.save()
        canvas.rotate(-90f, left - 35f, top + h / 2)
        canvas.drawText("y →", left - 35f, top + h / 2, axisLabelPaint)
        canvas.restore()

        // Legend
        val legendPaint = Paint().apply { textSize = 10f; isAntiAlias = true }
        val legendY = 640f
        drawLegendItem(canvas, 60f, legendY, Color.parseColor("#3399FF"), "Rec.709")
        drawLegendItem(canvas, 160f, legendY, Color.parseColor("#33FF99"), "Rec.2020")
        drawLegendItem(canvas, 260f, legendY, Color.parseColor("#FFCC00"), "ACES AP1")
        drawLegendItem(canvas, 360f, legendY, Color.WHITE, "Measured Diodes")

        val footerPaint = Paint().apply { color = Color.parseColor("#555555"); textSize = 9f; isAntiAlias = true }
        canvas.drawText("Generated by CineCalibrator  •  Page 2", 40f, 820f, footerPaint)

        doc.finishPage(page)
    }

    private fun drawCIEHorseshoe(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        // Simplified spectral locus boundary points (CIE 1931 xy, selected wavelengths)
        val spectralLocus = listOf(
            0.1741f to 0.0050f, 0.1738f to 0.0139f, 0.1736f to 0.0433f,
            0.1733f to 0.0899f, 0.1724f to 0.1390f, 0.1566f to 0.1770f,
            0.1295f to 0.2455f, 0.0992f to 0.3227f, 0.0669f to 0.3994f,
            0.0369f to 0.4997f, 0.0143f to 0.5877f, 0.0090f to 0.6341f,
            0.0138f to 0.6568f, 0.0236f to 0.6692f, 0.0536f to 0.7082f,
            0.1102f to 0.7438f, 0.1650f to 0.7752f, 0.2296f to 0.7938f,
            0.3016f to 0.6924f, 0.3731f to 0.6067f, 0.4441f to 0.5030f,
            0.5125f to 0.4183f, 0.5752f to 0.3449f, 0.6270f to 0.2892f,
            0.6658f to 0.2374f, 0.7079f to 0.2920f, 0.7348f to 0.2653f,
            0.7141f to 0.2856f, 0.6834f to 0.3166f, 0.6548f to 0.3448f,
            0.6270f to 0.3730f, 0.5848f to 0.4154f, 0.5298f to 0.4704f,
            0.4510f to 0.5390f, 0.3547f to 0.6234f, 0.2800f to 0.6800f,
            0.1741f to 0.0050f
        )

        val path = Path()
        spectralLocus.forEachIndexed { i, (x, y) ->
            val px = left + x * w
            val py = (top + h) - y * h  // flip Y
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }

        val outlinePaint = Paint().apply {
            color = Color.parseColor("#444444")
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            isAntiAlias = true
        }
        canvas.drawPath(path, outlinePaint)
    }

    private fun drawGamutTriangle(
        canvas: Canvas, gamut: ColorScience.Gamut,
        left: Float, top: Float, w: Float, h: Float,
        color: Int, label: String
    ) {
        val paint = Paint().apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            isAntiAlias = true
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
        }
        val path = Path()
        val pts = listOf(gamut.red, gamut.green, gamut.blue)
        pts.forEachIndexed { i, pt ->
            val px = left + pt.x.toFloat() * w
            val py = (top + h) - pt.y.toFloat() * h
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawLegendItem(canvas: Canvas, x: Float, y: Float, color: Int, label: String) {
        val boxPaint = Paint().apply { this.color = color; style = Paint.Style.FILL }
        val textPaint = Paint().apply { this.color = Color.parseColor("#CCCCCC"); textSize = 10f; isAntiAlias = true }
        canvas.drawRect(x, y - 10f, x + 14f, y + 2f, boxPaint)
        canvas.drawText(label, x + 18f, y, textPaint)
    }

    private fun xyToApproxColor(x: Double, y: Double): Int {
        // Very rough xy → RGB approximation for visual reference
        val r = (x * 2.5).coerceIn(0.0, 1.0)
        val g = (y * 2.0).coerceIn(0.0, 1.0)
        val b = ((1.0 - x - y) * 2.0).coerceIn(0.0, 1.0)
        return Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    }

    private fun drawMeasurementTablePage(doc: PdfDocument, result: ColorScience.ScanResult) {
        val info = PdfDocument.PageInfo.Builder(595, 842, 3).create()
        val page = doc.startPage(info)
        val canvas = page.canvas

        val bgPaint = Paint().apply { color = Color.parseColor("#0D0D0D") }
        canvas.drawRect(0f, 0f, 595f, 842f, bgPaint)

        val titlePaint = Paint().apply {
            color = Color.WHITE; textSize = 16f
            typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true
        }
        canvas.drawText("Per-Diode Measurements", 40f, 50f, titlePaint)

        val headerPaint = Paint().apply {
            color = Color.parseColor("#00C9FF"); textSize = 9f
            typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true
        }
        val cellPaint = Paint().apply { color = Color.parseColor("#CCCCCC"); textSize = 9f; isAntiAlias = true }
        val altRowPaint = Paint().apply { color = Color.parseColor("#1A1A1A") }

        val cols = listOf("Diode", "CIE x", "CIE y", "Y (flux)", "CCT (K)", "Duv", "709 %", "2020 %", "AP1 %")
        val colX = floatArrayOf(40f, 120f, 165f, 210f, 255f, 315f, 365f, 415f, 465f)
        val headerY = 80f

        cols.forEachIndexed { i, h -> canvas.drawText(h, colX[i], headerY, headerPaint) }

        val divPaint = Paint().apply { color = Color.parseColor("#333333"); strokeWidth = 0.5f }
        canvas.drawLine(40f, headerY + 5f, 555f, headerY + 5f, divPaint)

        result.measurements.forEachIndexed { row, m ->
            val y = 105f + row * 22f
            if (y > 800f) return@forEachIndexed  // Would need pagination

            if (row % 2 == 1) {
                canvas.drawRect(40f, y - 12f, 555f, y + 8f, altRowPaint)
            }

            canvas.drawText(m.diodeName.take(12), colX[0], y, cellPaint)
            canvas.drawText("%.4f".format(m.x), colX[1], y, cellPaint)
            canvas.drawText("%.4f".format(m.y), colX[2], y, cellPaint)
            canvas.drawText("%.3f".format(m.fluxRelative), colX[3], y, cellPaint)
            canvas.drawText(m.cct?.let { "%.0f".format(it) } ?: "N/A", colX[4], y, cellPaint)
            canvas.drawText("%.4f".format(m.duv), colX[5], y, cellPaint)
            canvas.drawText("%.1f".format(m.gamutCoverage["Rec.709"] ?: 0.0), colX[6], y, cellPaint)
            canvas.drawText("%.1f".format(m.gamutCoverage["Rec.2020"] ?: 0.0), colX[7], y, cellPaint)
            canvas.drawText("%.1f".format(m.gamutCoverage["ACES AP1 (ACEScg)"] ?: 0.0), colX[8], y, cellPaint)
        }

        val footerPaint = Paint().apply { color = Color.parseColor("#555555"); textSize = 9f; isAntiAlias = true }
        canvas.drawText("Generated by CineCalibrator  •  Page 3", 40f, 820f, footerPaint)

        doc.finishPage(page)
    }

    // ─── CSV Export ───────────────────────────────────────────────────────────────

    /**
     * Page 4: Color volume — a CIE 1931 diagram showing all blend samples as a
     * scatter cloud filling the fixture's reachable gamut. Great for side-by-side
     * comparisons between fixtures.
     */
    @Suppress("UNCHECKED_CAST")
    private fun drawColorVolumePage(
        doc: PdfDocument,
        result: ColorScience.ScanResult,
        samples: List<ScanEngine.ColorVolumeSample>
    ) {

        val info = PdfDocument.PageInfo.Builder(595, 842, 4).create()
        val page = doc.startPage(info)
        val canvas = page.canvas

        val bgPaint = Paint().apply { color = Color.parseColor("#0D0D0D") }
        canvas.drawRect(0f, 0f, 595f, 842f, bgPaint)

        val titlePaint = Paint().apply {
            color = Color.WHITE; textSize = 16f
            typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true
        }
        canvas.drawText("Color Volume  (CIE xyY)", 40f, 50f, titlePaint)

        val subtitlePaint = Paint().apply {
            color = Color.parseColor("#AAAAAA"); textSize = 12f; isAntiAlias = true
        }
        canvas.drawText(
            "${result.fixtureName}  ·  ${samples.size} blend samples  ·  xy = chromaticity, scatter height = luminance (Y)",
            40f, 68f, subtitlePaint
        )

        val left = 60f; val top = 85f; val chartW = 475f; val chartH = 475f
        val bottom = top + chartH

        fun cx(x: Float) = left + x * chartW
        fun cy(y: Float) = bottom - y * chartH

        // Grid
        val gridPaint = Paint().apply { color = Color.parseColor("#222222"); strokeWidth = 0.5f }
        for (i in 0..10) {
            val v = i / 10f
            canvas.drawLine(cx(v), top, cx(v), bottom, gridPaint)
            canvas.drawLine(left, cy(v), left + chartW, cy(v), gridPaint)
        }

        // Axes
        val axisPaint = Paint().apply { color = Color.parseColor("#444444"); strokeWidth = 1f; style = Paint.Style.STROKE }
        canvas.drawLine(left, bottom, left + chartW, bottom, axisPaint)
        canvas.drawLine(left, top, left, bottom, axisPaint)

        // Spectral locus outline
        val horseshoe = listOf(
            0.1741f to 0.0050f, 0.1724f to 0.1390f, 0.1295f to 0.2455f,
            0.0669f to 0.3994f, 0.0090f to 0.6341f, 0.1650f to 0.7752f,
            0.2296f to 0.7938f, 0.3731f to 0.6067f, 0.5752f to 0.3449f,
            0.7079f to 0.2920f, 0.7348f to 0.2653f
        )
        val hPath = Path()
        horseshoe.forEachIndexed { i, (x, y) ->
            if (i == 0) hPath.moveTo(cx(x), cy(y)) else hPath.lineTo(cx(x), cy(y))
        }
        val horseshoePaint = Paint().apply {
            color = Color.parseColor("#444444"); strokeWidth = 1.5f
            style = Paint.Style.STROKE; isAntiAlias = true
        }
        canvas.drawPath(hPath, horseshoePaint)

        // Reference gamut triangles
        fun drawGamutLine(g: ColorScience.Gamut, color: Int) {
            val pts = listOf(g.red, g.green, g.blue)
            val path = Path()
            pts.forEachIndexed { i, pt ->
                if (i == 0) path.moveTo(cx(pt.x.toFloat()), cy(pt.y.toFloat()))
                else path.lineTo(cx(pt.x.toFloat()), cy(pt.y.toFloat()))
            }
            path.close()
            canvas.drawPath(path, Paint().apply {
                this.color = color; strokeWidth = 1.5f; style = Paint.Style.STROKE
                isAntiAlias = true
                pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f)
            })
        }
        drawGamutLine(ColorScience.REC709_GAMUT, Color.parseColor("#3399FF"))
        drawGamutLine(ColorScience.REC2020_GAMUT, Color.parseColor("#33FF99"))
        drawGamutLine(ColorScience.ACES_AP1_GAMUT, Color.parseColor("#FFCC00"))

        if (samples.isNotEmpty()) {
            // Draw color volume scatter cloud — each sample as a small semi-transparent dot
            // Color the dot based on approximate xy → RGB
            val dotPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true; alpha = 160 }
            samples.forEach { s ->
                val r = (s.x * 2.2).coerceIn(0.0, 1.0).toFloat()
                val g = (s.y * 1.8).coerceIn(0.0, 1.0).toFloat()
                val b = ((1.0 - s.x - s.y) * 2.0).coerceIn(0.0, 1.0).toFloat()
                dotPaint.color = Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
                canvas.drawCircle(cx(s.x.toFloat()), cy(s.y.toFloat()), 3f, dotPaint)
            }

            // Draw primary diode points on top, larger
            val primaryPaint = Paint().apply {
                color = Color.WHITE; strokeWidth = 2f
                style = Paint.Style.FILL; isAntiAlias = true
            }
            val primaryLabelPaint = Paint().apply {
                color = Color.WHITE; textSize = 9f; isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD
            }
            result.measurements.forEach { m ->
                primaryPaint.color = Color.rgb(
                    (m.x * 2.2 * 255).coerceIn(0.0, 255.0).toInt(),
                    (m.y * 1.8 * 255).coerceIn(0.0, 255.0).toInt(),
                    ((1.0 - m.x - m.y) * 2.0 * 255).coerceIn(0.0, 255.0).toInt()
                )
                canvas.drawCircle(cx(m.x.toFloat()), cy(m.y.toFloat()), 6f, primaryPaint)
                canvas.drawText(m.diodeName.take(8), cx(m.x.toFloat()) + 8f, cy(m.y.toFloat()) + 4f, primaryLabelPaint)
            }
        } else {
            val noDataPaint = Paint().apply {
                color = Color.parseColor("#555555"); textSize = 14f
                isAntiAlias = true; textAlign = Paint.Align.CENTER
            }
            canvas.drawText("Color volume scan not run", cx(0.5f), cy(0.4f), noDataPaint)
            canvas.drawText("Enable in Setup to map the full gamut", cx(0.5f), cy(0.4f) + 24f, noDataPaint)
        }

        // Axis labels
        val axisLabelPaint = Paint().apply { color = Color.parseColor("#888888"); textSize = 10f; isAntiAlias = true }
        for (i in 0..10) {
            val v = i / 10f
            canvas.drawText("%.1f".format(v), cx(v) - 8f, bottom + 14f, axisLabelPaint)
            canvas.drawText("%.1f".format(v), left - 26f, cy(v) + 4f, axisLabelPaint)
        }
        canvas.drawText("x →", left + chartW - 15f, bottom + 24f, axisLabelPaint)

        // Legend
        val legendY = bottom + 50f
        fun legendDot(lx: Float, color: Int, label: String) {
            canvas.drawCircle(lx + 8f, legendY - 4f, 5f, Paint().apply {
                this.color = color; style = Paint.Style.FILL
            })
            canvas.drawText(label, lx + 18f, legendY, axisLabelPaint)
        }
        legendDot(60f, Color.parseColor("#3399FF"), "Rec.709")
        legendDot(140f, Color.parseColor("#33FF99"), "Rec.2020")
        legendDot(225f, Color.parseColor("#FFCC00"), "ACES AP1")
        legendDot(310f, Color.parseColor("#AAAAAA"), "Primaries")
        if (samples.isNotEmpty()) {
            legendDot(395f, Color.parseColor("#888888"), "${samples.size} blend pts")
        }

        // How to read note
        val notePaint = Paint().apply { color = Color.parseColor("#666666"); textSize = 9f; isAntiAlias = true }
        canvas.drawText(
            "Each dot = one measured blend of the fixture's emitters. " +
            "A wider cloud = larger achievable colour volume.",
            40f, legendY + 22f, notePaint
        )

        val footerPaint = Paint().apply { color = Color.parseColor("#555555"); textSize = 9f; isAntiAlias = true }
        canvas.drawText("Generated by CineCalibrator  •  Page 4", 40f, 820f, footerPaint)

        doc.finishPage(page)
    }

    fun exportCSV(result: ColorScience.ScanResult): File {
        val sb = StringBuilder()
        sb.appendLine("# CineCalibrator Export")
        sb.appendLine("# Fixture,${result.fixtureName}")
        sb.appendLine("# Manufacturer,${result.fixtureManufacturer}")
        sb.appendLine("# Date,${dateFormat.format(Date(result.timestamp))}")
        sb.appendLine()
        sb.appendLine("Diode,Raw_R,Raw_G,Raw_B,X,Y,Z,CIE_x,CIE_y,Flux_Relative,CCT_K,Duv,Rec709_pct,Rec2020_pct,ACES_AP0_pct,ACES_AP1_pct")

        result.measurements.forEach { m ->
            sb.appendLine(listOf(
                m.diodeName,
                "%.2f".format(m.rawR),
                "%.2f".format(m.rawG),
                "%.2f".format(m.rawB),
                "%.6f".format(m.tristX),
                "%.6f".format(m.tristY),
                "%.6f".format(m.tristZ),
                "%.6f".format(m.x),
                "%.6f".format(m.y),
                "%.6f".format(m.fluxRelative),
                m.cct?.let { "%.1f".format(it) } ?: "",
                "%.6f".format(m.duv),
                "%.2f".format(m.gamutCoverage["Rec.709"] ?: 0.0),
                "%.2f".format(m.gamutCoverage["Rec.2020"] ?: 0.0),
                "%.2f".format(m.gamutCoverage["ACES AP0"] ?: 0.0),
                "%.2f".format(m.gamutCoverage["ACES AP1 (ACEScg)"] ?: 0.0)
            ).joinToString(","))
        }

        val safeName = result.fixtureName.replace("[^A-Za-z0-9_\\-]".toRegex(), "_")
        val file = File(context.cacheDir, "CineCalibrator_${safeName}_${fileDate.format(Date())}.csv")
        file.writeText(sb.toString())
        Timber.d("CSV exported: ${file.absolutePath}")
        return file
    }

    // ─── Share / Open ────────────────────────────────────────────────────────────

    fun shareFile(file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = when (file.extension) {
                "pdf" -> "application/pdf"
                "csv" -> "text/csv"
                "cube" -> "application/octet-stream"
                else -> "*/*"
            }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
