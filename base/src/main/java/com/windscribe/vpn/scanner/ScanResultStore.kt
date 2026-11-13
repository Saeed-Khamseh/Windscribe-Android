package com.windscribe.vpn.scanner

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The scanner's own database, `scanner.db`. Deliberately not part of `WindscribeDatabase`: that
 * schema and its migration numbers belong to upstream, and a fork migration would collide.
 */
@Database(entities = [ScanResultEntity::class], version = 1, exportSchema = false)
abstract class ScannerDatabase : RoomDatabase() {
    abstract fun scanResultDao(): ScanResultDao

    companion object {
        const val NAME = "scanner.db"
    }
}

@Entity(
    tableName = "scan_result",
    indices = [Index(value = ["networkName", "timestampMs"])],
)
data class ScanResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val networkName: String,
    val timestampMs: Long,
    val cityId: Int,
    val cityName: String,
    val nodeHostname: String,
    val nodeIp: String,
    val protocol: String,
    val port: Int,
    val tweak: String,
    val probeReachable: Boolean?,
    val probeLatencyMs: Long?,
    val probeDetail: String,
    val verified: Boolean,
    val connected: Boolean,
    val connectMs: Long?,
    val trafficOk: Boolean,
    val verifyLatencyMs: Long?,
    val downloadKbps: Long?,
    val verifyError: String?,
    val score: Double,
)

@Dao
abstract class ScanResultDao {
    @Insert
    abstract suspend fun insertAll(rows: List<ScanResultEntity>)

    @Query("SELECT * FROM scan_result WHERE networkName = :networkName ORDER BY score DESC, timestampMs DESC")
    abstract suspend fun forNetwork(networkName: String): List<ScanResultEntity>

    @Query(
        "DELETE FROM scan_result WHERE networkName = :networkName AND id NOT IN " +
            "(SELECT id FROM scan_result WHERE networkName = :networkName ORDER BY timestampMs DESC, id DESC LIMIT :keep)",
    )
    abstract suspend fun trim(
        networkName: String,
        keep: Int,
    )

    @Query("DELETE FROM scan_result WHERE networkName = :networkName")
    abstract suspend fun clear(networkName: String)

    @Transaction
    open suspend fun insertAndTrim(
        rows: List<ScanResultEntity>,
        networkName: String,
        keep: Int,
    ) {
        insertAll(rows)
        trim(networkName, keep)
    }
}

/** Scan results, keyed by network name (the key the app uses for `NetworkInfo`). Newest 50 per network. */
@Singleton
class ScanResultStore
    @Inject
    constructor(
        private val dao: ScanResultDao,
    ) {
        suspend fun save(
            networkName: String,
            results: List<ScanResult>,
        ) {
            if (results.isEmpty()) return
            dao.insertAndTrim(results.map { it.toEntity(networkName) }, networkName, KEEP_PER_NETWORK)
        }

        /** Best first. */
        suspend fun load(networkName: String): List<ScanResult> = dao.forNetwork(networkName).map { it.toResult() }

        suspend fun clear(networkName: String) = dao.clear(networkName)

        private fun ScanResult.toEntity(network: String) =
            ScanResultEntity(
                networkName = network,
                timestampMs = timestampMs,
                cityId = candidate.cityId,
                cityName = candidate.cityName,
                nodeHostname = candidate.nodeHostname,
                nodeIp = candidate.nodeIp,
                protocol = candidate.protocol,
                port = candidate.port,
                tweak = candidate.tweak.key,
                probeReachable = probe.reachable,
                probeLatencyMs = probe.latencyMs,
                probeDetail = probe.detail,
                verified = verify != null,
                connected = verify?.connected ?: false,
                connectMs = verify?.connectMs,
                trafficOk = verify?.trafficOk ?: false,
                verifyLatencyMs = verify?.latencyMs,
                downloadKbps = verify?.downloadKbps,
                verifyError = verify?.error,
                score = score,
            )

        private fun ScanResultEntity.toResult() =
            ScanResult(
                candidate =
                    ScanCandidate(
                        cityId = cityId,
                        cityName = cityName,
                        nodeHostname = nodeHostname,
                        nodeIp = nodeIp,
                        protocol = protocol,
                        port = port,
                        tweak = ScanTweak.fromKey(tweak),
                    ),
                probe = ProbeResult(probeReachable, probeLatencyMs, probeDetail),
                verify =
                    if (verified) {
                        VerifyResult(connected, connectMs, trafficOk, verifyLatencyMs, downloadKbps, verifyError)
                    } else {
                        null
                    },
                score = score,
                timestampMs = timestampMs,
                networkName = networkName,
            )

        companion object {
            const val KEEP_PER_NETWORK = 50
        }
    }
