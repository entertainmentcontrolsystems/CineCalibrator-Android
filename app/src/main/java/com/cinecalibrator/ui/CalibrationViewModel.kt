package com.cinecalibrator.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cinecalibrator.core.*
import com.cinecalibrator.model.SessionRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * CalibrationViewModel
 *
 * Shared ViewModel for the full calibration workflow:
 *   SetupFragment → ScanFragment → ResultsFragment → ExportFragment
 *
 * Holds all persistent state: fixture config, DMX config, scan results, LUT data.
 */
class CalibrationViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("cinecalibrator_prefs", android.content.Context.MODE_PRIVATE)

    // ─── State ──────────────────────────────────────────────────────────────────

    // Fixture / GDTF
    private val _selectedFixture = MutableStateFlow<GDTFParser.GDTFFixture?>(null)
    val selectedFixture: StateFlow<GDTFParser.GDTFFixture?> = _selectedFixture

    private val _selectedMode = MutableStateFlow<GDTFParser.GDTFMode?>(null)
    val selectedMode: StateFlow<GDTFParser.GDTFMode?> = _selectedMode

    // DMX settings — loaded from prefs so they survive New Scan
    private val _dmxProtocol = MutableStateFlow(
        DMXOverIPClient.DMXProtocol.valueOf(
            prefs.getString("dmx_protocol", DMXOverIPClient.DMXProtocol.SACN.name)!!
        )
    )
    val dmxProtocol: StateFlow<DMXOverIPClient.DMXProtocol> = _dmxProtocol

    private val _dmxUniverse = MutableStateFlow(prefs.getInt("dmx_universe", 1))
    val dmxUniverse: StateFlow<Int> = _dmxUniverse

    private val _dmxTargetIP = MutableStateFlow(prefs.getString("dmx_ip", "") ?: "")
    val dmxTargetIP: StateFlow<String> = _dmxTargetIP

    // Scan config
    private val _scanConfig = MutableStateFlow<ScanEngine.ScanConfig?>(null)
    val scanConfig: StateFlow<ScanEngine.ScanConfig?> = _scanConfig

    // Scan results
    private val _scanResult = MutableStateFlow<ColorScience.ScanResult?>(null)
    val scanResult: StateFlow<ColorScience.ScanResult?> = _scanResult

    // Scan in progress
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _scanProgress = MutableSharedFlow<ScanEngine.ScanProgress>(replay = 1)
    val scanProgress: SharedFlow<ScanEngine.ScanProgress> = _scanProgress

    // Color volume samples — stored here so they persist after the scan engine finishes
    private val _colorVolumeSamples = MutableStateFlow<List<ScanEngine.ColorVolumeSample>>(emptyList())
    val colorVolumeSamples: StateFlow<List<ScanEngine.ColorVolumeSample>> = _colorVolumeSamples

    // Spectrometer readings per diode — populated when C-800 was used for the scan
    private val _spectrometerReadings = MutableStateFlow<List<SekonicMeasurementSource.SpectrometerReading>>(emptyList())
    val spectrometerReadings: StateFlow<List<SekonicMeasurementSource.SpectrometerReading>> = _spectrometerReadings

    // LUT
    private val _lutData = MutableStateFlow<LUTGenerator.LUTData?>(null)
    val lutData: StateFlow<LUTGenerator.LUTData?> = _lutData

    private val _selectedLUTTarget = MutableStateFlow(LUTGenerator.TargetColorspace.REC709)
    val selectedLUTTarget: StateFlow<LUTGenerator.TargetColorspace> = _selectedLUTTarget

    // Error messages
    private val _errorMessage = MutableSharedFlow<String>()
    val errorMessage: SharedFlow<String> = _errorMessage

    // ─── Infrastructure ──────────────────────────────────────────────────────────

    val repository = SessionRepository(application)
    val gdtfParser = GDTFParser(application)
    val dmxClient = DMXOverIPClient()
    val cameraManager = CameraManager(application)
    val sekonicSource = SekonicMeasurementSource(application)
    private val lutGenerator = LUTGenerator(application)
    val exportManager = ExportManager(application)

    // ─── Sekonic C-800 connection ─────────────────────────────────────────────────

    val sekonicConnectionState = sekonicSource.connectionState
    val sekonicLastReading = sekonicSource.lastResult

    fun connectSekonic() {
        viewModelScope.launch {
            try {
                sekonicSource.connect()
            } catch (e: com.sekonic.c800.SekonicException) {
                _errorMessage.emit("C-800: ${e.message}")
            } catch (e: Exception) {
                _errorMessage.emit("C-800 connection failed: ${e.message}")
            }
        }
    }

    fun disconnectSekonic() = sekonicSource.disconnect()

    fun setSekonicMode(mode: SekonicMeasurementSource.Mode) = sekonicSource.setMode(mode)

    /**
     * Take one test reading from the C-800 without driving any DMX.
     * Used on the Scan screen to verify the meter is aimed correctly
     * before committing to a full scan.
     * Returns a human-readable summary string via [onResult].
     */
    fun testSekonicReading(onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                if (!sekonicSource.isConnected()) {
                    onResult("C-800 not connected. Use the Connect button in Setup.")
                    return@launch
                }
                // measureDiode(0, "Test") fires the C-800 shutter through the existing connection
                val m = sekonicSource.measureDiode(0, "Test")
                val raw = sekonicSource.lastResult.value
                val summary = buildString {
                    appendLine("C-800 test reading:")
                    if (raw != null) {
                        appendLine("  CCT: ${if (raw.cct > 0) "${raw.cct.toInt()} K" else "out of range"}")
                        appendLine("  CIE x=${String.format("%.4f", raw.cie1931x)}  y=${String.format("%.4f", raw.cie1931y)}")
                        appendLine("  Lux: ${String.format("%.1f", raw.lux)}  fc: ${String.format("%.2f", raw.footCandle)}")
                        appendLine("  tristY: ${String.format("%.4f", raw.tristY)}")
                        if (raw.lux < 10f) appendLine("  ⚠ Very low — aim C-800 dome at the fixture (0.5–2 m)")
                        else appendLine("  ✓ Good signal — meter is receiving light")
                    } else {
                        appendLine("  No data returned.")
                    }
                }
                onResult(summary)
            } catch (e: Exception) {
                onResult("Test failed: ${e.message}")
            }
        }
    }

    // ─── Fixture Configuration ───────────────────────────────────────────────────

    fun setFixture(fixture: GDTFParser.GDTFFixture) {
        _selectedFixture.value = fixture
        _selectedMode.value = fixture.modes.firstOrNull()
        updateScanConfig()
    }

    fun setMode(mode: GDTFParser.GDTFMode) {
        _selectedMode.value = mode
        updateScanConfig()
    }

    // Scan options (wired to Setup screen toggles)
    private var runMultiLevelScan = true
    private var runColorVolumeScan = true
    private var settleTimeMs = 500L

    fun setRunMultiLevelScan(enabled: Boolean) { runMultiLevelScan = enabled; updateScanConfig() }
    fun setRunColorVolumeScan(enabled: Boolean) { runColorVolumeScan = enabled; updateScanConfig() }
    fun setSettleTimeMs(ms: Long) { settleTimeMs = ms; updateScanConfig() }

    fun setLUTTarget(t: LUTGenerator.TargetColorspace) {
        _selectedLUTTarget.value = t
        _scanResult.value?.let { result -> generateLUT(result) }
    }

    fun setDMXProtocol(proto: DMXOverIPClient.DMXProtocol) {
        _dmxProtocol.value = proto
        prefs.edit().putString("dmx_protocol", proto.name).apply()
    }
    fun setDMXUniverse(universe: Int) {
        val v = universe.coerceIn(1, 32768)
        _dmxUniverse.value = v
        prefs.edit().putInt("dmx_universe", v).apply()
    }
    fun setDMXTargetIP(ip: String) {
        _dmxTargetIP.value = ip
        prefs.edit().putString("dmx_ip", ip).apply()
    }

    private fun updateScanConfig() {
        val fixture = _selectedFixture.value ?: return
        val mode = _selectedMode.value ?: fixture.modes.firstOrNull() ?: return

        // Identify the master dimmer channel (keep it separate from colour channels)
        val dimmerCh = fixture.dimmerChannel(mode)

        val diodes = fixture.colorChannels(mode).map { ch ->
            ScanEngine.DiodeChannel(
                name = ch.attribute
                    .removePrefix("ColorAdd_")
                    .removePrefix("ColorRGB_")
                    .removePrefix("Color_")
                    .replace('_', ' '),
                dmxChannel = ch.dmxAddress
            )
        }.ifEmpty {
            fixture.emitters.mapIndexed { i, em ->
                ScanEngine.DiodeChannel(name = em.name, dmxChannel = i + 2) // +2 leaves room for dimmer at ch 1
            }
        }

        // When using the C-800 spectrometer, each measurement takes ~3s.
        // Multi-level sweep (4 levels × 8 diodes = 32 shots ≈ 96s) and
        // colour volume (150 shots ≈ 7.5 min) are off by default in spectrometer mode.
        // The user can re-enable them in Setup if they want the extra data.
        val usingSpectrometer = sekonicSource.isConnected() &&
            sekonicSource.activeMode != SekonicMeasurementSource.Mode.CAMERA_ONLY

        // Collect control channels (StrobeModeShutter, DimmerCurve, Fans, etc.)
        // so the scan engine can hold them at their default values during measurements
        val controlChannels = mode.channels
            .filter { it.isControl }
            .map { ch -> ScanEngine.ControlChannel(
                name = ch.attribute,
                dmxChannel = ch.dmxAddress,
                defaultValue = ch.defaultValue
            )}

        _scanConfig.value = ScanEngine.ScanConfig(
            fixtureName = fixture.name,
            manufacturer = fixture.manufacturer,
            diodeChannels = diodes,
            dimmerChannel = dimmerCh?.dmxAddress,
            controlChannels = controlChannels,
            dmxUniverse = _dmxUniverse.value,
            settleTimeMs = if (usingSpectrometer) maxOf(settleTimeMs, 800L) else settleTimeMs,
            runMultiLevelScan = if (usingSpectrometer) false else runMultiLevelScan,
            runColorVolumeScan = if (usingSpectrometer) false else runColorVolumeScan
        )
    }

    // ─── DMX Connection ───────────────────────────────────────────────────────────

    fun connectDMX(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val ip = _dmxTargetIP.value.ifEmpty { null }
                dmxClient.connect(_dmxProtocol.value, _dmxUniverse.value, ip)
                onResult(true, "Connected via ${_dmxProtocol.value.name}")
            } catch (e: Exception) {
                onResult(false, "Connection failed: ${e.message}")
                Timber.e(e, "DMX connect failed")
            }
        }
    }

    fun testDMXChannel(channel: Int, value: Int = 255) {
        viewModelScope.launch {
            try {
                dmxClient.setChannel(channel, value)
            } catch (e: Exception) {
                _errorMessage.emit("DMX test failed: ${e.message}")
            }
        }
    }

    fun dmxBlackout() {
        viewModelScope.launch {
            try {
                dmxClient.sendDMX(ByteArray(512))
            } catch (e: Exception) {
                Timber.e(e, "Blackout failed")
            }
        }
    }

    // ─── Scan ─────────────────────────────────────────────────────────────────────

    private var scanEngine: ScanEngine? = null

    fun startScan() {
        val config = _scanConfig.value ?: run {
            viewModelScope.launch { _errorMessage.emit("No fixture configured") }
            return
        }

        if (!dmxClient.isConnected) {
            viewModelScope.launch { _errorMessage.emit("DMX not connected") }
            return
        }

        _isScanning.value = true
        _scanResult.value = null            // clear previous result
        _spectrometerReadings.value = emptyList()   // reset spectrometer readings
        _colorVolumeSamples.value = emptyList()
        val engine = ScanEngine(cameraManager, dmxClient, sekonicSource)
        scanEngine = engine

        // Forward engine progress to our shared flow
        viewModelScope.launch {
            engine.progress.collect { progress ->
                _scanProgress.emit(progress)
                when (progress) {
                    is ScanEngine.ScanProgress.DiodeComplete -> {
                        // Capture full spectrometer reading if C-800 was used
                        val rawReading = sekonicSource.lastResult.value
                        if (rawReading != null && sekonicSource.isConnected()) {
                            val reading = sekonicSource.toSpectrometerReading(
                                rawReading, progress.measurement.diodeName)
                            _spectrometerReadings.value = _spectrometerReadings.value + reading
                        }
                    }
                    is ScanEngine.ScanProgress.Completed -> {
                        _scanResult.value = progress.result
                        _isScanning.value = false
                        _colorVolumeSamples.value = engine.colorVolumeSamples.toList()
                        repository.saveSession(
                            progress.result,
                            measurementSource = if (sekonicSource.isConnected()) "Sekonic C-800" else "Camera",
                            spectrometerReadings = _spectrometerReadings.value
                        )
                        generateLUT(progress.result)
                    }
                    is ScanEngine.ScanProgress.Error,
                    is ScanEngine.ScanProgress.Cancelled -> {
                        _isScanning.value = false
                    }
                    else -> {}
                }
            }
        }

        engine.startScan(config, viewModelScope)
    }

    fun cancelScan() {
        scanEngine?.cancelScan()
        _isScanning.value = false
    }

    // ─── LUT Generation ──────────────────────────────────────────────────────────

    private fun generateLUT(result: ColorScience.ScanResult) {
        viewModelScope.launch {
            try {
                val lut = lutGenerator.generateLUT(
                    measurements = result.measurements,
                    volumeSamples = _colorVolumeSamples.value,
                    fixtureName = result.fixtureName,
                    target = _selectedLUTTarget.value,
                    size = 33
                )
                _lutData.value = lut
                Timber.d("LUT generated: ${lut.fixtureName} → ${lut.targetColorspace} " +
                    "(${lut.strategy})")
            } catch (e: Exception) {
                _errorMessage.emit("LUT generation failed: ${e.message}")
                Timber.e(e, "LUT error")
            }
        }
    }

    fun exportLUT(): java.io.File? = try {
        _lutData.value?.let { lutGenerator.exportCubeFile(it) }
    } catch (e: Exception) {
        viewModelScope.launch { _errorMessage.emit("LUT export failed: ${e.message}") }
        null
    }

    fun exportPDF(): java.io.File? = try {
        _scanResult.value?.let { result ->
            exportManager.exportPDF(result, _colorVolumeSamples.value)
        }
    } catch (e: Exception) {
        viewModelScope.launch { _errorMessage.emit("PDF export failed: ${e.message}") }
        null
    }

    fun exportCSV(): java.io.File? = try {
        _scanResult.value?.let { exportManager.exportCSV(it) }
    } catch (e: Exception) {
        viewModelScope.launch { _errorMessage.emit("CSV export failed: ${e.message}") }
        null
    }

    fun exportColorVolumeCSV(): java.io.File? {
        val samples = _colorVolumeSamples.value
        if (samples.isEmpty()) return null
        val result = _scanResult.value ?: return null
        return try {
            val safeName = result.fixtureName.replace("[^A-Za-z0-9_\\-]".toRegex(), "_")
            val headers = samples.first().dmxLevels.keys.joinToString(",")
            val csv = StringBuilder("$headers,CIE_x,CIE_y,Y_Luminance\n")
            samples.forEach { s ->
                csv.append("${s.dmxLevels.values.joinToString(",")},")
                csv.appendLine("${"%.6f".format(s.x)},${"%.6f".format(s.y)},${"%.6f".format(s.luminance)}")
            }
            java.io.File(getApplication<android.app.Application>().cacheDir,
                "ColorVolume_${safeName}.csv").also { it.writeText(csv.toString()) }
        } catch (e: Exception) {
            viewModelScope.launch { _errorMessage.emit("Color volume export failed: ${e.message}") }
            null
        }
    }

    fun exportGDTF(): java.io.File? {
        return try {
            val result = _scanResult.value ?: return null

            // Build flux map in priority order:
            //  1. C-800 lux measurements (most accurate — actual photometric values)
            //  2. Reference fixture fc data (e.g. ETC Fos/4 photometric sheet)
            //  3. Empty map → exporter falls back to camera tristY
            val fluxMap: Map<String, Double> = when {

                // C-800 spectrometer readings available
                _spectrometerReadings.value.isNotEmpty() -> {
                    _spectrometerReadings.value.associate { reading ->
                        reading.diodeName to reading.lux.toDouble()
                    }.also { Timber.d("GDTF: using C-800 lux values for flux") }
                }

                // Match against a known reference fixture fc values
                else -> {
                    val fixture = _selectedFixture.value
                    val referenceName = fixture?.let { "${it.manufacturer} ${it.name}" } ?: ""
                    val knownRef = CameraCalibration.KNOWN_REFERENCES.firstOrNull { ref ->
                        referenceName.lowercase().contains(ref.model.lowercase()) ||
                        referenceName.lowercase().contains(ref.manufacturer.lowercase())
                    }
                    if (knownRef != null) {
                        // Match measured diodes to reference emitters by name
                        val map = mutableMapOf<String, Double>()
                        result.measurements.forEach { m ->
                            val mClean = m.diodeName.lowercase().replace(" ","")
                            val refEmitter = knownRef.emitters.firstOrNull { e ->
                                val eClean = e.name.lowercase().replace(" ","")
                                eClean == mClean || eClean.contains(mClean) || mClean.contains(eClean)
                            }
                            refEmitter?.fluxFc?.let { fc -> map[m.diodeName] = fc }
                        }
                        if (map.isNotEmpty()) {
                            Timber.d("GDTF: using reference fc values for ${knownRef.model}: $map")
                            map
                        } else {
                            Timber.d("GDTF: no reference match found, using camera tristY")
                            emptyMap()
                        }
                    } else {
                        Timber.d("GDTF: no known reference found, using camera tristY")
                        emptyMap()
                    }
                }
            }

            com.cinecalibrator.core.GDTFExporter(getApplication()).export(
                result, _selectedFixture.value, _selectedMode.value, fluxMap
            )
        } catch (e: Exception) {
            viewModelScope.launch { _errorMessage.emit("GDTF export failed: ${e.message}") }
            null
        }
    }

    /** Number of color volume samples collected in the last scan (or loaded from state) */
    val colorVolumeSampleCount: Int
        get() = _colorVolumeSamples.value.size

    // ─── Planckian Sweep ──────────────────────────────────────────────────────────

    private val _planckianTable = MutableStateFlow<PlanckianSweepEngine.PlanckianTable?>(null)
    val planckianTable: StateFlow<PlanckianSweepEngine.PlanckianTable?> = _planckianTable

    private val _sweepProgress = MutableSharedFlow<PlanckianSweepEngine.SweepProgress>(extraBufferCapacity = 64)
    val sweepProgress: SharedFlow<PlanckianSweepEngine.SweepProgress> = _sweepProgress

    private val _isSweeping = MutableStateFlow(false)
    val isSweeping: StateFlow<Boolean> = _isSweeping

    private var sweepEngine: PlanckianSweepEngine? = null

    fun startPlanckianSweep(cctTargets: List<Int>? = null) {
        val result = _scanResult.value ?: run {
            viewModelScope.launch { _errorMessage.emit("Run a scan first before the Planckian sweep") }
            return
        }
        val config = _scanConfig.value ?: run {
            viewModelScope.launch { _errorMessage.emit("No fixture config — set up fixture first") }
            return
        }
        if (!sekonicSource.isConnected()) {
            viewModelScope.launch { _errorMessage.emit("C-800 must be connected for Planckian sweep") }
            return
        }
        if (!dmxClient.isConnected) {
            viewModelScope.launch { _errorMessage.emit("DMX not connected") }
            return
        }

        _isSweeping.value = true
        val engine = PlanckianSweepEngine(dmxClient, sekonicSource, result, config)
        sweepEngine = engine

        viewModelScope.launch {
            engine.progress.collect { progress ->
                _sweepProgress.emit(progress)
                when (progress) {
                    is PlanckianSweepEngine.SweepProgress.Completed -> {
                        _planckianTable.value = progress.table
                        _isSweeping.value = false
                    }
                    is PlanckianSweepEngine.SweepProgress.Error,
                    is PlanckianSweepEngine.SweepProgress.Cancelled -> {
                        _isSweeping.value = false
                    }
                    else -> {}
                }
            }
        }

        engine.startSweep(
            cctTargets = cctTargets ?: engine.DEFAULT_CCT_TARGETS,
            scope = viewModelScope
        )
    }

    fun cancelPlanckianSweep() {
        sweepEngine?.cancel()
        _isSweeping.value = false
    }

    fun exportPlanckianTable(): java.io.File? {
        val table = _planckianTable.value ?: return null
        return try {
            val engine = PlanckianSweepEngine(dmxClient, sekonicSource,
                _scanResult.value ?: return null, _scanConfig.value ?: return null)
            val json = engine.tableToJson(table)
            val safeName = table.fixture.replace("[^A-Za-z0-9_\\-]".toRegex(), "_")
            val file = java.io.File(getApplication<android.app.Application>().cacheDir,
                "PCT_${safeName}_${table.scanDate}.pct.json")
            file.writeText(json)
            file
        } catch (e: Exception) {
            viewModelScope.launch { _errorMessage.emit("PCT export failed: ${e.message}") }
            null
        }
    }

    /** Load a previously saved session as the active result (from History) */
    fun loadExistingResult(
        result: ColorScience.ScanResult,
        spectroReadings: List<SekonicMeasurementSource.SpectrometerReading> = emptyList()
    ) {
        _scanResult.value = result
        _spectrometerReadings.value = spectroReadings
        _colorVolumeSamples.value = emptyList()  // not persisted yet
        generateLUT(result)
    }

    // ─── Camera Calibration Scan (Desaturation Path) ─────────────────────────────

    sealed class CalibrationProgress {
        data class StartingPrimary(val name: String, val index: Int, val total: Int) : CalibrationProgress()
        /** Emitted while ramping an emitter to find the camera's exposure limit */
        data class RampProgress(val emitterName: String, val safePeakDmx: Int) : CalibrationProgress()
        data class StepProgress(val primaryName: String, val step: Int, val totalSteps: Int,
                                val whiteFrac: Double) : CalibrationProgress()
        data class PrimaryComplete(val name: String, val reliableSteps: Int) : CalibrationProgress()
        data class Complete(
            val success: Boolean,
            val avgError: Double,
            val sampleCount: Int,
            /** Measured safe peak DMX per emitter from exposure ramp */
            val exposureLimits: Map<String, Int> = emptyMap()
        ) : CalibrationProgress()
        /** CCM could not be solved, but raw step data is available for visualisation */
        data class InsufficientData(
            val totalCollected: Int,
            val reliableCount: Int,
            val allSteps: List<CameraCalibration.DesatStep>,
            val message: String
        ) : CalibrationProgress()
        data class Error(val message: String) : CalibrationProgress()
        object Idle : CalibrationProgress()
    }

    private val _calibrationScanProgress = MutableSharedFlow<CalibrationProgress>(replay = 1)
    val calibrationScanProgress: SharedFlow<CalibrationProgress> = _calibrationScanProgress

    // Last calibration steps retained for diagram display after scan completes
    private var lastCalibrationSteps: List<CameraCalibration.DesatStep> = emptyList()
    fun getLastCalibrationSteps(): List<CameraCalibration.DesatStep> = lastCalibrationSteps

    /**
     * Run desaturation-path camera calibration.
     * For each primary emitter, steps from full saturation → near-D65 white
     * and collects (camera_measured, physically_predicted) pairs.
     */
    fun startCalibrationScan(
        config: ScanEngine.ScanConfig,
        reference: CameraCalibration.ReferenceFixture
    ) {
        viewModelScope.launch {
            try {
                val calibrator = DesaturationCalibrator(cameraManager, dmxClient)

                // Build channel map: emitter name → DMX channel
                val channelMap = calibrator.buildChannelMap(config, reference)

                if (channelMap.isEmpty()) {
                    _calibrationScanProgress.emit(CalibrationProgress.Error(
                        "Could not match any reference emitters to fixture channels. " +
                        "Load the reference fixture GDTF in Setup first."
                    ))
                    return@launch
                }

                val calibConfig = DesaturationCalibrator.CalibConfig(
                    reference = reference,
                    channelMap = channelMap,
                    dimmerChannel = config.dimmerChannel,
                    dmxUniverse = config.dmxUniverse,
                    settleTimeMs = config.settleTimeMs,
                    framesPerSample = 10,
                    roiCenterX = config.roiCenterX,
                    roiCenterY = config.roiCenterY
                )

                val cal = calibrator.runCalibration(calibConfig) { progress ->
                    when (progress) {
                        is DesaturationCalibrator.CalibProgress.StartingPrimary ->
                            _calibrationScanProgress.emit(
                                CalibrationProgress.StartingPrimary(progress.name, progress.index, progress.total))
                        is DesaturationCalibrator.CalibProgress.RampProgress ->
                            _calibrationScanProgress.emit(
                                CalibrationProgress.RampProgress(progress.emitterName, progress.safePeakDmx))
                        is DesaturationCalibrator.CalibProgress.StepProgress ->
                            _calibrationScanProgress.emit(
                                CalibrationProgress.StepProgress(progress.primaryName,
                                    progress.step, progress.totalSteps, progress.whiteFrac))
                        is DesaturationCalibrator.CalibProgress.PrimaryComplete ->
                            _calibrationScanProgress.emit(
                                CalibrationProgress.PrimaryComplete(progress.name, progress.reliableSteps))
                        is DesaturationCalibrator.CalibProgress.CalibrationComplete -> {
                            val c = progress.cal
                            lastCalibrationSteps = calibrator.getAllSteps()
                            logCalibrationResult(c, reference)
                            _calibrationScanProgress.emit(
                                CalibrationProgress.Complete(
                                    success = c.residualError < 0.015,
                                    avgError = c.residualError,
                                    sampleCount = c.sampleCount,
                                    exposureLimits = calibrator.getSafePeakDmxMap()
                                ))
                        }
                        is DesaturationCalibrator.CalibProgress.InsufficientData -> {
                            lastCalibrationSteps = progress.allSteps
                            _calibrationScanProgress.emit(
                                CalibrationProgress.InsufficientData(
                                    progress.totalCollected,
                                    progress.reliableCount,
                                    progress.allSteps,
                                    progress.message
                                ))
                        }
                        is DesaturationCalibrator.CalibProgress.Error ->
                            _calibrationScanProgress.emit(CalibrationProgress.Error(progress.message))
                    }
                }

                if (cal == null) {
                    val last = _calibrationScanProgress.replayCache.lastOrNull()
                    if (last !is CalibrationProgress.Error && last !is CalibrationProgress.InsufficientData) {
                        _calibrationScanProgress.emit(CalibrationProgress.Error("Calibration returned no result"))
                    }
                }

            } catch (e: Exception) {
                Timber.e(e, "Calibration scan failed")
                _calibrationScanProgress.emit(CalibrationProgress.Error(e.message ?: "Unknown error"))
            }
        }
    }

    private fun logCalibrationResult(
        cal: CameraCalibration.CalibrationMatrix,
        reference: CameraCalibration.ReferenceFixture
    ) {
        viewModelScope.launch {
            try {
                repository.logCameraCalibration(
                    referenceFixture = "${reference.manufacturer} ${reference.model}",
                    residualError = cal.residualError,
                    matchedChannels = cal.sampleCount,
                    matrixJson = com.google.gson.Gson().toJson(cal.matrix),
                    notes = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                            "Android ${android.os.Build.VERSION.RELEASE} — " +
                            "method: ${cal.method}"
                )
            } catch (e: Exception) { Timber.e(e, "Failed to log calibration") }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cameraManager.release()
        sekonicSource.release()
        dmxClient.disconnect()
    }
}
