/*
 * SettingsStore.kt
 * DataStore-backed settings + saved login. Holds the active source config,
 * the chosen player mode, last-watched channel and resume-on-launch flag.
 * NOTE: credentials are stored locally for auto-login; treat the device as
 * trusted. (Encryption can be layered on later via EncryptedSharedPreferences.)
 */
package com.iptv.player.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val SOURCE_TYPE = stringPreferencesKey("source_type")
        val SERVER_URL = stringPreferencesKey("server_url")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password")
        val M3U_URL = stringPreferencesKey("m3u_url")
        val PLAYER_MODE = stringPreferencesKey("player_mode")
        val LAST_CHANNEL = stringPreferencesKey("last_channel")
        val RESUME_ON_LAUNCH = booleanPreferencesKey("resume_on_launch")
        val LANGUAGE = stringPreferencesKey("language")
    }

    /** True once a source has been configured (controls login vs home routing). */
    val hasSource: Flow<Boolean> = context.dataStore.data.map {
        !it[Keys.SOURCE_TYPE].isNullOrEmpty()
    }

    val playerMode: Flow<PlayerMode> = context.dataStore.data.map {
        PlayerMode.fromName(it[Keys.PLAYER_MODE])
    }

    val resumeOnLaunch: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.RESUME_ON_LAUNCH] ?: false
    }

    val lastChannelId: Flow<String?> = context.dataStore.data.map { it[Keys.LAST_CHANNEL] }

    suspend fun getSourceConfig(): SourceConfig? {
        val prefs = context.dataStore.data.first()
        val typeName = prefs[Keys.SOURCE_TYPE] ?: return null
        val type = runCatching { SourceType.valueOf(typeName) }.getOrNull() ?: return null
        return SourceConfig(
            type = type,
            serverUrl = prefs[Keys.SERVER_URL].orEmpty(),
            username = prefs[Keys.USERNAME].orEmpty(),
            password = prefs[Keys.PASSWORD].orEmpty(),
            m3uUrl = prefs[Keys.M3U_URL].orEmpty()
        )
    }

    suspend fun saveSource(config: SourceConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SOURCE_TYPE] = config.type.name
            prefs[Keys.SERVER_URL] = config.serverUrl
            prefs[Keys.USERNAME] = config.username
            prefs[Keys.PASSWORD] = config.password
            prefs[Keys.M3U_URL] = config.m3uUrl
        }
    }

    suspend fun clearSource() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.SOURCE_TYPE)
            prefs.remove(Keys.SERVER_URL)
            prefs.remove(Keys.USERNAME)
            prefs.remove(Keys.PASSWORD)
            prefs.remove(Keys.M3U_URL)
        }
    }

    suspend fun setPlayerMode(mode: PlayerMode) {
        context.dataStore.edit { it[Keys.PLAYER_MODE] = mode.name }
    }

    suspend fun setLastChannel(channelId: String) {
        context.dataStore.edit { it[Keys.LAST_CHANNEL] = channelId }
    }

    suspend fun setResumeOnLaunch(enabled: Boolean) {
        context.dataStore.edit { it[Keys.RESUME_ON_LAUNCH] = enabled }
    }
}
