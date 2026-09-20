package com.yi.app.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.settingsStore by preferencesDataStore("settings")

data class AppSettings(
    val sourceLang: String = "English",
    val targetLang: String = "Chinese",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 1024,
    val contextSize: Int = 4096,
    val downloadHost: String = "huggingface.co",
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val SOURCE = stringPreferencesKey("source_lang")
        val TARGET = stringPreferencesKey("target_lang")
        val TEMP = floatPreferencesKey("temperature")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val CTX = intPreferencesKey("context_size")
        val HOST = stringPreferencesKey("download_host")
        val LAST_SOURCE = stringPreferencesKey("last_source")
        val LAST_OUTPUT = stringPreferencesKey("last_output")
        val LAST_STATS = stringPreferencesKey("last_stats")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { p ->
        AppSettings(
            sourceLang = p[Keys.SOURCE] ?: "English",
            targetLang = p[Keys.TARGET] ?: "Chinese",
            temperature = p[Keys.TEMP] ?: 0.7f,
            maxTokens = p[Keys.MAX_TOKENS] ?: 1024,
            contextSize = p[Keys.CTX] ?: 4096,
            downloadHost = p[Keys.HOST] ?: "huggingface.co",
        )
    }

    suspend fun setSourceLang(v: String) = context.settingsStore.edit { it[Keys.SOURCE] = v }
    suspend fun setTargetLang(v: String) = context.settingsStore.edit { it[Keys.TARGET] = v }
    suspend fun setTemperature(v: Float) = context.settingsStore.edit { it[Keys.TEMP] = v }
    suspend fun setMaxTokens(v: Int) = context.settingsStore.edit { it[Keys.MAX_TOKENS] = v }
    suspend fun setContextSize(v: Int) = context.settingsStore.edit { it[Keys.CTX] = v }
    suspend fun setDownloadHost(v: String) = context.settingsStore.edit { it[Keys.HOST] = v }

    // ---- last translation snapshot (cross-process restore) ----
    private val L_SRC = stringPreferencesKey("last_source")
    private val L_OUT = stringPreferencesKey("last_output")
    private val L_STA = stringPreferencesKey("last_stats")

    /** Blocking read, used once at ViewModel init only. */
    fun lastTranslationBlocking(): Triple<String, String, String> {
        val prefs = runBlocking { context.settingsStore.data.first() }
        return Triple(
            prefs[Keys.LAST_SOURCE] ?: "",
            prefs[Keys.LAST_OUTPUT] ?: "",
            prefs[Keys.LAST_STATS] ?: "",
        )
    }

    fun saveLastTranslationBlocking(source: String, output: String, stats: String) {
        runBlocking {
            context.settingsStore.edit {
                it[Keys.LAST_SOURCE] = source
                it[Keys.LAST_OUTPUT] = output
                it[Keys.LAST_STATS] = stats
            }
        }
    }
}
