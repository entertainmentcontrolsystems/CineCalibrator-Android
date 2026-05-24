package com.cinecalibrator.ui

import android.os.Bundle
import android.view.*
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cinecalibrator.R
import com.cinecalibrator.databinding.FragmentHistoryBinding
import com.cinecalibrator.model.CalibrationSessionEntity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * HistoryFragment
 *
 * Lists all past calibration sessions stored in Room.
 * Supports search by fixture name, tap to load, long-press to delete.
 */
class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CalibrationViewModel by activityViewModels()
    private lateinit var sessionAdapter: SessionAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSearch()
        observeSessions()
    }

    private fun setupRecyclerView() {
        sessionAdapter = SessionAdapter(
            onTap = { session -> loadSession(session) },
            onLongPress = { session -> confirmDelete(session) }
        )
        binding.rvSessions.apply {
            adapter = sessionAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                viewLifecycleOwner.lifecycleScope.launch {
                    val results = viewModel.repository.searchSessions(newText ?: "")
                    sessionAdapter.submitList(results)
                    binding.tvEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
                }
                return true
            }
        })
    }

    private fun observeSessions() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.repository.allSessions.collect { sessions ->
                    sessionAdapter.submitList(sessions)
                    binding.tvEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
                    binding.tvSessionCount.text = "${sessions.size} session${if (sessions.size != 1) "s" else ""}"
                }
            }
        }
    }

    private fun loadSession(session: CalibrationSessionEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = viewModel.repository.loadSession(session.sessionId)
            if (loaded != null) {
                val (result, spectroReadings) = loaded
                viewModel.loadExistingResult(result, spectroReadings)
                findNavController().navigate(R.id.action_history_to_results)
            }
        }
    }

    private fun confirmDelete(session: CalibrationSessionEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Session?")
            .setMessage("Delete calibration for \"${session.fixtureName}\"? This will also remove associated LUT files.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.repository.deleteSession(session)
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ─── SessionAdapter ───────────────────────────────────────────────────────

    class SessionAdapter(
        private val onTap: (CalibrationSessionEntity) -> Unit,
        private val onLongPress: (CalibrationSessionEntity) -> Unit
    ) : ListAdapter<CalibrationSessionEntity, SessionAdapter.VH>(DIFF) {

        private val dateFormat = SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.US)

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<CalibrationSessionEntity>() {
                override fun areItemsTheSame(a: CalibrationSessionEntity, b: CalibrationSessionEntity) =
                    a.sessionId == b.sessionId
                override fun areContentsTheSame(a: CalibrationSessionEntity, b: CalibrationSessionEntity) =
                    a == b
            }
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvName: TextView = itemView.findViewById(R.id.tv_session_name)
            val tvMeta: TextView = itemView.findViewById(R.id.tv_session_meta)
            val tvDate: TextView = itemView.findViewById(R.id.tv_session_date)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_session, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = getItem(position)
            holder.tvName.text = s.fixtureName
            val sourceTag = if (s.measurementSource == "Sekonic C-800") "⚡ C-800" else "📷 Camera"
            holder.tvMeta.text = "[$sourceTag]  ${s.fixtureManufacturer}  ·  ${s.measurementCount} diodes"
            holder.tvDate.text = dateFormat.format(Date(s.timestamp))
            holder.itemView.setOnClickListener { onTap(s) }
            holder.itemView.setOnLongClickListener { onLongPress(s); true }
        }
    }
}
