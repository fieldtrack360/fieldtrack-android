package com.field360.fieldtrack.sample.screen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The sample's look: a terminal someone is running a field test from.
 *
 * It is a deliberate choice for a diagnostic tool rather than a joke. Every screen here
 * exists to be read fast while a phone is on a car mount — monospace keeps numbers in
 * fixed columns so a changing digit is visible without reading the label, and a dark
 * ground with one saturated accent means the eye lands on the value that moved. The
 * severity colours below are the whole palette: green is "as expected", amber is "worth a
 * look", red is "this run is not recording what you think it is".
 *
 * Nothing here is SDK API. It is the host app's presentation, kept in one file so a
 * reader looking for how the SDK works never has to wade through styling to find it.
 */
object Hack {

    /** Near-black with a green cast, so the accent never sits on a neutral grey. */
    val Bg: Color = Color(0xFF060A06)

    /** One step up from [Bg]: card ground. */
    val Surface: Color = Color(0xFF0C130C)

    /** Two steps up: rows inside a card that need separating from it. */
    val SurfaceHi: Color = Color(0xFF121C12)

    /** The accent. Phosphor green, and the only fully saturated colour in normal use. */
    val Green: Color = Color(0xFF39FF14)

    /** Body text. Green-tinted white — pure white against [Bg] glares at night. */
    val Text: Color = Color(0xFFC6F5C6)

    /** Labels, units, anything the reader is not scanning for. */
    val Dim: Color = Color(0xFF5F8C5F)

    /** Borders and rules. */
    val Line: Color = Color(0xFF1E3B1E)

    /** "Worth a look" — degraded, waived, throttled, suspended. */
    val Amber: Color = Color(0xFFFFC043)

    /** "Not recording what you think it is." */
    val Red: Color = Color(0xFFFF4D4D)

    /** Reserved for values that are informational rather than judged, e.g. identifiers. */
    val Cyan: Color = Color(0xFF3DDCFF)
}

private val Mono = FontFamily.Monospace

/**
 * Monospace everywhere, including inside Material's own dialogs and menus.
 *
 * Copied style by style rather than set once because M3's [Typography] has no default
 * font family to override — a half-converted typography is how a dialog ends up in a
 * proportional face in the middle of a terminal.
 */
fun hackerTypography(base: Typography = Typography()): Typography = base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = Mono),
    displayMedium = base.displayMedium.copy(fontFamily = Mono),
    displaySmall = base.displaySmall.copy(fontFamily = Mono),
    headlineLarge = base.headlineLarge.copy(fontFamily = Mono),
    headlineMedium = base.headlineMedium.copy(fontFamily = Mono),
    headlineSmall = base.headlineSmall.copy(fontFamily = Mono),
    titleLarge = base.titleLarge.copy(fontFamily = Mono, fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontFamily = Mono, fontWeight = FontWeight.Bold),
    titleSmall = base.titleSmall.copy(fontFamily = Mono, fontWeight = FontWeight.Bold),
    bodyLarge = base.bodyLarge.copy(fontFamily = Mono),
    bodyMedium = base.bodyMedium.copy(fontFamily = Mono),
    bodySmall = base.bodySmall.copy(fontFamily = Mono),
    labelLarge = base.labelLarge.copy(fontFamily = Mono, fontWeight = FontWeight.Bold),
    labelMedium = base.labelMedium.copy(fontFamily = Mono),
    labelSmall = base.labelSmall.copy(fontFamily = Mono),
)

/** Material's own surfaces — dialogs, toasts, menus — dressed as the same terminal. */
fun hackerColorScheme(): ColorScheme = darkColorScheme(
    primary = Hack.Green,
    onPrimary = Color.Black,
    primaryContainer = Hack.SurfaceHi,
    onPrimaryContainer = Hack.Green,
    secondary = Hack.Cyan,
    onSecondary = Color.Black,
    background = Hack.Bg,
    onBackground = Hack.Text,
    surface = Hack.Surface,
    onSurface = Hack.Text,
    surfaceVariant = Hack.SurfaceHi,
    onSurfaceVariant = Hack.Dim,
    error = Hack.Red,
    onError = Color.Black,
    outline = Hack.Line,
    outlineVariant = Hack.Line,
)

