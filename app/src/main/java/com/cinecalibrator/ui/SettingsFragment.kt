package com.cinecalibrator.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.cinecalibrator.R
import com.cinecalibrator.databinding.FragmentSettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * SettingsFragment
 *
 * Advanced options:
 *   - Camera: settle time, frames per sample, ROI size
 *   - Scan: multi-level steps for LUT generation
 *   - Storage: list / export / delete saved LUT files
 *   - GDTF library: browse imported fixtures, delete
 *   - About: version info, color science notes
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CalibrationViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupStorageSection()
        setupGDTFLibrarySection()
        setupCameraGamutLog()
        setupAboutSection()
    }

    private fun setupCameraGamutLog() {
        binding.btnViewCameraLog.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val entries = viewModel.repository.cameraGamutLog.first()
                if (entries.isEmpty()) {
                    showInfo("No camera calibrations logged yet.\nCalibrate the camera in Step 2 to start building the log.")
                    return@launch
                }
                val df = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                val names = entries.map { e ->
                    "${e.deviceModel}\n  Ref: ${e.referenceFixture}\n  " +
                    "Δxy: ${"%.4f".format(e.residualErrorDxy)}  " +
                    "${e.matchedChannels} channels  ${df.format(java.util.Date(e.timestamp))}"
                }.toTypedArray()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Camera Gamut Log (${entries.size} entries)")
                    .setItems(names) { _, idx ->
                        val e = entries[idx]
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(e.deviceModel)
                            .setMessage(
                                "Reference: ${e.referenceFixture}\n" +
                                "Android: ${e.androidVersion}\n" +
                                "Avg Δxy: ${"%.6f".format(e.residualErrorDxy)}\n" +
                                "Matched channels: ${e.matchedChannels}\n" +
                                "Date: ${df.format(java.util.Date(e.timestamp))}\n\n" +
                                "CCM Matrix:\n${formatMatrix(e.matrixJson)}"
                            )
                            .setPositiveButton("Export Matrix") { _, _ ->
                                exportCameraLogEntry(e)
                            }
                            .setNegativeButton("Delete") { _, _ ->
                                viewLifecycleOwner.lifecycleScope.launch {
                                    viewModel.repository.deleteCameraLogEntry(e)
                                }
                                showInfo("Calibration entry deleted.")
                            }
                            .setNeutralButton("Close", null)
                            .show()
                    }
                    .setNeutralButton("Close", null)
                    .show()
            }
        }
    }

    private fun formatMatrix(json: String): String {
        return try {
            val m = com.google.gson.Gson().fromJson(json, Array<DoubleArray>::class.java)
            m.joinToString("\n") { row ->
                "  [${row.joinToString(", ") { "%.4f".format(it) }}]"
            }
        } catch (_: Exception) { json }
    }

    private fun exportCameraLogEntry(entry: com.cinecalibrator.model.CameraGamutLogEntity) {
        val safeName = entry.deviceModel.replace("[^A-Za-z0-9_]".toRegex(), "_")
        val file = java.io.File(requireContext().cacheDir, "CameraGamut_${safeName}.txt")
        file.writeText(
            "CineCalibrator Camera Gamut Log Entry\n" +
            "Device: ${entry.deviceModel}\n" +
            "Android: ${entry.androidVersion}\n" +
            "Reference: ${entry.referenceFixture}\n" +
            "Avg Δxy error: ${entry.residualErrorDxy}\n" +
            "Matched channels: ${entry.matchedChannels}\n" +
            "CCM Matrix:\n${formatMatrix(entry.matrixJson)}\n" +
            "Notes: ${entry.notes}\n"
        )
        startActivity(
            android.content.Intent.createChooser(
                viewModel.exportManager.shareFile(file),
                "Share Camera Profile"
            )
        )
    }

    private fun setupStorageSection() {
        binding.btnListLUTs.setOnClickListener {
            val files = viewModel.cameraManager.let {
                requireContext().cacheDir.listFiles()
                    ?.filter { f -> f.extension == "cube" || f.extension == "csv" }
                    ?.sortedByDescending { f -> f.lastModified() }
                    ?: emptyList()
            }
            if (files.isEmpty()) {
                showInfo("No exported files found in cache.")
                return@setOnClickListener
            }
            val names = files.map { "${it.name}  (${it.length() / 1024}KB)" }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Cached Export Files")
                .setItems(names) { _, idx ->
                    shareFile(files[idx])
                }
                .setNeutralButton("Close", null)
                .show()
        }

        binding.btnClearCache.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clear Cache?")
                .setMessage("This will delete all exported PDF, CSV and LUT files from device cache. Session data in the database will be preserved.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    val deleted = requireContext().cacheDir.listFiles()
                        ?.filter { it.extension in listOf("pdf", "csv", "cube") }
                        ?.onEach { it.delete() }
                        ?.size ?: 0
                    showInfo("Deleted $deleted cached file${if (deleted != 1) "s" else ""}.")
                }
                .show()
        }
    }

    private fun setupGDTFLibrarySection() {
        viewLifecycleOwner.lifecycleScope.launch {
            val count = viewModel.repository.gdtfLibraryCount()
            binding.tvGDTFCount.text = "$count fixture${if (count != 1) "s" else ""} in library"
        }

        binding.btnBrowseGDTF.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val fixtures = viewModel.repository.gdtfLibrary.first().toMutableList()
                if (fixtures.isEmpty()) {
                    showInfo("No GDTF fixtures saved. Import a .gdtf file from the Setup screen.")
                    return@launch
                }
                val names = fixtures.map { "${it.manufacturer} – ${it.fixtureName}" }.toTypedArray()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("GDTF Library")
                    .setItems(names) { _, idx ->
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(fixtures[idx].fixtureName)
                            .setMessage("Manufacturer: ${fixtures[idx].manufacturer}\nImported: ${
                                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                                    .format(java.util.Date(fixtures[idx].importedAt))
                            }")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Load Fixture") { _, _ ->
                                viewLifecycleOwner.lifecycleScope.launch {
                                    val fixture = viewModel.repository.loadGDTFFixture(fixtures[idx])
                                    fixture?.let { viewModel.setFixture(it) }
                                    showInfo("Loaded: ${fixtures[idx].fixtureName}")
                                }
                            }
                            .setNeutralButton("Delete") { _, _ ->
                                viewLifecycleOwner.lifecycleScope.launch {
                                    viewModel.repository.deleteFixture(fixtures[idx])
                                    setupGDTFLibrarySection()
                                }
                            }
                            .show()
                    }
                    .setNeutralButton("Close", null)
                    .show()
            }
        }
    }

    private fun setupAboutSection() {
        binding.tvAboutText.text = """
CineCalibrator v1.0.0
Cinema Light Colorimetric Calibration

Color Science:
• CIE 1931 XYZ chromaticity (xy)
• Camera RAW → linear RGB → XYZ via sRGB matrix
• CCT via Robertson's method (±150 K accuracy)
• Gamut coverage via Sutherland-Hodgman polygon clipping
• LUT generation: 3D cube, 33³ grid, XYZ→target matrix

Supported Output Gamuts:
  Rec.709 / Rec.2020 / ACES AP0 / ACES AP1 (ACEScg)

Accuracy Notes:
For best results, use a phone with RAW capture capability.
Lock white balance and exposure before scanning.
Avoid ambient light contamination.
Average 15+ frames per diode measurement.

Network Protocols:
  sACN (ANSI E1.31-2016) — multicast or unicast
  ArtNet 4 (Artistic Licence) — broadcast or unicast

GDTF: DIN SPEC 15800 / GDTF 1.2 format supported.
        """.trimIndent()
    }

    private fun shareFile(file: File) {
        startActivity(
            android.content.Intent.createChooser(
                viewModel.exportManager.shareFile(file),
                "Share ${file.name}"
            )
        )
    }

    private fun showInfo(msg: String) {
        com.google.android.material.snackbar.Snackbar
            .make(binding.root, msg, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// Wire deleteFixture to the real DAO method
private suspend fun com.cinecalibrator.model.SessionRepository.deleteFixture(
    entity: com.cinecalibrator.model.GDTFLibraryEntity
) = deleteGDTFFixture(entity)
