package com.yi.app.engine

/**
 * Pure prompt/format logic for Hy-MT2 — no native calls, unit-testable on host JVM.
 */
object TranslationPrompts {
    /** Official Hy-MT2 default translation command (README: Default Translation). */
    fun build(source: String, targetLang: String): String =
        "Translate the following text into $targetLang. " +
            "Note that you should only output the translated result without any additional explanation:\n\n" +
            source

    /** Stop sequences that bound output without being emitted into the stream. */
    val stopSequences = listOf("<|hy_Assistant|>", "<｜hy_Assistant｜>", "Translate the following")
}
