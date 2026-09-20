package com.yi.app.download

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Model registry — source of truth for which GGUFs the app can fetch. */
private val Context.modelStore by preferencesDataStore("models")

@Serializable
data class InstalledModel(
    val id: String,
    val fileName: String,
    val repo: String,
    val revision: String,
    val sizeBytes: Long,
    val sha256: String,
)

/** A downloadable model entry (mirrors the HF repo metadata). */
data class ModelSpec(
    val id: String,
    val repo: String = "tencent/Hy-MT2-1.8B-1.25Bit-GGUF",
    val revision: String = "9df5c824a00a744fb0512a29c640466f4d97dfb0",
    val fileName: String = "Hy-MT2-1.8B-1.25Bit.gguf",
    val sizeBytes: Long = 461_860_800L,
    // HF LFS oid of the file at `revision` — verified after download.
    val sha256: String = "cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93",
) {
    /** Base URL — huggingface.co, overridable to a mirror (e.g. hf-mirror.com). */
    fun baseUrl(host: String = "huggingface.co") = "https://$host/$repo/resolve/$revision"
    fun url(host: String = "huggingface.co") = "${baseUrl(host)}/$fileName"
}

object Models {
    /** The primary deliverable: Hy-MT2 1.8B @ 1.25-bit (440MB). */
    val HY_MT2_1_8B_1_25BIT = ModelSpec(
        id = "hymt2_1_8b_1_25bit",
    )

    val ALL = listOf(HY_MT2_1_8B_1_25BIT)
    fun byId(id: String) = ALL.firstOrNull { it.id == id }
}

enum class ModelState { NOT_INSTALLED, VERIFYING, VERIFIED, DOWNLOADED }

class ModelRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val key = stringPreferencesKey("installed_models")

    /** models live under filesDir/models/<spec.id>/<spec.fileName> */
    fun modelFile(spec: ModelSpec) =
        java.io.File(context.filesDir, "models/${spec.id}/${spec.fileName}")

    /** Persisted record of verified installations. */
    val installed: Flow<List<InstalledModel>> = context.modelStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<List<InstalledModel>>(it) }.getOrNull() }
            .orEmpty()
    }

    suspend fun markInstalled(model: InstalledModel) {
        context.modelStore.edit { prefs ->
            val list = prefs[key]?.let {
                runCatching { json.decodeFromString<List<InstalledModel>>(it) }.getOrNull()
            }.orEmpty().filterNot { it.id == model.id } + model
            prefs[key] = json.encodeToString(list)
        }
    }

    suspend fun markUninstalled(id: String) {
        context.modelStore.edit { prefs ->
            val list = prefs[key]?.let {
                runCatching { json.decodeFromString<List<InstalledModel>>(it) }.getOrNull()
            }.orEmpty().filterNot { it.id == id }
            prefs[key] = json.encodeToString(list)
        }
    }

    fun sha256File(file: java.io.File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** A downloaded file counts as installed only after SHA-256 matches the LFS oid. */
    fun isVerifiedFile(spec: ModelSpec): Boolean {
        val f = modelFile(spec)
        if (!f.exists() || f.length() != spec.sizeBytes) return false
        return sha256File(f).equals(spec.sha256, ignoreCase = true)
    }
}
