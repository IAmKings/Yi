package com.yi.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yi.app.engine.Languages

fun formatBytes(b: Long): String = when {
    b >= (1L shl 30) -> "%.2f GB".format(b / 1073741824.0)
    b >= (1L shl 20) -> "%.1f MB".format(b / 1048576.0)
    b >= 1024 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

@Composable
fun MainScreen(vm: TranslationViewModel, initialSource: String? = null) {
    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(vm::onDirectoryPicked) }
    val onPickModelDirectory = { dirPicker.launch(null) }

    val engineState by vm.engineState.collectAsState()
    val transState by vm.transState.collectAsState()
    val settings by vm.settingsFlow.collectAsState()

    var source by rememberSaveable { mutableStateOf(initialSource.orEmpty()) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                vm.persistSession(source)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // Debounced persistence: any source change mirrors the flash-even-before-stop view of the session
    androidx.compose.runtime.LaunchedEffect(source) {
        kotlinx.coroutines.delay(300)
        if (source.isBlank() && transState is TranslationUiState.Idle) return@LaunchedEffect
        vm.persistSession(source)
    }

    var sourceLangOpen by remember { mutableStateOf(false) }
    var targetLangOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("譯", style = MaterialTheme.typography.headlineSmall)

        // --- Engine / model state card ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (val s = engineState) {
                    is EngineUiState.NoModel -> {
                        Text("模型未安装 — Hy-MT2-1.8B (1.25bit, 440MB)")
                        if (vm.hasSavedDir()) {
                            Text(
                                "已选目录：${vm.savedDirSummary()}\n未在该目录找到可用 .gguf，只能去下载",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Text(
                                "优先从本机目录加载模型（离线）；目录里没有 .gguf 时才需要下载",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(onClick = onPickModelDirectory) { Text("从目录选择模型…") }
                        OutlinedButton(
                            onClick = vm::download,
                            enabled = !vm.hasSavedDir(),
                        ) { Text("没有模型？去下载 440MB") }
                        TextButton(onClick = { dirPicker.launch(null) }) { Text("查看/更改已选目录") }
                    }
                    is EngineUiState.Importing -> {
                        Text(s.message)
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                    is EngineUiState.Downloading -> {
                        Text("下载中 ${formatBytes(s.bytesDone)} / ${formatBytes(s.bytesTotal)}")
                        LinearProgressIndicator(
                            progress = { s.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is EngineUiState.DownloadFailed -> {
                        Text("下载失败：${s.message}")
                        TextButton(onClick = vm::download) { Text("重试") }
                    }
                    is EngineUiState.Loading -> {
                        Text("加载模型… ${(s.progress * 100).toInt()}%")
                        LinearProgressIndicator(
                            progress = { s.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is EngineUiState.Ready -> {
                        Text("就绪 · 后端：${s.backend}")
                        Text(
                            "Hy-MT2-1.8B · CPU · ${settings.contextSize} ctx",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { dirPicker.launch(null) }) { Text("从目录重新加载") }
                    }
                    is EngineUiState.LoadFailed -> {
                        Text("加载失败：${s.message}")
                        if (vm.hasSavedDir()) {
                            TextButton(onClick = onPickModelDirectory) { Text("重新选择目录") }
                        }
                        TextButton(onClick = { vm.tryLoad() }) { Text("重试") }
                    }
                }
            }
        }

        TextButton(onClick = {
            vm.clearSession()
            source = ""
        }) { Text("清空会话") }

        // --- language pair ---
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { sourceLangOpen = true }) {
                Text("${settings.sourceLang} ⇢ ${settings.targetLang}")
            }
            DropdownMenu(expanded = sourceLangOpen, onDismissRequest = { sourceLangOpen = false }) {
                Languages.ALL.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(lang.display) },
                        onClick = {
                            vm.setSourceLang(lang)
                            sourceLangOpen = false
                        },
                    )
                }
            }
            DropdownMenu(expanded = targetLangOpen, onDismissRequest = { targetLangOpen = false }) {
                Languages.ALL.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(lang.display) },
                        onClick = {
                            vm.setTargetLang(lang)
                            targetLangOpen = false
                        },
                    )
                }
            }
        }

        // --- source input ---
        OutlinedTextField(
            value = source,
            onValueChange = { source = it },
            modifier = Modifier.fillMaxWidth().weight(0.35f),
            placeholder = { Text("输入要翻译的文本…") },
        )

        // --- output / status ---
        Card(modifier = Modifier.fillMaxWidth().weight(0.5f)) {
            Column(
                Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (val t = transState) {
                    is TranslationUiState.Idle -> Text("译文将显示在这里", color = MaterialTheme.colorScheme.outline)
                    is TranslationUiState.Streaming -> Text(t.text)
                    is TranslationUiState.Done -> {
                        Text(t.text)
                        HorizontalDivider()
                        Text(
                            "${t.tokPerSec} tok/s" + if (t.truncated) " · 已截断" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    is TranslationUiState.Failed -> Text("失败：${t.message}", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // --- actions ---
        when (val st = engineState) {
            is EngineUiState.Ready -> {
                when (val t = transState) {
                    is TranslationUiState.Streaming -> OutlinedButton(onClick = vm::cancelTranslation) {
                        Text("停止")
                    }
                    else -> ExtendedFloatingActionButton(onClick = {
                        vm.translate(source, settings.sourceLang, settings.targetLang)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("翻译 → ${settings.targetLang}")
                    }
                }
            }
            else -> {}
        }
    }
}
