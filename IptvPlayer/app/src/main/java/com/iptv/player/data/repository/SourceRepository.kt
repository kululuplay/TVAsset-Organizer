/*
 * SourceRepository.kt
 * Source-level concerns that are not catalog data: credential validation,
 * account info, connectivity diagnostics and the one-time encryption of
 * sensitive columns written by pre-secure-storage versions.
 */
package com.iptv.player.data.repository

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.iptv.player.data.local.AppDatabase
import com.iptv.player.data.model.AccountInfo
import com.iptv.player.data.model.DiagnosticResult
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.SourceType
import com.iptv.player.data.parser.M3uParser
import com.iptv.player.data.prefs.SettingsStore
import com.iptv.player.security.SecureValueCodec
import com.iptv.player.util.AppError
import com.iptv.player.util.HttpAppErrorPolicy
import com.iptv.player.util.Logger
import com.iptv.player.util.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress

internal class SourceRepository(
    private val db: AppDatabase,
    private val httpClient: OkHttpClient,
    private val settings: SettingsStore,
    private val secureValues: SecureValueCodec,
    private val support: CatalogSyncSupport,
) {

    /**
     * Encrypt sensitive values written by versions before secure field storage.
     * The Room schema is unchanged; only column payloads gain a versioned AES-GCM
     * envelope. The transaction makes an interrupted migration retryable.
     */
    suspend fun migrateSensitiveStorage() = withContext(Dispatchers.IO) {
        if (settings.isSecureStorageMigrated()) return@withContext
        settings.migrateSensitiveValues()
        var migrated = 0
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            migrated += migrateEncryptedColumn(sql, "channels", "id", "streamUrl")
            migrated += migrateEncryptedColumn(sql, "vod", "id", "streamUrl")
            migrated += migrateEncryptedColumn(sql, "episodes", "id", "streamUrl")
            migrated += migrateEncryptedResumeUrls(sql)
            migrated += migrateEncryptedColumn(sql, "profiles", "id", "serverUrl")
            migrated += migrateEncryptedColumn(sql, "profiles", "id", "username")
            migrated += migrateEncryptedColumn(sql, "profiles", "id", "password")
            migrated += migrateEncryptedColumn(sql, "profiles", "id", "m3uUrl")
        }
        settings.markSecureStorageMigrated()
        Logger.i("SecureStorage", "encrypted $migrated legacy database values")
    }

    private fun migrateEncryptedColumn(
        db: SupportSQLiteDatabase,
        table: String,
        idColumn: String,
        valueColumn: String,
    ): Int {
        val pending = mutableListOf<Pair<String, String>>()
        db.query(
            "SELECT `$idColumn`, `$valueColumn` FROM `$table` " +
                "WHERE `$valueColumn` IS NOT NULL AND `$valueColumn` != ''",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val value = cursor.getString(1)
                if (!secureValues.isEncrypted(value)) pending += id to value
            }
        }
        pending.forEach { (id, value) ->
            db.execSQL(
                "UPDATE `$table` SET `$valueColumn` = ? WHERE `$idColumn` = ?",
                arrayOf(secureValues.encrypt(value), id),
            )
        }
        return pending.size
    }

    /** Resume is keyed by (profileId, contentId) as of schema v13. */
    private fun migrateEncryptedResumeUrls(db: SupportSQLiteDatabase): Int {
        val pending = mutableListOf<Triple<Long, String, String>>()
        db.query(
            "SELECT `profileId`, `contentId`, `streamUrl` FROM `resume` " +
                "WHERE `streamUrl` IS NOT NULL AND `streamUrl` != ''",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val profileId = cursor.getLong(0)
                val contentId = cursor.getString(1)
                val value = cursor.getString(2)
                if (!secureValues.isEncrypted(value)) {
                    pending += Triple(profileId, contentId, value)
                }
            }
        }
        pending.forEach { (profileId, contentId, value) ->
            db.execSQL(
                "UPDATE `resume` SET `streamUrl` = ? " +
                    "WHERE `profileId` = ? AND `contentId` = ?",
                arrayOf(secureValues.encrypt(value), profileId, contentId),
            )
        }
        return pending.size
    }

    // ---- Source validation / live ---------------------------------------

    suspend fun testSource(config: SourceConfig): Outcome<Unit> = withContext(Dispatchers.IO) {
        try {
            when (config.type) {
                SourceType.XTREAM -> {
                    val api = support.buildXtreamApi(config.serverUrl)
                    val auth = api.authenticate(config.username, config.password)
                    auth.serverInfo?.timezone?.let { support.panelTimezones[config.serverUrl] = it }
                    val info = auth.userInfo
                        ?: return@withContext Outcome.Failure(AppError.CANNOT_CONNECT)
                    when {
                        info.auth == 0 -> Outcome.Failure(AppError.BAD_CREDENTIALS)
                        info.auth != 1 -> Outcome.Failure(AppError.CANNOT_CONNECT)
                        info.status.equals("Disabled", true) ||
                            info.status.equals("Banned", true) ->
                            Outcome.Failure(AppError.ACCOUNT_DISABLED)
                        info.status.equals("Expired", true) ->
                            Outcome.Failure(AppError.SUBSCRIPTION_EXPIRED)
                        info.status.equals("Active", true) -> Outcome.Success(Unit)
                        else -> Outcome.Failure(AppError.CANNOT_CONNECT)
                    }
                }
                SourceType.M3U_URL -> {
                    val request = Request.Builder()
                        .url(config.m3uUrl)
                        .header("Range", "bytes=0-1023")
                        .build()
                    httpClient.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val prefix = resp.body?.charStream()?.let { reader ->
                                val chars = CharArray(1024)
                                val count = reader.read(chars)
                                if (count > 0) String(chars, 0, count) else ""
                            }.orEmpty()
                            if (M3uParser.hasPlaylistSignature(prefix)) Outcome.Success(Unit)
                            else Outcome.Failure(AppError.EMPTY_PLAYLIST)
                        }
                        else Outcome.Failure(
                            HttpAppErrorPolicy.fromStatus(resp.code),
                            httpStatus = resp.code.takeIf { it in 400..599 },
                        )
                    }
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e // never swallow coroutine cancellation
            e.toOutcomeFailure()
        }
    }

    // ---- Account info ---------------------------------------------------

    suspend fun getAccountInfo(config: SourceConfig): AccountInfo? = withContext(Dispatchers.IO) {
        if (config.type != SourceType.XTREAM) return@withContext null
        runCatching {
            val info = support.buildXtreamApi(config.serverUrl)
                .authenticate(config.username, config.password).userInfo ?: return@runCatching null
            val expMs = info.expDate?.toLongOrNull()?.times(1000)
            val daysLeft = expMs?.let { (it - System.currentTimeMillis()) / 86_400_000L }
            AccountInfo(
                status = info.status,
                isActive = info.auth == 1 && !info.status.equals("Expired", true),
                expiryDateMs = expMs,
                daysRemaining = daysLeft,
                activeConnections = info.activeConnections?.toIntOrNull(),
                maxConnections = info.maxConnections?.toIntOrNull()
            )
        }.getOrNull()
    }

    // ---- Diagnostics ----------------------------------------------------

    suspend fun pingServer(config: SourceConfig): DiagnosticResult = withContext(Dispatchers.IO) {
        val target = config.serverUrl.ifBlank { config.m3uUrl }
        runCatching {
            val start = System.currentTimeMillis()
            val request = Request.Builder().url(target).header("Range", "bytes=0-0").build()
            httpClient.newCall(request).execute().use { resp ->
                val ms = System.currentTimeMillis() - start
                DiagnosticResult("ping", resp.isSuccessful || resp.code in 200..416, "${ms}ms")
            }
        }.getOrElse { DiagnosticResult("ping", false, it.message ?: "error") }
    }

    /** The panel root and M3U are not throughput endpoints; do not download them. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun speedTestMbps(config: SourceConfig): DiagnosticResult = withContext(Dispatchers.IO) {
        DiagnosticResult("speed", false, "Not measured: no verified server speed-test endpoint")
    }

    suspend fun checkDns(config: SourceConfig): DiagnosticResult = withContext(Dispatchers.IO) {
        val host = runCatching {
            java.net.URI(config.serverUrl.ifBlank { config.m3uUrl }).host
        }.getOrNull() ?: return@withContext DiagnosticResult("dns", false, "no host")
        runCatching {
            val addr = InetAddress.getByName(host)
            DiagnosticResult("dns", true, addr.hostAddress ?: host)
        }.getOrElse { DiagnosticResult("dns", false, "cannot resolve") }
    }
}
