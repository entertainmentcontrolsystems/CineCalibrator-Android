package com.cinecalibrator.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.cinecalibrator.R
import com.cinecalibrator.core.CameraCalibration
import com.cinecalibrator.core.ColorScience
import com.cinecalibrator.core.PlanckianSweepEngine
import com.cinecalibrator.core.ScanEngine
import com.cinecalibrator.core.SekonicMeasurementSource
import com.cinecalibrator.databinding.FragmentResultsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class ResultsFragment : Fragment() {

    private var _binding: FragmentResultsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CalibrationViewModel by activityViewModels()
    private lateinit var diodeAdapter: DiodeMeasurementAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentResultsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        // Refresh calibration status when returning from calibration screen
        updateCalibrationStatus()
        // Also re-render CIE chart in case gamut hull is now available
        viewModel.scanResult.value?.let {
            binding.cieChartView.setMeasurements(it.measurements)
            binding.cieChartView.invalidate()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupExportButtons()
        setupLUTOptions()
        setupNewScanButton()
        setupCameraCalibration()
        observeResults()
    }

    // ─── New Scan ─────────────────────────────────────────────────────────────────

    private fun setupNewScanButton() {
        binding.btnNewScan.setOnClickListener {
            findNavController().navigate(
                R.id.setupFragment,
                null,
                androidx.navigation.NavOptions.Builder()
                    .setPopUpTo(R.id.nav_graph, true)
                    .build()
            )
        }
    }

    // ─── RecyclerView ─────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        diodeAdapter = DiodeMeasurementAdapter()
        binding.rvDiodes.apply {
            adapter = diodeAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    // ─── Populate ─────────────────────────────────────────────────────────────────

    private fun populateResults(result: ColorScience.ScanResult) {
        binding.tvFixtureName.text = result.fixtureName
        binding.tvManufacturer.text = result.fixtureManufacturer
        binding.tvDiodeCount.text =
            "${result.measurements.size} diode${if (result.measurements.size != 1) "s" else ""} measured"
        // Only average CCTs that are within a meaningful range (filter C-800 ceiling values)
        val validCCTs = result.measurements.mapNotNull { m ->
            m.cct?.takeIf { it < 49000.0 && m.duv < 0.5 }
        }
        val avgCCT = if (validCCTs.isNotEmpty()) validCCTs.average() else Double.NaN
        binding.tvAvgCCT.text = if (!avgCCT.isNaN()) "Avg CCT: ${"%.0f".format(avgCCT)} K" else ""
        binding.cieChartView.setMeasurements(result.measurements)
        binding.cieChartView.invalidate()
        updateGamutBars(result)
        diodeAdapter.submitList(result.measurements)
    }

    private fun updateGamutBars(result: ColorScience.ScanResult) {
        val refs = listOf("Rec.709", "Rec.2020", "ACES AP1 (ACEScg)")
        val bars = listOf(
            binding.progressRec709 to binding.tvRec709,
            binding.progressRec2020 to binding.tvRec2020,
            binding.progressAcesAP1 to binding.tvAcesAP1
        )
        refs.forEachIndexed { i, refName ->
            val avg = result.measurements.mapNotNull { it.gamutCoverage[refName] }.average()
            if (!avg.isNaN()) {
                bars[i].first.progress = avg.toInt().coerceIn(0, 100)
                bars[i].second.text = "$refName: ${"%.1f".format(avg)}%"
            }
        }
    }

    private fun updateSpectrometerDetail(readings: List<SekonicMeasurementSource.SpectrometerReading>) {
        if (readings.isEmpty()) {
            binding.cardSpectrometerDetail.visibility = View.GONE
            return
        }
        binding.cardSpectrometerDetail.visibility = View.VISIBLE

        // Aggregate broadcast quality metrics across all diodes
        val withMetrics = readings.filter { it.hasExtendedMetrics }
        val avgRf   = if (withMetrics.isNotEmpty()) withMetrics.map { it.tm30Rf }.average() else 0.0
        val avgRg   = if (withMetrics.isNotEmpty()) withMetrics.map { it.tm30Rg }.average() else 0.0
        val avgTlci = if (withMetrics.isNotEmpty()) withMetrics.map { it.tlci }.average() else 0.0
        val avgSsiD = if (withMetrics.isNotEmpty()) withMetrics.map { it.ssiD }.average() else 0.0
        val avgSsiT = if (withMetrics.isNotEmpty()) withMetrics.map { it.ssiT }.average() else 0.0
        fun fmt(v: Double) = if (v > 0) "%.0f".format(v) else "—"
        binding.tvTm30Rf.text = fmt(avgRf)
        binding.tvTm30Rg.text = fmt(avgRg)
        binding.tvTlci.text   = fmt(avgTlci)
        binding.tvSsiD.text   = fmt(avgSsiD)
        binding.tvSsiT.text   = fmt(avgSsiT)
        binding.tvTm30Rf.setTextColor(requireContext().getColor(when {
            avgRf >= 90 -> R.color.success_green
            avgRf >= 80 -> R.color.accent_orange
            avgRf > 0   -> R.color.error_red
            else        -> R.color.text_hint
        }))

        // For SPD and CRI R1-R15: prefer Planckian table 5600K entry (nearest to D55)
        // over individual emitter readings. A single saturated emitter like GY has Ra~30
        // — not meaningful. The calibrated white-point blend is what matters.
        val pctEntry = viewModel.planckianTable.value?.entries
            ?.minByOrNull { Math.abs(it.targetCCT - 5600) }
        val featured = readings.maxByOrNull { it.lux } ?: readings.last()

        if (pctEntry != null && pctEntry.measuredCriRa > 0f) {
            // Planckian table available — show CRI from calibrated white-point blend
            val criLines = pctEntry.measuredCriR.take(15)
                .mapIndexed { i, v -> "R${i+1}:${"%.0f".format(v)}" }
                .chunked(5).joinToString("\n") { it.joinToString("  ") }
            binding.tvCriRValues.text = "Ra: ${"%.1f".format(pctEntry.measuredCriRa)}" +
                "  (from ${pctEntry.measuredCCT.toInt()}K Planckian blend)\n$criLines"
            // SPD from the highest-lux primary (most informative spectral shape)
            binding.spdView.setSPD(featured.spd5nm, "Peak: ${featured.diodeName}")
        } else {
            // No Planckian table yet — show note that values are for a single emitter
            val criLines = featured.criR.take(15).mapIndexed { i, v -> "R${i+1}:${"%.0f".format(v)}" }
                .chunked(5).joinToString("\n") { it.joinToString("  ") }
            binding.tvCriRValues.text = "Ra: ${"%.1f".format(featured.criRa)}" +
                "  (${featured.diodeName} alone — run Planckian sweep for white-point CRI)\n$criLines"
            binding.spdView.setSPD(featured.spd5nm, featured.diodeName)
        }

        binding.tvSpectroLux.text = "${readings.size} diodes by C-800  |  ${featured.diodeName}: " +
            "${"%.0f".format(featured.lux)} lx / ${"%.1f".format(featured.footCandle)} fc  |  " +
            "DWL ${featured.dominantWavelength.toInt()} nm"
        binding.tvSpectrometerSource.text =
            "${readings.size} emitter${if (readings.size != 1) "s" else ""} — Sekonic C-800  |  " +
            "FULL confidence — no camera gamut limits"
    }

    private fun updateColorVolumeSection(samples: List<ScanEngine.ColorVolumeSample>) {
        if (samples.isNotEmpty()) {
            binding.tvColorVolumeStatus.text = "✓ ${samples.size} color volume samples collected"
            binding.btnExportColorVolume.isEnabled = true
            // Populate the xyY 3D view
            val result = viewModel.scanResult.value
            if (result != null) {
                binding.colorVolumeXYYView.setData(samples, result.measurements)
                binding.colorVolumeXYYView.visibility = View.VISIBLE
                binding.tvColorVolumeStatus.visibility = View.GONE
            }
        } else {
            binding.tvColorVolumeStatus.visibility = View.VISIBLE
            binding.colorVolumeXYYView.visibility = View.GONE
            binding.btnExportColorVolume.isEnabled = false
        }
    }

    // ─── Camera Calibration ───────────────────────────────────────────────────────

    private fun setupCameraCalibration() {
        updateCalibrationStatus()
        binding.btnCalibrateCamera.setOnClickListener { showCameraCalibrationDialog() }
        binding.btnClearCalibration.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clear Camera Calibration?")
                .setMessage("Subsequent scans will use raw camera values without colour correction.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    CameraCalibration.clearCalibration()
                    CameraCalibration.save(requireContext())
                    updateCalibrationStatus()
                    Snackbar.make(binding.root, "Camera calibration cleared", Snackbar.LENGTH_SHORT).show()
                }.show()
        }
    }

    private fun updateCalibrationStatus() {
        val state = CameraCalibration.getCalibrationState()
        binding.tvCalibrationStatus.text = state.description
        binding.tvCalibrationStatus.setTextColor(
            requireContext().getColor(
                if (state.isCalibrated) R.color.success_green else R.color.text_hint
            )
        )
        binding.btnClearCalibration.visibility =
            if (state.isCalibrated) View.VISIBLE else View.GONE

        // Show gamut profile summary if available
        val profile = state.gamutProfile
        if (profile != null) {
            val coveragePct = "%.1f".format(profile.visibleGamutCoveragePercent)
            val reliabilityTag = when {
                profile.avgResidualDxy < 0.010 -> "excellent"
                profile.avgResidualDxy < 0.025 -> "moderate"
                else -> "high error"
            }
            binding.tvGamutProfileSummary.text =
                "Camera gamut: $coveragePct% of visible spectrum  ·  " +
                "Avg Δxy: ${"%.4f".format(profile.avgResidualDxy)} ($reliabilityTag)  ·  " +
                "${profile.sampleCount} calibration samples"
            binding.tvGamutProfileSummary.setTextColor(
                requireContext().getColor(when {
                    profile.avgResidualDxy < 0.010 -> R.color.success_green
                    profile.avgResidualDxy < 0.025 -> R.color.accent_orange
                    else -> R.color.error_red
                })
            )
            binding.tvGamutProfileSummary.visibility = View.VISIBLE
        } else {
            binding.tvGamutProfileSummary.visibility = View.GONE
        }
    }

    /**
     * Two-step calibration dialog:
     *   Step 1 — Pick a reference fixture from the known list
     *   Step 2 — Show which measured channels matched, let user confirm before computing
     */
    private fun showCameraCalibrationDialog() {
        val result = viewModel.scanResult.value
        if (result == null) {
            Snackbar.make(
                binding.root,
                "Scan a reference fixture first, then tap Calibrate Camera",
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        val references = CameraCalibration.KNOWN_REFERENCES
        // Build item labels that include emitter count so user can see at a glance
        val displayNames = references.map { ref ->
            "${ref.manufacturer} ${ref.model}\n${ref.emitters.size} emitters: ${ref.emitters.joinToString(", ") { it.name }}"
        }.toTypedArray()

        // NOTE: setMessage + setItems conflict in MaterialAlertDialogBuilder —
        // channel info goes in the title only; items get the full content area.
        val channelSummary = result.measurements.joinToString(", ") { it.diodeName }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Reference Fixture\n(Scanned: $channelSummary)")
            .setItems(displayNames) { _, idx ->
                showCalibrationConfirmDialog(result, references[idx])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Step 2 — show matched pairs and let user confirm before computing CCM.
     */
    private fun showCalibrationConfirmDialog(
        result: ColorScience.ScanResult,
        ref: CameraCalibration.ReferenceFixture
    ) {
        // Match measured diodes to reference emitters with fuzzy name matching
        data class MatchedPair(
            val measured: ColorScience.DiodeMeasurement,
            val reference: CameraCalibration.ReferenceEmitter
        )

        // Abbreviation map — covers ETC Fos/4 GDTF short names and other common formats
        // Fos/4 PL8 actual GDTF names: DR, R, RY (or A), GY (or L), G, C, B, I
        val abbrevMap = mapOf(
            "dr"  to listOf("deepred", "deep red"),
            "r"   to listOf("red"),
            "ry"  to listOf("amber", "red orange", "redorange"),   // Fos/4 "RY" = Amber
            "a"   to listOf("amber"),
            "gy"  to listOf("lime", "green yellow", "yellow green"),
            "l"   to listOf("lime"),
            "g"   to listOf("green"),
            "c"   to listOf("cyan"),
            "b"   to listOf("blue"),
            "i"   to listOf("indigo", "violet"),
            "w"   to listOf("white"),
            "ww"  to listOf("warm white", "warmwhite"),
            "cw"  to listOf("cool white", "coolwhite"),
            "m"   to listOf("mint", "magenta"),
            "uv"  to listOf("uv", "ultraviolet"),
            // Reverse: reference full name → GDTF abbreviation targets
            "deepred"  to listOf("dr"),
            "red"      to listOf("r"),
            "amber"    to listOf("a", "ry"),
            "cyan"     to listOf("c"),
            "indigo"   to listOf("i"),
            "blue"     to listOf("b"),
            "lime"     to listOf("l", "gy"),
            "green"    to listOf("g")
        )

        fun normalise(s: String) = s.lowercase().replace(" ", "").replace("_", "")

        val matched = mutableListOf<MatchedPair>()
        val usedRef = mutableSetOf<String>()

        result.measurements.forEach { m ->
            val mClean = normalise(m.diodeName)
            val mAbbrevTargets = abbrevMap[mClean] ?: emptyList()

            val refEmitter = ref.emitters
                .filter { it.name !in usedRef }
                .firstOrNull { re ->
                    val rClean = normalise(re.name)
                    rClean == mClean ||                                    // exact match
                    rClean.contains(mClean) ||                             // ref contains measured
                    mClean.contains(rClean) ||                             // measured contains ref
                    mAbbrevTargets.any { rClean.contains(normalise(it)) } || // abbrev lookup
                    abbrevMap[rClean]?.any { normalise(it).contains(mClean) } == true // reverse
                }
            if (refEmitter != null) {
                matched.add(MatchedPair(m, refEmitter))
                usedRef.add(refEmitter.name)
            }
        }

        if (matched.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("No Matching Channels Found")
                .setMessage(
                    "Could not match any of your scanned channels to ${ref.model}.\n\n" +
                    "Your channels: ${result.measurements.joinToString(", ") { it.diodeName }}\n\n" +
                    "Reference channels: ${ref.emitters.joinToString(", ") { it.name }}\n\n" +
                    "Try a different reference fixture, or use a template that matches your fixture's channel names."
                )
                .setPositiveButton("Back") { _, _ -> showCameraCalibrationDialog() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val matchSummary = matched.joinToString("\n") { (m, r) ->
            val measXY = "x=%.4f y=%.4f".format(m.x, m.y)
            val refXY  = "x=%.4f y=%.4f".format(r.cieX, r.cieY)
            val delta  = Math.hypot(m.x - r.cieX, m.y - r.cieY)
            "  ${m.diodeName}  →  ${r.name}\n" +
            "    Measured: $measXY\n" +
            "    Reference: $refXY\n" +
            "    Δxy: ${"%.4f".format(delta)}"
        }

        val unmatched = result.measurements.filter { m ->
            matched.none { it.measured.diodeName == m.diodeName }
        }
        val unmatchedNote = if (unmatched.isNotEmpty())
            "\n\nUnmatched channels (will be excluded): ${unmatched.joinToString(", ") { it.diodeName }}"
        else ""

        val warningNote = if (matched.size < 3)
            "\n\n⚠ Only ${matched.size} matched pair${if (matched.size != 1) "s" else ""} found. " +
            "At least 3 are needed for a reliable calibration matrix." else ""

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Step 2: Confirm Calibration (${matched.size} pairs)")
            .setMessage("$matchSummary$unmatchedNote$warningNote")
            .setNegativeButton("Back") { _, _ -> showCameraCalibrationDialog() }
            .setNeutralButton("Cancel", null)
            .setPositiveButton(
                if (matched.size >= 3) "Apply Calibration" else "Apply Anyway"
            ) { _, _ ->
                applyCalibration(matched.map { it.measured }, matched.map { it.reference },
                    "${ref.manufacturer} ${ref.model}")
            }
            .show()
    }

    private fun applyCalibration(
        measuredList: List<ColorScience.DiodeMeasurement>,
        referenceList: List<CameraCalibration.ReferenceEmitter>,
        referenceName: String
    ) {
        // Convert to CameraCalibration.CIExy (separate type from ColorScience.CIExy)
        val measured   = measuredList.map { CameraCalibration.CIExy(it.x, it.y) }
        val reference  = referenceList.map { CameraCalibration.CIExy(it.cieX, it.cieY) }
        val luminances = measuredList.map { it.fluxRelative.coerceAtLeast(0.01) }

        val cal = CameraCalibration.computeCalibrationMatrix(
            measured, reference, luminances, referenceName
        )
        if (cal != null) {
            CameraCalibration.save(requireContext())
            updateCalibrationStatus()
            Snackbar.make(
                binding.root,
                "Camera calibrated to $referenceName — " +
                "avg error ${"%.4f".format(cal.residualError)} Δxy  " +
                "(${measuredList.size} pairs)",
                Snackbar.LENGTH_LONG
            ).show()
        } else {
            Snackbar.make(
                binding.root,
                "Calibration failed — matrix could not be solved. " +
                "Need 3+ non-collinear reference points.",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    // ─── Export ───────────────────────────────────────────────────────────────────

    private fun setupExportButtons() {
        binding.btnExportPDF.setOnClickListener {
            val file = viewModel.exportPDF()
            file?.let {
                startActivity(android.content.Intent.createChooser(
                    viewModel.exportManager.shareFile(it), "Share PDF"))
            } ?: Snackbar.make(binding.root, "No results to export", Snackbar.LENGTH_SHORT).show()
        }

        binding.btnExportCSV.setOnClickListener {
            val file = viewModel.exportCSV()
            file?.let {
                startActivity(android.content.Intent.createChooser(
                    viewModel.exportManager.shareFile(it), "Share CSV"))
            } ?: Snackbar.make(binding.root, "No results to export", Snackbar.LENGTH_SHORT).show()
        }

        binding.btnExportLUT.setOnClickListener {
            val file = viewModel.exportLUT()
            if (file != null) {
                // Generic share chooser
                startActivity(android.content.Intent.createChooser(
                    viewModel.exportManager.shareFile(file), "Share LUT"))
            } else {
                Snackbar.make(binding.root, "LUT not yet generated", Snackbar.LENGTH_SHORT).show()
            }
        }

        // Direct share to MSxy-Converter
        binding.btnShareToMSxy.setOnClickListener {
            val file = viewModel.exportLUT()
            if (file != null) {
                try {
                    val uri = viewModel.exportManager.getFileUri(file)
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/octet-stream"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        setPackage("com.msxyconverter")
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // MSxy-Converter not installed — fall back to chooser
                    startActivity(android.content.Intent.createChooser(
                        viewModel.exportManager.shareFile(file), "Share LUT"))
                }
            } else {
                Snackbar.make(binding.root, "Generate a LUT first by running a scan", Snackbar.LENGTH_SHORT).show()
            }
        }

        binding.btnExportColorVolume.setOnClickListener {
            val file = viewModel.exportColorVolumeCSV()
            file?.let {
                startActivity(android.content.Intent.createChooser(
                    viewModel.exportManager.shareFile(it), "Share Color Volume"))
            } ?: Snackbar.make(binding.root, "No color volume data yet", Snackbar.LENGTH_SHORT).show()
        }

        setupPlanckianSweepCard()
    }

    private fun setupPlanckianSweepCard() {
        // Only show if C-800 is connected and there's a scan result
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.sekonicConnectionState.collect { state ->
                        val connected = state == com.cinecalibrator.core.SekonicMeasurementSource.ConnectionState.CONNECTED
                        val hasResult = viewModel.scanResult.value != null
                        binding.cardPlanckianSweep.visibility =
                            if (connected && hasResult) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.isSweeping.collect { sweeping ->
                        binding.btnStartSweep.text = if (sweeping) "⏹ Cancel Sweep" else "▶ Run Planckian Sweep"
                        binding.progressSweep.visibility = if (sweeping) View.VISIBLE else View.GONE
                        binding.tvSweepProgress.visibility = if (sweeping) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.sweepProgress.collect { progress ->
                        updateSweepProgress(progress)
                    }
                }
                launch {
                    viewModel.planckianTable.collect { table ->
                        if (table != null) {
                            binding.btnExportPCT.isEnabled = true
                            val hasExtended = table.entries.any { it.measuredTm30Rf > 0 }
                            val header = if (hasExtended)
                                "CCT target → measured     Duv        Ra   Rf   Rg   TLCI"
                            else
                                "CCT target → measured     Duv        Ra"
                            val rows = table.entries.joinToString("\n") { e ->
                                val base = "${e.targetCCT}K → ${e.measuredCCT.toInt()}K" +
                                    "  Duv${String.format("%+.4f", e.measuredDuv)}" +
                                    "  Ra${e.measuredCriRa.toInt()}"
                                if (hasExtended && e.measuredTm30Rf > 0)
                                    "$base  Rf${e.measuredTm30Rf.toInt()}  Rg${e.measuredTm30Rg.toInt()}  TLCI${e.measuredTlci.toInt()}"
                                else base
                            }
                            binding.tvSweepResult.text = "$header\n$rows"
                            binding.tvSweepResult.visibility = View.VISIBLE
                            // Refresh spectrometer CRI now that Planckian table is available
                            val readings = viewModel.spectrometerReadings.value
                            if (readings.isNotEmpty()) updateSpectrometerDetail(readings)
                        }
                    }
                }
            }
        }

        binding.btnStartSweep.setOnClickListener {
            if (viewModel.isSweeping.value) {
                viewModel.cancelPlanckianSweep()
            } else {
                viewModel.startPlanckianSweep()
            }
        }

        binding.btnExportPCT.setOnClickListener {
            val file = viewModel.exportPlanckianTable()
            if (file != null) {
                Snackbar.make(binding.root, "PCT: ${file.name}", Snackbar.LENGTH_SHORT).show()
                startActivity(android.content.Intent.createChooser(
                    viewModel.exportManager.shareFile(file), "Share Planckian Table"))
            }
        }
    }

    private fun updateSweepProgress(progress: PlanckianSweepEngine.SweepProgress) {
        when (progress) {
            is PlanckianSweepEngine.SweepProgress.Started -> {
                val mins = progress.estimatedMinutes
                binding.tvSweepProgress.text =
                    "${progress.totalTargets} CCT points (1700–10000K, every 100K)  ·  " +
                    "est. $mins–${mins + 10} min"
                binding.tvSweepProgress.visibility = View.VISIBLE
                binding.progressSweep.progress = 0
            }
            is PlanckianSweepEngine.SweepProgress.TargetStarted -> {
                binding.progressSweep.progress = (100 * progress.index / progress.total)
                binding.tvSweepProgress.text = "Target ${progress.index + 1}/${progress.total}: ${progress.targetCCT}K"
            }
            is PlanckianSweepEngine.SweepProgress.Iteration -> {
                val criStr = if (progress.measuredCriRa > 0) "  Ra${progress.measuredCriRa.toInt()}" else ""
                binding.tvSweepProgress.text =
                    "${progress.targetCCT}K  it${progress.iteration}  →  " +
                    "${progress.measuredCCT.toInt()}K  Duv${String.format("%+.4f", progress.measuredDuv)}$criStr"
            }
            is PlanckianSweepEngine.SweepProgress.TargetComplete -> {
                val e = progress.entry
                val criStr = if (e.measuredCriRa > 0) "  Ra${e.measuredCriRa.toInt()}" else ""
                Snackbar.make(binding.root,
                    "✓ ${e.targetCCT}K → ${e.measuredCCT.toInt()}K  Duv${String.format("%+.3f", e.measuredDuv)}$criStr",
                    Snackbar.LENGTH_SHORT).show()
            }
            is PlanckianSweepEngine.SweepProgress.Completed -> {
                binding.progressSweep.progress = 100
                binding.tvSweepProgress.text = "✓ Sweep complete — ${progress.table.entries.size} CCT points"
                Snackbar.make(binding.root, "Planckian sweep complete", Snackbar.LENGTH_SHORT).show()
            }
            is PlanckianSweepEngine.SweepProgress.Error -> {
                binding.tvSweepProgress.text = "⚠ Error: ${progress.message}"
                binding.tvSweepProgress.setTextColor(requireContext().getColor(R.color.error_red))
                binding.tvSweepProgress.visibility = View.VISIBLE
                binding.progressSweep.visibility = View.GONE
                Snackbar.make(binding.root, "Sweep failed: ${progress.message}", Snackbar.LENGTH_LONG).show()
            }
            else -> {}
        }
    }

    // ─── LUT Options ─────────────────────────────────────────────────────────────

    private fun setupLUTOptions() {
        val targets = com.cinecalibrator.core.LUTGenerator.TargetColorspace.values()
        binding.spinnerLUTTarget.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_dropdown_item, targets.map { it.name })
        binding.spinnerLUTTarget.setOnItemSelectedListener(
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long
                ) { viewModel.setLUTTarget(targets[pos]) }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            })
    }

    // ─── Observe ─────────────────────────────────────────────────────────────────

    private fun observeResults() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.scanResult.collect { result ->
                        result?.let { populateResults(it) }
                    }
                }
                launch {
                    viewModel.lutData.collect { lut ->
                        if (lut != null) {
                            binding.tvLUTStatus.text =
                                "✓ LUT: ${lut.fixtureName} → ${lut.targetColorspace.name} (${lut.size}³)"
                            binding.btnExportLUT.isEnabled = true
                        }
                    }
                }
                launch {
                    viewModel.colorVolumeSamples.collect { samples ->
                        updateColorVolumeSection(samples)
                    }
                }
                launch {
                    viewModel.spectrometerReadings.collect { readings ->
                        updateSpectrometerDetail(readings)
                    }
                }
                launch {
                    viewModel.errorMessage.collect { msg ->
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
