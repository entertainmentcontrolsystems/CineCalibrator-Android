package com.cinecalibrator.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cinecalibrator.R
import com.cinecalibrator.databinding.FragmentConvertBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class ConvertFragment : Fragment() {

    private var _binding: FragmentConvertBinding? = null
    private val binding get() = _binding!!

    private val convertVm: ConvertViewModel by viewModels()
    private val calibVm: CalibrationViewModel by activityViewModels()

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    val json = requireContext().contentResolver
                        .openInputStream(uri)?.bufferedReader()?.readText() ?: return@let
                    convertVm.loadFromJson(json)
                } catch (e: Exception) {
                    Snackbar.make(binding.root, "Import failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentConvertBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Auto-load PCT from calibration ViewModel if available
        convertVm.loadFromCalibrationViewModel(calibVm)

        setupInputFields()
        setupManualControl()
        setupButtons()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        // Refresh whenever user returns from calibration screen
        convertVm.loadFromCalibrationViewModel(calibVm)
    }

    private fun setupInputFields() {
        binding.etInputUniverse.setText(convertVm.inputUniverse.toString())
        binding.etInputStartAddr.setText(convertVm.inputStartAddr.toString())
        binding.etFixtureCount.setText(convertVm.fixtureCount.toString())
        binding.etOutputUniverse.setText(convertVm.outputUniverse.toString())
        binding.etOutputStartAddr.setText(convertVm.outputStartAddr.toString())
    }

    private fun readInputFields() {
        convertVm.inputUniverse  = binding.etInputUniverse.text.toString().toIntOrNull()?.coerceIn(1, 63999) ?: 1
        convertVm.inputStartAddr = binding.etInputStartAddr.text.toString().toIntOrNull()?.coerceIn(1, 512) ?: 1
        convertVm.fixtureCount   = binding.etFixtureCount.text.toString().toIntOrNull()?.coerceIn(1, 50) ?: 1
        convertVm.outputUniverse = binding.etOutputUniverse.text.toString().toIntOrNull()?.coerceIn(1, 63999) ?: 2
        convertVm.outputStartAddr = binding.etOutputStartAddr.text.toString().toIntOrNull()?.coerceIn(1, 512) ?: 1
    }

    private fun setupManualControl() {
        // Dimmer slider
        binding.seekDimmer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvDimmerPct.text = "$progress%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // CIE xy picker
        binding.ciexyPicker.setXY(0.3127f, 0.3290f)
        binding.ciexyPicker.onXYChanged = { x, y ->
            binding.tvManualXY.text = "x = ${"%.4f".format(x)}   y = ${"%.4f".format(y)}"
        }

        binding.btnSendManual.setOnClickListener {
            val dimmer = binding.seekDimmer.progress / 100f
            convertVm.sendManual(dimmer, binding.ciexyPicker.getCIEX(), binding.ciexyPicker.getCIEY())
        }
    }

    private fun setupButtons() {
        binding.btnImportPCT.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "*/*"))
            }
            importLauncher.launch(intent)
        }

        binding.btnStartConvert.setOnClickListener {
            if (convertVm.isConverting.value) {
                convertVm.stopConversion()
            } else {
                readInputFields()
                convertVm.startConversion()
            }
        }

        binding.btnExportConvConfig.setOnClickListener {
            readInputFields()
            val json = convertVm.exportConversionConfig()
            if (json != null) {
                try {
                    val file = java.io.File(requireContext().cacheDir, "CineCalibrator_ConvConfig.json")
                    file.writeText(json)
                    startActivity(android.content.Intent.createChooser(
                        calibVm.exportManager.shareFile(file), "Export Conversion Config"))
                } catch (e: Exception) {
                    Snackbar.make(binding.root, "Export failed: ${e.message}", Snackbar.LENGTH_SHORT).show()
                }
            } else {
                Snackbar.make(binding.root, "Load a calibration table first", Snackbar.LENGTH_SHORT).show()
            }
        }

        binding.btnVerifyC800.setOnClickListener {
            calibVm.testSekonicReading { result ->
                requireActivity().runOnUiThread {
                    Snackbar.make(binding.root, result.lines().take(3).joinToString("  "), Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    convertVm.pctStatus.collect { status ->
                        binding.tvPCTStatus.text = status
                        binding.tvPCTStatus.setTextColor(requireContext().getColor(
                            if (status.startsWith("✓")) R.color.success_green else R.color.accent_orange
                        ))
                    }
                }

                launch {
                    convertVm.isConverting.collect { converting ->
                        binding.btnStartConvert.text = if (converting)
                            "⏹  Stop Conversion" else "▶  Start Live Conversion"
                        binding.btnStartConvert.backgroundTintList = android.content.res.ColorStateList.valueOf(
                            requireContext().getColor(if (converting) R.color.error_red else R.color.accent_cyan)
                        )
                        binding.btnStartConvert.setTextColor(requireContext().getColor(
                            if (converting) android.graphics.Color.WHITE else R.color.bg_dark
                        ))
                        binding.cardLiveStatus.visibility = if (converting) View.VISIBLE else View.GONE
                        binding.ivConvertStatus.setImageResource(
                            if (converting) R.drawable.ic_dot_green else R.drawable.ic_dot_red)
                        binding.tvConvertStatus.text = if (converting) "Running" else "Stopped"
                    }
                }

                launch {
                    convertVm.liveStatus.collect { status ->
                        status ?: return@collect
                        binding.tvLiveInput.text =
                            "IN  dim=${"%3.0f".format(status.inputDimmer * 100)}%  " +
                            "x=${"%.4f".format(status.inputX)}  y=${"%.4f".format(status.inputY)}"
                        val inRangeTag = if (status.inRange) "✓" else "⚠Δ${"%.3f".format(status.lociDistance)}"
                        binding.tvLiveOutput.text =
                            "OUT → ${status.nearestCCT}K  $inRangeTag  " +
                            status.outputDmx.entries
                                .filter { it.value > 0 }
                                .joinToString(" ") { (ch, v) -> "ch${ch}:$v" }
                        binding.tvPacketRate.text = "${status.packetsPerSecond} pkt/s"
                    }
                }

                launch {
                    convertVm.errorMessage.collect { msg ->
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
