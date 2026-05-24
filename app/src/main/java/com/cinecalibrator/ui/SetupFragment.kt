package com.cinecalibrator.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.cinecalibrator.R
import com.cinecalibrator.core.DMXOverIPClient
import com.cinecalibrator.core.GDTFParser
import com.cinecalibrator.databinding.FragmentSetupBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * SetupFragment
 *
 * Step 1 of the workflow. The user:
 *   1. Selects or imports a GDTF fixture file (or picks a built-in template)
 *   2. Configures DMX output (sACN or ArtNet, universe, IP address)
 *   3. Reviews the diode/channel list
 *   4. Proceeds to the scan
 */
class SetupFragment : Fragment() {

    private var _binding: FragmentSetupBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CalibrationViewModel by activityViewModels()
    private var fixtureTemplates: List<GDTFParser.GDTFFixture> = emptyList()

    private val gdtfFilePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                loadGDTFFile(uri)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupFixtureSelection()
        setupDMXConfig()
        setupSekonicCard()
        setupActionButtons()
        updateCalibrationStatusBadge()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        // Refresh calibration badge if user returned from the calibration screen
        updateCalibrationStatusBadge()
    }

    // ─── Fixture Selection ───────────────────────────────────────────────────────

    private fun setupFixtureSelection() {
        fixtureTemplates = viewModel.gdtfParser.commonTemplates()

        // Populate template spinner
        val templateNames = fixtureTemplates.map { "${it.manufacturer} – ${it.name}" }
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            listOf("— Select template —") + templateNames
        )
        binding.spinnerFixtureTemplate.adapter = adapter

        binding.spinnerFixtureTemplate.setOnItemSelectedListener(
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position > 0) {
                        val fixture = fixtureTemplates[position - 1]
                        viewModel.setFixture(fixture)
                        updateDiodeList(fixture)
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        )

        // GDTF import button
        binding.btnImportGDTF.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/zip",
                    "application/octet-stream",
                    "*/*"
                ))
            }
            gdtfFilePicker.launch(intent)
        }
    }

    private fun loadGDTFFile(uri: android.net.Uri) {
        val fixture = viewModel.gdtfParser.parseFromUri(uri)
        if (fixture != null) {
            viewModel.setFixture(fixture)
            updateDiodeList(fixture)
            binding.tvFixtureInfo.text = "✓ Loaded: ${fixture.manufacturer} ${fixture.name}\n" +
                    "${fixture.modes.size} mode(s) • ${fixture.emitters.size} emitter(s)"
            Snackbar.make(binding.root, "GDTF loaded: ${fixture.name}", Snackbar.LENGTH_SHORT).show()
        } else {
            Snackbar.make(binding.root, "Failed to parse GDTF file", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun updateDiodeList(fixture: GDTFParser.GDTFFixture) {
        val mode = fixture.modes.firstOrNull() ?: GDTFParser.GDTFMode("", emptyList())

        val dimmerCh  = fixture.dimmerChannel(mode)
        val colorChs  = fixture.colorChannels(mode)
        val controlChs = mode.channels.filter { it.isControl }

        val sb = StringBuilder()

        if (dimmerCh != null) {
            sb.appendLine("  🔆 CH${dimmerCh.dmxAddress}  ${dimmerCh.name}  [DIMMER — held at 255 during scan]")
        }
        colorChs.forEach { ch ->
            val label = ch.attribute
                .removePrefix("ColorAdd_").removePrefix("ColorRGB_").removePrefix("Color_")
                .replace('_', ' ')
            sb.appendLine("  🎨 CH${ch.dmxAddress}  $label  [COLOR]")
        }
        controlChs.take(3).forEach { ch ->
            sb.appendLine("  ⚙️  CH${ch.dmxAddress}  ${ch.name}  [CONTROL — ignored]")
        }
        if (controlChs.size > 3) {
            sb.append("  ⚙️  … +${controlChs.size - 3} more control channels")
        }

        binding.tvDiodeList.text = if (sb.isEmpty()) "No channels found" else sb.toString().trimEnd()

        // Mode selection if multiple modes
        if (fixture.modes.size > 1) {
            val modeNames = fixture.modes.map { it.name }
            binding.spinnerMode.adapter = ArrayAdapter(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, modeNames)
            binding.spinnerMode.visibility = View.VISIBLE
            binding.labelMode.visibility = View.VISIBLE
        } else {
            binding.spinnerMode.visibility = View.GONE
            binding.labelMode.visibility = View.GONE
        }

        val colorCount = colorChs.size
        val dimmerNote = if (dimmerCh != null) " • dimmer CH${dimmerCh.dmxAddress}" else " • no dimmer found"
        binding.tvFixtureInfo.text = "${fixture.manufacturer} · ${fixture.name}\n" +
                "$colorCount colour channel(s)$dimmerNote • ${fixture.modes.size} mode(s)"
    }

    // ─── DMX Configuration ───────────────────────────────────────────────────────

    private fun setupDMXConfig() {
        // Protocol toggle
        binding.radioSACN.setOnCheckedChangeListener { _, checked ->
            if (checked) viewModel.setDMXProtocol(DMXOverIPClient.DMXProtocol.SACN)
        }
        binding.radioArtNet.setOnCheckedChangeListener { _, checked ->
            if (checked) viewModel.setDMXProtocol(DMXOverIPClient.DMXProtocol.ARTNET)
        }

        // Universe input
        binding.etUniverse.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val u = binding.etUniverse.text.toString().toIntOrNull() ?: 1
                viewModel.setDMXUniverse(u)
            }
        }

        // IP address
        binding.etTargetIP.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) viewModel.setDMXTargetIP(binding.etTargetIP.text.toString().trim())
        }

        // Connect button
        binding.btnConnect.setOnClickListener {
            viewModel.setDMXTargetIP(binding.etTargetIP.text.toString().trim())
            val u = binding.etUniverse.text.toString().toIntOrNull() ?: 1
            viewModel.setDMXUniverse(u)

            binding.btnConnect.isEnabled = false
            binding.btnConnect.text = "Connecting…"

            viewModel.connectDMX { success, msg ->
                requireActivity().runOnUiThread {
                    binding.btnConnect.isEnabled = true
                    if (success) {
                        binding.btnConnect.text = "✓ Connected"
                        binding.ivDMXStatus.setImageResource(R.drawable.ic_dot_green)
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
                    } else {
                        binding.btnConnect.text = "Connect"
                        binding.ivDMXStatus.setImageResource(R.drawable.ic_dot_red)
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Test button: briefly flash all channels
        binding.btnTestDMX.setOnClickListener {
            val config = viewModel.scanConfig.value ?: return@setOnClickListener
            config.diodeChannels.forEach { diode ->
                viewModel.testDMXChannel(diode.dmxChannel, 128)
            }
            binding.root.postDelayed({ viewModel.dmxBlackout() }, 500)
        }
    }

    // ─── Action Buttons ───────────────────────────────────────────────────────────

    // ─── Sekonic C-800 Card ───────────────────────────────────────────────────────

    private fun setupSekonicCard() {
        binding.btnConnectSekonic.setOnClickListener {
            if (viewModel.sekonicSource.isConnected()) {
                viewModel.disconnectSekonic()
            } else {
                viewModel.connectSekonic()
            }
        }

        binding.btnModeSpectrometer.setOnClickListener {
            viewModel.setSekonicMode(com.cinecalibrator.core.SekonicMeasurementSource.Mode.SPECTROMETER_ONLY)
            updateSekonicModeButtons(specActive = true)
        }
        binding.btnModeCamera.setOnClickListener {
            viewModel.setSekonicMode(com.cinecalibrator.core.SekonicMeasurementSource.Mode.CAMERA_ONLY)
            updateSekonicModeButtons(specActive = false)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sekonicConnectionState.collect { state ->
                    updateSekonicStatus(state)
                    updateMeasurementSourceBanner(state)
                }
            }
        }
    }

    private fun updateMeasurementSourceBanner(state: com.cinecalibrator.core.SekonicMeasurementSource.ConnectionState) {
        val isConnected = state == com.cinecalibrator.core.SekonicMeasurementSource.ConnectionState.CONNECTED
        binding.ivMeasurementSourceIcon.setImageResource(
            if (isConnected) R.drawable.ic_dot_green else R.drawable.ic_dot_red
        )
        binding.tvMeasurementSourceValue.text = if (isConnected)
            "Sekonic C-800 spectrometer — full accuracy mode"
        else
            "Phone camera — limited accuracy (calibration recommended)"
        binding.tvMeasurementSourceValue.setTextColor(
            requireContext().getColor(
                if (isConnected) R.color.success_green else R.color.text_secondary
            )
        )
        // Show C-800 timing note in scan options
        binding.tvSpectrometerScanNote.visibility =
            if (isConnected) View.VISIBLE else View.GONE

        // Camera calibration is only needed when no spectrometer is connected
        binding.btnGoToCalibration.text = if (isConnected)
            "Camera Calibration (not needed with C-800)"
        else
            "▶  Calibrate Camera  (needed for accurate results)"
        binding.btnGoToCalibration.isEnabled = !isConnected
        binding.btnGoToCalibration.alpha = if (isConnected) 0.4f else 1.0f
    }

    private fun updateSekonicStatus(state: com.cinecalibrator.core.SekonicMeasurementSource.ConnectionState) {
        binding.tvSekonicStatus.text = when (state) {
            com.cinecalibrator.core.SekonicMeasurementSource.ConnectionState.CONNECTED ->
                "✓ Sekonic C-800 connected — spectrometer mode active"
            com.cinecalibrator.core.SekonicMeasurementSource.ConnectionState.CONNECTING ->
                "Connecting to C-800…"
            com.cinecalibrator.core.SekonicMeasurementSource.ConnectionState.ERROR ->
                "C-800 connection failed — check USB cable"
            com.cinecalibrator.core.SekonicMeasurementSource.ConnectionState.DISCONNECTED ->
                "Sekonic C-800 not connected — using phone camera"
        }
        val isConnected = state == com.cinecalibrator.core.SekonicMeasurementSource.ConnectionState.CONNECTED
        binding.ivSekonicStatus.setImageResource(
            if (isConnected) R.drawable.ic_dot_green else R.drawable.ic_dot_red
        )
        binding.btnConnectSekonic.text = if (isConnected) "Disconnect C-800" else "Connect Sekonic C-800"
        binding.layoutSekonicMode.visibility = if (isConnected) View.VISIBLE else View.GONE
        if (isConnected) updateSekonicModeButtons(specActive = true)
    }

    private fun updateSekonicModeButtons(specActive: Boolean) {
        val activeColor = requireContext().getColor(R.color.bg_dark)
        val inactiveColor = requireContext().getColor(R.color.text_secondary)
        with(binding.btnModeSpectrometer) {
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (specActive) requireContext().getColor(R.color.accent_cyan)
                else android.graphics.Color.TRANSPARENT
            )
            setTextColor(if (specActive) activeColor else inactiveColor)
        }
        with(binding.btnModeCamera) {
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (!specActive) requireContext().getColor(R.color.accent_cyan)
                else android.graphics.Color.TRANSPARENT
            )
            setTextColor(if (!specActive) activeColor else inactiveColor)
        }
    }

    private fun setupActionButtons() {
        // Scan option switches
        binding.switchMultiLevel.setOnCheckedChangeListener { _, checked ->
            viewModel.setRunMultiLevelScan(checked)
        }
        binding.switchColorVolume.setOnCheckedChangeListener { _, checked ->
            viewModel.setRunColorVolumeScan(checked)
        }
        binding.etSettleTime.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val ms = binding.etSettleTime.text.toString().toLongOrNull() ?: 500L
                viewModel.setSettleTimeMs(ms.coerceIn(100, 5000))
            }
        }

        // Calibration button — always visible, navigates to calibration screen
        binding.btnGoToCalibration.setOnClickListener {
            if (viewModel.selectedFixture.value == null) {
                Snackbar.make(binding.root,
                    "Select a fixture first — load the reference fixture (e.g. Fos/4) before calibrating",
                    Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            findNavController().navigate(R.id.action_setup_to_calibration)
        }

        // Continue / Skip — goes straight to scan without calibrating
        binding.btnContinue.setOnClickListener {
            if (viewModel.selectedFixture.value == null) {
                Snackbar.make(binding.root, "Please select a fixture first", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            findNavController().navigate(R.id.action_setup_to_scan)
        }
    }

    // ─── Update calibration status badge ─────────────────────────────────────────

    private fun updateCalibrationStatusBadge() {
        val state = com.cinecalibrator.core.CameraCalibration.getCalibrationState()
        if (state.isCalibrated) {
            binding.ivCalibStatus.setImageResource(R.drawable.ic_dot_green)
            binding.tvCalibStatusSetup.text = state.description
            binding.tvCalibStatusSetup.setTextColor(requireContext().getColor(R.color.success_green))
            binding.btnGoToCalibration.text = "✓ Calibrated — Recalibrate Camera"
        } else {
            binding.ivCalibStatus.setImageResource(R.drawable.ic_dot_red)
            binding.tvCalibStatusSetup.text = "Not calibrated — recommended before scanning"
            binding.tvCalibStatusSetup.setTextColor(requireContext().getColor(R.color.text_secondary))
            binding.btnGoToCalibration.text = "▶  Calibrate Camera  (Step 2 of 3)"
        }
    }

    // ─── Observe ─────────────────────────────────────────────────────────────────

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.errorMessage.collect { msg ->
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                    }
                }
                launch {
                    viewModel.selectedFixture.collect { fixture ->
                        fixture?.let { updateDiodeList(it) }
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
