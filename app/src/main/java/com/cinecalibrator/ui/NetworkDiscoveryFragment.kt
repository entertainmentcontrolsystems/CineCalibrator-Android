package com.cinecalibrator.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cinecalibrator.databinding.FragmentNetworkBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * NetworkFragment  (embedded in SetupFragment as a dialog or bottom sheet)
 *
 * Lets the user:
 *  - Scan for ArtNet nodes on the local WiFi network
 *  - Tap a node to auto-fill the IP address
 *  - Test sACN multicast connectivity
 */
class NetworkDiscoveryFragment : Fragment() {

    private var _binding: FragmentNetworkBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CalibrationViewModel by activityViewModels()

    private lateinit var nodeAdapter: NodeAdapter

    interface NodeSelectedListener {
        fun onNodeSelected(ip: String)
    }
    var nodeSelectedListener: NodeSelectedListener? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNetworkBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        nodeAdapter = NodeAdapter { ip ->
            viewModel.setDMXTargetIP(ip)
            nodeSelectedListener?.onNodeSelected(ip)
            Snackbar.make(binding.root, "Target set to $ip", Snackbar.LENGTH_SHORT).show()
        }
        binding.rvNodes.apply {
            adapter = nodeAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }

        binding.btnDiscover.setOnClickListener { discoverNodes() }
    }

    private fun discoverNodes() {
        binding.btnDiscover.isEnabled = false
        binding.btnDiscover.text = "Scanning…"
        binding.progressDiscovery.visibility = View.VISIBLE
        binding.tvNoNodes.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val nodes = viewModel.dmxClient.discoverArtNetNodes(timeoutMs = 3000)
                nodeAdapter.submitList(nodes)
                binding.tvNoNodes.visibility = if (nodes.isEmpty()) View.VISIBLE else View.GONE
                Snackbar.make(
                    binding.root,
                    if (nodes.isEmpty()) "No ArtNet nodes found" else "Found ${nodes.size} node${if (nodes.size != 1) "s" else ""}",
                    Snackbar.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Discovery failed: ${e.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                binding.btnDiscover.isEnabled = true
                binding.btnDiscover.text = "Scan Network"
                binding.progressDiscovery.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ─── Adapter ──────────────────────────────────────────────────────────────

    class NodeAdapter(private val onSelect: (String) -> Unit) :
        ListAdapter<String, NodeAdapter.VH>(object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(a: String, b: String) = a == b
            override fun areContentsTheSame(a: String, b: String) = a == b
        }) {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvIP: TextView = v.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ip = getItem(position)
            holder.tvIP.text = "📡  $ip"
            holder.tvIP.setTextColor(android.graphics.Color.parseColor("#F0F2F5"))
            holder.tvIP.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            holder.itemView.setOnClickListener { onSelect(ip) }
        }
    }
}
