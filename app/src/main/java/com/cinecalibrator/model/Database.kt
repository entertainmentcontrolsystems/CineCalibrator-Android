package com.cinecalibrator.model

import android.content.Context
import androidx.room.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow

// ─── Type Converters ──────────────────────────────────────────────────────────

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromStringList(value: List<String>): String = gson.toJson(value)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        gson.fromJson(value, object : TypeToken<List<String>>() {}.type) ?: emptyList()

    @TypeConverter
    fun fromDoubleMap(value: Map<String, Double>): String = gson.toJson(value)

    @TypeConverter
    fun toDoubleMap(value: String): Map<String, Double> =
        gson.fromJson(value, object : TypeToken<Map<String, Double>>() {}.type) ?: emptyMap()
}

// ─── Entities ─────────────────────────────────────────────────────────────────

@Entity(tableName = "calibration_sessions")
data class CalibrationSessionEntity(
    @PrimaryKey val sessionId: String,
    val timestamp: Long,
    val fixtureName: String,
    val fixtureManufacturer: String,
    val notes: String,
    val measurementCount: Int,
    /** Full JSON blob of all DiodeMeasurement objects */
    val measurementsJson: String,
    /** "Camera" or "Sekonic C-800" — for display in History */
    val measurementSource: String = "Camera",
    /** JSON of SpectrometerReading list — empty string if camera was used */
    val spectrometerReadingsJson: String = ""
)

@Entity(tableName = "lut_files")
data class LUTFileEntity(
    @PrimaryKey val lutId: String,
    val sessionId: String,
    val timestamp: Long,
    val fixtureName: String,
    val targetColorspace: String,
    val lutSize: Int,
    /** Absolute path to the .cube file on device storage */
    val filePath: String,
    val fileSizeBytes: Long
)

@Entity(tableName = "gdtf_library")
data class GDTFLibraryEntity(
    @PrimaryKey val gdtfId: String,
    val manufacturer: String,
    val fixtureName: String,
    val shortName: String,
    val importedAt: Long,
    /** Full JSON of the parsed GDTFFixture object */
    val fixtureJson: String
)

// ─── DAOs ──────────────────────────────────────────────────────────────────────

@Dao
interface CalibrationSessionDao {

    @Query("SELECT * FROM calibration_sessions ORDER BY timestamp DESC")
    fun getAllSessions(): Flow<List<CalibrationSessionEntity>>

    @Query("SELECT * FROM calibration_sessions WHERE sessionId = :id")
    suspend fun getSession(id: String): CalibrationSessionEntity?

    @Query("SELECT * FROM calibration_sessions WHERE fixtureName LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun searchSessions(query: String): List<CalibrationSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: CalibrationSessionEntity)

    @Delete
    suspend fun deleteSession(session: CalibrationSessionEntity)

    @Query("DELETE FROM calibration_sessions")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM calibration_sessions")
    suspend fun count(): Int
}

@Dao
interface LUTFileDao {

    @Query("SELECT * FROM lut_files ORDER BY timestamp DESC")
    fun getAllLUTs(): Flow<List<LUTFileEntity>>

    @Query("SELECT * FROM lut_files WHERE sessionId = :sessionId")
    suspend fun getLUTsForSession(sessionId: String): List<LUTFileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLUT(lut: LUTFileEntity)

    @Delete
    suspend fun deleteLUT(lut: LUTFileEntity)

    @Query("DELETE FROM lut_files WHERE sessionId = :sessionId")
    suspend fun deleteLUTsForSession(sessionId: String)
}

@Dao
interface GDTFLibraryDao {

    @Query("SELECT * FROM gdtf_library ORDER BY manufacturer, fixtureName")
    fun getAllFixtures(): Flow<List<GDTFLibraryEntity>>

    @Query("SELECT * FROM gdtf_library WHERE manufacturer LIKE '%' || :q || '%' OR fixtureName LIKE '%' || :q || '%'")
    suspend fun search(q: String): List<GDTFLibraryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFixture(fixture: GDTFLibraryEntity)

    @Delete
    suspend fun deleteFixture(fixture: GDTFLibraryEntity)

    @Query("SELECT COUNT(*) FROM gdtf_library")
    suspend fun count(): Int
}

// ─── Database ──────────────────────────────────────────────────────────────────

@Database(
    entities = [
        CalibrationSessionEntity::class,
        LUTFileEntity::class,
        GDTFLibraryEntity::class,
        CameraGamutLogEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class CineCalibratorDatabase : RoomDatabase() {

    abstract fun sessionDao(): CalibrationSessionDao
    abstract fun lutDao(): LUTFileDao
    abstract fun gdtfLibraryDao(): GDTFLibraryDao
    abstract fun cameraGamutLogDao(): CameraGamutLogDao

    companion object {
        @Volatile
        private var INSTANCE: CineCalibratorDatabase? = null

        fun getInstance(context: Context): CineCalibratorDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CineCalibratorDatabase::class.java,
                    "cinecalibrator.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// ─── Camera Gamut Log ─────────────────────────────────────────────────────────

@Entity(tableName = "camera_gamut_log")
data class CameraGamutLogEntity(
    @PrimaryKey val logId: String,
    val timestamp: Long,
    val deviceManufacturer: String,       // e.g. "Google", "Samsung", "Apple"
    val deviceModel: String,              // e.g. "Pixel 8 Pro"
    val androidVersion: String,
    val referenceFixture: String,         // e.g. "ETC Fos/4 Panel 8-Light"
    val residualErrorDxy: Double,         // Average Δxy after calibration
    val matchedChannels: Int,             // How many channels were matched
    val matrixJson: String,               // 3x3 CCM as JSON
    val notes: String = ""
)

@Dao
interface CameraGamutLogDao {
    @Query("SELECT * FROM camera_gamut_log ORDER BY timestamp DESC")
    fun getAllEntries(): kotlinx.coroutines.flow.Flow<List<CameraGamutLogEntity>>

    @Query("SELECT * FROM camera_gamut_log WHERE deviceModel = :model ORDER BY timestamp DESC")
    suspend fun getEntriesForDevice(model: String): List<CameraGamutLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: CameraGamutLogEntity)

    @Delete
    suspend fun delete(entry: CameraGamutLogEntity)

    @Query("SELECT COUNT(*) FROM camera_gamut_log")
    suspend fun count(): Int
}
