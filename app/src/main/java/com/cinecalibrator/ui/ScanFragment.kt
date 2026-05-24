package com.cinecalibrator.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.cinecalibrator.R
import com.cinecalibrator.core.ScanEngine
import com.cinecalibrator.databinding.FragmentScanBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * ScanFragment
 *
 * Shows:
 *  - Full-screen camera preview
 *  - Crosshair / ROI overlay (user aims at the light source)
 *  - Tripod distance reminder banner
 *  - Start Scan / Cancel button
 *  - Live scan progress (diode name, progress bar)
 *  - Warning overlay if clipping detected
 */
class ScanFragment : Fragment() {

    private var _binding: FragmentScanBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CalibrationViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        startCameraPreview()
        setupSourceBanner()
        setupButtons()
        observeScanProgress()
    }

    // ─── Camera Preview ──────────────────────────────────────────────────────────

    private fun startCameraPreview() {
        viewModel.cameraManager.startPreview(
            lifecycleOwner = viewLifecycleOwner,
            previewView = binding.cameraPreview,
            onReady = {
                binding.ivCameraReady.visibility = View.GONE
                binding.tvSetupTip.visibility = View.VISIBLE
                // Show scan summary in the tip banner
                val config = viewModel.scanConfig.value
                if (config != null) {
                    val dimmerNote = if (config.dimmerChannel != null)
                        "Dimmer CH${config.dimmerChannel} → 255" else "⚠ No dimmer channel found"
                    val colorNote = "${config.diodeChannels.size} colour channels"
                    val phaseNote = buildList {
                        add("primaries")
                        if (config.runMultiLevelScan) add("LUT sweep")
                        if (config.runColorVolumeScan) add("colour volume")
                    }.joinToString(" + ")
                    binding.tvScanSummary.text = "$colorNote  ·  $dimmerNote  ·  Phases: $phaseNote"
                    binding.tvScanSummary.visibility = View.VISIBLE
                }
            }
        )
    }

    // ─── Source Banner ────────────────────────────────────────────────────────────

    private fun setupSourceBanner() {
        val isSpectrometer = viewModel.sekonicSource.isConnected()

        if (isSpectrometer) {
            binding.ivScanSourceIcon.setImageResource(R.drawable.ic_dot_green)
            binding.tvScanSource.text = "✓ Sekonic C-800 connected — spectrometer measurements"
            binding.tvScanSource.setTextColor(requireContext().getColor(R.color.success_green))
            binding.btnTestMeter.visibility = View.VISIBLE
        } else {
            binding.ivScanSourceIcon.setImageResource(R.drawable.ic_dot_red)
            binding.tvScanSource.text = "Phone camera — connect C-800 via USB for better accuracy"
            binding.tvScanSource.setTextColor(requireContext().getColor(R.color.text_secondary))
            binding.btnTestMeter.visibility = View.GONE
        }

        binding.btnTestMeter.setOnClickListener {
            binding.btnTestMeter.isEnabled = false
            binding.btnTestMeter.text = "Testing…"
            binding.tvTestResult.visibility = View.VISIBLE
            binding.tvTestResult.text = "Taking reading…"
            viewModel.testSekonicReading { result ->
                requireActivity().runOnUiThread {
                    binding.tvTestResult.text = result
                    binding.btnTestMeter.isEnabled = true
                    binding.btnTestMeter.text = "Test C-800"
                    // Colour the result by whether signal was received
                    val isLowSignal = result.contains("⚠")
                    binding.tvTestResult.setTextColor(
                        requireContext().getColor(
                            if (isLowSignal) R.color.accent_orange else R.color.success_green
                        )
                    )
                }
            }
        }
    }

    // ─── Buttons ─────────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnStartScan.setOnClickListener {
            if (viewModel.isScanning.value) {
                viewModel.cancelScan()
            } else {
                startScan()
            }
        }

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun startScan() {
        if (!(requireActivity() as com.cinecalibrator.MainActivity).hasAllPermissions()) {
            Snackbar.make(binding.root, "Camera permission required", Snackbar.LENGTH_LONG).show()
            return
        }

        viewModel.startScan()
        binding.btnStartScan.text = "Cancel Scan"
        binding.btnStartScan.setBackgroundColor(requireContext().getColor(R.color.error_red))
        binding.progressCard.visibility = View.VISIBLE
    }

    // ─── Progress Observation ────────────────────────────────────────────────────

    private fun observeScanProgress() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.scanProgress.collect { progress ->
                        handleProgress(progress)
                    }
                }
                launch {
                    viewModel.isScanning.collect { scanning ->
                        if (!scanning) {
                            binding.btnStartScan.text = "Start Scan"
                            binding.btnStartScan.setBackgroundColor(
                                requireContext().getColor(R.color.accent_cyan)
                            )
                        }
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

    private fun handleProgress(progress: ScanEngine.ScanProgress) {
        when (progress) {
            is ScanEngine.ScanProgress.Started -> {
                binding.progressBar.max = progress.totalDiodes + 1
                binding.progressBar.progress = 0
                binding.tvProgressLabel.text = "Starting scan via ${progress.source}…"
                binding.tvDiodeStatus.text = ""
                binding.clippingWarning.visibility = View.GONE
            }
            is ScanEngine.ScanProgress.ExposureLocking -> {
                binding.tvProgressLabel.text = "🔒  Locking exposure…"
                binding.tvDiodeStatus.text = "Bringing fixture to reference colour — please wait"
                binding.progressBar.isIndeterminate = true
            }
            is ScanEngine.ScanProgress.ExposureLocked -> {
                binding.progressBar.isIndeterminate = false
                binding.tvProgressLabel.text = "✓  Exposure locked — beginning scan"
                binding.tvDiodeStatus.text = ""
            }
            is ScanEngine.ScanProgress.ScanningDiode -> {
                binding.progressBar.progress = progress.index + 1
                binding.tvProgressLabel.text = "[${progress.source}]  ${progress.index + 1} / ${progress.total}"
                binding.tvDiodeStatus.text = "▶ ${progress.diodeName}"
                binding.roiOverlay.alpha = 1f
                binding.roiOverlay.animate().alpha(0.4f).setDuration(200).withEndAction {
                    binding.roiOverlay.animate().alpha(1f).setDuration(200).start()
                }.start()
            }
            is ScanEngine.ScanProgress.Warning -> {
                Snackbar.make(binding.root, progress.message, Snackbar.LENGTH_LONG).show()
                binding.tvDiodeStatus.text = "⚠ ${progress.message}"
            }
            is ScanEngine.ScanProgress.DiodeComplete -> {
                val m = progress.measurement
                val cctStr = m.cct?.let { "%.0f K".format(it) } ?: "N/A"
                binding.tvDiodeResult.text =
                    "✓ ${m.diodeName}  [${progress.source}]  |  x=%.4f  y=%.4f  |  CCT=$cctStr"
                        .format(m.x, m.y)
                binding.tvDiodeResult.visibility = View.VISIBLE
            }
            is ScanEngine.ScanProgress.MultiLevelProgress -> {
                binding.tvProgressLabel.text =
                    "LUT sweep: ${progress.diodeName} @ DMX ${progress.level} " +
                    "(${progress.levelIndex + 1}/${progress.totalLevels})"
            }
            is ScanEngine.ScanProgress.ColorVolumeProgress -> {
                if (binding.progressBar.max != 100) {
                    binding.progressBar.max = 100
                    binding.progressBar.progress = 0
                }
                binding.progressBar.progress =
                    (100 * progress.sample / progress.total.toFloat()).toInt()
                binding.tvProgressLabel.text =
                    "Color volume: ${progress.sample} / ${progress.total} blend samples"
                if (progress.lastX != null && progress.lastY != null) {
                    val cctPart = progress.lastCCT?.let { " | CCT ${"%.0f".format(it)}K" } ?: ""
                    binding.tvDiodeResult.text =
                        "  x=${"%.4f".format(progress.lastX)}  y=${"%.4f".format(progress.lastY)}$cctPart"
                    binding.tvDiodeResult.visibility = View.VISIBLE
                }
            }
            is ScanEngine.ScanProgress.Completed -> {
                binding.progressBar.progress = binding.progressBar.max
                binding.tvProgressLabel.text = "Scan complete ✓"
                binding.tvDiodeStatus.text = "${progress.result.measurements.size} diodes measured"
                binding.root.postDelayed({
                    if (isAdded) findNavController().navigate(R.id.action_scan_to_results)
                }, 1200)
            }
            is ScanEngine.ScanProgress.Error -> {
                binding.progressBar.isIndeterminate = false
                binding.tvProgressLabel.text = "Error: ${progress.message}"
                binding.progressCard.setCardBackgroundColor(
                    requireContext().getColor(R.color.error_red)
                )
                Snackbar.make(binding.root, "Scan error: ${progress.message}", Snackbar.LENGTH_LONG).show()
            }
            is ScanEngine.ScanProgress.Cancelled -> {
                binding.progressBar.isIndeterminate = false
                binding.progressCard.visibility = View.GONE
                binding.tvDiodeStatus.text = ""
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
