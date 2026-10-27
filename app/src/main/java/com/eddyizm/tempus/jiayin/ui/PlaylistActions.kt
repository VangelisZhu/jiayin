package com.eddyizm.tempus.jiayin.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.eddyizm.tempus.jiayin.core.*

/** Horizontal slop locks the gesture; a fling alone never satisfies the distance threshold. */
@Composable internal fun PlaylistSwipeRow(enabled: Boolean, remove: () -> Unit, content: @Composable () -> Unit) {
    var distance by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val action by rememberUpdatedState(remove)
    val offset by animateFloatAsState(distance, if (dragging) snap() else tween(180), label = "playlistSwipe")
    LaunchedEffect(enabled) { if (!enabled) { distance = 0f; dragging = false } }
    Box(Modifier.fillMaxWidth().then(if (enabled) Modifier.semantics {
        customActions = listOf(CustomAccessibilityAction("从此歌单移除") { action(); true })
    }.pointerInput(threshold) {
        detectHorizontalDragGestures(onDragStart = { dragging = true }, onDragCancel = { dragging = false; distance = 0f },
            onDragEnd = { val confirm = distance <= -threshold; dragging = false; distance = 0f; if (confirm) action() }) { change, amount ->
            change.consume(); distance = (distance + amount).coerceIn(-threshold * 1.6f, 0f)
        }
    } else Modifier)) {
        if (offset < -1f) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.errorContainer).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
            Text("移除", color = MaterialTheme.colorScheme.onErrorContainer)
        }
        Box(Modifier.graphicsLayer { translationX = offset }.background(MaterialTheme.colorScheme.surface)) { content() }
    }
}

internal fun playlistEditError(result: PlaylistEditResult): String? = when (result) {
    PlaylistEditResult.SAVED, PlaylistEditResult.ALREADY_PRESENT -> null
    PlaylistEditResult.CHANGED -> "歌单内容已变化，请关闭后重新选择歌曲"
    PlaylistEditResult.READ_ONLY -> "这是只读歌单，无法修改"
    PlaylistEditResult.MISSING -> "歌单已不存在，请返回曲库刷新"
}

@Composable internal fun PlaylistRemovalDialog(name: String, song: Track, saving: Boolean, changed: Boolean, error: String?, confirm: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!saving) dismiss() }, title = { Text("从「$name」移除这首歌？") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("${song.title} · ${song.artist}"); Spacer(Modifier.height(12.dp))
            Text("仅移除歌单中的记录，不删除 NAS 文件或已下载音乐。")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(confirm, enabled = !saving && !changed) { Text(if (saving) "正在移除…" else "移除") } },
        dismissButton = { TextButton(dismiss, enabled = !saving) { Text("取消") } })
}

@Composable internal fun PlaylistPicker(song: Track, playlists: List<MusicList>, add: (String, (Result<PlaylistEditResult>) -> Unit) -> Unit,
    create: () -> Unit, dismiss: () -> Unit) {
    var saving by remember(song.id) { mutableStateOf<String?>(null) }
    var error by remember(song.id) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (saving == null) dismiss() }, title = { Text("加入歌单") }, text = {
        Column {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item { TextButton(create, enabled = saving == null) { Text("＋ 新建歌单") } }
                items(playlists, key = { it.id }) { p ->
                    val present = song.id in p.tracks
                    val enabled = !present && !p.readOnly && saving == null
                    val status = when {
                        saving == p.id -> "正在保存…"
                        p.readOnly -> "只读歌单"
                        present -> if (p.pending) "✓ 已在歌单 · 待同步" else "✓ 已在歌单"
                        else -> "${p.tracks.size} 首"
                    }
                    ListItem(headlineContent = { Text(p.name) }, supportingContent = { Text(status) }, modifier = Modifier
                        .alpha(if (present || p.readOnly) 0.55f else 1f)
                        .semantics { stateDescription = status }
                        .clickable(enabled = enabled) {
                            saving = p.id; error = null
                            add(p.id) { outcome ->
                                saving = null
                                error = outcome.fold(::playlistEditError) { it.message ?: "保存失败，请重试" }
                                if (error == null) dismiss()
                            }
                        })
                }
            }
        }
    }, confirmButton = { TextButton(dismiss, enabled = saving == null) { Text("完成") } })
}
