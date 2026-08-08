/*
 * SettingsStore.kt
 * DataStore-backed settings + saved login. Holds the active source config,
 * player mode, language, parental PIN, TMDB key, aspect ratio, active profile,
 * last-watched channel and UI toggles (clock, screensaver).
 * Credentials, PINs and user API keys are field-encrypted with an Android
 * Keystore-backed key before they enter DataStore.
 */
package com.iptv.player.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.iptv.player.BuildConfig
import com.iptv.player.data.model.AspectRatio
import com.iptv.player.data.model.BufferMode
import com.iptv.player.data.model.ContentSort
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.PlaybackSelection
import com.iptv.player.data.model.PlaybackSelectionPolicy
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.data.model.StreamFormat
import com.iptv.player.player.LiveSubtitlePreference
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.SourceType
import com.iptv.player.security.SecureValueCodec
import com.iptv.player.util.KululuEndpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(
    private val context: Context,
    private val secureValues: SecureValueCodec,
) {

    private object Keys {
        val SOURCE_TYPE = stringPreferencesKey("source_type")
        val SERVER_URL = stringPreferencesKey("server_url")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password")
        val M3U_URL = stringPreferencesKey("m3u_url")
        val PLAYER_MODE = stringPreferencesKey("player_mode")
        val DECODER_MODE = stringPreferencesKey("decoder_mode")
        val LIVE_STREAM_FORMAT = stringPreferencesKey("live_stream_format")
        val BUFFER_MODE = stringPreferencesKey("buffer_mode")
        val AUDIO_PASSTHROUGH = booleanPreferencesKey("audio_passthrough")
        val DEBUG_OVERLAY = booleanPreferencesKey("debug_overlay")
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
        val LIVE_AUDIO_LANGUAGE = stringPreferencesKey("live_audio_language")
        val LIVE_SUBTITLE_LANGUAGE = stringPreferencesKey("live_subtitle_language")
        val EXPIRY_WARN_SUPPRESSED = longPreferencesKey("expiry_warn_suppressed")
        val SECURE_STORAGE_MIGRATED = booleanPreferencesKey("secure_storage_migrated_v1")
        val PLAYBACK_PREFS_MIGRATED = booleanPreferencesKey("playback_prefs_migrated_v1")

        // Content Manager: per-content-type hidden categories + custom order.
        val HIDDEN_CATS_LIVE = stringSetPreferencesKey("hidden_cats_live")
        val HIDDEN_CATS_VOD = stringSetPreferencesKey("hidden_cats_vod")
        val HIDDEN_CATS_SERIES = stringSetPreferencesKey("hidden_cats_series")
        val CAT_ORDER_LIVE = stringPreferencesKey("cat_order_live")
        val CAT_ORDER_VOD = stringPreferencesKey("cat_order_vod")
        val CAT_ORDER_SERIES = stringPreferencesKey("cat_order_series")

        // Persisted grid sort order for the Movies / Series browse screens.
        val CONTENT_SORT_VOD = stringPreferencesKey("content_sort_vod")
        val CONTENT_SORT_SERIES = stringPreferencesKey("content_sort_series")

        // Stable per-install id used by the live-device heartbeat telemetry.
        val DEVICE_ID = stringPreferencesKey("device_id")

        // Id of the most recent remote announcement already shown (dedup).
        val LAST_ANNOUNCEMENT_ID = longPreferencesKey("last_announcement_id")
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

    /**
     * Stable per-install device id for telemetry. Returns the persisted id, or
     * persists and returns one produced by [generate] on first use. [generate]
     * runs only when no id exists yet.
     */
    suspend fun getOrCreateDeviceId(generate: () -> String): String {
        val existing = context.dataStore.data.map { it[Keys.DEVICE_ID] }.first()
        if (!existing.isNullOrBlank()) return existing
        val created = generate()
        context.dataStore.edit { it[Keys.DEVICE_ID] = created }
        return created
    }

    /** Id of the most recent remote announcement already shown (0 = none). */
    suspend fun getLastShownAnnouncementId(): Long =
        context.dataStore.data.first()[Keys.LAST_ANNOUNCEMENT_ID] ?: 0L

    suspend fun setLastShownAnnouncementId(id: Long) =
        context.dataStore.edit { it[Keys.LAST_ANNOUNCEMENT_ID] = id }

    // ---- Playback -------------------------------------------------------

    val playbackSelection: Flow<PlaybackSelection> = flow {
        migratePlaybackPreferences()
        emitAll(
            context.dataStore.data
                .map(::resolvedPlaybackSelection)
                .distinctUntilChanged(),
        )
    }

    val playerMode: Flow<PlayerMode> = playbackSelection.map { it.player }

    /** Decoder strategy: AUTO (hw + software fallback), HARDWARE, SOFTWARE. */
    val decoderMode: Flow<DecoderMode> = playbackSelection.map { it.decoder }

    suspend fun getDecoderMode(): DecoderMode {
        return getPlaybackSelection().decoder
    }

    /** Read player + decoder from the same DataStore revision. */
    suspend fun getPlaybackSelection(): PlaybackSelection {
        migratePlaybackPreferences()
        return resolvedPlaybackSelection(context.dataStore.data.first())
    }

    /** Atomically update the two playback-routing axes (avoids transient conflicts). */
    suspend fun setPlaybackSelection(player: PlayerMode, decoder: DecoderMode) =
        context.dataStore.edit { prefs ->
            val selection = PlaybackSelectionPolicy.normalize(player, decoder)
            prefs[Keys.PLAYER_MODE] = selection.player.name
            prefs[Keys.DECODER_MODE] = selection.decoder.name
            prefs[Keys.PLAYBACK_PREFS_MIGRATED] = true
        }

    /**
     * Persist a canonical playback pair once for installs that predate explicit
     * playback preference versioning. Existing valid selections are kept exactly;
     * missing/unknown values receive the current defaults, and the one historically
     * invalid EXOPLAYER+SOFTWARE pair is normalised. No source/login key is touched.
     *
     * The marker makes this safe to call from every read path. Keeping migration in
     * SettingsStore also guarantees a player cannot observe pre-migration defaults
     * while an asynchronous app-start migration is still pending.
     */
    private suspend fun migratePlaybackPreferences() =
        context.dataStore.edit { prefs ->
            if (prefs[Keys.PLAYBACK_PREFS_MIGRATED] == true) return@edit
            val selection = PlaybackSelectionPolicy.normalize(
                player = PlayerMode.fromName(prefs[Keys.PLAYER_MODE]),
                decoder = DecoderMode.fromName(prefs[Keys.DECODER_MODE]),
            )
            prefs[Keys.PLAYER_MODE] = selection.player.name
            prefs[Keys.DECODER_MODE] = selection.decoder.name
            prefs[Keys.PLAYBACK_PREFS_MIGRATED] = true
        }

    /** Resolve defensively even if a future/corrupt writer bypasses the migration. */
    private fun resolvedPlaybackSelection(
        prefs: Preferences,
    ): PlaybackSelection = PlaybackSelectionPolicy.normalize(
        player = PlayerMode.fromName(prefs[Keys.PLAYER_MODE]),
        decoder = DecoderMode.fromName(prefs[Keys.DECODER_MODE]),
    )

    val streamFormat: Flow<StreamFormat> = context.dataStore.data.map {
        StreamFormat.fromName(it[Keys.LIVE_STREAM_FORMAT])
    }

    suspend fun getStreamFormat(): StreamFormat =
        StreamFormat.fromName(context.dataStore.data.first()[Keys.LIVE_STREAM_FORMAT])

    suspend fun setStreamFormat(format: StreamFormat) =
        context.dataStore.edit { it[Keys.LIVE_STREAM_FORMAT] = format.name }

    val bufferMode: Flow<BufferMode> = context.dataStore.data.map {
        BufferMode.fromName(it[Keys.BUFFER_MODE])
    }

    suspend fun getBufferMode(): BufferMode =
        BufferMode.fromName(context.dataStore.data.first()[Keys.BUFFER_MODE])

    suspend fun setBufferMode(mode: BufferMode) =
        context.dataStore.edit { it[Keys.BUFFER_MODE] = mode.name }

    /**
     * Audio passthrough (encoded bitstream over HDMI/SPDIF for AV receivers).
     * Default OFF: decode everything to PCM so cheap sticks always have sound.
     */
    val audioPassthrough: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.AUDIO_PASSTHROUGH] ?: false
    }

    suspend fun getAudioPassthrough(): Boolean =
        context.dataStore.data.first()[Keys.AUDIO_PASSTHROUGH] ?: false

    suspend fun setAudioPassthrough(enabled: Boolean) =
        context.dataStore.edit { it[Keys.AUDIO_PASSTHROUGH] = enabled }

    /**
     * On-screen Debug overlay (engine/stage/resolution + live PlaybackLog tail)
     * shown on the live preview, fullscreen and VOD players. Default OFF — it is
     * a diagnostics aid for reproducing green-screen / fallback issues.
     */
    val debugOverlay: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.DEBUG_OVERLAY] ?: false
    }

    suspend fun setDebugOverlay(enabled: Boolean) =
        context.dataStore.edit { it[Keys.DEBUG_OVERLAY] = enabled }

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

    /** Stable ISO language preferences for the engine-agnostic live player. */
    suspend fun getLiveAudioLanguage(): String? =
        context.dataStore.data.first()[Keys.LIVE_AUDIO_LANGUAGE]

    suspend fun setLiveAudioLanguage(language: String?) =
        context.dataStore.edit { prefs ->
            val normalized = com.iptv.player.player.TrackLanguage.normalize(language)
            if (normalized == null) prefs.remove(Keys.LIVE_AUDIO_LANGUAGE)
            else prefs[Keys.LIVE_AUDIO_LANGUAGE] = normalized
        }

    suspend fun getLiveSubtitlePreference(): LiveSubtitlePreference =
        LiveSubtitlePreference.fromStorage(
            context.dataStore.data.first()[Keys.LIVE_SUBTITLE_LANGUAGE],
        )

    suspend fun setLiveSubtitlePreference(preference: LiveSubtitlePreference) =
        context.dataStore.edit { prefs ->
            prefs[Keys.LIVE_SUBTITLE_LANGUAGE] = preference.storageValue()
        }

    /** Compatibility for callers compiled against the former language-only API. */
    suspend fun getLiveSubtitleLanguage(): String? =
        getLiveSubtitlePreference().languageOrNull()

    suspend fun setLiveSubtitleLanguage(language: String?) =
        setLiveSubtitlePreference(
            LiveSubtitlePreference.Language.from(language) ?: LiveSubtitlePreference.Auto,
        )

    // ---- Language -------------------------------------------------------

    val languageTag: Flow<String> = context.dataStore.data.map { it[Keys.LANGUAGE] ?: "" }

    suspend fun getLanguageTag(): String =
        context.dataStore.data.first()[Keys.LANGUAGE] ?: ""

    suspend fun setLanguageTag(tag: String) {
        // Mirror to plain prefs FIRST so BaseActivity.attachBaseContext can read it
        // synchronously (DataStore is suspend-only and cannot run on that path).
        // Writing the mirror before the DataStore suspend point guarantees a caller
        // that recreates right after this returns sees the new locale immediately.
        localePrefs.edit().putString(Keys.LANGUAGE.name, tag).apply()
        context.dataStore.edit { it[Keys.LANGUAGE] = tag }
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
    suspend fun getPin(): String? =
        context.dataStore.data.first()[Keys.PIN]
            ?.let(secureValues::decrypt)
            ?: DEFAULT_PIN

    suspend fun setPin(pin: String) =
        context.dataStore.edit { it[Keys.PIN] = secureValues.encrypt(pin) }

    suspend fun hasPin(): Boolean = !getPin().isNullOrEmpty()

    // ---- TMDB -----------------------------------------------------------

    // Preserve a key saved by an older app version; otherwise use the
    // CI-injected BuildConfig value. The key is no longer exposed in Settings.
    val tmdbKey: Flow<String> = context.dataStore.data.map {
        secureValues.decrypt(it[Keys.TMDB_KEY]).takeIf(String::isNotBlank)
            ?: DEFAULT_TMDB_KEY
    }

    suspend fun getTmdbKey(): String =
        secureValues.decrypt(context.dataStore.data.first()[Keys.TMDB_KEY])
            .takeIf(String::isNotBlank)
            ?: DEFAULT_TMDB_KEY

    suspend fun setTmdbKey(key: String) =
        context.dataStore.edit {
            if (key.isBlank()) it.remove(Keys.TMDB_KEY)
            else it[Keys.TMDB_KEY] = secureValues.encrypt(key)
        }

    // ---- Profiles -------------------------------------------------------

    val activeProfileId: Flow<Long> = context.dataStore.data.map { it[Keys.ACTIVE_PROFILE] ?: -1L }

    suspend fun getActiveProfileId(): Long =
        context.dataStore.data.first()[Keys.ACTIVE_PROFILE] ?: -1L

    suspend fun setActiveProfileId(id: Long) =
        context.dataStore.edit { it[Keys.ACTIVE_PROFILE] = id }

    // ---- UI toggles -----------------------------------------------------

    val showClock: Flow<Boolean> = context.dataStore.data.map { it[Keys.SHOW_CLOCK] ?: false }

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

    // ---- Subscription expiry warning -----------------------------------
    // Stores the expiry timestamp the user chose to suppress the reminder for.
    // A later renewal (a different expiry date) re-enables the reminder.

    suspend fun getSuppressedExpiryWarning(): Long =
        context.dataStore.data.first()[Keys.EXPIRY_WARN_SUPPRESSED] ?: 0L

    suspend fun setSuppressedExpiryWarning(expiryMs: Long) =
        context.dataStore.edit { it[Keys.EXPIRY_WARN_SUPPRESSED] = expiryMs }

    // ---- Source config --------------------------------------------------

    suspend fun getSourceConfig(): SourceConfig? {
        val prefs = context.dataStore.data.first()
        val typeName = prefs[Keys.SOURCE_TYPE] ?: return null
        val type = runCatching { SourceType.valueOf(typeName) }.getOrNull() ?: return null
        return SourceConfig(
            type = type,
            serverUrl = KululuEndpoint.migrateLegacyServerUrl(
                secureValues.decrypt(prefs[Keys.SERVER_URL]),
            ),
            username = secureValues.decrypt(prefs[Keys.USERNAME]),
            password = secureValues.decrypt(prefs[Keys.PASSWORD]),
            m3uUrl = secureValues.decrypt(prefs[Keys.M3U_URL])
        )
    }

    suspend fun saveSource(config: SourceConfig) {
        // Encrypt before opening the DataStore transaction: secure-storage failure
        // aborts the save and never falls back to writing cleartext credentials.
        val serverUrl = secureValues.encrypt(
            KululuEndpoint.migrateLegacyServerUrl(config.serverUrl),
        )
        val username = secureValues.encrypt(config.username)
        val password = secureValues.encrypt(config.password)
        val m3uUrl = secureValues.encrypt(config.m3uUrl)
        context.dataStore.edit { prefs ->
            prefs[Keys.SOURCE_TYPE] = config.type.name
            prefs[Keys.SERVER_URL] = serverUrl
            prefs[Keys.USERNAME] = username
            prefs[Keys.PASSWORD] = password
            prefs[Keys.M3U_URL] = m3uUrl
            // EPG_UPDATED_AT belongs to the active source. A provider/profile
            // change must never let the new account reuse the old account's
            // four-hour cold-start freshness window.
            prefs[Keys.EPG_UPDATED_AT] = 0L
        }
    }

    /**
     * One-time/lazy migration for installs created before secure field storage.
     * Safe to run on every launch: versioned envelopes are left untouched.
     */
    suspend fun migrateSensitiveValues() {
        context.dataStore.edit { prefs ->
            fun migrate(key: Preferences.Key<String>) {
                val value = prefs[key] ?: return
                if (value.isNotBlank() && !secureValues.isEncrypted(value)) {
                    prefs[key] = secureValues.encrypt(value)
                }
            }
            migrate(Keys.SERVER_URL)
            migrate(Keys.USERNAME)
            migrate(Keys.PASSWORD)
            migrate(Keys.M3U_URL)
            migrate(Keys.PIN)
            migrate(Keys.TMDB_KEY)
        }
    }

    suspend fun isSecureStorageMigrated(): Boolean =
        context.dataStore.data.first()[Keys.SECURE_STORAGE_MIGRATED] ?: false

    suspend fun markSecureStorageMigrated() =
        context.dataStore.edit { it[Keys.SECURE_STORAGE_MIGRATED] = true }

    suspend fun clearSource() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.SOURCE_TYPE)
            prefs.remove(Keys.SERVER_URL)
            prefs.remove(Keys.USERNAME)
            prefs.remove(Keys.PASSWORD)
            prefs.remove(Keys.M3U_URL)
            prefs.remove(Keys.EPG_UPDATED_AT)
        }
    }

    // ---- Content Manager: category hide / custom order ------------------
    // Stored in DataStore (NOT Room) so they survive playlist/EPG refreshes
    // without touching the destructive-migration database. Category order is a
    // single newline-joined id list; hidden is a string set of category ids.

    private fun hiddenKey(type: ContentType) = when (type) {
        ContentType.LIVE -> Keys.HIDDEN_CATS_LIVE
        ContentType.VOD -> Keys.HIDDEN_CATS_VOD
        ContentType.SERIES -> Keys.HIDDEN_CATS_SERIES
    }

    private fun orderKey(type: ContentType) = when (type) {
        ContentType.LIVE -> Keys.CAT_ORDER_LIVE
        ContentType.VOD -> Keys.CAT_ORDER_VOD
        ContentType.SERIES -> Keys.CAT_ORDER_SERIES
    }

    /** Category ids the user has hidden for [type]. */
    fun hiddenCategories(type: ContentType): Flow<Set<String>> =
        context.dataStore.data.map { it[hiddenKey(type)] ?: emptySet() }

    suspend fun setCategoryHidden(type: ContentType, categoryId: String, hidden: Boolean) =
        context.dataStore.edit { prefs ->
            val current = prefs[hiddenKey(type)]?.toMutableSet() ?: mutableSetOf()
            if (hidden) current.add(categoryId) else current.remove(categoryId)
            prefs[hiddenKey(type)] = current
        }

    /** User's custom category order for [type] (empty = source/default order). */
    fun categoryOrder(type: ContentType): Flow<List<String>> =
        context.dataStore.data.map { prefs ->
            prefs[orderKey(type)]?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
        }

    suspend fun setCategoryOrder(type: ContentType, orderedIds: List<String>) =
        context.dataStore.edit { it[orderKey(type)] = orderedIds.joinToString("\n") }

    suspend fun resetCategoryOrder(type: ContentType) =
        context.dataStore.edit { it.remove(orderKey(type)) }

    // LIVE has no sort control; it maps to the VOD key but is never read for LIVE.
    private fun sortKey(type: ContentType) = when (type) {
        ContentType.SERIES -> Keys.CONTENT_SORT_SERIES
        else -> Keys.CONTENT_SORT_VOD
    }

    /** Persisted grid sort for [type]; defaults to newest-first. */
    fun contentSort(type: ContentType): Flow<ContentSort> =
        context.dataStore.data.map { prefs ->
            prefs[sortKey(type)]
                ?.let { runCatching { ContentSort.valueOf(it) }.getOrNull() }
                ?: ContentSort.RECENT
        }

    suspend fun setContentSort(type: ContentType, sort: ContentSort) =
        context.dataStore.edit { it[sortKey(type)] = sort.name }

    companion object {
        /** Default parental PIN used until the user sets their own. */
        const val DEFAULT_PIN = "0000"

        /** CI-injected TMDB key; older encrypted overrides remain compatible. */
        val DEFAULT_TMDB_KEY: String = BuildConfig.TMDB_API_KEY
    }
}
