package com.yi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yi.app.ui.MainScreen
import com.yi.app.ui.TranslationViewModel

// 深海靛蓝 theme — indigo × sky blue, distinct from stock M3 purple.
private object YiPalette {
    val DeepIndigo = Color(0xFF26406B)   // primary (light scheme)
    val IndigoMid  = Color(0xFF4A6FA5)
    val SkyBlue    = Color(0xFF9FC6F8)
    val SkyBlueDeep= Color(0xFF24405F)   // primaryContainer in dark scheme
    val SkyBlueLt  = Color(0xFFA9C7EA)   // primaryContainer (light scheme)
    val Paper      = Color(0xFFFFFAF0)   // 「譯」字形纸白
    val InkDark    = Color(0xFF0E2244)
}

private fun YiLight() = lightColorScheme(
    primary = YiPalette.DeepIndigo,
    onPrimary = Color.White,
    primaryContainer = YiPalette.SkyBlueLt,
    onPrimaryContainer = YiPalette.InkDark,
    secondary = YiPalette.IndigoMid,
    secondaryContainer = YiPalette.SkyBlueLt,
    tertiary = YiPalette.IndigoMid,
)

private fun YiDark() = darkColorScheme(
    primary = YiPalette.SkyBlue,
    onPrimary = YiPalette.InkDark,
    primaryContainer = YiPalette.SkyBlueDeep,
    onPrimaryContainer = Color(0xFFD6E6FF),
    secondary = Color(0xFF8FB6E4),
    secondaryContainer = YiPalette.SkyBlueDeep,
    tertiary = Color(0xFF7CA9DC),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialSource = intent?.getStringExtra("source")
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) YiDark() else YiLight(),
            ) {
                Surface {
                    val vm: TranslationViewModel = viewModel(
                        factory = TranslationViewModel.Factory(applicationContext),
                    )
                    MainScreen(vm, initialSource)
                }
            }
        }
    }
}
