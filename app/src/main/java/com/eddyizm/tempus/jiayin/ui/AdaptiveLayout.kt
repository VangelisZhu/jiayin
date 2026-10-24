package com.eddyizm.tempus.jiayin.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Available window space, never a device model or physical pixel resolution. */
data class PhoneLayout(val width: Dp = 393.dp, val height: Dp = 800.dp, val fontScale: Float = 1f) {
    val gutter get() = if (width < 360.dp) 16.dp else 24.dp
    val largeText get() = fontScale >= 1.5f
    val albumMinimum get() = if (largeText) 200.dp else 136.dp
    val shortWindow get() = height < 480.dp
    val miniHeight get() = if (largeText && !shortWindow) 72.dp else 56.dp
}
val LocalPlayerCompact = staticCompositionLocalOf { false }
val LocalPhoneLayout = staticCompositionLocalOf { PhoneLayout() }

@Composable fun PhoneLayoutProvider(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = PhoneLayout(maxWidth, maxHeight, LocalDensity.current.fontScale)
        CompositionLocalProvider(LocalPhoneLayout provides layout) { content() }
    }
}

/** Keeps large text actions readable, and equal-width actions on ordinary phones. */
@Composable fun ActionPair(modifier: Modifier = Modifier, first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth < 300.dp || LocalPhoneLayout.current.largeText) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { first(Modifier.fillMaxWidth()); second(Modifier.fillMaxWidth()) }
        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { first(Modifier.weight(1f)); second(Modifier.weight(1f)) }
    }
}

/** Measure controls first. Their actual text height determines the remaining artwork space.
 * On short windows the whole portrait panel scrolls; nested lyrics retain a bounded viewport. */
@Composable fun AdaptivePlayerBody(lyrics: Boolean, artwork: @Composable () -> Unit, controls: @Composable () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        val wide = maxWidth >= 600.dp && maxHeight < 500.dp && fontScale < 1.5f
        if (wide) {
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight()) { artwork() }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) { CompositionLocalProvider(LocalPlayerCompact provides true) { controls() } }
            }
        } else {
            Layout(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), content = {
                Box(Modifier.fillMaxWidth()) { artwork() }
                Column(Modifier.fillMaxWidth()) { controls() }
            }) { measurables, constraints ->
                val control = measurables[1].measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                val minimum = (if (lyrics) 300.dp else 160.dp).roundToPx()
                val artHeight = (viewport.roundToPx() - control.height).coerceAtLeast(minimum)
                val art = measurables[0].measure(Constraints.fixed(constraints.maxWidth, artHeight))
                layout(constraints.maxWidth, art.height + control.height) {
                    art.placeRelative(0, 0); control.placeRelative(0, art.height)
                }
            }
        }
    }
}

/** Header may scroll off a short window rather than reducing a nested list to zero height. */
@Composable fun AdaptiveBrowserPage(header: @Composable () -> Unit, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        Layout(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), content = {
            Column(Modifier.fillMaxWidth()) { header() }
            Box(Modifier.fillMaxWidth()) { content() }
        }) { measurables, constraints ->
            val head = measurables[0].measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            val body = measurables[1].measure(Constraints.fixed(constraints.maxWidth, (viewport.roundToPx() - head.height).coerceAtLeast(160.dp.roundToPx())))
            layout(constraints.maxWidth, head.height + body.height) { head.placeRelative(0, 0); body.placeRelative(0, head.height) }
        }
    }
}
