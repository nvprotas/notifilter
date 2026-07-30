package io.github.nvprotas.notifilter.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF176B45),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA5F2C5),
    onPrimaryContainer = Color(0xFF002113),
    secondary = Color(0xFF4E6355),
    surface = Color(0xFFF8FAF8),
    surfaceVariant = Color(0xFFDCE5DD),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF89D6AA),
    onPrimary = Color(0xFF003823),
    primaryContainer = Color(0xFF005234),
    onPrimaryContainer = Color(0xFFA5F2C5),
    secondary = Color(0xFFB5CCBC),
)

@Composable
fun NotifilterTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkTheme) {
        DarkColors
    } else {
        LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}

