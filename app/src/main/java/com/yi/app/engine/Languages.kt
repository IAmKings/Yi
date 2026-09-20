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
}
