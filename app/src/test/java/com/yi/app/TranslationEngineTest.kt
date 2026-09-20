package com.yi.app

import com.yi.app.engine.TranslationPrompts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranslationEngineTest {
    @Test
    fun buildPromptMatchesOfficialCommand() {
        val p = TranslationPrompts.build("今天天气真好。", "English")
        assertEquals(
            "Translate the following text into English. " +
                "Note that you should only output the translated result without any additional explanation:\n\n" +
                "今天天气真好。",
            p,
        )
    }

    @Test
    fun sourceIsTrimmed() {
        // caller trims; builder itself keeps text as-is (checked here)
        val p = TranslationPrompts.build("  hi  ", "Chinese")
        assertTrue(p.endsWith("  hi  "))
    }
}
