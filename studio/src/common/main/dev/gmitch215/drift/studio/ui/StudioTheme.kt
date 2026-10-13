package dev.gmitch215.drift.studio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import drift.studio.generated.resources.Res
import drift.studio.generated.resources.jetbrains_mono_regular
import org.jetbrains.compose.resources.Font

/** Colors of one theme; every text color is checked against its backgrounds in a test. */
@Immutable
class Palette(
    val canvas: Color,
    val surface: Color,
    val hairline: Color,
    val control: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val accentText: Color,
    val accentTint: Color,
    val warn: Color,
    val warnText: Color,
    val danger: Color,
) {
    /** Foreground and background pairs that carry text; each needs a 4.5 to 1 ratio. */
    val textPairs: List<Pair<Color, Color>>
        get() = listOf(
            text to canvas,
            text to surface,
            muted to canvas,
            muted to surface,
            accentText to surface,
            accentText to accentTint,
            warnText to surface,
            danger to surface,
        )

    /** Pairs that draw a meaningful edge or mark; each needs a 3 to 1 ratio. */
    val graphicPairs: List<Pair<Color, Color>>
        get() = listOf(
            accent to surface,
            accent to canvas,
            warn to surface,
            warn to canvas,
            control to surface,
            control to canvas,
            accentText to canvas,
        )
}

val LightPalette = Palette(
    canvas = Color(0xFFFCFBFE),
    surface = Color(0xFFFFFFFF),
    hairline = Color(0xFFE4DEF0),
    control = Color(0xFF6F6A80),
    text = Color(0xFF1D1A26),
    muted = Color(0xFF5B556B),
    accent = Color(0xFF8A63F0),
    accentText = Color(0xFF6B3FD4),
    accentTint = Color(0xFFEFE8FB),
    warn = Color(0xFFAA782A),
    warnText = Color(0xFF866129),
    danger = Color(0xFFB53334),
)

val DarkPalette = Palette(
    canvas = Color(0xFF110E17),
    surface = Color(0xFF191521),
    hairline = Color(0xFF2B2438),
    control = Color(0xFF7A7490),
    text = Color(0xFFECE9F3),
    muted = Color(0xFFA69FB8),
    accent = Color(0xFF9D7BF5),
    accentText = Color(0xFFB99CFF),
    accentTint = Color(0xFF2A2140),
    warn = Color(0xFFEAAA46),
    warnText = Color(0xFFF1C890),
    danger = Color(0xFFF1C2BE),
)

internal val LocalPalette = staticCompositionLocalOf { LightPalette }

internal val LocalMono = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

internal object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

internal val ControlShape = RoundedCornerShape(6.dp)

internal val BarShape = RoundedCornerShape(2.dp)

internal val ContentWidth = 960.dp

internal val MinTarget = 44.dp

internal object Type {
    val title = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
    val heading = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 24.sp)
    val small = TextStyle(fontSize = 13.sp, lineHeight = 20.sp)
    val mono = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontFamily = FontFamily.Monospace)
    val monoSmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
fun StudioTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalPalette provides if (dark) DarkPalette else LightPalette,
        LocalMono provides FontFamily(Font(Res.font.jetbrains_mono_regular)),
        content = content,
    )
}

@Composable
internal fun StudioText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Type.body,
    color: Color = LocalPalette.current.text,
    weight: FontWeight? = null,
) {
    val mono = LocalMono.current
    val family = if (style.fontFamily == FontFamily.Monospace) mono else style.fontFamily
    BasicText(text, modifier, style.merge(color = color, fontWeight = weight, fontFamily = family))
}

@Composable
internal fun Heading(text: String, modifier: Modifier = Modifier, style: TextStyle = Type.heading) {
    StudioText(text, modifier.semantics { heading() }, style)
}

@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier, style: TextStyle = Type.small) {
    StudioText(text, modifier, style, LocalPalette.current.muted)
}

@Composable
internal fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalPalette.current.hairline))
}

/** A bordered surface; with [onClick] it is one selectable item that shows keyboard focus. */
@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    val on = selected == true
    val press = if (onClick == null) Modifier else Modifier.pressable(selected, null, onClick)
    Box(
        modifier
            .fillMaxWidth()
            .background(if (on) palette.accentTint else palette.surface, ControlShape)
            .border(
                BorderStroke(1.dp, if (on) palette.accent else palette.hairline),
                ControlShape,
            )
            .then(press)
            .padding(Space.m),
    ) { content() }
}

/** A clickable that shows keyboard focus and a hand cursor; [selected] null means not a choice. */
@Composable
internal fun Modifier.pressable(
    selected: Boolean? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val ring = if (focused) {
        Modifier.border(BorderStroke(2.dp, LocalPalette.current.accentText), ControlShape)
    } else {
        Modifier
    }
    val press = if (selected == null) {
        Modifier.clickable(source, null, role = role, onClick = onClick)
    } else {
        Modifier.selectable(selected, source, null, role = role, onClick = onClick)
    }
    return then(press).then(ring).pointerHoverIcon(PointerIcon.Hand)
}

/** A choice or button; selected shows as a tint, an accent border and a heavier weight. */
@Composable
internal fun Choice(
    label: String,
    selected: Boolean?,
    modifier: Modifier = Modifier,
    role: Role = Role.RadioButton,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val on = selected == true
    Box(
        modifier
            .heightIn(min = MinTarget).widthIn(min = MinTarget)
            .background(if (on) palette.accentTint else palette.surface, ControlShape)
            .border(
                BorderStroke(1.dp, if (on) palette.accent else palette.control),
                ControlShape,
            )
            .pressable(selected, role, onClick)
            .padding(horizontal = Space.m, vertical = Space.xs),
        contentAlignment = Alignment.Center,
    ) {
        StudioText(
            label,
            style = Type.body,
            color = if (on) palette.accentText else palette.text,
            weight = if (on) FontWeight.SemiBold else null,
        )
    }
}

@Composable
internal fun Section(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Heading(title)
        content()
    }
}

/** What the screen is, in plain words, and what to do on it next. */
@Composable
internal fun Intro(title: String, what: String, next: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Heading(title, style = Type.title)
        Muted(what, style = Type.body)
        StudioText("What to do next: $next", style = Type.body)
    }
}

/** Raw output stays out of sight until asked for; the button says which way it will toggle. */
@Composable
internal fun Disclosure(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.l)) {
        Choice(
            if (open) "Hide $title" else "Show $title",
            null,
            Modifier.semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
            Role.Button,
        ) { open = !open }
        if (open) content()
    }
}
