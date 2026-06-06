/*
 * SettingsStore.kt
 * DataStore-backed settings + saved login. Holds the active source config,
 * player mode, language, parental PIN, TMDB key, aspect ratio, active profile,
 * last-watched channel and UI toggles (clock, screensaver).
 * NOTE: credentials/PIN are stored locally for convenience; treat the device as
 * trusted. (Encryption can be layered on later via EncryptedSharedPreferences.)
 */
package com.iptv.player.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.iptv.player.data.model.AspectRatio
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
        val PIN = stringPreferencesKey("parental_pin")
        val LOCK_ADULT = booleanPreferencesKey("lock_adult")
        val TMDB_KEY = stringPreferencesKey("tmdb_key")
        val ASPECT = stringPreferencesKey("aspect_ratio")
        val ACTIVE_PROFILE = longPreferencesKey("active_profile")
        val SHOW_CLOCK = booleanPreferencesKey("show_clock")
        val SCREENSAVER_MIN = intPreferencesKey("screensaver_min")
        val EPG_UPDATED_AT = longPreferencesKey("epg_updated_at")
        val WIZARD_DONE = booleanPreferencesKey("wizard_done")
        val AUTO_SYNC = booleanPreferencesKey("auto_sync")
        val AUTO_SYNC_HOURS = intPreferencesKey("auto_sync_hours")
        val PREF_AUDIO_TRACK = stringPreferencesKey("pref_audio_track")
        val PREF_SUBTITLE_TRACK = stringPreferencesKey("pref_subtitle_track")
    }

    // ---- Routing flags --------------------------------------------------

    val hasSource: Flow<Boolean> = context.dataStore.data.map {
        !it[Keys.SOURCE_TYPE].isNullOrEmpty()
    }

    val wizardDone: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.WIZARD_DONE] ?: false
    }

    suspend fun setWizardDone(done: Boolean) =
        context.dataStore.edit { it[Keys.WIZARD_DONE] = done }

    // ---- Playback -------------------------------------------------------

    val playerMode: Flow<PlayerMode> = context.dataStore.data.map {
        PlayerMode.fromName(it[Keys.PLAYER_MODE])
    }

    suspend fun setPlayerMode(mode: PlayerMode) =
        context.dataStore.edit { it[Keys.PLAYER_MODE] = mode.name }

    val aspectRatio: Flow<AspectRatio> = context.dataStore.data.map { prefs ->
        runCatching { AspectRatio.valueOf(prefs[Keys.ASPECT] ?: "") }
            .getOrDefault(AspectRatio.ORIGINAL)
    }

    suspend fun setAspectRatio(ratio: AspectRatio) =
        context.dataStore.edit { it[Keys.ASPECT] = ratio.name }

    val resumeOnLaunch: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.RESUME_ON_LAUNCH] ?: false
    }

    suspend fun setResumeOnLaunch(enabled: Boolean) =
        context.dataStore.edit { it[Keys.RESUME_ON_LAUNCH] = enabled }

    val lastChannelId: Flow<String?> = context.dataStore.data.map { it[Keys.LAST_CHANNEL] }

    suspend fun setLastChannel(channelId: String) =
        context.dataStore.edit { it[Keys.LAST_CHANNEL] = channelId }

    // ---- Preferred audio / subtitle track ------------------------------
    // Stored as an engine token (a language tag on ExoPlayer, a track name on
    // libVLC). Best-effort across engines/streams: re-applied when it matches.

    suspend fun getPreferredAudioTrack(): String? =
        context.dataStore.data.first()[Keys.PREF_AUDIO_TRACK]

    suspend fun setPreferredAudioTrack(token: String) =
        context.dataStore.edit { it[Keys.PREF_AUDIO_TRACK] = token }

    suspend fun getPreferredSubtitleTrack(): String? =
        context.dataStore.data.first()[Keys.PREF_SUBTITLE_TRACK]

    suspend fun setPreferredSubtitleTrack(token: String) =
        context.dataStore.edit { it[Keys.PREF_SUBTITLE_TRACK] = token }

    // ---- Language -------------------------------------------------------

    val languageTag: Flow<String> = context.dataStore.data.map { it[Keys.LANGUAGE] ?: "" }

    suspend fun getLanguageTag(): String =
        context.dataStore.data.first()[Keys.LANGUAGE] ?: ""

    suspend fun setLanguageTag(tag: String) {
        context.dataStore.edit { it[Keys.LANGUAGE] = tag }
        // Mirror to plain prefs so BaseActivity.attachBaseContext can read it
        // synchronously (DataStore is suspend-only and cannot run on that path).
        localePrefs.edit().putString(Keys.LANGUAGE.name, tag).apply()
    }

    /**
     * Synchronous, non-blocking read of the saved language tag. Backed by a tiny
     * SharedPreferences mirror kept in sync by [setLanguageTag]. Used only on the
     * attachBaseContext path where suspend reads are not possible.
     */
    fun languageTagBlocking(): String =
        localePrefs.getString(Keys.LANGUAGE.name, "") ?: ""

    private val localePrefs by lazy {
        context.getSharedPreferences("locale_mirror", Context.MODE_PRIVATE)
    }

    // ---- Parental control ----------------------------------------------

    /** Adult content is PIN-locked by default. */
    val lockAdult: Flow<Boolean> = context.dataStore.data.map { it[Keys.LOCK_ADULT] ?: true }

    suspend fun setLockAdult(enabled: Boolean) =
        context.dataStore.edit { it[Keys.LOCK_ADULT] = enabled }

    /** Defaults to [DEFAULT_PIN] until the user sets their own. */
    suspend fun getPin(): String? = context.dataStore.data.first()[Keys.PIN] ?: DEFAULT_PIN

    suspend fun setPin(pin: String) =
        context.dataStore.edit { it[Keys.PIN] = pin }

    suspend fun hasPin(): Boolean = !getPin().isNullOrEmpty()

    // ---- TMDB -----------------------------------------------------------

    val tmdbKey: Flow<String> = context.dataStore.data.map { it[Keys.TMDB_KEY] ?: "" }

    suspend fun getTmdbKey(): String = context.dataStore.data.first()[Keys.TMDB_KEY] ?: ""

    suspend fun setTmdbKey(key: String) =
        context.dataStore.edit { it[Keys.TMDB_KEY] = key }

    // ---- Profiles -------------------------------------------------------

    val activeProfileId: Flow<Long> = context.dataStore.data.map { it[Keys.ACTIVE_PROFILE] ?: -1L }

    suspend fun getActiveProfileId(): Long =
        context.dataStore.data.first()[Keys.ACTIVE_PROFILE] ?: -1L

    suspend fun setActiveProfileId(id: Long) =
        context.dataStore.edit { it[Keys.ACTIVE_PROFILE] = id }

    // ---- UI toggles -----------------------------------------------------

    val showClock: Flow<Boolean> = context.dataStore.data.map { it[Keys.SHOW_CLOCK] ?: true }

    suspend fun setShowClock(enabled: Boolean) =
        context.dataStore.edit { it[Keys.SHOW_CLOCK] = enabled }

    /** Screensaver idle minutes; 0 disables it. */
    val screensaverMinutes: Flow<Int> = context.dataStore.data.map { it[Keys.SCREENSAVER_MIN] ?: 10 }

    suspend fun setScreensaverMinutes(minutes: Int) =
        context.dataStore.edit { it[Keys.SCREENSAVER_MIN] = minutes }

    // ---- Background auto-sync -------------------------------------------

    val autoSyncEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_SYNC] ?: false }

    suspend fun isAutoSyncEnabled(): Boolean = context.dataStore.data.first()[Keys.AUTO_SYNC] ?: false

    suspend fun setAutoSyncEnabled(enabled: Boolean) =
        context.dataStore.edit { it[Keys.AUTO_SYNC] = enabled }

    /** Sync interval in hours (default 12; minimum enforced by the scheduler). */
    val autoSyncHours: Flow<Int> = context.dataStore.data.map { it[Keys.AUTO_SYNC_HOURS] ?: 12 }

    suspend fun getAutoSyncHours(): Int = context.dataStore.data.first()[Keys.AUTO_SYNC_HOURS] ?: 12

    suspend fun setAutoSyncHours(hours: Int) =
        context.dataStore.edit { it[Keys.AUTO_SYNC_HOURS] = hours }

    // ---- EPG bookkeeping ------------------------------------------------

    suspend fun getEpgUpdatedAt(): Long = context.dataStore.data.first()[Keys.EPG_UPDATED_AT] ?: 0L

    suspend fun setEpgUpdatedAt(time: Long) =
        context.dataStore.edit { it[Keys.EPG_UPDATED_AT] = time }

    // ---- Source config --------------------------------------------------

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

    companion object {
        /** Default parental PIN used until the user sets their own. */
        const val DEFAULT_PIN = "0000"
    }
}
