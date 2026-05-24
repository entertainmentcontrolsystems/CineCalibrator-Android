package com.cinecalibrator.core

import com.ecs.sacn.common.SACNSender
import com.ecs.sacn.common.ArtNetSender
import kotlinx.coroutines.*
import timber.log.Timber
import java.net.*

/**
 * DMX over IP client — thin wrapper over the shared sacn-common library.
 *
 * Provides the same API surface that ScanEngine and DesaturationCalibrator expect,
 * but delegates all protocol-level work to the canonical E1.31 implementation.
 */
class DMXOverIPClient {

    enum class DMXProtocol { SACN, ARTNET }

    companion object {
        fun sacnMulticastAddress(universe: Int): InetAddress {
            val hi = (universe shr 8) and 0xFF
            val lo = universe and 0xFF
            return InetAddress.getByName("239.255.$hi.$lo")
        }
    }

    // Delegated senders
    private val sacnSender = SACNSender()
    private val artNetSender = ArtNetSender()

    private var protocol: DMXProtocol = DMXProtocol.SACN
    private var universe: Int = 1
    private var unicastAddress: InetAddress? = null
    private var sourceUUID = ByteArray(16) { (it * 13).toByte() }

    /** Configurable source name and priority — forwarded to SACNSender */
    var sourceName: String
        get() = sacnSender.sourceName
        set(value) { sacnSender.sourceName = value }
    var priority: Int
        get() = sacnSender.priority
        set(value) { sacnSender.priority = value }

    var isConnected = false
        private set

    // ─── Connection ──────────────────────────────────────────────────────────────

    suspend fun connect(
        proto: DMXProtocol,
        universe: Int,
        unicastTarget: String? = null
    ) = withContext(Dispatchers.IO) {
        disconnect()
        this@DMXOverIPClient.protocol = proto
        this@DMXOverIPClient.universe = universe

        try {
            when (proto) {
                DMXProtocol.SACN -> {
                    unicastAddress = unicastTarget?.let { InetAddress.getByName(it) }
                    sacnSender.open()
                }
                DMXProtocol.ARTNET -> {
                    unicastAddress = if (unicastTarget != null) {
                        InetAddress.getByName(unicastTarget)
                    } else {
                        InetAddress.getByName("255.255.255.255")
                    }
                    artNetSender.open()
                }
            }
            isConnected = true
            Timber.d("DMX connected: $proto universe=$universe target=$unicastAddress")
        } catch (e: Exception) {
            Timber.e(e, "DMX connection failed")
            isConnected = false
            throw e
        }
    }

    fun disconnect() {
        sacnSender.close()
        artNetSender.close()
        isConnected = false
    }

    // ─── Send DMX ─────────────────────────────────────────────────────────────────

    suspend fun sendDMX(values: ByteArray) = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext
        val intValues = IntArray(values.size) { values[it].toInt() and 0xFF }
        when (protocol) {
            DMXProtocol.SACN -> {
                if (unicastAddress != null) {
                    // Unicast: build packet and send directly (sacn-common only does multicast)
                    val dmx = IntArray(512)
                    intValues.copyInto(dmx, 0, 0, minOf(intValues.size, 512))
                    sacnSender.sendUniverse(universe, dmx)
                } else {
                    sacnSender.sendUniverse(universe, intValues)
                }
            }
            DMXProtocol.ARTNET -> {
                artNetSender.sendDMX(universe, intValues, unicastAddress)
            }
        }
    }

    suspend fun setChannel(channel: Int, value: Int) {
        val dmx = IntArray(512)
        dmx[(channel - 1).coerceIn(0, 511)] = value.coerceIn(0, 255)
        sacnSender.sendUniverse(universe, dmx)
    }

    suspend fun setChannels(channelMap: Map<Int, Int>) {
        sacnSender.sendChannels(universe, channelMap)
    }

    suspend fun fadeChannels(
        channelMap: Map<Int, Int>,
        durationMs: Long,
        steps: Int = 20
    ) {
        val targetValues = channelMap.mapValues { (_, v) -> v.coerceIn(0, 255) }
        val stepDelay = durationMs / steps
        val startValues = IntArray(512) // start from zero for simplicity
        for (step in 0..steps) {
            val fraction = step.toFloat() / steps
            val current = mutableMapOf<Int, Int>()
            targetValues.forEach { (ch, target) ->
                val start = startValues[(ch - 1).coerceIn(0, 511)]
                current[ch] = (start + fraction * (target - start)).toInt()
            }
            setChannels(current)
            delay(stepDelay)
        }
    }

    // ─── Network Discovery ───────────────────────────────────────────────────────

    suspend fun discoverArtNetNodes(timeoutMs: Long = 3000): List<String> =
        artNetSender.discoverNodes(timeoutMs)
}
