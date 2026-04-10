package co.crackn.kompressor.sample.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = Teal40,
    onPrimary = Neutral99,
    primaryContainer = Teal90,
    onPrimaryContainer = Teal10,
    secondary = Green40,
    onSecondary = Neutral99,
    secondaryContainer = Green90,
    onSecondaryContainer = Neutral10,
    background = Neutral99,
    onBackground = Neutral10,
    surface = Neutral95,
    onSurface = Neutral10,
    error = Error40,
    onError = Neutral99,
    errorContainer = Error90,
)

private val DarkColorScheme = darkColorScheme(
    primary = Teal80,
    onPrimary = Teal20,
    primaryContainer = Teal30,
    onPrimaryContainer = Teal90,
    secondary = Green80,
    onSecondary = Neutral10,
    secondaryContainer = Green40,
    onSecondaryContainer = Green90,
    background = Neutral10,
    onBackground = Neutral90,
    surface = Teal10,
    onSurface = Neutral90,
    error = Error80,
    onError = Neutral10,
    errorContainer = Error40,
)

@Composable
fun KompressorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        content = content,
    )
}
