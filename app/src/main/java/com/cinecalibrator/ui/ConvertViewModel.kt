package com.cinecalibrator.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cinecalibrator.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

class ConvertViewModel(application: Application) : AndroidViewModel(application) {

    // ── Config ────────────────────────────────────────────────────────────────
    var inputUniverse: Int = 1
    var inputStartAddr: Int = 1
    var fixtureCount: Int = 1
    var outputUniverse: Int = 2
    var outputStartAddr: Int = 1

    // ── State ─────────────────────────────────────────────────────────────────
    private val _isConverting = MutableStateFlow(false)
    val isConverting: StateFlow<Boolean> = _isConverting

    private val _pctStatus = MutableStateFlow<String>("No calibration table loaded")
    val pctStatus: StateFlow<String> = _pctStatus

    private val _liveStatus = MutableStateFlow<LiveStatus?>(null)
    val liveStatus: StateFlow<LiveStatus?> = _liveStatus

    private val _errorMessage = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errorMessage: SharedFlow<String> = _errorMessage

    private val _manualXY = MutableStateFlow(0.3127f to 0.3290f)
    val manualXY: StateFlow<Pair<Float, Float>> = _manualXY

    data class LiveStatus(
        val fixtureIndex: Int,
        val inputDimmer: Float,
        val inputX: Float,
        val inputY: Float,
        val nearestCCT: Int,
        val lociDistance: Float,
        val inRange: Boolean,
        val outputDmx: Map<Int, Int>,
        val packetsPerSecond: Int
    )

    // ── Infrastructure ────────────────────────────────────────────────────────
    private var planckianTable: PlanckianSweepEngine.PlanckianTable? = null
    private var scanConfig: ScanEngine.ScanConfig? = null
    private var engine: ConversionEngine? = null

    private val sacnReceiver = SACNReceiver(application.applicationContext)
    private val dmxClient = DMXOverIPClient()

    private var convertJob: Job? = null
    private val packetCount = AtomicLong(0)
    private var lastPacketRateCalc = System.currentTimeMillis()
    private var currentPacketRate = 0

    // ── PCT table loading ─────────────────────────────────────────────────────

    fun loadFromCalibrationViewModel(vm: CalibrationViewModel) {
        val table = vm.planckianTable.value
        val config = vm.scanConfig.value
        if (table != null && config != null) {
            planckianTable = table
            scanConfig = config
            engine = ConversionEngine(table, config)
            _pctStatus.value = "✓ ${table.fixture}  ·  ${table.entries.size} CCT points  ·  ${table.scanDate}"
        } else if (table == null) {
            _pctStatus.value = "No Planckian table — run a scan + sweep on the Calibrate screen"
        } else {
            _pctStatus.value = "No fixture config — set up fixture in Calibrate screen"
        }
    }

    fun loadFromJson(json: String) {
        try {
            val sweepEngine = PlanckianSweepEngine(
                dmxClient, SekonicMeasurementSource(getApplication()),
                ColorScience.ScanResult("", 0, "", "", emptyList()),
                ScanEngine.ScanConfig("", "", emptyList())
            )
            planckianTable = sweepEngine.tableFromJson(json)
            _pctStatus.value = "✓ ${planckianTable!!.fixture}  ·  " +
                "${planckianTable!!.entries.size} CCT points imported"
        } catch (e: Exception) {
            viewModelScope.launch { _errorMessage.emit("Import failed: ${e.message}") }
        }
    }

    // ── DMX output ────────────────────────────────────────────────────────────

