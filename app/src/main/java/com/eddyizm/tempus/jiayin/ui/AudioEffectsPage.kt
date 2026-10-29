@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.eddyizm.tempus.jiayin.ui

import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eddyizm.tempus.jiayin.JiayinViewModel
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.AudioEffectStore
import com.eddyizm.tempus.jiayin.playback.*
import java.util.UUID

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun AudioEffectsPage(vm: JiayinViewModel, close: () -> Unit) {
    val repo = remember { vm.repo.scoped() }
    val store = remember { AudioEffectStore(repo.settings) }
    val actual by AudioEffectHub.state.collectAsState()
    val playback by PlaybackHub.state.collectAsState()
    val compareToken = remember { UUID.randomUUID().toString() }
    val comparing by AudioEffectHub.comparison.collectAsState()
    var revision by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(actual.output.takeIf { it in listOf(SoundOutput.HEADPHONES, SoundOutput.SPEAKER, SoundOutput.OTHER) } ?: SoundOutput.HEADPHONES) }
    var routeSelected by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    LaunchedEffect(actual.output) {
        if (!routeSelected && actual.output in listOf(SoundOutput.HEADPHONES, SoundOutput.SPEAKER, SoundOutput.OTHER)) editing = actual.output
    }
    var presetPicker by remember { mutableStateOf(false) }
    var songPicker by remember { mutableStateOf<Track?>(null) }
    var reset by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val context = LocalContext.current
    DisposableEffect(repo) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        repo.settings.registerOnSharedPreferenceChangeListener(listener)
        onDispose { repo.settings.unregisterOnSharedPreferenceChangeListener(listener); AudioEffectHub.compare(compareToken, false) }
    }
    val settings = remember(revision, editing) { store.settings(editing) }
    val current = playback.current
    fun save(value: AudioEffectSettings) { store.save(editing, value); revision++ }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                PageTitle("音效与耳机", back = close)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth().toggleable(settings.enabled, role = Role.Switch) { save(settings.copy(enabled = it)) }.semantics { contentDescription = "音效总开关" }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("音效总开关", style = MaterialTheme.typography.titleMedium); Text("关闭保留设置；仅处理家音本机播放", style = MaterialTheme.typography.bodySmall) }
                        Switch(settings.enabled, null)
                    }
                    Text("当前输出：${actual.output.label}")
                    Text(actual.status, color = MaterialTheme.colorScheme.primary)
                    if (current != null) {
                        Text("当前歌曲：${current.title}", style = MaterialTheme.typography.bodySmall)
                        Text("${actual.resolved.preset.label} · ${actual.resolved.reason}", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                    Text("编辑输出偏好", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(SoundOutput.HEADPHONES, SoundOutput.SPEAKER, SoundOutput.OTHER).forEach { output -> FilterChip(editing == output, { routeSelected = true; editing = output }, label = { Text(output.label) }) }
                    }
                    if (editing != actual.output) Text("正在设置${editing.label}，实际切换到此输出后应用。", style = MaterialTheme.typography.bodySmall)
                    Text("音色策略", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { SoundStrategy.entries.forEach { s -> FilterChip(settings.strategy == s, { save(settings.copy(strategy = s)) }, label = { Text(s.label) }) } }
                    OutlinedButton({ presetPicker = true }, Modifier.fillMaxWidth()) { Text("${if (settings.strategy == SoundStrategy.AUTO) "默认音色" else "固定音色"}：${settings.preset.label}") }
                    Text(if (settings.strategy == SoundStrategy.AUTO) "歌曲指定优先，其次按明确曲风选择；未知或冲突时使用默认音色。" else "所有歌曲使用固定音色，暂不使用曲风和歌曲指定。", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { SoundCharacter.entries.forEach { c ->
                        FilterChip(settings.character == c, { save(settings.copy(character = c)) }, label = { Text("${c.label}档") })
                    } }
                    Text(if (settings.character == SoundCharacter.VIVID) "音色变化更鲜明，保留增益余量；空间效果单独调整。" else "克制修饰，保持原有自然音色。", style = MaterialTheme.typography.bodySmall)
                    Text("音色强度 ${(settings.strength * 100).toInt()}%")
                    Slider(settings.strength, { save(settings.copy(strength = it)) }, Modifier.semantics { contentDescription = "音色强度" })
                    Text("耳机空间", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { SpaceMode.entries.forEach { s -> FilterChip(settings.space == s, { save(settings.copy(space = s)) }, label = { Text(s.label) }) } }
                    Text(when (settings.space) { SpaceMode.NATURAL -> "轻度交叉馈送，缓和左右分离。"; SpaceMode.IMMERSIVE -> "立体声轻度扩展，保留居中人声；不添加长混响。"; else -> "保持原有声道关系。" }, style = MaterialTheme.typography.bodySmall)
                    Text("空间强度 ${(settings.spaceStrength * 100).toInt()}%")
                    Slider(settings.spaceStrength, { save(settings.copy(spaceStrength = it)) }, enabled = settings.space != SpaceMode.OFF, modifier = Modifier.semantics { contentDescription = "空间强度" })
                    OutlinedButton({ songPicker = current }, enabled = current != null, modifier = Modifier.fillMaxWidth()) { Text("此歌曲的音效设置") }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ AudioEffectHub.compare(compareToken, comparing != compareToken) }, enabled = current != null && settings.enabled) { Text(if (comparing == compareToken) "结束原声对比" else "原声对比") }
                        TextButton({ reset = true }) { Text("恢复默认") }
                    }
                    TextButton({ diagnostics = !diagnostics }) { Text(if (diagnostics) "收起音效运行状态" else "音效运行状态") }
                    if (diagnostics) {
                        val applied = actual.processing.applied
                        Text("实际输出：${actual.route}\n${if (actual.receiving) "正在接收 PCM 数据" else "当前没有持续输入（暂停或缓冲）"} · 累计 ${actual.inputFrames} 帧\n" +
                            "实际配置：${applied?.let { "${it.preset.label} · ${it.character.label} · ${(it.strength * 100).toInt()}% · ${it.space.label}" } ?: "尚未应用"}\n" +
                            "状态：${actual.status}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
                    Text("音效使用软件 PCM 输出，不承诺位完美或高位深直出。空间模式是立体声处理，不是杜比全景声或头部追踪。", style = MaterialTheme.typography.bodySmall)
                    Text("若手机或耳机另开了音效，可在其设置中对比关闭，避免重复处理；家音不会改动系统设置。", style = MaterialTheme.typography.bodySmall)
                    TextButton({ runCatching { context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }.onFailure { message = "系统未提供声音设置入口" } }) { Text("打开系统声音设置") }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        if (presetPicker) AlertDialog(onDismissRequest = { presetPicker = false }, title = { Text("选择默认音色") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) { SoundPreset.entries.forEach { p -> TextButton({ save(settings.copy(preset = p)); presetPicker = false }, Modifier.fillMaxWidth()) { Text(p.label) } } }
        }, confirmButton = { TextButton({ presetPicker = false }) { Text("取消") } })
        songPicker?.let { song -> AlertDialog(onDismissRequest = { songPicker = null }, title = { Text("歌曲音效：${song.title}") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("仅保存到本机当前账户，固定策略下暂不使用。当前：${store.song(repo.account, song.id)?.label ?: "跟随自动"}")
                (listOf<SoundPreset?>(null) + SoundPreset.entries).forEach { p -> TextButton({
                    runCatching { store.saveSong(repo, song.id, p) }.onSuccess { songPicker = null; revision++ }.onFailure { message = it.message ?: "保存失败"; songPicker = null }
                }, Modifier.fillMaxWidth()) { Text(p?.label ?: "恢复自动") } }
            }
        }, confirmButton = { TextButton({ songPicker = null }) { Text("取消") } }) }
        if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("恢复${editing.label}的默认音效？") }, text = { Text("关闭总开关，恢复此输出的自动音色和默认空间设置。歌曲指定及其他输出的设置保留。") }, confirmButton = { TextButton({ save(AudioEffectSettings(space = if (editing == SoundOutput.HEADPHONES) SpaceMode.NATURAL else SpaceMode.OFF)); AudioEffectHub.compare(compareToken, false); reset = false }) { Text("恢复") } }, dismissButton = { TextButton({ reset = false }) { Text("取消") } })
    }
}
