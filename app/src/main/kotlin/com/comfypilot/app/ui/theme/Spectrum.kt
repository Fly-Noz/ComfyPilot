package com.comfypilot.app.ui.theme

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * 光谱（Spectrum）设计语言：色晕背景 + 玻璃卡面 + 青绿→紫→粉的渐变强调。
 *
 * 页面只读 [Spectrum.colors]，不写死颜色；深色/浅色是同一套 token 的两组取值。
 */
enum class ThemeMode(val storageValue: String, val label: String) {
    System("system", "跟随系统"),
    Dark("dark", "深色"),
    Light("light", "浅色"),
    ;

    companion object {
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: System
    }
}

/** 纯本机外观偏好：不进入会话协议，也不影响连接。 */
class AppearanceStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ThemeMode = ThemeMode.fromStorage(prefs.getString(KEY_MODE, null))

    fun save(mode: ThemeMode) {
        prefs.edit().putString(KEY_MODE, mode.storageValue).apply()
    }

    private companion object {
        const val PREFS_NAME = "appearance"
        const val KEY_MODE = "mode"
    }
}

@Immutable
data class SpectrumColors(
    val isDark: Boolean,
    val background: Color,
    val glowTop: Color,
    val glowSide: Color,
    val glowBottom: Color,
    val card: Color,
    val cardStrong: Color,
    val dialog: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val textMuted: Color,
    val textDim: Color,
    val accent: Color,
    val accentInk: Color,
    val accentSoft: Color,
    val accentBorder: Color,
    val ok: Color,
    val okSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val err: Color,
    val errSoft: Color,
    val gradient: List<Color>,
    val barTrack: Color,
    val heroText: Color,
) {
    /** 强调渐变：主按钮、选中芯片、进度条、数值。 */
    fun gradientBrush(): Brush = Brush.linearGradient(gradient)

    fun gradientStyle(): TextStyle = TextStyle(brush = gradientBrush())
}

internal val DarkSpectrum = SpectrumColors(
    isDark = true,
    background = Color(0xFF0A0B10),
    glowTop = Color(0x618B5CF6),
    glowSide = Color(0x385EEAD4),
    glowBottom = Color(0x3DF472B6),
    card = Color(0x0EFFFFFF),
    cardStrong = Color(0x18FFFFFF),
    dialog = Color(0xFF14151B),
    border = Color(0x17FFFFFF),
    borderStrong = Color(0x29FFFFFF),
    text = Color(0xFFF3F4F8),
    textMuted = Color(0xFFA9AEBE),
    textDim = Color(0xFF7C8196),
    accent = Color(0xFFA78BFA),
    accentInk = Color(0xFF0B0614),
    accentSoft = Color(0x298B5CF6),
    accentBorder = Color(0x708B5CF6),
    ok = Color(0xFF5EEAD4),
    okSoft = Color(0x1A5EEAD4),
    warn = Color(0xFFFBBF24),
    warnSoft = Color(0x1AFBBF24),
    err = Color(0xFFFB7185),
    errSoft = Color(0x1FFB7185),
    gradient = listOf(Color(0xFF5EEAD4), Color(0xFF8B5CF6), Color(0xFFF472B6)),
    barTrack = Color(0x14FFFFFF),
    heroText = Color(0xFFF2F4F7),
)

internal val LightSpectrum = SpectrumColors(
    isDark = false,
    background = Color(0xFFF5F4F8),
    glowTop = Color(0x428B5CF6),
    glowSide = Color(0x4D2DD4BF),
    glowBottom = Color(0x3DF472B6),
    card = Color(0xA6FFFFFF),
    cardStrong = Color(0xD9FFFFFF),
    dialog = Color(0xFFFFFFFF),
    border = Color(0x1714151A),
    borderStrong = Color(0x2914151A),
    text = Color(0xFF14151A),
    textMuted = Color(0xFF545666),
    textDim = Color(0xFF6F7182),
    accent = Color(0xFF7C3AED),
    accentInk = Color(0xFFFFFFFF),
    accentSoft = Color(0x1A7C3AED),
    accentBorder = Color(0x4D7C3AED),
    ok = Color(0xFF0B7F6A),
    okSoft = Color(0x140B7F6A),
    warn = Color(0xFFA16207),
    warnSoft = Color(0x14A16207),
    err = Color(0xFFBE123C),
    errSoft = Color(0x14BE123C),
    gradient = listOf(Color(0xFF2DD4BF), Color(0xFF7C3AED), Color(0xFFDB2777)),
    barTrack = Color(0x1414151A),
    heroText = Color(0xFFF2F4F7),
)

val LocalSpectrumColors = staticCompositionLocalOf { DarkSpectrum }

object Spectrum {
    val colors: SpectrumColors
        @Composable
        @ReadOnlyComposable
        get() = LocalSpectrumColors.current
}

val SpectrumSans = FontFamily.SansSerif
val SpectrumMono = FontFamily.Monospace

@Composable
fun ComfyPilotTheme(
    mode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }
    val colors = if (dark) DarkSpectrum else LightSpectrum

    // 系统栏跟随主题：深色底配浅色图标，浅色底配深色图标；手动切换时也立刻生效。
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !colors.isDark
            controller.isAppearanceLightNavigationBars = !colors.isDark
        }
    }

    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.accentInk,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.dialog,
            onSurface = colors.text,
            surfaceVariant = colors.cardStrong,
            onSurfaceVariant = colors.textMuted,
            error = colors.err,
            errorContainer = colors.errSoft,
            onErrorContainer = colors.err,
            outline = colors.borderStrong,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.accentInk,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.dialog,
            onSurface = colors.text,
            surfaceVariant = colors.cardStrong,
            onSurfaceVariant = colors.textMuted,
            error = colors.err,
            errorContainer = colors.errSoft,
            onErrorContainer = colors.err,
            outline = colors.borderStrong,
        )
    }

    val typography = MaterialTheme.typography.copy(
        titleLarge = TextStyle(
            fontFamily = SpectrumSans,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            letterSpacing = (-0.3).sp,
        ),
        titleMedium = TextStyle(
            fontFamily = SpectrumSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            letterSpacing = (-0.1).sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = SpectrumSans,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
        ),
        bodySmall = TextStyle(
            fontFamily = SpectrumSans,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = SpectrumMono,
            fontSize = 10.sp,
            letterSpacing = 0.6.sp,
        ),
    )

    CompositionLocalProvider(LocalSpectrumColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            content = content,
        )
    }
}