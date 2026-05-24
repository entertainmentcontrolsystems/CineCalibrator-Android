package com.cinecalibrator.model

import android.content.Context
import com.cinecalibrator.core.ColorScience
import com.cinecalibrator.core.GDTFParser
import com.cinecalibrator.core.LUTGenerator
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

/**
 * SessionRepository
 *
 * Single source of truth for:
 *   - Persisting and loading calibration sessions (Room DB)
 *   - Storing LUT file references (Room DB + file system)
 *   - GDTF library management (Room DB)
 *
 * All database calls are suspend functions; UI consumers observe
 * Flow<> streams for reactive updates.
 */
class SessionRepository(private val context: Context) {

    private val db = CineCalibratorDatabase.getInstance(context)
    private val gson = Gson()

    // ─── Calibration Sessions ─────────────────────────────────────────────────

    /** Observe all sessions ordered by newest first */
    val allSessions: Flow<List<CalibrationSessionEntity>> =
        db.sessionDao().getAllSessions()

    /** Save a completed ScanResult to the database, including optional spectrometer readings */
    suspend fun saveSession(
        result: ColorScience.ScanResult,
        measurementSource: String = "Camera",
        spectrometerReadings: List<com.cinecalibrator.core.SekonicMeasurementSource.SpectrometerReading> = emptyList()
    ) {
        val json = gson.toJson(result.measurements)
        val spectroJson = if (spectrometerReadings.isNotEmpty()) gson.toJson(spectrometerReadings) else ""
        db.sessionDao().insertSession(
            CalibrationSessionEntity(
                sessionId = result.sessionId,
                timestamp = result.timestamp,
                fixtureName = result.fixtureName,
                fixtureManufacturer = result.fixtureManufacturer,
                notes = result.notes,
                measurementCount = result.measurements.size,
                measurementsJson = json,
                measurementSource = measurementSource,
                spectrometerReadingsJson = spectroJson
            )
        )
    }

    /** Load a full ScanResult by session ID, also returning any persisted spectrometer readings */
    suspend fun loadSession(sessionId: String):
            Pair<ColorScience.ScanResult, List<com.cinecalibrator.core.SekonicMeasurementSource.SpectrometerReading>>? {
        val entity = db.sessionDao().getSession(sessionId) ?: return null
        val measureType = object : TypeToken<List<ColorScience.DiodeMeasurement>>() {}.type
        val measurements: List<ColorScience.DiodeMeasurement> =
            gson.fromJson(entity.measurementsJson, measureType) ?: emptyList()

        val spectroType = object : TypeToken<List<com.cinecalibrator.core.SekonicMeasurementSource.SpectrometerReading>>() {}.type
        val spectroReadings: List<com.cinecalibrator.core.SekonicMeasurementSource.SpectrometerReading> =
            if (entity.spectrometerReadingsJson.isNotEmpty())
                try { gson.fromJson(entity.spectrometerReadingsJson, spectroType) ?: emptyList() }
                catch (_: Exception) { emptyList() }
            else emptyList()

        val result = ColorScience.ScanResult(
            sessionId = entity.sessionId,
            timestamp = entity.timestamp,
            fixtureName = entity.fixtureName,
            fixtureManufacturer = entity.fixtureManufacturer,
            measurements = measurements,
            notes = entity.notes
        )
        return result to spectroReadings
    }

    suspend fun searchSessions(query: String): List<CalibrationSessionEntity> =
        db.sessionDao().searchSessions(query)

    suspend fun deleteSession(entity: CalibrationSessionEntity) {
        // Also remove associated LUTs
        db.lutDao().deleteLUTsForSession(entity.sessionId)
        db.sessionDao().deleteSession(entity)
    }

    suspend fun sessionCount(): Int = db.sessionDao().count()

    // ─── LUT Files ────────────────────────────────────────────────────────────

    val allLUTs: Flow<List<LUTFileEntity>> = db.lutDao().getAllLUTs()

    /** Record a saved LUT file in the database */
    suspend fun saveLUTReference(
        sessionId: String,
        fixtureName: String,
        lut: LUTGenerator.LUTData,
        file: File
    ) {
        db.lutDao().insertLUT(
            LUTFileEntity(
                lutId = UUID.randomUUID().toString(),
                sessionId = sessionId,
                timestamp = System.currentTimeMillis(),
                fixtureName = fixtureName,
                targetColorspace = lut.targetColorspace.name,
                lutSize = lut.size,
                filePath = file.absolutePath,
                fileSizeBytes = file.length()
            )
        )
    }

    suspend fun getLUTsForSession(sessionId: String): List<LUTFileEntity> =
        db.lutDao().getLUTsForSession(sessionId)

    suspend fun deleteLUT(entity: LUTFileEntity) {
        // Remove file from disk
        File(entity.filePath).takeIf { it.exists() }?.delete()
        db.lutDao().deleteLUT(entity)
    }

    // ─── GDTF Library ─────────────────────────────────────────────────────────

    val gdtfLibrary: Flow<List<GDTFLibraryEntity>> =
        db.gdtfLibraryDao().getAllFixtures()

    suspend fun saveGDTFFixture(fixture: GDTFParser.GDTFFixture) {
        db.gdtfLibraryDao().insertFixture(
            GDTFLibraryEntity(
                gdtfId = UUID.randomUUID().toString(),
                manufacturer = fixture.manufacturer,
                fixtureName = fixture.name,
                shortName = fixture.shortName,
                importedAt = System.currentTimeMillis(),
                fixtureJson = gson.toJson(fixture)
            )
        )
    }

    suspend fun loadGDTFFixture(entity: GDTFLibraryEntity): GDTFParser.GDTFFixture? {
        return try {
            gson.fromJson(entity.fixtureJson, GDTFParser.GDTFFixture::class.java)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun deleteGDTFFixture(entity: GDTFLibraryEntity) =
        db.gdtfLibraryDao().deleteFixture(entity)

    suspend fun searchGDTFLibrary(query: String): List<GDTFLibraryEntity> =
        db.gdtfLibraryDao().search(query)

    suspend fun gdtfLibraryCount(): Int = db.gdtfLibraryDao().count()

    // ─── Camera Gamut Log ─────────────────────────────────────────────────────────

    val cameraGamutLog: kotlinx.coroutines.flow.Flow<List<CameraGamutLogEntity>> =
        db.cameraGamutLogDao().getAllEntries()

    suspend fun logCameraCalibration(
        referenceFixture: String,
        residualError: Double,
        matchedChannels: Int,
        matrixJson: String,
        notes: String = ""
    ) {
        // Device info via android.os.Build
        db.cameraGamutLogDao().insert(
            CameraGamutLogEntity(
                logId = java.util.UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                deviceManufacturer = android.os.Build.MANUFACTURER,
                deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                androidVersion = android.os.Build.VERSION.RELEASE,
                referenceFixture = referenceFixture,
                residualErrorDxy = residualError,
                matchedChannels = matchedChannels,
                matrixJson = matrixJson,
                notes = notes
            )
        )
    }

    suspend fun getCameraLogForDevice(model: String) =
        db.cameraGamutLogDao().getEntriesForDevice(model)

    suspend fun cameraLogCount(): Int = db.cameraGamutLogDao().count()

    suspend fun deleteCameraLogEntry(entry: CameraGamutLogEntity) =
        db.cameraGamutLogDao().delete(entry)
}
