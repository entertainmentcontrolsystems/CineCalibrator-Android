package com.cinecalibrator.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cinecalibrator.R
import com.cinecalibrator.core.CameraGamutProfile
import com.cinecalibrator.core.ColorScience

class DiodeMeasurementAdapter :
    ListAdapter<ColorScience.DiodeMeasurement, DiodeMeasurementAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ColorScience.DiodeMeasurement>() {
            override fun areItemsTheSame(a: ColorScience.DiodeMeasurement, b: ColorScience.DiodeMeasurement) =
                a.diodeIndex == b.diodeIndex
            override fun areContentsTheSame(a: ColorScience.DiodeMeasurement, b: ColorScience.DiodeMeasurement) =
                a == b
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView       = itemView.findViewById(R.id.tv_diode_name)
        val tvCIExy: TextView      = itemView.findViewById(R.id.tv_cie_xy)
        val tvFlux: TextView       = itemView.findViewById(R.id.tv_flux)
        val tvCCT: TextView        = itemView.findViewById(R.id.tv_cct)
        val tvGamut: TextView      = itemView.findViewById(R.id.tv_gamut_coverage)
        val tvConfidence: TextView = itemView.findViewById(R.id.tv_confidence)
        val colorSwatch: View      = itemView.findViewById(R.id.view_color_swatch)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_diode_measurement, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val m = getItem(position)
        val ctx = holder.itemView.context

        holder.tvName.text = m.diodeName
        holder.tvCIExy.text = "x = %.4f   y = %.4f".format(m.x, m.y)
        holder.tvFlux.text = "Flux: %.1f%%".format(m.fluxRelative * 100)

        // CCT display — treat C-800 firmware ceiling values as out of range.
        // The C-800 returns 50000K / Duv=2.0 when a colour is too far from the Planckian
        // locus for a reliable reading (saturated greens, cyans, etc.).
        val cctInRange = m.cct != null && m.cct < 49000.0 && m.duv < 0.5
        val cctStr = if (cctInRange)
            "CCT: ${"%.0f".format(m.cct!!)} K  (Duv ${"%.4f".format(m.duv)})"
        else
            "CCT: Out of range"
        holder.tvCCT.text = when {
            !cctInRange -> cctStr
            m.measurementConfidence.isCCTReliable -> cctStr
            else -> "$cctStr ⚠"
        }

        val cov709  = m.gamutCoverage["Rec.709"] ?: 0.0
        val cov2020 = m.gamutCoverage["Rec.2020"] ?: 0.0
        val covAP1  = m.gamutCoverage["ACES AP1 (ACEScg)"] ?: 0.0
        // Gamut coverage is a fixture-wide metric (convex hull of all primaries) — same for all rows
        // Only show it if it looks meaningful (>10%), otherwise omit to avoid confusion
        holder.tvGamut.text = if (cov709 > 5.0)
            "Fixture gamut — 709: ${"%.1f".format(cov709)}%  |  2020: ${"%.1f".format(cov2020)}%  |  AP1: ${"%.1f".format(covAP1)}%"
        else
            ""
        holder.tvGamut.visibility = if (cov709 > 5.0) View.VISIBLE else View.GONE

        // Confidence badge — only shown if camera is calibrated and confidence is not FULL
        when (m.measurementConfidence) {
            CameraGamutProfile.Confidence.FULL -> {
                holder.tvConfidence.visibility = View.GONE
            }
            CameraGamutProfile.Confidence.CCT_ONLY -> {
                holder.tvConfidence.visibility = View.VISIBLE
                holder.tvConfidence.text = "⚠ CCT/Duv reliable — xy outside camera gamut"
                holder.tvConfidence.setTextColor(ctx.getColor(com.cinecalibrator.R.color.accent_orange))
            }
            CameraGamutProfile.Confidence.MARGINAL -> {
                holder.tvConfidence.visibility = View.VISIBLE
                holder.tvConfidence.text = "⚠ Marginal — near camera gamut boundary"
                holder.tvConfidence.setTextColor(ctx.getColor(com.cinecalibrator.R.color.accent_orange))
            }
            CameraGamutProfile.Confidence.UNRELIABLE -> {
                holder.tvConfidence.visibility = View.VISIBLE
                holder.tvConfidence.text = "✗ Outside camera gamut — calibrate for accuracy"
                holder.tvConfidence.setTextColor(ctx.getColor(com.cinecalibrator.R.color.error_red))
            }
        }

        holder.colorSwatch.setBackgroundColor(xyToColor(m.x, m.y, m.fluxRelative))
    }

    private fun xyToColor(x: Double, y: Double, Y: Double): Int {
        val r = (x * 2.5 * Y).coerceIn(0.0, 1.0)
        val g = (y * 2.0 * Y).coerceIn(0.0, 1.0)
        val b = ((1.0 - x - y) * 1.8 * Y).coerceIn(0.0, 1.0)
        return android.graphics.Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    }
}
