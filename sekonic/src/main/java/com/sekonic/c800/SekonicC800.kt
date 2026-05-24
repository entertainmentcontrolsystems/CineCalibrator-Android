package com.sekonic.c800

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Android USB driver for the Sekonic C-800 / C-800-U spectrometer.
 *
 * Requires android.permission.USB_PERMISSION (requested automatically on first connect).
 * No Zadig or custom driver needed — uses Android's built-in USB Host API.
 *
 * Usage:
 *   val meter = SekonicC800(context)
 *   meter.connect()                     // suspending — requests permission if needed
 *   val result = meter.measure()        // suspending — fires shutter, returns result
 *   meter.disconnect()
 *
 * Or with Kotlin use-style:
 *   meter.use { result = it.measure() }
 *
 * Threading: All public methods are suspending and run on Dispatchers.IO.
 * Safe to call from a ViewModel coroutine scope.
 */
class SekonicC800(private val context: Context) : AutoCloseable {

    // ── USB state ─────────────────────────────────────────────────────────────
    private var usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var epOut: UsbEndpoint? = null
    private var epIn:  UsbEndpoint? = null

    /** Human-readable device description once connected. */
    var deviceName: String = "Sekonic C-800"
        private set

    companion object {
        private const val ACTION_USB_PERMISSION = "com.sekonic.c800.USB_PERMISSION"
    }

    // ── Connection ────────────────────────────────────────────────────────────

