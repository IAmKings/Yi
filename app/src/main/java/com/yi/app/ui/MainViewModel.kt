package com.yi.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.yi.app.download.DownloadWorker
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

    private val _engineState = MutableStateFlow<EngineUiState>(EngineUiState.NoModel)
    val engineState: StateFlow<EngineUiState> = _engineState.asStateFlow()

    private val _transState = MutableStateFlow<TranslationUiState>(TranslationUiState.Idle)
    val transState: StateFlow<TranslationUiState> = _transState.asStateFlow()

    val installed = models.installed

    val settingsFlow: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private var translateJob: Job? = null
    private var loaded = false

    init {
        observeDownloadProgress()
        // Cold start with a verified model present → load immediately.
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

    fun tryLoad() = viewModelScope.launch {
        if (loaded) return@launch
        _engineState.value = EngineUiState.Loading(0f)
        try {
            val cfg = settingsFlow.value
            val result = engine.load(models.modelFile(spec).absolutePath, nCtx = cfg.contextSize) { p ->
                _engineState.value = EngineUiState.Loading(p)
            }
            loaded = true
            _engineState.value = EngineUiState.Ready(result.backend)
        } catch (e: Exception) {
            _engineState.value = EngineUiState.LoadFailed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun translate(source: String, sourceLang: String, targetLang: String) {
        if (!loaded || source.isBlank()) return
        translateJob?.cancel()
        translateJob = viewModelScope.launch {
            engine.translate(source, targetLang, maxTokens = settingsFlow.value.maxTokens,
                temperature = settingsFlow.value.temperature)
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