    fun connectDMX(ip: String? = null, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                dmxClient.connect(DMXOverIPClient.DMXProtocol.SACN, outputUniverse, ip)
                onResult(true, "DMX output connected")
            } catch (e: Exception) {
                onResult(false, "DMX connect failed: ${e.message}")
            }
        }
    }

    // ── Manual send ───────────────────────────────────────────────────────────

    fun sendManual(dimmer: Float, x: Float, y: Float) {
        _manualXY.value = x to y
        val eng = engine ?: run {
            viewModelScope.launch { _errorMessage.emit("No calibration table loaded") }
            return
        }
        val result = eng.convert(dimmer, x, y)
        viewModelScope.launch {
            try {
                if (!dmxClient.isConnected) dmxClient.connect(
                    DMXOverIPClient.DMXProtocol.SACN, outputUniverse, null)
                dmxClient.setChannels(result.dmxMap)
                _liveStatus.value = LiveStatus(
                    fixtureIndex = 0, inputDimmer = dimmer,
                    inputX = x, inputY = y,
                    nearestCCT = result.nearestCCT,
                    lociDistance = result.lociDistance,
                    inRange = result.inRange,
                    outputDmx = result.dmxMap,
                    packetsPerSecond = 0
                )
            } catch (e: Exception) {
                _errorMessage.emit("Send failed: ${e.message}")
            }
        }
    }

    // ── Live conversion loop ──────────────────────────────────────────────────

    fun startConversion() {
        val eng = engine ?: run {
            viewModelScope.launch { _errorMessage.emit("Load a calibration table first") }
            return
        }

        if (_isConverting.value) return
        _isConverting.value = true

        if (!dmxClient.isConnected) {
            viewModelScope.launch {
                try { dmxClient.connect(DMXOverIPClient.DMXProtocol.SACN, outputUniverse, null) }
                catch (e: Exception) {
                    _errorMessage.emit("DMX connect failed: ${e.message}")
                    _isConverting.value = false
                    return@launch
                }
            }
        }

        sacnReceiver.start(inputUniverse, inputStartAddr, fixtureCount, viewModelScope)

        convertJob = viewModelScope.launch {
            // Packet rate counter
            launch {
                while (isActive) {
                    delay(1000)
                    val now = System.currentTimeMillis()
                    val elapsed = (now - lastPacketRateCalc) / 1000.0
                    currentPacketRate = (packetCount.getAndSet(0) / elapsed).toInt()
                    lastPacketRateCalc = now
                }
            }

            sacnReceiver.frames.collect { commands ->
                currentCoroutineContext().ensureActive()
                packetCount.incrementAndGet()

                commands.forEach { cmd ->
                    val result = eng.convert(cmd.dimmer, cmd.x, cmd.y)
                    try {
                        // Offset output address for each fixture
                        val offsetMap = result.dmxMap.mapKeys { (ch, _) ->
                            ch + (outputStartAddr - 1) + cmd.fixtureIndex * (scanConfig?.let {
                                it.diodeChannels.size + 1 + it.controlChannels.size
                            } ?: 12)
                        }
                        dmxClient.setChannels(offsetMap)
                    } catch (e: Exception) {
                        Timber.w("Output send failed: ${e.message}")
                    }

                    // Update UI with last fixture state
                    if (cmd.fixtureIndex == 0) {
                        _liveStatus.value = LiveStatus(
                            fixtureIndex = cmd.fixtureIndex,
                            inputDimmer  = cmd.dimmer,
                            inputX       = cmd.x,
                            inputY       = cmd.y,
                            nearestCCT   = result.nearestCCT,
                            lociDistance = result.lociDistance,
                            inRange      = result.inRange,
                            outputDmx    = result.dmxMap,
                            packetsPerSecond = currentPacketRate
                        )
                    }
                }
            }
        }
    }

    fun stopConversion() {
        sacnReceiver.stop()
        convertJob?.cancel()
        convertJob = null
        _isConverting.value = false
    }

    fun exportConversionConfig(): String? {
        val eng = engine ?: return null
        return eng.exportConfig(
            inputUniverse  = inputUniverse,
            outputUniverse = outputUniverse,
            fixtureCount   = fixtureCount,
            startAddressIn = inputStartAddr,
            startAddressOut = outputStartAddr
        )
    }

    override fun onCleared() {
        super.onCleared()
        stopConversion()
        dmxClient.disconnect()
    }
}