    /**
     * Connect to the C-800.
     * Requests USB permission from the user if not already granted.
     * Throws [SekonicException] if no device found or permission denied.
     */
    suspend fun connect() = withContext(Dispatchers.IO) {
        val mgr = usbManager

        // Find the device
        val dev = mgr.deviceList.values.firstOrNull {
            it.vendorId == SekonicProtocol.VENDOR_ID &&
            it.productId == SekonicProtocol.PRODUCT_ID
        } ?: throw SekonicException(
            "Sekonic C-800 not found. Check USB cable and that the device is powered on.")

        // Request permission if needed
        if (!mgr.hasPermission(dev)) {
            requestPermissionSync(dev, mgr)
        }

        // Open the device
        val conn = mgr.openDevice(dev)
            ?: throw SekonicException("Failed to open USB device. Permission may have been denied.")

        // Find the vendor-specific interface and endpoints
        val intf: UsbInterface = (0 until dev.interfaceCount)
            .map { dev.getInterface(it) }
            .firstOrNull { it.interfaceClass == 0xFF }
            ?: dev.getInterface(0)

        conn.claimInterface(intf, true)

        var out: UsbEndpoint? = null
        var inp: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.address == SekonicProtocol.EP_OUT) out = ep
            if (ep.address == SekonicProtocol.EP_IN)  inp = ep
        }
        if (out == null || inp == null) {
            // Fallback: find by direction
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_OUT && out == null) out = ep
                if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN  && inp == null) inp = ep
            }
        }
        requireNotNull(out) { "Bulk OUT endpoint not found" }
        requireNotNull(inp) { "Bulk IN endpoint not found" }

        device     = dev
        connection = conn
        epOut      = out
        epIn       = inp

        // Settle time, then drain any stale bytes
        delay(500)
        drain()
        delay(100)

        // Init sequence (confirmed from pcap + skreader-go)
        for (cmd in listOf("RT2", "MN", "SAr", "FTr", "IUr", "FV")) {
            try { cmdRaw(cmd) } catch (_: Exception) {}
            delay(50)
        }

        // Try to get model name for display
        try { deviceName = "Sekonic ${cmdRaw("MN").trim().trimEnd('\u0000')}" }
        catch (_: Exception) {}
    }

    /**
     * Disconnect from the device and release all USB resources.
     */
    fun disconnect() {
        try { connection?.releaseInterface(device?.getInterface(0)) } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        device     = null
        connection = null
        epOut      = null
        epIn       = null
    }

    override fun close() = disconnect()

    // ── Measurement ───────────────────────────────────────────────────────────

    /**
     * Trigger a live measurement and return the complete result.
     *
     * Confirmed flow (from skreader-go + C-7000 SDK manual):
     *   RT1  → Enable remote mode
     *   RM0  → Start measuring (fires shutter)
     *   Poll ST until idle (bit-decoded flags)
     *   NR   → Get result (current measurement, no index needed)
     *   MI   → Get new memory index (for ME command)
     *   ME   → Get extended metrics (TM-30, SSI, TLCI, Hue/Sat)
     *   RT0  → Disable remote mode
     *
     * @param timeoutMs How long to wait for measurement to complete (default 60s)
     */
    suspend fun measure(timeoutMs: Long = 60_000L): MeasurementResult = withContext(Dispatchers.IO) {
        requireConnected()

        waitIdle()
        drain()

        // Enable remote mode
        cmdRaw("RT1")

        // Start measurement
        cmdRaw("RM0")

        // Wait for completion
        delay(300)
        withTimeout(timeoutMs) {
            while (true) {
                val status = readStatus()
                if (status == DeviceStatus.IDLE || status == DeviceStatus.IDLE_OUT_MEAS) break
                if (status == DeviceStatus.ERROR) throw SekonicException("Device reported hardware error")
                delay(100)
            }
        }
        delay(150)
        drain()

        // Fetch the current measurement result
        val nrRaw = cmdBinary("NR")
        val nrPayload = nrRaw.drop(SekonicProtocol.RESP_HEADER_LEN).toByteArray()
        var result = PayloadParser.parseNr(nrPayload)

        // Fetch extended metrics
        try {
            val mi = cmdRaw("MI").trim()
            val idx = mi.split(",").getOrNull(1)?.toIntOrNull() ?: 0
            if (idx > 0) {
                val meRaw = cmdBinary("ME%04d,01".format(idx))
                val mePayload = meRaw.drop(SekonicProtocol.RESP_HEADER_LEN).toByteArray()
                result = PayloadParser.mergeMe(mePayload, result)
            }
        } catch (_: Exception) {}

        // Disable remote mode
        try { cmdRaw("RT0") } catch (_: Exception) {}

        result
    }

    /**
     * Download all stored measurements from device memory.
     * Returns a list ordered by memory index (oldest first).
     * Extended metrics (TM-30/SSI/TLCI) are fetched for each record.
     *
     * @param onProgress Called with (current, total) as each record downloads
     */
    suspend fun downloadMemory(
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<MeasurementResult> = withContext(Dispatchers.IO) {
        requireConnected()

        val mi = cmdRaw("MI").trim()
        val parts = mi.split(",")
        val used = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (used == 0) return@withContext emptyList()

        val results = mutableListOf<MeasurementResult>()
        for (idx in 1..used) {
            onProgress(idx, used)
            try {
                val mrRaw = cmdBinary("MR%04d".format(idx))
                val mrPayload = mrRaw.drop(SekonicProtocol.RESP_HEADER_LEN).toByteArray()
                var r = PayloadParser.parseMr(mrPayload)

                // Extended metrics
                try {
                    val meRaw = cmdBinary("ME%04d,01".format(idx))
                    val mePayload = meRaw.drop(SekonicProtocol.RESP_HEADER_LEN).toByteArray()
                    r = PayloadParser.mergeMe(mePayload, r)
                } catch (_: Exception) {}

                results.add(r)
            } catch (e: Exception) {
                // Skip failed records
            }
        }
        results
    }

    /**
     * Get basic device information without a full measurement.
     */
    suspend fun getDeviceInfo(): DeviceInfo = withContext(Dispatchers.IO) {
        requireConnected()
        DeviceInfo(
            modelName       = cmdRaw("MN").trim().trimEnd('\u0000'),
            firmwareVersion = cmdRaw("FV").trim(),
            serialNumber    = cmdRaw("SNr").trim().trimEnd('\u0000'),
            memoryUsed      = cmdRaw("MI").trim().split(",").getOrNull(1)?.toIntOrNull() ?: 0,
            memoryMax       = cmdRaw("MI").trim().split(",").getOrNull(0)?.toIntOrNull() ?: 99,
        )
    }

    // ── Status ────────────────────────────────────────────────────────────────

    private enum class DeviceStatus {
        IDLE, IDLE_OUT_MEAS, BUSY_MEASURING, BUSY_INITIALIZING,
        BUSY_DARK_CAL, BUSY_FLASH_STANDBY, ERROR
    }

    /**
     * Read device status using bit-accurate flag decoding from skreader-go device.go.
     * ST response bytes: [S][T][st1][st2][key]
     */
    private fun readStatus(): DeviceStatus {
        write("ST".toByteArray())
        val ack = read(2)
        if (!ack.contentEquals(SekonicProtocol.ACK)) {
            val ack2 = read(2)
            if (!ack2.contentEquals(SekonicProtocol.ACK)) return DeviceStatus.IDLE
        }
        val resp = read(512)
        if (resp.size < 3) return DeviceStatus.IDLE

        val st1 = resp[2].toInt() and 0xFF
        val st2 = if (resp.size > 3) resp[3].toInt() and 0xFF else 0

        return when {
            (st1 and SekonicProtocol.STATUS_BIT_ERROR) != 0 -> DeviceStatus.ERROR
            (st1 and SekonicProtocol.STATUS_BIT_BUSY)  != 0 -> when {
                (st2 and 0x08) != 0 -> DeviceStatus.BUSY_MEASURING
                (st2 and 0x01) != 0 -> DeviceStatus.BUSY_INITIALIZING
                (st2 and 0x04) != 0 -> DeviceStatus.BUSY_DARK_CAL
                (st2 and 0x10) != 0 -> DeviceStatus.BUSY_FLASH_STANDBY
                else                -> DeviceStatus.BUSY_MEASURING
            }
            (st1 and SekonicProtocol.STATUS_BIT_IDLE_OUT) != 0 -> DeviceStatus.IDLE_OUT_MEAS
            else -> DeviceStatus.IDLE
        }
    }

    private suspend fun waitIdle(timeoutMs: Long = 10_000L) = withContext(Dispatchers.IO) {
        withTimeout(timeoutMs) {
            while (true) {
                val s = readStatus()
                if (s == DeviceStatus.IDLE || s == DeviceStatus.IDLE_OUT_MEAS) {
                    delay(150)
                    drain()
                    return@withTimeout
                }
                delay(100)
            }
        }
    }

    // ── Low-level I/O ─────────────────────────────────────────────────────────

    /**
     * Send a command and return the response payload (header stripped).
     *
     * Wire format (confirmed from skreader-go + pcap):
     *   Write: raw ASCII command bytes
     *   Read1: exactly 2 bytes = ACK {0x06, 0x30}  — distinct USB transfer
     *   Read2: CMD_ECHO(2B) + FLAGS(2B) + SEP(1B) + PAYLOAD(...)
     *
     * The ACK is ALWAYS its own USB transfer. Reading a larger buffer for the
     * ACK causes the Android USB host to stall.
     */
    private fun cmdRaw(cmd: String): String {
        write(cmd.toByteArray(Charsets.US_ASCII))

        // Read 1: ACK (2 bytes, separate transfer)
        var ack = read(2)
        if (!ack.contentEquals(SekonicProtocol.ACK)) {
            // One retry — absorb a stale notification byte
            ack = read(2)
            if (!ack.contentEquals(SekonicProtocol.ACK))
                throw SekonicException("Expected ACK for '$cmd', got ${ack.toHex()}")
        }

        // Read 2: response frame
        val resp = read(512)
        if (resp.size < SekonicProtocol.RESP_HEADER_LEN)
            throw SekonicException("Response too short for '$cmd': ${resp.toHex()}")

        return String(resp, SekonicProtocol.RESP_HEADER_LEN,
            resp.size - SekonicProtocol.RESP_HEADER_LEN, Charsets.US_ASCII)
            .trimEnd('\u0000')
    }

    /**
     * Send a command and receive a potentially large binary response.
     * Used for NR (2380 bytes), MR (2380 bytes), ME (~390 bytes), DG002,1 (~196KB).
     */
    private fun cmdBinary(cmd: String): ByteArray {
        write(cmd.toByteArray(Charsets.US_ASCII))

        // Read 1: ACK
        var ack = read(2)
        if (!ack.contentEquals(SekonicProtocol.ACK)) {
            ack = read(2)
            if (!ack.contentEquals(SekonicProtocol.ACK))
                throw SekonicException("Expected ACK for binary '$cmd', got ${ack.toHex()}")
        }

        // Read 2+: accumulate chunks until a short one signals end
        val result = mutableListOf<Byte>()
        val bufSize = SekonicProtocol.READ_BUF_SIZE
        while (true) {
            val chunk = read(bufSize)
            result.addAll(chunk.toList())
            if (chunk.size < bufSize) break
        }
        return result.toByteArray()
    }

    /** Drain stale bytes from the USB IN buffer. */
    private fun drain() {
        repeat(16) {
            val buf = ByteArray(512)
            val n = connection?.bulkTransfer(epIn, buf, buf.size, 100) ?: return
            if (n <= 0) return
        }
    }

    private fun write(data: ByteArray) {
        val conn = connection ?: throw SekonicException("Not connected")
        val ep   = epOut     ?: throw SekonicException("Not connected")
        val n = conn.bulkTransfer(ep, data, data.size, SekonicProtocol.READ_TIMEOUT_MS)
        if (n < data.size)
            throw SekonicException("Write failed: sent $n of ${data.size} bytes")
    }

    private fun read(maxBytes: Int): ByteArray {
        val conn = connection ?: throw SekonicException("Not connected")
        val ep   = epIn      ?: throw SekonicException("Not connected")
        val buf  = ByteArray(maxBytes)
        val n    = conn.bulkTransfer(ep, buf, buf.size, SekonicProtocol.READ_TIMEOUT_MS)
        if (n < 0) throw SekonicException("Read failed (returned $n)")
        return buf.copyOf(n)
    }

    private fun requireConnected() {
        if (connection == null) throw SekonicException("Not connected. Call connect() first.")
    }

    // ── Permission helper ─────────────────────────────────────────────────────

    private suspend fun requestPermissionSync(dev: UsbDevice, mgr: UsbManager) {
        var granted = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (ACTION_USB_PERMISSION == intent.action) {
                    granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                }
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, 0,
            Intent(ACTION_USB_PERMISSION), flags)
        mgr.requestPermission(dev, pi)

        // Poll until permission dialog is answered (max 60s)
        var waited = 0
        while (!mgr.hasPermission(dev) && waited < 60_000) {
            delay(200)
            waited += 200
        }
        context.unregisterReceiver(receiver)
        if (!mgr.hasPermission(dev))
            throw SekonicException("USB permission denied by user.")
    }

    // ── Extension helpers ─────────────────────────────────────────────────────
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun List<Byte>.toHex() = joinToString("") { "%02x".format(it) }
}

/** Thrown when the Sekonic USB communication fails. */
class SekonicException(message: String) : Exception(message)

/** Basic device information. */
data class DeviceInfo(
    val modelName:       String,
    val firmwareVersion: String,
    val serialNumber:    String,
    val memoryUsed:      Int,
    val memoryMax:       Int,
)
