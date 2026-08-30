package com.airclip.data

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "airclip_settings")

/**
 * Persists [AirClipSettings] in a Preferences DataStore. Every read runs through
 * [AirClipSettings.normalised], so a hand-edited or partially written store can never hand the
 * service an out-of-range port or limit.
 */
class SettingsStore(context: Context) {

    private val store = context.applicationContext.settingsDataStore

    /** Default device name: the user's own Bluetooth-style name is not readable, so use the model. */
    private val fallbackName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android" }

    val settings: Flow<AirClipSettings> = store.data
        // A corrupt store must not take the app down with it; defaults are always usable.
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map(::read)

    suspend fun current(): AirClipSettings = settings.first()

    suspend fun update(transform: (AirClipSettings) -> AirClipSettings): AirClipSettings {
        var result = AirClipSettings()
        store.edit { preferences ->
            result = transform(read(preferences)).normalised(fallbackName)
            write(preferences, result)
        }
        return result
    }

    /** Writes back the generated device id/name on first run so peers see a stable identity. */
    suspend fun ensureIdentity(): AirClipSettings = update { it }

    private fun read(preferences: Preferences): AirClipSettings {
        val defaults = AirClipSettings()
        return AirClipSettings(
            deviceId = preferences[Keys.deviceId] ?: defaults.deviceId,
            deviceName = preferences[Keys.deviceName] ?: defaults.deviceName,
            serviceName = preferences[Keys.serviceName] ?: defaults.serviceName,
            listenPort = preferences[Keys.listenPort] ?: defaults.listenPort,
            syncImages = preferences[Keys.syncImages] ?: defaults.syncImages,
            honorSensitiveMarkers = preferences[Keys.honorSensitive] ?: defaults.honorSensitiveMarkers,
            keepHistory = preferences[Keys.keepHistory] ?: defaults.keepHistory,
            historyLimit = preferences[Keys.historyLimit] ?: defaults.historyLimit,
            maxTextKb = preferences[Keys.maxTextKb] ?: defaults.maxTextKb,
            maxImageKb = preferences[Keys.maxImageKb] ?: defaults.maxImageKb,
            debounceMs = preferences[Keys.debounceMs] ?: defaults.debounceMs,
            notifyOnReceive = preferences[Keys.notifyOnReceive] ?: defaults.notifyOnReceive,
            serviceEnabled = preferences[Keys.serviceEnabled] ?: defaults.serviceEnabled,
            startOnBoot = preferences[Keys.startOnBoot] ?: defaults.startOnBoot,
            autoApplyRemote = preferences[Keys.autoApply] ?: defaults.autoApplyRemote,
            toastOnReceive = preferences[Keys.toastOnReceive] ?: defaults.toastOnReceive,
            requireEncryption = preferences[Keys.requireEncryption] ?: defaults.requireEncryption,
            overlayAssist = preferences[Keys.overlayAssist] ?: defaults.overlayAssist,
            shizukuPolling = preferences[Keys.shizukuPolling] ?: defaults.shizukuPolling,
            shizukuPollMillis = preferences[Keys.shizukuPollMs] ?: defaults.shizukuPollMillis,
        ).normalised(fallbackName)
    }

    private fun write(preferences: MutablePreferences, value: AirClipSettings) {
        preferences[Keys.deviceId] = value.deviceId
        preferences[Keys.deviceName] = value.deviceName
        preferences[Keys.serviceName] = value.serviceName
        preferences[Keys.listenPort] = value.listenPort
        preferences[Keys.syncImages] = value.syncImages
        preferences[Keys.honorSensitive] = value.honorSensitiveMarkers
        preferences[Keys.keepHistory] = value.keepHistory
        preferences[Keys.historyLimit] = value.historyLimit
        preferences[Keys.maxTextKb] = value.maxTextKb
        preferences[Keys.maxImageKb] = value.maxImageKb
        preferences[Keys.debounceMs] = value.debounceMs
        preferences[Keys.notifyOnReceive] = value.notifyOnReceive
        preferences[Keys.serviceEnabled] = value.serviceEnabled
        preferences[Keys.startOnBoot] = value.startOnBoot
        preferences[Keys.autoApply] = value.autoApplyRemote
        preferences[Keys.toastOnReceive] = value.toastOnReceive
        preferences[Keys.requireEncryption] = value.requireEncryption
        preferences[Keys.overlayAssist] = value.overlayAssist
        preferences[Keys.shizukuPolling] = value.shizukuPolling
        preferences[Keys.shizukuPollMs] = value.shizukuPollMillis
    }

    /** Key names match the JSON property names in `AppSettings.cs` wherever the field exists there. */
    private object Keys {
        val deviceId = stringPreferencesKey("device_id")
        val deviceName = stringPreferencesKey("device_name")
        val serviceName = stringPreferencesKey("service_name")
        val listenPort = intPreferencesKey("listen_port")
        val syncImages = booleanPreferencesKey("sync_images")
        val honorSensitive = booleanPreferencesKey("honor_sensitive_markers")
        val keepHistory = booleanPreferencesKey("keep_history")
        val historyLimit = intPreferencesKey("history_limit")
        val maxTextKb = intPreferencesKey("max_text_kb")
        val maxImageKb = intPreferencesKey("max_image_kb")
        val debounceMs = intPreferencesKey("debounce_ms")
        val notifyOnReceive = booleanPreferencesKey("notify_on_receive")
        val serviceEnabled = booleanPreferencesKey("service_enabled")
        val startOnBoot = booleanPreferencesKey("start_on_boot")
        val autoApply = booleanPreferencesKey("auto_apply_remote")
        val toastOnReceive = booleanPreferencesKey("toast_on_receive")
        val requireEncryption = booleanPreferencesKey("require_encryption")
        val overlayAssist = booleanPreferencesKey("overlay_assist")
        val shizukuPolling = booleanPreferencesKey("shizuku_polling")
        val shizukuPollMs = longPreferencesKey("shizuku_poll_ms")
    }
}
