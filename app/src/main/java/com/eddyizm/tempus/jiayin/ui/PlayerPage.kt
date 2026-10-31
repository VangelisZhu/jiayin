@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.eddyizm.tempus.jiayin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.eddyizm.tempus.R
import com.eddyizm.tempus.jiayin.*
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.playback.PlaybackState
import kotlinx.coroutines.launch
import sh.calvin.reorderable.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun MiniPlayer(state: PlaybackState, open: () -> Unit, toggle: () -> Unit, queue: () -> Unit, visible: Boolean = true) {
    val t = state.current ?: return
    val visual = coverVisual(t.cover)
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val foreground = MaterialTheme.colorScheme.onSurface
    val repo = com.eddyizm.tempus.jiayin.data.JiayinRepository.get(androidx.compose.ui.platform.LocalContext.current)
    val reduced = animationsReduced()
    val progress = (state.queue.positionMs.toFloat() / (t.duration * 1000f).coerceAtLeast(1f)).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().padding(horizontal = LocalPhoneLayout.current.gutter, vertical = 8.dp).height(LocalPhoneLayout.current.miniHeight)) {
        Surface(onClick = open, modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(30.dp),
            color = if (dark) Color(0xFF28312D) else Color.White, shadowElevation = 3.dp) {
            Row(Modifier.fillMaxSize().padding(start = 62.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.buffering) "${t.title} · 正在缓冲…" else "${t.title} · ${t.artist}", Modifier.weight(1f).padding(end = 4.dp), fontSize = 14.sp, maxLines = if (LocalPhoneLayout.current.largeText && !LocalPhoneLayout.current.shortWindow) 2 else 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(29.dp)) {
                        drawCircle(foreground.copy(alpha = .14f), style = Stroke(1.5.dp.toPx()))
                        drawArc(foreground.copy(alpha = .8f), -90f, progress * 360, false, style = Stroke(1.5.dp.toPx()))
                    }
                    IconButton(onClick = toggle) { Icon(androidx.compose.ui.res.painterResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play), if (state.playing) "暂停" else "继续播放", Modifier.size(20.dp)) }
                }
                IconAction(R.drawable.ic_queue, "播放队列", action = queue)
            }
        }
        MiniRecord(visual, state.playing, visible && screenResumed() && repo.settings.getBoolean("rotate", true), reduced,
            Modifier.size(56.dp).align(Alignment.CenterStart).offset(x = (-4).dp).semantics { contentDescription = "迷你唱片" }.clickable(onClick = open))
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun FullPlayer(ui: HomeState, playback: PlaybackState, vm: JiayinViewModel, dark: Boolean, close: () -> Unit, addToPlaylist: (Track) -> Unit = {}, information: (Track) -> Unit = {}) {
    var audioEffects by remember { mutableStateOf(false) }
    if (audioEffects) AudioEffectsPage(vm) { audioEffects = false }
    var lyrics by rememberSaveable { mutableStateOf(false) }
    val lyricScroll = rememberLazyListState()
    val lyricFollow = rememberSaveable(playback.current?.id) { mutableStateOf(true) }
    var queue by rememberSaveable { mutableStateOf(false) }
    var timer by remember { mutableStateOf(false) }
    var matching by remember { mutableStateOf(false) }
    var correction by remember { mutableStateOf<LyricLine?>(null) }
    var correctionIndex by remember { mutableIntStateOf(-1) }
    var rotate by remember { mutableStateOf(vm.repo.settings.getBoolean("rotate", true)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    val reduced = animationsReduced()
    val t = ui.library.tracks.find { it.id == playback.current?.id } ?: playback.current
    val actual = t
    val visual = coverVisual(t?.cover.orEmpty())
    val foreground = if (dark) Color(0xFFF2F5F1) else Color(0xFF202A28)
    BackHandler { if (lyrics) lyrics = false else close() }
    CompositionLocalProvider(LocalContentColor provides foreground) {
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            // Own the overlay hit path so taps cannot reach the page behind it.
            // Consuming Final-pass changes here would cancel child scroll/drag gestures.
            awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Final) }
        }) {
            AmbientBackground(visual, dark, lyrics, reduced)
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconAction(R.drawable.ic_expand_more, "收起播放器", action = close)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (lyrics) "歌词" else "正在播放", fontSize = 12.sp, color = foreground.copy(alpha = .7f))
                        Text(playback.queue.source, fontSize = 11.sp, color = foreground.copy(alpha = .6f), modifier = Modifier.padding(top = 4.dp), maxLines = 1)
                    }
                    IconAction(R.drawable.ic_timer, "睡眠定时") { timer = true }
                    IconAction(R.drawable.ic_audio_effects, "音效与耳机") { audioEffects = true }
                }
                if (t == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("从曲库挑一首喜欢的歌吧") }
                else {
                    AdaptivePlayerBody(lyrics, artwork = {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val showHint = maxHeight >= 300.dp
                        val recordSize = minOf(maxWidth - 28.dp, maxHeight - if (showHint) 44.dp else 8.dp, 390.dp).coerceAtLeast(64.dp)
                        if (lyrics) LyricsPanel(ui, playback, vm, foreground, reduced, { matching = true }, { i, line -> correctionIndex = i; correction = line }, { lyrics = false }, lyricScroll, lyricFollow)
                        else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Vinyl(visual, playback.playing, resumed && rotate, reduced, Modifier.size(recordSize).semantics { contentDescription = "打开歌词" }.clickable { lyrics = true })
                            if (showHint) Text("轻点唱片，查看歌词", Modifier.clickable { lyrics = true }.padding(12.dp), fontSize = 11.sp, color = foreground.copy(alpha = .75f))
                        }
                    }
                    }, controls = {
                    val compact = LocalPlayerCompact.current
                    Row(Modifier.fillMaxWidth().padding(start = LocalPhoneLayout.current.gutter, end = 16.dp, top = if (compact) 0.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, fontSize = if (compact) 20.sp else 25.sp, fontWeight = FontWeight.SemiBold, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                            Text(t.artist, Modifier.padding(top = if (compact) 2.dp else 9.dp, bottom = if (compact) 0.dp else 6.dp), fontSize = if (compact) 12.sp else 14.sp, color = foreground.copy(alpha = .75f))
                        }
                        IconAction(if (actual?.starred == true) R.drawable.ic_favorite else R.drawable.ic_favorites_outlined, "喜欢", tint = foreground) { actual?.let { vm.favorite(it) } }
                    }
                    if (playback.error != null || playback.cause == PauseCause.FOCUS || playback.cause == PauseCause.HEADSET) {
                        Text(playback.error ?: if (playback.cause == PauseCause.HEADSET) "耳机已断开，点播放继续" else "音乐被其他应用暂停，点播放原位继续", Modifier.padding(horizontal = 32.dp, vertical = 8.dp), fontSize = 11.sp, color = foreground.copy(alpha = .8f))
                    } else Spacer(Modifier.height(if (compact) 0.dp else 16.dp))
                    PlayerProgress(playback, vm, foreground)
                    Row(Modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else 24.dp, vertical = if (compact) 4.dp else 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        IconAction(R.drawable.ic_shuffle, if (playback.queue.shuffled) "关闭随机" else "开启随机", tint = if (playback.queue.shuffled) foreground else foreground.copy(alpha = .45f)) { vm.command("shuffle") }
                        IconAction(R.drawable.ic_skip_previous, "上一首", tint = foreground) { vm.command("previous") }
                        IconButton({ vm.command("toggle") }, Modifier.size(if (compact) 56.dp else 72.dp).background(foreground, CircleShape)) {
                            if (playback.buffering) CircularProgressIndicator(Modifier.size(32.dp), color = if (dark) Color(0xFF27322D) else Color(0xFFE7EEEA), strokeWidth = 2.dp)
                            else AppIcon(if (playback.playing) R.drawable.ic_pause else R.drawable.ic_play, if (playback.playing) "暂停" else "继续播放", Modifier.size(34.dp), if (dark) Color(0xFF27322D) else Color(0xFFE7EEEA))
                        }
                        IconAction(R.drawable.ic_skip_next, "下一首", tint = foreground) { vm.command("next") }
                        IconAction(if (playback.queue.repeat == 1) R.drawable.ic_repeat_one else R.drawable.ic_repeat, when (playback.queue.repeat) { 1 -> "单曲循环"; 2 -> "列表循环"; else -> "顺序播放" }, tint = if (playback.queue.repeat != 0) foreground else foreground.copy(alpha = .45f)) { vm.command("repeat") }
                    }
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 0.dp else 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton({ lyrics = !lyrics }) { Text(if (lyrics) "唱片" else "歌词", color = foreground, fontSize = 13.sp) }
                        TextButton({ queue = true }) { AppIcon(R.drawable.ic_queue, "队列", tint = foreground); Spacer(Modifier.width(6.dp)); Text("队列", color = foreground, fontSize = 13.sp) }
                        TextButton({ addToPlaylist(t) }) { AppIcon(R.drawable.ic_playlist_add, "加入歌单", tint = foreground) }
                        TextButton({ vm.download(listOf(t)) }) { AppIcon(R.drawable.ic_download, "下载", tint = foreground) }
                        TextButton({ information(actual ?: t) }, Modifier.heightIn(min = 48.dp)) { AppIcon(R.drawable.ic_info_stream, "歌曲信息", tint = foreground); Spacer(Modifier.width(4.dp)); Text("歌曲信息", color = foreground) }
                    }
                    })
                }
            }
        }
    }
    if (queue) QueueSheet(ui, playback, vm) { queue = false }
    if (timer) AlertDialog(onDismissRequest = { timer = false }, title = { Text("睡眠定时") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) { if (playback.sleepUntil > 0) Text("剩余约 ${(playback.sleepUntil - System.currentTimeMillis()).coerceAtLeast(0) / 60000} 分钟")
            listOf(15, 30, 45, 60, 90, 0).forEach { minutes -> TextButton({ vm.command("sleep") { putInt("minutes", minutes) }; timer = false }) { Text(if (minutes == 0) "关闭定时" else "$minutes 分钟后暂停") } }
        }
    }, confirmButton = { TextButton({ timer = false }) { Text("返回") } })
    if (matching) LyricMatchDialog(ui, vm) { matching = false }
    correction?.let { line ->
        var value by remember(line) { mutableStateOf(line.translation.orEmpty()) }
        AlertDialog(onDismissRequest = { correction = null }, title = { Text("校正译文") }, text = { Column { Text(line.original); OutlinedTextField(value, { value = it }, Modifier.padding(top = 16.dp).fillMaxWidth(), label = { Text("简体中文译文") }, minLines = 2) } }, confirmButton = { TextButton({ ui.lyrics?.let { l -> vm.editLyrics(l.copy(lines = l.lines.mapIndexed { i, old -> if (i == correctionIndex) old.copy(translation = value, translationInvalid = false) else old })) }; correction = null }) { Text("保存") } }, dismissButton = { TextButton({ correction = null }) { Text("取消") } })
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun PlayerProgress(playback: PlaybackState, vm: JiayinViewModel, foreground: Color) {
    val duration = ((playback.current?.duration ?: 0) * 1000L).coerceAtLeast(1)
    var seeking by remember(playback.current?.id) { mutableStateOf(false) }
    var value by remember(playback.current?.id) { mutableFloatStateOf(0f) }
    Column(Modifier.padding(horizontal = 28.dp)) {
        Slider(if (seeking) value else (playback.queue.positionMs.toFloat() / duration).coerceIn(0f, 1f), { value = it; seeking = true }, onValueChangeFinished = { vm.command("seek") { putLong("position", (value * duration).toLong()) }; seeking = false }, modifier = Modifier.fillMaxWidth().height(48.dp),
            thumb = { Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(10.dp).background(foreground, CircleShape)) } },
            track = { slider -> Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                val y = size.height / 2
                drawLine(foreground.copy(alpha = .15f), Offset(0f, y), Offset(size.width, y), 3.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(foreground.copy(alpha = .8f), Offset(0f, y), Offset(size.width * slider.value.coerceIn(0f, 1f), y), 3.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
            } })
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(timeLabel(if (seeking) (value * duration).toLong() else playback.queue.positionMs), fontSize = 11.sp, color = foreground.copy(alpha = .65f)); Text(timeLabel(duration), fontSize = 11.sp, color = foreground.copy(alpha = .65f)) }
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun LyricsPanel(ui: HomeState, playback: PlaybackState, vm: JiayinViewModel, color: Color, reduced: Boolean, match: () -> Unit, edit: (Int, LyricLine) -> Unit, showRecord: () -> Unit = {}, scroll: LazyListState = rememberLazyListState(), followState: MutableState<Boolean> = remember { mutableStateOf(true) }) {
    val lyrics = ui.lyrics
    var bilingual by rememberSaveable(playback.current?.id) { mutableStateOf(true) }
    var follow by followState
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var armed by remember(playback.current?.id) { mutableStateOf(false) }
    var target by remember(playback.current?.id) { mutableIntStateOf(-1) }
    var seekConfirmation by remember { mutableIntStateOf(-1) }
    val toggle by rememberUpdatedState(showRecord)
    val display = remember(lyrics) { lyrics?.let { LyricRepairPolicy.display(it) } }
    val damaged = display?.issue != null
    val lines = display?.lines.orEmpty()
    val position = playback.queue.positionMs + (lyrics?.offsetMs ?: 0)
    val current = lines.indexOfLast { it.timeMs != null && it.timeMs <= position }.coerceAtLeast(0)
    LaunchedEffect(lyrics?.songId, lyrics?.language) {
        val lang = lyrics?.language.orEmpty().lowercase()
        bilingual = !(lang.startsWith("zh") || lang.startsWith("chi") || lang == "cmn")
    }
    LaunchedEffect(current, follow, playback.current?.id) {
        if (follow && lines.isNotEmpty()) {
            val target = -(scroll.layoutInfo.viewportEndOffset / 3)
            if (reduced) scroll.scrollToItem(current, target)
            else {
                val visible = scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }
                if (visible == null) scroll.scrollToItem(current, target + 64)
                val item = scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }
                if (item != null) scroll.animateScrollBy((item.offset + target).toFloat(), tween(450))
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.Center) {
            TextButton({ bilingual = !bilingual }) { Text(if (bilingual) "双语" else "原文", color = color, fontSize = 12.sp) }
            TextButton(match) { Text("匹配歌词", color = color, fontSize = 12.sp) }
            if (lines.isNotEmpty()) TextButton({ vm.translate() }, enabled = !ui.lyricsBusy) { Text(if (ui.lyricsBusy) "处理中…" else "翻译为中文", color = color, fontSize = 12.sp) }
        }
        if (damaged && lines.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(display?.issue.orEmpty(), Modifier.weight(1f).clickable(onClick = showRecord), color = color.copy(alpha = .7f), fontSize = 11.sp)
            TextButton({ vm.loadLyrics(true) }, enabled = !ui.lyricsBusy) { Text("重试修复", color = color, fontSize = 11.sp) }
        }
        if (lines.isEmpty()) Column(Modifier.fillMaxWidth().weight(1f).clickable(onClickLabel = "显示唱片", onClick = showRecord), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(if (ui.lyricsBusy) "正在读取和检查歌词…" else if (damaged) display?.issue.orEmpty() else "这首歌还没有歌词", Modifier.padding(horizontal = 24.dp), color = color.copy(alpha = .7f), fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (damaged && vm.repo.settings.getBoolean("auto-repair-lyrics", false)) TextButton({ vm.loadLyrics(true) }, enabled = !ui.lyricsBusy) { Text("重试自动修复", color = color, fontSize = 12.sp) }
            TextButton(match) { Text("手动联网匹配", color = color) }
        } else {
            LazyColumn(state = scroll, modifier = Modifier.weight(1f).fillMaxWidth().semantics {
                contentDescription = "歌词区域：轻点切换唱片，长按三秒后拖动并松手跳转"
                customActions = listOf(CustomAccessibilityAction("显示唱片") { showRecord(); true })
            }.pointerInput(lines, lyrics?.offsetMs) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val wasScrolling = !follow && scroll.isScrollInProgress
                    val start = down.position
                    var tap = !wasScrolling
                    val ended = withTimeoutOrNull(3000) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                            if (event.changes.size != 1) { tap = false; return@withTimeoutOrNull true }
                            if ((change.position - start).getDistance() > viewConfiguration.touchSlop) { follow = false; tap = false; return@withTimeoutOrNull true }
                            if (!change.pressed) return@withTimeoutOrNull true
                        }
                        @Suppress("UNREACHABLE_CODE") true
                    }
                    if (ended != null) { if (tap) toggle() }
                    else {
                        armed = true; follow = false
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        var dragged = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (event.changes.size != 1) break
                                if ((change.position - start).getDistance() > viewConfiguration.touchSlop) dragged = true
                                val y = change.position.y.toInt()
                                target = scroll.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.index ?: -1
                                event.changes.forEach { it.consume() }
                                if (!change.pressed) {
                                    if (dragged) lines.getOrNull(target)?.timeMs?.let { time -> vm.command("seek") { putLong("position", (time - (lyrics?.offsetMs ?: 0)).coerceAtLeast(0)) } }
                                    follow = true
                                    break
                                }
                                val edge = 48.dp.toPx()
                                if (y < edge) scope.launch { scroll.scrollBy(-20f) }
                                else if (y > size.height - edge) scope.launch { scroll.scrollBy(20f) }
                            }
                        } finally { armed = false; target = -1 }
                    }
                }
            }, contentPadding = PaddingValues(horizontal = 32.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                itemsIndexed(lines, key = { i, _ -> i }) { i, line ->
                    val alpha by androidx.compose.animation.core.animateFloatAsState(if (i == current) 1f else .76f, tween(if (reduced) 0 else 250), label = "lyric highlight")
                    Column(Modifier.fillMaxWidth().background(if (armed && target == i) color.copy(alpha = .12f) else Color.Transparent, RoundedCornerShape(8.dp)).semantics { customActions = listOf(CustomAccessibilityAction("跳转到这句歌词") { seekConfirmation = i; true }, CustomAccessibilityAction("校正译文") { edit(i, line); true }) }) {
                        if (line.original.isNotBlank()) Text(line.original, fontSize = if (i == current) 19.sp else 18.sp, lineHeight = 28.sp, color = color.copy(alpha = alpha), fontWeight = if (i == current) FontWeight.SemiBold else FontWeight.Normal)
                        if (bilingual && !line.translation.isNullOrBlank()) Text(line.translation, Modifier.padding(top = 5.dp), fontSize = 15.sp, lineHeight = 23.sp, color = color.copy(alpha = alpha * .9f))
                    }
                }
            }
            if (armed) Text("松手跳转到选中的歌词", Modifier.align(Alignment.CenterHorizontally), color = color, fontSize = 11.sp)
            if (!follow && !armed) TextButton({ follow = true; scope.launch { scroll.animateScrollToItem(current, -(scroll.layoutInfo.viewportEndOffset / 3)) } }, Modifier.align(Alignment.CenterHorizontally)) { Text("回到当前", color = color) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ lyrics?.let { vm.editLyrics(it.copy(offsetMs = it.offsetMs - 500)) } }) { Text("慢 0.5s", color = color, fontSize = 10.sp) }
                TextButton({ lines.getOrNull(current)?.let { edit(current, it) } }) { Text(if (lyrics?.machine == true) "机器翻译 · 校正" else "校正译文", fontSize = 10.sp, color = color.copy(alpha = .7f)) }
                TextButton({ lyrics?.let { vm.editLyrics(it.copy(offsetMs = it.offsetMs + 500)) } }) { Text("快 0.5s", color = color, fontSize = 10.sp) }
            }
        }
    }
    if (seekConfirmation >= 0) AlertDialog(onDismissRequest = { seekConfirmation = -1 }, title = { Text("跳转到这句歌词？") }, text = { Text(lines.getOrNull(seekConfirmation)?.original.orEmpty()) }, confirmButton = { TextButton({ lines.getOrNull(seekConfirmation)?.timeMs?.let { time -> vm.command("seek") { putLong("position", (time - (lyrics?.offsetMs ?: 0)).coerceAtLeast(0)) } }; seekConfirmation = -1; follow = true }) { Text("跳转") } }, dismissButton = { TextButton({ seekConfirmation = -1 }) { Text("取消") } })
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun LyricMatchDialog(ui: HomeState, vm: JiayinViewModel, close: () -> Unit) {
    val current by com.eddyizm.tempus.jiayin.playback.PlaybackHub.state.collectAsState()
    var query by remember { mutableStateOf(current.current?.let { LyricRepairPolicy.query(it) }.orEmpty()) }
    AlertDialog(onDismissRequest = close, title = { Text("匹配歌词") }, text = {
        Column { Text("搜索会把歌曲名和歌手发给 LRCLIB；请选择版本与时长接近的结果。", fontSize = 12.sp)
            OutlinedTextField(query, { query = it }, Modifier.padding(top = 14.dp), label = { Text("歌曲和歌手") })
            TextButton({ vm.searchLyrics(query) }, enabled = !ui.lyricsBusy) { Text(if (ui.lyricsBusy) "正在搜索…" else "搜索") }
            TextButton({ vm.loadLyrics(true); close() }) { Text("重新读取 NAS 歌词") }
            LazyColumn(Modifier.heightIn(max = 280.dp)) { items(ui.lyricMatches, key = { it.id }) { match -> ListItem(headlineContent = { Text(match.title) }, supportingContent = { Text("${match.artist} · ${timeLabel(match.duration * 1000L)} · ${if (match.synced != null) "逐行歌词" else "纯文本"}\n${match.album}") }, modifier = Modifier.clickable { vm.selectLyrics(match); close() }) } }
        }
    }, confirmButton = { TextButton(close) { Text("返回") } })
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun QueueSheet(ui: HomeState, playback: PlaybackState, vm: JiayinViewModel, close: () -> Unit) {
    val scroll = rememberLazyListState()
    var entries by remember { mutableStateOf(playback.queue.entries) }
    var dragging by remember { mutableStateOf(false) }
    val byId = remember(ui.library.tracks) { ui.library.tracks.associateBy { it.id } }
    LaunchedEffect(playback.queue.entries) { if (!dragging) entries = playback.queue.entries }
    LaunchedEffect(Unit) { if (entries.isNotEmpty()) scroll.scrollToItem(playback.queue.index.coerceIn(entries.indices)) }
    val reorder = rememberReorderableLazyListState(scroll) { from, to ->
        val a = entries.indexOfFirst { it.key == from.key }; val b = entries.indexOfFirst { it.key == to.key }
        if (a >= 0 && b >= 0) entries = entries.toMutableList().apply { add(b, removeAt(a)) }
    }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.83f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("播放队列", fontSize = 24.sp, fontWeight = FontWeight.SemiBold); Text("${entries.size} 首 · ${if (playback.queue.shuffled) "随机播放" else "顺序播放"}", fontSize = 12.sp) }
                TextButton({ vm.command("reshuffle") }) { Text("重新随机") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("无限续播", Modifier.weight(1f))
                Switch(playback.queue.discovery?.enabled == true, { enabled -> vm.command("discovery") { putBoolean("enabled", enabled) } })
            }
            playback.discoveryNotice?.let { Text(it, Modifier.padding(horizontal = 24.dp, vertical = 6.dp), fontSize = 12.sp) }
            LazyColumn(state = scroll, contentPadding = PaddingValues(bottom = 30.dp)) {
                itemsIndexed(entries, key = { _, e -> e.key }) { i, entry ->
                    ReorderableItem(reorder, key = entry.key) { _ ->
                        val t = byId[entry.trackId]
                        Column {
                            if (entry.key == playback.queue.current?.key || (i == playback.queue.index + 1) || (entry.manual && (i == 0 || !entries[i - 1].manual))) Text(if (entry.key == playback.queue.current?.key) "正在听" else if (entry.manual) "手动加入" else "接下来", Modifier.padding(start = 24.dp, top = 18.dp, bottom = 8.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Cover(t?.cover.orEmpty(), Modifier.size(42.dp), 9)
                                Column(Modifier.weight(1f).clickable { vm.command("jump") { putInt("index", playback.queue.entries.indexOfFirst { it.key == entry.key }) } }.padding(horizontal = 12.dp, vertical = 12.dp)) {
                                    Text(t?.title ?: "歌曲不可用", fontSize = 14.sp, color = if (entry.key == playback.queue.current?.key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(t?.artist.orEmpty(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconAction(R.drawable.ic_close, "移除") { vm.command("remove") { putInt("index", playback.queue.entries.indexOfFirst { it.key == entry.key }) } }
                                IconButton({}, Modifier.draggableHandle(onDragStarted = { dragging = true }, onDragStopped = {
                                    dragging = false; vm.command("reorder") { putStringArrayList("keys", ArrayList(entries.map { it.key })) }
                                })) { AppIcon(R.drawable.ic_drag_handle, "拖动调整顺序") }
                            }
                        }
                    }
                }
            }
        }
    }
}
