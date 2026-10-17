package com.windscribe.vpn.scanner

import android.content.Context
import androidx.annotation.Keep
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Keep
data class ScanCheckpoint(
    val settings: ScanSettings = ScanSettings(),
    val networkKey: String = "",
    val plan: List<ScanRoute> = emptyList(),
    val results: List<ScanResult> = emptyList(),
    val testedSettings: ScanSettings? = null,
    val owner: String = "",
)

@Entity(tableName = "scan_checkpoint")
data class ScanCheckpointRow(
    @PrimaryKey val id: Int = 1,
    val payload: String,
)

@Dao
interface ScanCheckpointDao {
    @Query("SELECT payload FROM scan_checkpoint WHERE id = 1")
    suspend fun load(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: ScanCheckpointRow)
}

// A separate, disposable checkpoint database: no changes to the account/server database schema.
@Database(entities = [ScanCheckpointRow::class], version = 1, exportSchema = false)
abstract class ScanDatabase : RoomDatabase() {
    abstract fun checkpoint(): ScanCheckpointDao
}

@Singleton
class ScanStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val database by lazy { Room.databaseBuilder(context, ScanDatabase::class.java, "network-scanner.db").build() }
        private val gson = Gson()

        suspend fun load(): ScanCheckpoint? =
            runCatching { database.checkpoint().load()?.let { gson.fromJson(it, ScanCheckpoint::class.java) } }.getOrNull()

        suspend fun save(checkpoint: ScanCheckpoint) {
            database.checkpoint().save(ScanCheckpointRow(payload = gson.toJson(checkpoint)))
        }
    }

fun canResumeScan(
    checkpoint: ScanCheckpoint,
    settings: ScanSettings,
    plan: List<ScanRoute>,
    networkKey: String,
): Boolean =
    settings.resume &&
        checkpoint.networkKey == networkKey &&
        checkpoint.plan == plan &&
        checkpoint.testedSettings?.copy(resume = false, stopOnSuccess = false) == settings.copy(resume = false, stopOnSuccess = false)
