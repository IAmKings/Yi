package com.yi.app.engine

import com.tensai.llamakt.LlamaEngine
import com.tensai.llamakt.GgufMetadata
import com.tensai.llamakt.SamplingParams
import com.tensai.llamakt.ChatMessage
import com.tensai.llamakt.TokenCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import android.util.Log
import kotlinx.coroutines.withContext

/** Events emitted while a translation runs. */
sealed interface TranslationEvent {
    data object Started : TranslationEvent
    /** [delta] is the newest token chunk; [fullText] the accumulated answer. */
    data class Token(val delta: String, val fullText: String) : TranslationEvent
    data class Done(
        val text: String,
        val sampledTokens: Int,
        val truncated: Boolean,
        val tokensPerSec: Long,
    ) : TranslationEvent
    data class Error(val message: String) : TranslationEvent
}

/**
 * Offline translation engine built on llama.kt.
 *
 * Hy-MT2 facts baked in here:
 *  - Chat/metadata: `hunyuan-dense` architecture, context_length 262144.
 *  - Quant: AngelSlim 1.25-bit (STQ / TQ-style), single file ~440MB.
 *  - Prompt: official Default Translation command (README "推理和部署").
 *  - Recommended sampling for 1.8B: temp 0.7 / top_p 0.6 / top_k 20.
 *
 * We deliberately do NOT rely on the embedded chat template being viable
 * inside llama.cpp (R2). The engine formats the official command itself and
 * runs it through the plain completion path; EOS + stop sequences bound it
 * (the model has no system prompt by design).
 */
class TranslationEngine {

    /** Native engine is created lazily — never on host-JVM unit tests. */
    private val engine: LlamaEngine by lazy { LlamaEngine() }

    @Volatile
    private var loadedPath: String? = null

    val isLoaded: Boolean get() = loadedPath != null
    @Volatile var ctxSize: Int = 4096
        private set

    data class LoadResult(
        val backend: String,
        val metadata: GgufMetadata,
    )

    /**
     * Load a GGUF. CPU-only by design — on UMA mobile SoCs llama.kt's own
     * measurements show the Vulkan path loses on both prefill and decode for
     * dense 1B-class models, and `nThreads = 0` pins the pool to big cores
     * (measured up to 6× faster than llama.cpp auto-detect on big.LITTLE).
     */
    suspend fun load(
        path: String,
        nCtx: Int = 4096,
        onProgress: (Float) -> Unit = {},
    ): LoadResult = withContext(Dispatchers.Default) {
        if (loadedPath == path) {
            val md = LlamaEngine.readMetadata(path) ?: error("metadata unreadable")
            return@withContext LoadResult(engine.activeBackend(), md)
        }
        Log.i("YiEngine", "loading model: $path (nCtx=$nCtx); meta=" + LlamaEngine.readMetadata(path))
        if (loadedPath != null) unload()
        // flashAttn "off" + default f16 KV: translation context is short and
        // KV memory is not a bottleneck for 1.8B; never combine a quantized KV
        // cache with flashAttn "off".
        engine.load(
            path = path,
            nGpuLayers = 0,
            nCtx = nCtx,
            nThreads = 0,
            onProgress = { p -> onProgress(p); true },
            kvCacheType = null,
            flashAttn = "off",
        )
        loadedPath = path
        ctxSize = nCtx
        val md = LlamaEngine.readMetadata(path) ?: error("GGUF metadata unreadable after load")
        LoadResult(engine.activeBackend(), md)
    }

    /** Build the official Hy-MT2 default translation prompt. */
    fun buildPrompt(source: String, targetLang: String): String =
        TranslationPrompts.build(source, targetLang)

    /**
     * Stream a translation. Emits token deltas; finishes on EOS, the nPredict
     * cap, or cancellation (which interrupts the native decode).
     *
     * Sampling: Hy-MT2 1.8B recommendations (temp 0.7 / top_p 0.6 / top_k 20),
     * with a minP floor to keep extreme-quantization noise down.
     */
    fun translate(
        source: String,
        targetLang: String,
        maxTokens: Int = 1024,
        temperature: Float = 0.7f,
    ): Flow<TranslationEvent> = callbackFlow {
        val path = loadedPath ?: run {
            trySend(TranslationEvent.Error("engine_not_loaded"))
            close()
            return@callbackFlow
        }
        trySend(TranslationEvent.Started)
        // Stop on the hy assistant-tag family and trailing artifacts the model
        // may emit; matched sequences are never emitted into the stream.
        val params = SamplingParams(
            nPredict = maxTokens,
            temperature = temperature,
            topK = 20,
            topP = 0.6f,
            minP = 0.05f,
        )
        val messages = listOf(ChatMessage("user", buildPrompt(source, targetLang)))
        val prompt = engine.formatChat(messages, enableThinking = false)
        Log.i("YiEngine", "chatPrompt=[$prompt]")
        // Context budget guard: envelope + source + predicted output must fit.
        // nPredict is the *output* budget the user asked for; anything left is
        // spent on the prompt. We translate only when it fits — truncating
        // source silently would produce a misleading "translation".
        val promptTokens = try { engine.tokenize(prompt).size } catch (_: Exception) { -1 }
        if (promptTokens >= 0) {
            val budget = ctxSize - maxTokens - 32 // margin: template/special tokens
            if (budget <= 0 || promptTokens > budget) {
                trySend(TranslationEvent.Error(
                    "输入过长 — 共 $promptTokens 个 token，" +
                    "超出当前上下文预算（ctx=$ctxSize，含输出预留 $maxTokens）。" +
                    "请缩短或分段处理（本模型约 1 token ≈ 0.6 汉字 / 0.75 英文单词）。"))
                close()
                return@callbackFlow
            }
        }
        val startedAt = System.nanoTime()
        // 覆写：用 chat() 流（模板渲染 + 原生解析）
        var sb = StringBuilder()
        val sampled = engine.completion(
            prompt,
            params,
            TokenCallback { tok ->
                sb.append(tok)
                trySend(TranslationEvent.Token(tok, sb.toString()))
            },
        )
        if (sampled < 0) {
            trySend(TranslationEvent.Error("native_decode_failed ($sampled)"))
        } else {
            val truncated = sampled >= maxTokens
            val text = sb.toString().trim()
            val tokensPerSec = sampled.coerceAtLeast(1) * 1_000_000_000L /
                (System.nanoTime() - startedAt).coerceAtLeast(1)
                trySend(TranslationEvent.Done(text, sampled, truncated, tokensPerSec))
        }
        close()
        awaitClose { engine.interrupt() }
    }.flowOn(Dispatchers.Default)

    fun interrupt() = engine.interrupt()

    fun unload() {
        if (loadedPath != null) {
            runCatching { engine.free() }
            loadedPath = null
        }
    }

    companion object {
        fun readMetadata(path: String): GgufMetadata? = LlamaEngine.readMetadata(path)
    }
}
