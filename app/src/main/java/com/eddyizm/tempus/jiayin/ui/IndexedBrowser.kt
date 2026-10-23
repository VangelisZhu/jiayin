@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.eddyizm.tempus.jiayin.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddyizm.tempus.jiayin.core.IndexedSection
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable fun CompactTitle(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}
@Composable fun ChoiceFilter(label: String, value: String, choices: List<String>, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text("$label$value ▾", fontSize = 12.sp) }
        DropdownMenu(expanded, { expanded = false }, Modifier.heightIn(max = 360.dp)) {
            choices.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { select(option); expanded = false }) }
        }
    }
}

@Composable fun <T> IndexedBrowser(sections: List<IndexedSection<T>>, state: LazyListState, key: (T) -> String, modifier: Modifier = Modifier, footer: LazyListScope.() -> Unit = {}, row: @Composable (T) -> Unit) {
    val positions = remember(sections) { sections.associate { it.label to it.firstIndex } }
    val scope = rememberCoroutineScope()
    var jumpJob by remember { mutableStateOf<Job?>(null) }
    var selection by remember { mutableStateOf(false) }
    var bubble by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    val current by remember(positions, state) { derivedStateOf { positions.entries.lastOrNull { it.value <= state.firstVisibleItemIndex }?.key } }
    val jump: (String) -> Unit = { label -> positions[label]?.let { at ->
        jumpJob?.cancel(); jumpJob = scope.launch { state.scrollToItem(at) }
    } }
    val latestJump by rememberUpdatedState(jump)
    Box(modifier.fillMaxWidth()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(end = 48.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            sections.forEach { section ->
                stickyHeader(key = "section:${section.label}") { Text(section.label, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 24.dp, vertical = 7.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
                items(section.entries, key = key) { row(it) }
            }
            footer()
        }
        if (sections.isNotEmpty()) {
            val labels = remember { ('A'..'Z').map { it.toString() } + "#" }
            BoxWithConstraints(Modifier.align(Alignment.CenterEnd).width(44.dp).fillMaxHeight(.95f)) {
            // Dense A–Z labels must fit their slot even when system text is enlarged.
            // The accessibility letter picker keeps full-sized 48dp targets.
            val labelSize = with(LocalDensity.current) { (maxHeight / labels.size * .8f).toSp() }.value.coerceAtMost(10f).sp
            Column(Modifier.fillMaxSize().semantics {
                contentDescription = "字母快速定位"
                onClick(label = "打开字母选择器") { selection = true; true }
                customActions = positions.keys.map { label -> CustomAccessibilityAction("定位到 $label") { latestJump(label); true } }
            }.pointerInput(labels, positions) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false); down.consume()
                    var previous: String? = null
                    fun point(y: Float) {
                        val label = labels[(y / size.height.coerceAtLeast(1) * labels.size).toInt().coerceIn(labels.indices)]
                        if (label != previous && label in positions) { previous = label; bubble = label; latestJump(label); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    }
                    try {
                        point(down.position.y)
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            event.changes.forEach { it.consume() }
                            if (!change.pressed || event.changes.size != 1) break
                            point(change.position.y)
                        }
                    } finally { bubble = null }
                }
            }, horizontalAlignment = Alignment.CenterHorizontally) {
                labels.forEach { label -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(label, fontSize = labelSize, lineHeight = labelSize, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)), maxLines = 1, color = if (label in positions) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .25f), fontWeight = if (label == current) FontWeight.Bold else FontWeight.Normal) } }
            }
            }
            bubble?.let { Text(it, Modifier.align(Alignment.CenterEnd).padding(end = 48.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp)).padding(horizontal = 20.dp, vertical = 14.dp), fontSize = 32.sp, color = MaterialTheme.colorScheme.onSecondaryContainer) }
        }
    }
    if (selection) AlertDialog(onDismissRequest = { selection = false }, title = { Text("选择字母") }, text = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { positions.keys.forEach { label -> TextButton({ latestJump(label); selection = false }, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text(label) } } }
    }, confirmButton = { TextButton({ selection = false }) { Text("取消") } })
}
