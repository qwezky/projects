package dev.example.journal.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = InkPrimary,
    onPrimary = InkOnPrimary,
    primaryContainer = InkPrimaryContainer,
    onPrimaryContainer = InkOnPrimaryContainer,
    secondary = InkSecondary,
    onSecondary = InkOnSecondary,
    secondaryContainer = InkSecondaryContainer,
    onSecondaryContainer = InkOnSecondaryContainer,
    tertiary = InkTertiary,
    onTertiary = InkOnTertiary,
    error = InkError,
    onError = InkOnError,
    errorContainer = InkErrorContainer,
    onErrorContainer = InkOnErrorContainer,
    background = InkBackground,
    onBackground = InkOnBackground,
    surface = InkSurface,
    onSurface = InkOnSurface,
    surfaceVariant = InkSurfaceVariant,
    onSurfaceVariant = InkOnSurfaceVariant,
    outline = InkOutline,
)

private val DarkColors = darkColorScheme(
    primary = InkPrimaryDark,
    onPrimary = InkOnPrimaryDark,
    primaryContainer = InkPrimaryContainerDark,
    onPrimaryContainer = InkOnPrimaryContainerDark,
    secondary = InkSecondaryDark,
    onSecondary = InkOnSecondaryDark,
    secondaryContainer = InkSecondaryContainerDark,
    onSecondaryContainer = InkOnSecondaryContainerDark,
    tertiary = InkTertiaryDark,
    onTertiary = InkOnTertiaryDark,
    error = InkErrorDark,
    onError = InkOnErrorDark,
    errorContainer = InkErrorContainerDark,
    onErrorContainer = InkOnErrorContainerDark,
    background = InkBackgroundDark,
    onBackground = InkOnBackgroundDark,
    surface = InkSurfaceDark,
    onSurface = InkOnSurfaceDark,
    surfaceVariant = InkSurfaceVariantDark,
    onSurfaceVariant = InkOnSurfaceVariantDark,
    outline = InkOutlineDark,
)

/** 圆角统一放大一点，卡片看起来更"软"。 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 日志 App 的主题。
 *
 * @param dynamicColor 默认**关**：开着的话在 Android 12+ 会跟随壁纸取色，
 *   几个 App 的主题就分不出来了。想要系统取色可以显式传 true。
 */
@Composable
fun JournalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        shapes = AppShapes,
        content = content,
    )
}
