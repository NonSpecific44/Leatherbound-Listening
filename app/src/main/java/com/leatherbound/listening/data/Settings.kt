package com.leatherbound.listening.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore by preferencesDataStore("settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK, LAMPLIGHT }

enum class LibrarySort(val label: String) {
    RECENTLY_ADDED("Recently added"),
    RECENTLY_PLAYED("Recently played"),
    TITLE("Title"),
    AUTHOR("Author"),
    YEAR("Year"),
    SERIES("Series"),
    LENGTH("Length"),
    GENRE("Genre"),
}

data class AppSettings(
    val resumeRewindSeconds: Int = 5,
    val skipSeconds: Int = 15,
    val autoMarkFinished: Boolean = true,
    val autoQueueSeries: Boolean = true,
    val removeFinishedFromList: Boolean = true,
    val downloadOnWifiOnly: Boolean = true,
    val playbackSpeed: Float = 1f,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val librarySort: LibrarySort = LibrarySort.RECENTLY_ADDED,
    val sortDescending: Boolean = false,
    val libraryGrid: Boolean = true,
    /** Scale the resume rewind by how long you were away. */
    val smartRewind: Boolean = true,
    val skipSilence: Boolean = false,
    val voiceBoost: Boolean = false,
    val autoDownloadNext: Boolean = true,
    val deleteDownloadWhenFinished: Boolean = true,
    val yearlyGoal: Int = 12,
    /** Name from your Plex (or Jellyfin) account. */
    val accountName: String = "",
    /** Name you typed in Settings; wins over [accountName]. */
    val customName: String = "",
    val homeLayout: HomeLayout = HomeLayout.DEFAULT,
    /** Genres unticked in the Library's genre filter. */
    val hiddenGenres: Set<String> = emptySet(),
    /** Switch to the Lamplight theme between [nightStartHour] and [nightEndHour], whatever [theme] is. */
    val lamplightAtNight: Boolean = false,
    val nightStartHour: Int = 21,
    val nightEndHour: Int = 7,
    /** Folder new downloads go in (one of the app's storage folders); empty for the phone's main storage. */
    val downloadRoot: String = "",
    /** GitHub token for the update check, encrypted with [TokenVault]; empty turns the check off. */
    val updateToken: String = "",
    /** A version you chose to skip in the update prompt. */
    val skippedVersion: String = "",
) {
    /** Whether the clock is in the night window (which may run past midnight). */
    fun isNight(hour: Int): Boolean =
        if (nightStartHour <= nightEndHour) hour in nightStartHour until nightEndHour
        else hour >= nightStartHour || hour < nightEndHour

    /** What the home screen greets you by; empty for no name. */
    val displayName: String get() = customName.ifBlank { accountName }
}

private val NOT_BACKED_UP = setOf("device_id", "account_name", "update_token", "download_root")

class SettingsStore(private val context: Context) {
    private object Keys {
        val rewind = intPreferencesKey("resume_rewind_seconds")
        val autoFinish = booleanPreferencesKey("auto_mark_finished")
        val autoQueue = booleanPreferencesKey("auto_queue_series")
        val removeFinished = booleanPreferencesKey("remove_finished_from_list")
        val wifiOnly = booleanPreferencesKey("download_wifi_only")
        val speed = floatPreferencesKey("playback_speed")
        val theme = stringPreferencesKey("theme")
        val sort = stringPreferencesKey("library_sort")
        val sortDesc = booleanPreferencesKey("library_sort_desc")
        val grid = booleanPreferencesKey("library_grid")
        val deviceId = stringPreferencesKey("device_id")
        val smartRewind = booleanPreferencesKey("smart_rewind")
        val skipSilence = booleanPreferencesKey("skip_silence")
        val voiceBoost = booleanPreferencesKey("voice_boost")
        val autoDownloadNext = booleanPreferencesKey("auto_download_next")
        val deleteWhenFinished = booleanPreferencesKey("delete_download_when_finished")
        val yearlyGoal = intPreferencesKey("yearly_goal")
        val accountName = stringPreferencesKey("account_name")
        val customName = stringPreferencesKey("custom_name")
        val homeLayout = stringPreferencesKey("home_layout")
        val hiddenGenres = stringSetPreferencesKey("hidden_genres")
        val lamplightAtNight = booleanPreferencesKey("lamplight_at_night")
        val nightStart = intPreferencesKey("night_start_hour")
        val nightEnd = intPreferencesKey("night_end_hour")
        val downloadRoot = stringPreferencesKey("download_root")
        val updateToken = stringPreferencesKey("update_token")
        val skippedVersion = stringPreferencesKey("skipped_version")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            resumeRewindSeconds = p[Keys.rewind] ?: 5,
            autoMarkFinished = p[Keys.autoFinish] ?: true,
            autoQueueSeries = p[Keys.autoQueue] ?: true,
            removeFinishedFromList = p[Keys.removeFinished] ?: true,
            downloadOnWifiOnly = p[Keys.wifiOnly] ?: true,
            playbackSpeed = p[Keys.speed] ?: 1f,
            theme = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            librarySort = p[Keys.sort]?.let { runCatching { LibrarySort.valueOf(it) }.getOrNull() } ?: LibrarySort.RECENTLY_ADDED,
            sortDescending = p[Keys.sortDesc] ?: false,
            libraryGrid = p[Keys.grid] ?: true,
            smartRewind = p[Keys.smartRewind] ?: true,
            skipSilence = p[Keys.skipSilence] ?: false,
            voiceBoost = p[Keys.voiceBoost] ?: false,
            autoDownloadNext = p[Keys.autoDownloadNext] ?: true,
            deleteDownloadWhenFinished = p[Keys.deleteWhenFinished] ?: true,
            yearlyGoal = p[Keys.yearlyGoal] ?: 12,
            accountName = p[Keys.accountName].orEmpty(),
            customName = p[Keys.customName].orEmpty(),
            homeLayout = HomeLayout.parse(p[Keys.homeLayout]),
            hiddenGenres = p[Keys.hiddenGenres].orEmpty(),
            lamplightAtNight = p[Keys.lamplightAtNight] ?: false,
            nightStartHour = p[Keys.nightStart] ?: 21,
            nightEndHour = p[Keys.nightEnd] ?: 7,
            downloadRoot = p[Keys.downloadRoot].orEmpty(),
            updateToken = p[Keys.updateToken].orEmpty(),
            skippedVersion = p[Keys.skippedVersion].orEmpty(),
        )
    }

    suspend fun current(): AppSettings = settings.first()

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setResumeRewind(seconds: Int) = set(Keys.rewind, seconds)
    suspend fun setAutoMarkFinished(v: Boolean) = set(Keys.autoFinish, v)
    suspend fun setAutoQueueSeries(v: Boolean) = set(Keys.autoQueue, v)
    suspend fun setRemoveFinishedFromList(v: Boolean) = set(Keys.removeFinished, v)
    suspend fun setDownloadOnWifiOnly(v: Boolean) = set(Keys.wifiOnly, v)
    suspend fun setPlaybackSpeed(v: Float) = set(Keys.speed, v)
    suspend fun setTheme(v: ThemeMode) = set(Keys.theme, v.name)
    suspend fun setLibrarySort(v: LibrarySort) = set(Keys.sort, v.name)
    suspend fun setSortDescending(v: Boolean) = set(Keys.sortDesc, v)
    suspend fun setLibraryGrid(v: Boolean) = set(Keys.grid, v)
    suspend fun setSmartRewind(v: Boolean) = set(Keys.smartRewind, v)
    suspend fun setSkipSilence(v: Boolean) = set(Keys.skipSilence, v)
    suspend fun setVoiceBoost(v: Boolean) = set(Keys.voiceBoost, v)
    suspend fun setAutoDownloadNext(v: Boolean) = set(Keys.autoDownloadNext, v)
    suspend fun setDeleteDownloadWhenFinished(v: Boolean) = set(Keys.deleteWhenFinished, v)
    suspend fun setYearlyGoal(v: Int) = set(Keys.yearlyGoal, v)
    suspend fun setAccountName(v: String) = set(Keys.accountName, v)
    suspend fun setCustomName(v: String) = set(Keys.customName, v.trim())
    suspend fun setHomeLayout(v: HomeLayout) = set(Keys.homeLayout, v.encode())
    suspend fun setHiddenGenres(v: Set<String>) = set(Keys.hiddenGenres, v)
    suspend fun setLamplightAtNight(v: Boolean) = set(Keys.lamplightAtNight, v)
    suspend fun setNightHours(start: Int, end: Int) = context.dataStore.edit { it[Keys.nightStart] = start; it[Keys.nightEnd] = end }
    suspend fun setDownloadRoot(v: String) = set(Keys.downloadRoot, v)
    suspend fun setUpdateToken(plain: String) = set(Keys.updateToken, TokenVault.encrypt(plain.trim()))
    suspend fun setSkippedVersion(v: String) = set(Keys.skippedVersion, v)

    /** Every setting except per-install ones (device id, account name, secrets), for backups. */
    suspend fun exportAll(): Map<String, Any> = context.dataStore.data.first().asMap()
        .filterKeys { it.name !in NOT_BACKED_UP }
        .mapKeys { it.key.name }

    /** Puts back settings from [exportAll]; unknown names and types are ignored. */
    suspend fun importAll(values: Map<String, Any>) {
        context.dataStore.edit { p ->
            values.forEach { (name, value) ->
                if (name in NOT_BACKED_UP) return@forEach
                when (value) {
                    is Boolean -> p[booleanPreferencesKey(name)] = value
                    is Int -> p[intPreferencesKey(name)] = value
                    is Float -> p[floatPreferencesKey(name)] = value
                    is String -> p[stringPreferencesKey(name)] = value
                    is Set<*> -> p[stringSetPreferencesKey(name)] = value.filterIsInstance<String>().toSet()
                }
            }
        }
    }

    /** Stable per-install identifier that Plex and Jellyfin use to recognise this device. */
    suspend fun deviceId(): String {
        context.dataStore.data.first()[Keys.deviceId]?.let { return it }
        val id = UUID.randomUUID().toString()
        set(Keys.deviceId, id)
        return id
    }
}