/** The one text style everything else is a variation of. */
val MonoBody: TextStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp, color = Hack.Text)

/**
 * A bordered panel with a bracketed caption sitting on its top rule.
 *
 * The caption is drawn over the border rather than inside the card so the panels read as
 * one continuous frame down the screen, the way a TUI does.
 */
@Composable
fun TerminalCard(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = Hack.Green,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "[ ${title.uppercase()} ]",
                style = MonoBody.copy(
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                ),
            )
            trailing?.let {
                Text(it, style = MonoBody.copy(color = Hack.Dim, fontSize = 11.sp))
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(2.dp),
            colors = CardDefaults.cardColors(containerColor = Hack.Surface),
            border = BorderStroke(1.dp, accent.copy(alpha = ACCENT_BORDER_ALPHA)),
        ) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content,
            )
        }
    }
}

/**
 * `label ......... value`, with the leader dots that make a column of these scannable.
 *
 * The dots are not decoration: without them the eye has to track across empty space to
 * pair a label with its value, and on a moving vehicle it loses the row.
 */
@Composable
fun KeyValue(
    label: String,
    value: String,
    valueColor: Color = Hack.Text,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(
            text = label.uppercase(),
            style = MonoBody.copy(color = Hack.Dim, fontSize = 11.sp),
        )
        Text(
            text = " " + ".".repeat(LEADER_DOTS) + " ",
            style = MonoBody.copy(color = Hack.Line, fontSize = 11.sp),
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.weight(1f, fill = true),
        )
        Text(
            text = value,
            style = MonoBody.copy(color = valueColor, fontWeight = FontWeight.Bold),
        )
    }
}

/** A boxed word — the state of one flag, readable without its label. */
@Composable
fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MonoBody.copy(color = color, fontWeight = FontWeight.Bold, fontSize = 10.sp),
        modifier = modifier
            .border(1.dp, color.copy(alpha = BADGE_BORDER_ALPHA), RoundedCornerShape(2.dp))
            .background(color.copy(alpha = BADGE_FILL_ALPHA))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** `on` and `off` as the same width, so a row of flags does not reflow when one flips. */
@Composable
fun FlagBadge(label: String, on: Boolean, onColor: Color = Hack.Green, offColor: Color = Hack.Red) {
    Badge(text = label, color = if (on) onColor else offColor)
}

/** The primary action: black on solid green, the way a terminal inverts a selection. */
@Composable
fun HackButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = Hack.Green,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(2.dp),
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = accent,
            contentColor = Color.Black,
            disabledContainerColor = Hack.SurfaceHi,
            disabledContentColor = Hack.Dim,
        ),
        modifier = modifier,
    ) {
        Text(
            text = text.uppercase(),
            style = MonoBody.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
        )
    }
}

/** The secondary action: outline only, so a screen of them has one obvious primary. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = Hack.Green,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(2.dp),
        contentPadding = ButtonPadding,
        border = BorderStroke(1.dp, if (enabled) accent.copy(alpha = ACCENT_BORDER_ALPHA) else Hack.Line),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = accent,
            disabledContentColor = Hack.Dim,
        ),
        modifier = modifier,
    ) {
        Text(
            text = text.uppercase(),
            style = MonoBody.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
        )
    }
}

/**
 * The block cursor, blinking at a terminal's rate.
 *
 * Load-bearing, just about: it is the only thing on a static screen that says the UI is
 * still being composed rather than frozen behind a stuck coroutine.
 */
@Composable
fun BlinkingCursor(color: Color = Hack.Green) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = CURSOR_BLINK_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursorAlpha",
    )
    Box(
        Modifier
            .background(color.copy(alpha = alpha))
            .padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

private val ButtonPadding = androidx.compose.foundation.layout.PaddingValues(
    horizontal = 12.dp,
    vertical = 6.dp,
)

private const val ACCENT_BORDER_ALPHA = 0.55f
private const val BADGE_BORDER_ALPHA = 0.6f
private const val BADGE_FILL_ALPHA = 0.08f
private const val LEADER_DOTS = 60
private const val CURSOR_BLINK_MS = 600
