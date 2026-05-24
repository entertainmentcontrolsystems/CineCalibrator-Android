package com.cinecalibrator.core

import android.content.Context
import android.net.wifi.WifiManager
import com.ecs.sacn.common.parseD16xy
import com.ecs.sacn.common.SACNSender
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

/**
 * E1.31 sACN multicast listener — parses D16xy fixture blocks.
 *
 * Uses the shared sacn-common library for D16xy decoding helpers.
 * Acquires a WifiManager multicast lock so budget Android phones
 * (which suppress multicast by default) receive packets.
 */
class SACNReceiver(private val context: Context) {

    companion object {
        const val SACN_PORT = 5568
        private const val DMX_DATA_OFFSET = 126
        private const val CHANNELS_PER_FIXTURE = 6  // D16xy: dimmer(2) + x(2) + y(2)

        private val ACN_ID = byteArrayOf(
            0x41, 0x53, 0x43, 0x2D, 0x45, 0x31, 0x2E, 0x31,
            0x37, 0x00, 0x00, 0x00
        )

        fun universeToMulticast(universe: Int): String = SACNSender.universeToMulticast(universe)
    }

    data class FixtureCommand(
        val fixtureIndex: Int,
        val dimmer: Float,       // 0.0–1.0
        val x: Float,            // CIE 1931 x
        val y: Float,            // CIE 1931 y
        val rawDimmer: Int       // 0–65535 raw
    )

    private val _frames = MutableSharedFlow<List<FixtureCommand>>(extraBufferCapacity = 32)
    val frames: SharedFlow<List<FixtureCommand>> = _frames

    private var receiveJob: Job? = null
    private var socket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    var isRunning: Boolean = false
        private set

    fun start(
        universe: Int,
        startAddress: Int,
        fixtureCount: Int,
        scope: CoroutineScope
    ) {
        stop()
        isRunning = true

        receiveJob = scope.launch(Dispatchers.IO) {
            val wifiMgr = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifiMgr.createMulticastLock("CineCalibrator_sACN").apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = lock
            Timber.i("sACN multicast lock acquired")

            try {
                val multicastAddr = InetAddress.getByName(
                    SACNSender.universeToMulticast(universe)
                )
                val sock = MulticastSocket(SACN_PORT).apply {
                    reuseAddress = true
                    joinGroup(multicastAddr)
                    soTimeout = 2000
                }
                socket = sock
                Timber.i("sACN receiver: universe $universe  start $startAddress  ${fixtureCount}x fixtures")

                val buf = ByteArray(638)
                val packet = DatagramPacket(buf, buf.size)

                while (isActive && isRunning) {
                    try {
                        sock.receive(packet)
                        val commands = parseFixtureCommands(
                            buf, packet.length, startAddress, fixtureCount, universe
                        )
                        if (commands.isNotEmpty()) _frames.tryEmit(commands)
                    } catch (_: java.net.SocketTimeoutException) { }
                }

                try { sock.leaveGroup(multicastAddr) } catch (_: Exception) {}
                sock.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "sACN receiver error")
            } finally {
                isRunning = false
                try { lock.release() } catch (_: Exception) {}
                Timber.i("sACN multicast lock released")
            }
        }
    }

    fun stop() {
        isRunning = false
        receiveJob?.cancel()
        receiveJob = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
    }

    // ── Packet parsing (uses shared D16xy helpers from sacn-common) ──────────────

    private fun parseFixtureCommands(
        buf: ByteArray,
        len: Int,
        startAddress: Int,
        fixtureCount: Int,
        expectedUniverse: Int
    ): List<FixtureCommand> {
        if (len < DMX_DATA_OFFSET + CHANNELS_PER_FIXTURE) return emptyList()

        // Verify ACN packet identifier
        for (i in ACN_ID.indices) {
            if (buf[4 + i] != ACN_ID[i]) return emptyList()
        }

        // Universe (bytes 113-114, big-endian)
        val universe = ((buf[113].toInt() and 0xFF) shl 8) or (buf[114].toInt() and 0xFF)
        if (universe != expectedUniverse) return emptyList()

        val slotCount = ((buf[123].toInt() and 0xFF) shl 8) or (buf[124].toInt() and 0xFF)
        if (slotCount < 1) return emptyList()

        val dmxBytes = buf.sliceArray(DMX_DATA_OFFSET until minOf(len, DMX_DATA_OFFSET + slotCount - 1))
        if (dmxBytes.size < CHANNELS_PER_FIXTURE) return emptyList()

        val commands = mutableListOf<FixtureCommand>()
        for (i in 0 until fixtureCount) {
            val base = (startAddress - 1) + i * CHANNELS_PER_FIXTURE
            if (base + CHANNELS_PER_FIXTURE > dmxBytes.size) break

            val d16xy = parseD16xy(dmxBytes, base + 1) // +1 because parseD16xy expects 1-based address
                ?: continue

            commands.add(FixtureCommand(
                fixtureIndex = i,
                dimmer = d16xy.first,
                x = d16xy.second,
                y = d16xy.third,
                rawDimmer = com.ecs.sacn.common.dmx16(dmxBytes[base], dmxBytes[base + 1])
            ))
        }
        return commands
    }
}
