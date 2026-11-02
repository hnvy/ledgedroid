package app.ledgedroid.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * The look borrows from a paper accounting ledger: pale green columnar paper, dark ink,
 * blue ruling, red ink for negative amounts, and the accountant's double underline under a total.
 */

@Immutable
data class LedgerColors(
    val ink: Color,
    val mutedInk: Color,
    val redInk: Color,
    val rule: Color,
    val hairline: Color,
    val guide: Color,
    /** True for the dark theme, to pick branch colours that suit it (see BranchColors). */
    val dark: Boolean,
    /** The branch colour for <unbudgeted> and accounts without a colour yet. */
    val otherBranch: Color,
    /** Pinned accounts: a highlighter's yellow. */
    val pin: Color,
)

private val LightLedger = LedgerColors(
    ink = Color(0xFF1F2A24),
    mutedInk = Color(0xFF5E6E64),
    redInk = Color(0xFFB0342C),
    rule = Color(0xFF2F5F8F),
    hairline = Color(0x262F5F8F),
    guide = Color(0x1A1F2A24),
    dark = false,
    otherBranch = Color(0xFF6E7E74),
    pin = Color(0xFFC99A2E),
)

private val DarkLedger = LedgerColors(
    ink = Color(0xFFDDE6DF),
    mutedInk = Color(0xFF93A399),
    redInk = Color(0xFFEE8E86),
    rule = Color(0xFF8DB3DA),
    hairline = Color(0x338DB3DA),
    guide = Color(0x1FDDE6DF),
    dark = true,
    otherBranch = Color(0xFF93A399),
    pin = Color(0xFFE8C35A),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2F5F8F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E2F1),
    onPrimaryContainer = Color(0xFF0E2A45),
    secondary = Color(0xFF4F6357),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E2F1),
    onSecondaryContainer = Color(0xFF0E2A45),
    background = Color(0xFFF2F5EF),
    onBackground = Color(0xFF1F2A24),
    surface = Color(0xFFF2F5EF),
    onSurface = Color(0xFF1F2A24),
    surfaceVariant = Color(0xFFE2EADF),
    onSurfaceVariant = Color(0xFF5E6E64),
    surfaceContainerLowest = Color(0xFFFAFCF8),
    surfaceContainerLow = Color(0xFFECF1E8),
    surfaceContainer = Color(0xFFE7EDE3),
    surfaceContainerHigh = Color(0xFFE1E8DD),
    surfaceContainerHighest = Color(0xFFDBE3D7),
    outline = Color(0xFF8A9A90),
    outlineVariant = Color(0xFFC6D2C9),
    error = Color(0xFFB0342C),
    onError = Color.White,
    errorContainer = Color(0xFFF6DAD6),
    onErrorContainer = Color(0xFF5A1410),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8DB3DA),
    onPrimary = Color(0xFF0E2A45),
    primaryContainer = Color(0xFF24476B),
    onPrimaryContainer = Color(0xFFD3E2F1),
    secondary = Color(0xFFB4C7BA),
    onSecondary = Color(0xFF1F2A24),
    secondaryContainer = Color(0xFF24476B),
    onSecondaryContainer = Color(0xFFD3E2F1),
    background = Color(0xFF111915),
    onBackground = Color(0xFFDDE6DF),
    surface = Color(0xFF111915),
    onSurface = Color(0xFFDDE6DF),
    surfaceVariant = Color(0xFF26312B),
    onSurfaceVariant = Color(0xFF93A399),
    surfaceContainerLowest = Color(0xFF0C120F),
    surfaceContainerLow = Color(0xFF161F1A),
    surfaceContainer = Color(0xFF1B2520),
    surfaceContainerHigh = Color(0xFF212C26),
    surfaceContainerHighest = Color(0xFF28342D),
    outline = Color(0xFF6E7E74),
    outlineVariant = Color(0xFF3A4840),
    error = Color(0xFFEE8E86),
    onError = Color(0xFF5A1410),
    errorContainer = Color(0xFF5A1D18),
    onErrorContainer = Color(0xFFF6DAD6),
)

private val Ledgerly = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontFamily = FontFamily.Serif),
    )
}

/** Amount text: tabular figures so columns of numbers line up digit for digit. */
val NumberStyle = TextStyle(fontFeatureSettings = "tnum", fontSize = 15.sp)
val NumberStyleStrong = NumberStyle.copy(fontWeight = FontWeight.SemiBold)

val LocalLedgerColors = staticCompositionLocalOf { LightLedger }

@Composable
fun LedgerTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLedgerColors provides if (dark) DarkLedger else LightLedger) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = Ledgerly,
            content = content,
        )
    }
}
