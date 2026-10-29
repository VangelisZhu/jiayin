package com.eddyizm.tempus.jiayin.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.eddyizm.tempus.jiayin.playback.*

@Composable fun VideoRecoverySettings(prefs: SharedPreferences) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val monitor by VideoResumeHub.state.collectAsState()
    val events by RecoveryDiagnostics.events.collectAsState()
    var enabled by remember { mutableStateOf(prefs.getBoolean("video-resume-compat", false)) }
    var automatic by remember { mutableStateOf(prefs.getBoolean("auto-resume", false)) }
    var authorized by remember { mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) }
    var explain by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    DisposableEffect(prefs, owner) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            enabled = prefs.getBoolean("video-resume-compat", false); automatic = prefs.getBoolean("auto-resume", false)
        }
        val lifecycle = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) authorized = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName) }
        prefs.registerOnSharedPreferenceChangeListener(listener); owner.lifecycle.addObserver(lifecycle)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener); owner.lifecycle.removeObserver(lifecycle) }
    }
    Column {
        ListItem(headlineContent = { Text("视频恢复兼容模式 · B站") },
            supportingContent = { Text(when { !enabled -> "可选辅助恢复，默认关闭"; !automatic -> "需同时开启“被视频打断后自动恢复”"; !authorized -> "需授权通知使用权"; else -> monitor.status }) },
            trailingContent = { Switch(enabled, { if (it) explain = true else prefs.edit().putBoolean("video-resume-compat", false).apply() }) })
        if (enabled && !authorized) TextButton({ runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }, Modifier.padding(horizontal = 18.dp)) { Text("开启通知使用权") }
        TextButton({ diagnostics = true }, Modifier.padding(horizontal = 18.dp)) { Text("播放恢复诊断") }
    }
    if (explain) AlertDialog(onDismissRequest = { explain = false }, title = { Text("开启 B站恢复兼容模式？") }, text = {
        Text("需要在系统中授予通知使用权，以访问媒体会话。家音仅处理播放状态，不读取或保存通知正文、不上传内容。\n\n开启后同时启用自动恢复。仅当观察到 B站明确暂停或停止时尝试一次；缓冲、状态缺失或权限断开时不会抢播。部分 B站版本可能不提供可用状态。")
    }, confirmButton = { TextButton({
        prefs.edit().putBoolean("auto-resume", true).putBoolean("video-resume-compat", true).apply(); explain = false
        if (!authorized) runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }) { Text("开启") } }, dismissButton = { TextButton({ explain = false }) { Text("取消") } })
    if (diagnostics) AlertDialog(onDismissRequest = { diagnostics = false }, title = { Text("播放恢复诊断") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("仅记录本次播放服务的焦点、暂停原因和恢复结果，不包含歌曲、视频标题或账户。时间为开机后的毫秒数。\n\n${monitor.status}\n\n${events.joinToString("\n").ifBlank { "暂无事件；播放音乐并用视频打断后再查看。" }}")
        }
    }, confirmButton = { TextButton({
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("家音播放诊断", "${monitor.status}\n${events.joinToString("\n")}"))
    }) { Text("复制诊断") } }, dismissButton = { TextButton({ diagnostics = false }) { Text("关闭") } })
}
