package com.yi.app.engine

/** The full Hy-MT2 1.8B language list (README "支持的语种"). */
object Languages {
    data class Lang(val code: String, val display: String)

    val ALL = listOf(
        Lang("Chinese", "中文"),
        Lang("English", "English"),
        Lang("French", "Français"),
        Lang("Portuguese", "Português"),
        Lang("Spanish", "Español"),
        Lang("Japanese", "日本語"),
        Lang("Turkish", "Türkçe"),
        Lang("Russian", "Русский"),
        Lang("Arabic", "العربية"),
        Lang("Korean", "한국어"),
        Lang("Thai", "ไทย"),
        Lang("Italian", "Italiano"),
        Lang("German", "Deutsch"),
        Lang("Vietnamese", "Tiếng Việt"),
        Lang("Malay", "Bahasa Melayu"),
        Lang("Indonesian", "Bahasa Indonesia"),
        Lang("Filipino", "Filipino"),
        Lang("Hindi", "हिन्दी"),
        Lang("Traditional Chinese", "繁體中文"),
        Lang("Polish", "Polski"),
        Lang("Czech", "Čeština"),
        Lang("Dutch", "Nederlands"),
        Lang("Khmer", "ខ្មែរ"),
        Lang("Burmese", "မြန်မာ"),
        Lang("Persian", "فارسی"),
        Lang("Gujarati", "ગુજરાતી"),
        Lang("Urdu", "اردو"),
        Lang("Telugu", "తెలుగు"),
        Lang("Marathi", "मराठी"),
        Lang("Hebrew", "עברית"),
        Lang("Bengali", "বাংলা"),
        Lang("Tamil", "தமிழ்"),
        Lang("Ukrainian", "Українська"),
        Lang("Tibetan", "བོད་སྐད"),
        Lang("Kazakh", "Қазақша"),
        Lang("Mongolian", "Монгол"),
        Lang("Uyghur", "ئۇيغۇرچە"),
        Lang("Cantonese", "粤語"),
    )

    /**
     * Script-based language guess for the "自动" source option.
     * NOTE: the Hy-MT2 prompt does NOT require a source_lang at all —
     * only target_lang — so the model does its own implicit detection
     * at decode time; this heuristic is for UI display / prompt text only.
     */
    fun detectSource(text: String): Lang {
        if (text.isBlank()) return ALL.first()
        var han = 0; var kana = 0; var hangul = 0; var latin = 0
        for (ch in text) when (ch) {
            in 'ぁ'..'ー'  -> kana++
            in '㐀'..'鿿'  -> han++
            in 'ᄀ'..'ᇿ'  -> hangul++
            in '가'..'힣'  -> hangul++
            in 'A'..'Z', in 'a'..'z' -> latin++
        }
        val cjk = han + hangul
        return when {
            kana > 0          -> find("Japanese")
            hangul > 0        -> find("Korean")
            han >= latin      -> find("Chinese")
            latin > 0         -> find("English")
            else              -> find("English")
        }
    }

    private fun find(code: String): Lang = ALL.first { it.code == code }
}
