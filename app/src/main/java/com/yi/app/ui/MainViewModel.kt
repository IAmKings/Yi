package com.yi.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.yi.app.download.DownloadWorker
import com.yi.app.download.LocalModelSource
import com.yi.app.download.InstalledModel
import com.yi.app.download.ModelRepository
import com.yi.app.download.Models
import com.yi.app.download.ModelSpec
import com.yi.app.engine.TranslationEngine
import com.yi.app.engine.TranslationEvent
import com.yi.app.settings.AppSettings
import com.yi.app.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface EngineUiState {
    data object NoModel : EngineUiState
    data class Downloading(val progress: Float, val bytesDone: Long, val bytesTotal: Long) : EngineUiState
    data class DownloadFailed(val message: String) : EngineUiState
    data class Importing(val message: String) : EngineUiState
    data class Loading(val progress: Float) : EngineUiState
    data class Ready(val backend: String) : EngineUiState
    data class LoadFailed(val message: String) : EngineUiState
}

sealed interface TranslationUiState {
    data object Idle : TranslationUiState
    data class Streaming(val text: String) : TranslationUiState
    data class Done(val text: String, val tokPerSec: Long, val truncated: Boolean) : TranslationUiState
    data class Failed(val message: String) : TranslationUiState
}

class TranslationViewModel(
    private val appContext: android.content.Context,
    private val engine: TranslationEngine,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val spec: ModelSpec = Models.HY_MT2_1_8B_1_25BIT
    /** 临时排查：设为 true 强制贪心（temp=0）以隔离采样噪声。 */
    private val DEBUG_GREEDY = false
    private fun debugTemp(): Float = if (DEBUG_GREEDY) 0f else settingsFlow.value.temperature

    private val _engineState = MutableStateFlow<EngineUiState>(EngineUiState.NoModel)
    val engineState: StateFlow<EngineUiState> = _engineState.asStateFlow()

    private val _transState = MutableStateFlow<TranslationUiState>(TranslationUiState.Idle)
    val transState: StateFlow<TranslationUiState> = _transState.asStateFlow()

    val installed = models.installed

    val settingsFlow: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private var translateJob: Job? = null
    private var loaded = false
    private val local = LocalModelSource(appContext)
    @Volatile private var activeModelPath: String? = null

    init {
        observeDownloadProgress()
        // Cold start priority: (1) app-storage verified model → load immediately;
        // (2) saved directory → re-import from it; (3) nothing → stay NoModel
        // (the UI shows directory-pick first, download second).
        viewModelScope.launch {
            installed.collect { list ->
                val mine = list.firstOrNull { it.id == spec.id }
                when {
                    mine != null && !loaded -> tryLoad()
                    mine == null && loaded -> unload()
                }
            }
        }
    }

    @Volatile var pendingSource: String? = null

    fun hasSavedDir(): Boolean = local.savedDirUri() != null

    fun consumePendingSource(): String? {
        val v = pendingSource
        pendingSource = null
        return v
    }

    fun savedDirSummary(): String {
        val uri = local.savedDirUri()?.lastPathSegment ?: return "未选择"
        return java.net.URLDecoder.decode(uri, "UTF-8")
    }

    fun pickModelDirectory() {
        _engineState.value = EngineUiState.Importing("等待选择目录…")
    }

    /** Called from the UI when the SAF tree picker returns a directory. */
    fun onDirectoryPicked(uri: android.net.Uri) {
        local.saveDir(uri)
        viewModelScope.launch {
            _engineState.value = EngineUiState.Importing("扫描目录…")
            val docs = local.listGgufs()
            when {
                docs.isEmpty() -> {
                    _engineState.value = EngineUiState.NoModel
                }
                docs.any { it.name.equals(spec.fileName, ignoreCase = true) } -> {
                    _engineState.value = EngineUiState.Importing("正在通过目录复用模型…")
                    importSpec(docs.first { it.name.equals(spec.fileName, ignoreCase = true) })
                }
                else -> {
                    _engineState.value = EngineUiState.Importing("发现 ${docs.size} 个 gguf，正在导入…")
                    importGeneric(docs.first())
                }
            }
        }
    }

    private suspend fun importSpec(doc: androidx.documentfile.provider.DocumentFile) {
        try {
            // 1) Fast path: reuse the file in place when the direct path is
            //    actually readable (scoped storage denies raw reads for
            //    non-media files like .gguf → expect EACCES on /sdcard).
            // 2) Fallback: stream-copy the SAF grant into app storage.
            var file: java.io.File? = null
            val direct = local.directPath(doc)
            if (direct != null) {
                val f = java.io.File(direct)
                try {
                    f.inputStream().use { it.readNBytes(64) } // readability probe
                    file = f
                } catch (_: Exception) {
                    // EACCES style denial → fall through to the SAF copy
                    file = null
                }
            }
            if (file == null) {
                _engineState.value = EngineUiState.Importing("正在拷贝模型到应用目录（约 440MB）…")
                file = local.copyIntoAppDir(doc, spec.fileName)
            }
            file = file!!
            if (!file.exists()) throw java.io.IOException("model file not accessible")
            if (file.length() != spec.sizeBytes) throw java.io.IOException(
                "文件大小不匹配：读取到 ${file.length()} 字节，预期 ${spec.sizeBytes}",
            )
            models.markInstalled(
                InstalledModel(
                    id = spec.id,
                    fileName = spec.fileName,
                    repo = spec.repo,
                    revision = spec.revision,
                    sizeBytes = file.length(),
                    sha256 = local.sha256File(file),
                ),
            )
        } catch (e: Exception) {
            _engineState.value = EngineUiState.LoadFailed("导入失败：${e.message}")
        }
    }

    private suspend fun importGeneric(doc: androidx.documentfile.provider.DocumentFile) {
        try {
            val name = doc.name ?: "model.gguf"
            _engineState.value = EngineUiState.Importing("正在拷贝 $name …")
            val dest = local.copyIntoAppDir(doc, name)
            tryLoad(importedPath = dest.absolutePath)
        } catch (e: Exception) {
            _engineState.value = EngineUiState.LoadFailed("导入失败：${e.message}")
        }
    }

    fun download() {
        DownloadWorker.enqueue(appContext, spec, settingsFlow.value.downloadHost)
    }

    fun setSourceLang(lang: com.yi.app.engine.Languages.Lang) = viewModelScope.launch {
        settings.setSourceLang(lang.code)
    }

    fun setTargetLang(lang: com.yi.app.engine.Languages.Lang) = viewModelScope.launch {
        settings.setTargetLang(lang.code)
    }

    private fun observeDownloadProgress() {
        val wm = WorkManager.getInstance(appContext)
        viewModelScope.launch {
            wm.getWorkInfosForUniqueWorkFlow("download-${spec.id}").collect { list ->
                val w = list.firstOrNull() ?: return@collect
                when (w.state) {
                    WorkInfo.State.RUNNING -> {
                        val p = w.progress
                        _engineState.value = EngineUiState.Downloading(
                            p.getFloat(DownloadWorker.KEY_PROGRESS, 0f),
                            p.getLong(DownloadWorker.KEY_BYTES_DONE, 0),
                            p.getLong(DownloadWorker.KEY_BYTES_TOTAL, spec.sizeBytes),
                        )
                    }
                    WorkInfo.State.FAILED -> _engineState.value =
                        EngineUiState.DownloadFailed(
                            w.outputData.getString(DownloadWorker.KEY_ERROR) ?: "download failed",
                        )
                    else -> Unit
                }
            }
        }
    }

    fun tryLoad(importedPath: String? = null) = viewModelScope.launch {
        if (loaded) return@launch
        importedPath?.let { activeModelPath = it }
        _engineState.value = EngineUiState.Loading(0f)
        val path = resolveModelPath()
            ?: run {
                _engineState.value = EngineUiState.NoModel
                return@launch
            }
        try {
            val cfg = settingsFlow.value
            val result = engine.load(path, nCtx = cfg.contextSize) { p ->
                _engineState.value = EngineUiState.Loading(p)
            }
            loaded = true
            _engineState.value = EngineUiState.Ready(result.backend)
        } catch (e: Exception) {
            _engineState.value = EngineUiState.LoadFailed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Priority: explicit import path → verified spec file → largest imported gguf. */
    private fun resolveModelPath(): String? {
        activeModelPath?.let { return it }
        val specFile = models.modelFile(spec)
        if (specFile.isFile && specFile.length() == spec.sizeBytes) return specFile.absolutePath
        val imported = java.io.File(appContext.filesDir, "models/imported")
            .listFiles { f -> f.name.endsWith(".gguf", ignoreCase = true) }
            ?.maxByOrNull { it.length() }
        if (imported != null && imported.length() > 1_000_000L) return imported.absolutePath
        if (specFile.isFile) return specFile.absolutePath // last resort: try, verify by load
        return null
    }

    fun translate(source: String, sourceLang: String, targetLang: String) {
        if (!loaded || source.isBlank()) return
        translateJob?.cancel()
        translateJob = viewModelScope.launch {
            engine.translate(source, targetLang, maxTokens = settingsFlow.value.maxTokens,
                temperature = debugTemp())  // 临时对照：贪心解码排查采样退化
                .collect { ev ->
                    when (ev) {
                        is TranslationEvent.Started -> _transState.value = TranslationUiState.Streaming("")
                        is TranslationEvent.Token -> _transState.value = TranslationUiState.Streaming(ev.fullText)
                        is TranslationEvent.Done ->
                            _transState.value = TranslationUiState.Done(ev.text, ev.tokensPerSec, ev.truncated)
                        is TranslationEvent.Error ->
                            _transState.value = TranslationUiState.Failed(ev.message)
                    }
                }
        }
    }

    fun cancelTranslation() {
        translateJob?.cancel()
        engine.interrupt()
        _transState.value = TranslationUiState.Idle
    }

    private fun unload() {
        viewModelScope.launch {
            engine.unload()
            loaded = false
            _engineState.value = EngineUiState.NoModel
        }
    }

    class Factory(private val appContext: android.content.Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val ctx = appContext.applicationContext
            return TranslationViewModel(
                appContext = ctx,
                engine = TranslationEngine(),
                models = ModelRepository(ctx),
                settings = SettingsRepository(ctx),
            ) as T
        }
    }
}
