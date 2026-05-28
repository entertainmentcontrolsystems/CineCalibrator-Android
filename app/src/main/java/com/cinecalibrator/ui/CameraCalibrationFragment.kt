package com.cinecalibrator.ui

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.cinecalibrator.R
import com.cinecalibrator.core.CameraCalibration
import com.cinecalibrator.core.DesaturationCalibrator
import com.cinecalibrator.core.GDTFParser
import com.cinecalibrator.core.ScanEngine
import com.cinecalibrator.databinding.FragmentCameraCalibrationBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class CameraCalibrationFragment : Fragment() {

    private var _binding: FragmentCameraCalibrationBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CalibrationViewModel by activityViewModels()

    private var selectedReference: CameraCalibration.ReferenceFixture? = null
    private var isCalibrating = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraCalibrationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── Critical: start camera preview so the scan engine has frames to read ──
        startCameraPreview()

        setupReferenceList()
        setupButtons()
        updateCalibrationStatus()
        observeScanProgress()
    }

    // ─── Camera Preview ───────────────────────────────────────────────────────────

    private fun startCameraPreview() {
        viewModel.cameraManager.startPreview(
            lifecycleOwner = viewLifecycleOwner,
            previewView = binding.cameraPreviewCalib,
            onReady = {
                binding.tvCameraStatus.text = "✓ Camera ready"
                binding.tvCameraStatus.setTextColor(
                    requireContext().getColor(R.color.success_green)
                )
            }
        )
    }

    // ─── Reference Fixture Selection ─────────────────────────────────────────────

    private fun setupReferenceList() {
        val refs = CameraCalibration.KNOWN_REFERENCES
        val adapter = android.widget.ArrayAdapter(
            requireContext(),
            R.layout.dark_list_item,
            refs.map { "${it.manufacturer} ${it.model}" }
        )
        binding.lvReferences.adapter = adapter
        binding.lvReferences.choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE

        binding.lvReferences.setOnItemClickListener { _, _, pos, _ ->
            selectedReference = refs[pos]
            binding.btnStartCalibration.isEnabled = !isCalibrating
            val ref = refs[pos]
            binding.tvSelectedRef.text =
                "Emitters: ${ref.emitters.joinToString(", ") { it.name }}"
            binding.tvSelectedRef.visibility = View.VISIBLE
        }

        // Pre-select Fos/4
        val fos4Index = refs.indexOfFirst { it.model.contains("Fos") }
        val preselect = if (fos4Index >= 0) fos4Index else 0
        binding.lvReferences.setItemChecked(preselect, true)
        selectedReference = refs[preselect]
        binding.tvSelectedRef.text =
            "Emitters: ${refs[preselect].emitters.joinToString(", ") { it.name }}"
        binding.tvSelectedRef.visibility = View.VISIBLE
        binding.btnStartCalibration.isEnabled = true
    }

    // ─── Buttons ─────────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnStartCalibration.setOnClickListener {
            val ref = selectedReference ?: return@setOnClickListener
            startCalibrationScan(ref)
        }

        binding.btnSkipCalibration.setOnClickListener {
            findNavController().navigate(R.id.action_cameraCalibration_to_scan)
        }

        binding.btnClearCalibration.setOnClickListener {
            CameraCalibration.clearCalibration()
            CameraCalibration.save(requireContext())
            updateCalibrationStatus()
            Snackbar.make(binding.root, "Calibration cleared", Snackbar.LENGTH_SHORT).show()
        }

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    // ─── Calibration Scan ────────────────────────────────────────────────────────

    private fun startCalibrationScan(ref: CameraCalibration.ReferenceFixture) {
        if (!viewModel.dmxClient.isConnected) {
            Snackbar.make(
                binding.root,
                "DMX not connected — go back to Setup and connect first",
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        isCalibrating = true
        binding.btnStartCalibration.isEnabled = false
        binding.btnSkipCalibration.isEnabled = false
        binding.progressCard.visibility = View.VISIBLE
        binding.tvCalibProgress.text = "Locking exposure…"
        binding.calibProgressBar.isIndeterminate = true

        // Build ScanConfig from currently-loaded fixture (should be the Fos/4)
        val fixture = viewModel.selectedFixture.value
        val mode = viewModel.selectedMode.value
            ?: fixture?.modes?.firstOrNull()
            ?: GDTFParser.GDTFMode("", emptyList())

        val diodeChannels = if (fixture != null) {
            fixture.colorChannels(mode).map { ch ->
                ScanEngine.DiodeChannel(
                    name = ch.attribute
                        .removePrefix("ColorAdd_").removePrefix("ColorRGB_")
                        .removePrefix("Color_").replace('_', ' '),
                    dmxChannel = ch.dmxAddress
                )
            }.ifEmpty {
                // Fallback from reference emitter list
                ref.emitters.mapIndexed { i, em ->
                    ScanEngine.DiodeChannel(name = em.name, dmxChannel = i + 2)
                }
            }
        } else {
            ref.emitters.mapIndexed { i, em ->
                ScanEngine.DiodeChannel(name = em.name, dmxChannel = i + 2)
            }
        }

        if (diodeChannels.isEmpty()) {
            Snackbar.make(
                binding.root,
                "No colour channels found — load the Fos/4 GDTF in Setup first",
                Snackbar.LENGTH_LONG
            ).show()
            resetButtons()
            return
        }

        val dimmerCh = fixture?.dimmerChannel(mode)?.dmxAddress

        val config = ScanEngine.ScanConfig(
            fixtureName = ref.model,
            manufacturer = ref.manufacturer,
            diodeChannels = diodeChannels,
            dimmerChannel = dimmerCh,
            dmxUniverse = viewModel.dmxUniverse.value,
            settleTimeMs = 600,
            runMultiLevelScan = false,
            runColorVolumeScan = false
        )

        viewModel.startCalibrationScan(config, ref)
    }

    // ─── Progress ────────────────────────────────────────────────────────────────

    private fun observeScanProgress() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.calibrationScanProgress.collect { progress ->
                        when (progress) {
                            is CalibrationViewModel.CalibrationProgress.StartingPrimary -> {
                                binding.calibProgressBar.isIndeterminate = false
                                binding.calibProgressBar.max = progress.total * DesaturationCalibrator.DESAT_STEPS
                                binding.calibProgressBar.progress = progress.index * DesaturationCalibrator.DESAT_STEPS
                                binding.tvCalibProgress.text =
                                    "Primary ${progress.index + 1}/${progress.total}: ${progress.name}\n" +
                                    "Sweeping from saturated → D65 white…"
                            }
                            is CalibrationViewModel.CalibrationProgress.RampProgress -> {
                                binding.tvCalibProgress.text =
                                    "${progress.emitterName}: finding exposure limit… (safe peak: ${progress.safePeakDmx} DMX)"
                            }
                            is CalibrationViewModel.CalibrationProgress.StepProgress -> {
                                val totalSteps = binding.calibProgressBar.max
                                binding.calibProgressBar.progress =
                                    (binding.calibProgressBar.progress + 1).coerceAtMost(totalSteps)
                                val reliableTag = if (progress.whiteFrac >= DesaturationCalibrator.RELIABILITY_THRESHOLD)
                                    "✓ reliable" else "skip (saturated)"
                                binding.tvCalibProgress.text =
                                    "${progress.primaryName}  " +
                                    "step ${progress.step + 1}/${progress.totalSteps}  " +
                                    "${"%.0f".format(progress.whiteFrac * 100)}% white  " +
                                    "[$reliableTag]"
                            }
                            is CalibrationViewModel.CalibrationProgress.PrimaryComplete -> {
                                binding.tvCalibProgress.text =
                                    "✓ ${progress.name}: ${progress.reliableSteps} reliable samples"
                            }
                            is CalibrationViewModel.CalibrationProgress.Complete -> {
                                binding.calibProgressBar.progress = binding.calibProgressBar.max
                                val err = "%.4f".format(progress.avgError)
                                val limitsStr = if (progress.exposureLimits.isNotEmpty()) {
                                    "\n\nCamera exposure limits (peak DMX):\n" +
                                    progress.exposureLimits.entries
                                        .joinToString("  ") { (name, dmx) ->
                                            "$name:$dmx"
                                        }
                                } else ""
                                binding.tvCalibProgress.text =
                                    if (progress.success)
                                        "✓ Calibration complete\n" +
                                        "Avg Δxy: $err  ·  ${progress.sampleCount} samples\n" +
                                        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" +
                                        limitsStr
                                    else
                                        "⚠ Calibrated but high error ($err Δxy)\n" +
                                        "Ensure room is dark and fixture fills the ROI crosshair" +
                                        limitsStr
                                binding.tvCalibProgress.setTextColor(
                                    requireContext().getColor(
                                        if (progress.success) R.color.success_green else R.color.accent_orange
                                    )
                                )
                                // Always show the deviation diagram so the user can see
                                // what the correction achieved
                                selectedReference?.let { ref ->
                                    val allSteps = viewModel.getLastCalibrationSteps()
                                    if (allSteps.isNotEmpty()) {
                                        binding.cardDeviation.visibility = View.VISIBLE
                                        binding.cameraDeviationView.setData(allSteps, ref)
                                    }
                                }
                                updateCalibrationStatus()
                                CameraCalibration.save(requireContext())
                                binding.btnSkipCalibration.isEnabled = true
                                binding.btnSkipCalibration.text = "Continue to Scan →"
                                binding.btnStartCalibration.isEnabled = true
                                isCalibrating = false
                            }
                            is CalibrationViewModel.CalibrationProgress.InsufficientData -> {
                                binding.calibProgressBar.isIndeterminate = false
                                binding.calibProgressBar.progress = binding.calibProgressBar.max / 2
                                binding.tvCalibProgress.text =
                                    "⚠ ${progress.message}\n" +
                                    "${progress.totalCollected} total measurements, " +
                                    "${progress.reliableCount} passed reliability filter.\n\n" +
                                    "The diagram below shows how far your camera's readings are from " +
                                    "the reference values — use this to assess if this phone is " +
                                    "suitable for accurate colorimetry."
                                binding.tvCalibProgress.setTextColor(
                                    requireContext().getColor(R.color.accent_orange)
                                )
                                // Show the deviation diagram
                                selectedReference?.let { ref ->
                                    binding.cardDeviation.visibility = View.VISIBLE
                                    binding.cameraDeviationView.setData(progress.allSteps, ref)
                                }
                                resetButtons()
                            }
                            is CalibrationViewModel.CalibrationProgress.Error -> {
                                binding.calibProgressBar.isIndeterminate = false
                                binding.tvCalibProgress.text = "✗ ${progress.message}"
                                binding.tvCalibProgress.setTextColor(
                                    requireContext().getColor(R.color.error_red)
                                )
                                resetButtons()
                                Snackbar.make(binding.root, progress.message, Snackbar.LENGTH_LONG).show()
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    private fun resetButtons() {
        isCalibrating = false
        binding.btnStartCalibration.isEnabled = true
        binding.btnSkipCalibration.isEnabled = true
    }

    private fun updateCalibrationStatus() {
        val state = CameraCalibration.getCalibrationState()
        binding.tvCurrentCalibration.text = state.description
        binding.tvCurrentCalibration.setTextColor(
            requireContext().getColor(
                if (state.isCalibrated) R.color.success_green else R.color.text_hint
            )
        )
        binding.btnClearCalibration.visibility =
            if (state.isCalibrated) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
