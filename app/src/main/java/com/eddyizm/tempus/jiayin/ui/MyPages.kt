@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.eddyizm.tempus.jiayin.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.scheduler.Requirements
import com.eddyizm.tempus.App
import com.eddyizm.tempus.R
import com.eddyizm.tempus.jiayin.*
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.playback.PlaybackHub
import com.eddyizm.tempus.util.DownloadUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun MyPage(ui: HomeState, vm: JiayinViewModel, collection: (MusicList) -> Unit, route: (String) -> Unit, newPlaylist: () -> Unit) {
    LazyColumn {
        item { PageTitle("我的", "留住每一次喜欢") { IconAction(R.drawable.ic_settings, "设置") { route("settings") } } }
        item {
            Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) { Text("家", fontSize = 28.sp, color = MaterialTheme.colorScheme.primary) }
                Column(Modifier.padding(start = 18.dp)) { Text(vm.repo.user, fontSize = 22.sp, fontWeight = FontWeight.SemiBold); Text("我的家庭曲库 · ${ui.library.tracks.size} 首", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        item { SheetActionPadded("我喜欢 · ${ui.library.tracks.count { it.starred }} 首", R.drawable.ic_favorite) { collection(MusicList("favorites", "我喜欢", ui.library.tracks.filter { it.starred }.map { it.id })) } }
        item { SectionTitle("收藏的歌手") }
        items(ui.artists.values.filter { it.starred }.sortedBy { it.name }, key = { it.id }) { p ->
            val tracks = ui.library.tracks.filter { artistKey(it) == p.id }
            ListItem(headlineContent = { Text(p.name) }, supportingContent = { Text("${tracks.size} 首") }, modifier = Modifier.clickable { collection(MusicList("artist:${p.id}", p.name, tracks.map { it.id }, tracks.firstOrNull()?.cover.orEmpty())) })
        }
        item { SheetActionPadded("最近听过", R.drawable.ic_timer) { route("history") } }
        item { SheetActionPadded("下载与离线", R.drawable.ic_download) { route("downloads") } }
        item { SectionTitle("我的歌单", "新建", newPlaylist) }
        items(ui.library.playlists, key = { it.id }) { p -> ListItem(headlineContent = { Text(p.name) }, supportingContent = { Text("${p.tracks.size} 首${if (p.pending) " · 等待同步" else ""}") }, leadingContent = { PlaylistCover(p, ui.library.tracks, Modifier.size(58.dp)) }, modifier = Modifier.clickable { collection(p) }) }
        item { HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 18.dp)); Text(ui.connection, Modifier.padding(horizontal = 24.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); SheetActionPadded("连接测试${if (ui.pending > 0) " · ${ui.pending} 项待同步" else ""}", R.drawable.ic_refresh) { vm.test() } }
        item { Text("家音 1.0 · 基于 Tempus / GPL-3.0", Modifier.padding(24.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun SettingsPage(vm: JiayinViewModel, ui: HomeState, theme: String, setTheme: (String) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = vm.repo.settings
    var apiKey by remember { mutableStateOf("") }
    var storedKey by remember { mutableStateOf(false) }
    var keyBusy by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showBitrate by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableLongStateOf(0L) }
    val playback by PlaybackHub.state.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { storedKey = !vm.repo.vault.get("${vm.repo.account}.deepl").isNullOrBlank(); cacheSize = DownloadUtil.getStreamingCacheSize(context) } }
    LazyColumn {
        item { PageTitle("设置", back = back) }
        item { SectionTitle("播放与恢复") }
        item { SettingToggle("被视频打断后自动恢复", "默认关闭。仅恢复被声音打断的音乐，不覆盖主动暂停或耳机断开。", "auto-resume", false, vm) }
        item { SettingToggle("队列结束后推荐续播", "从现有曲库继续挑选；与循环播放独立。", "recommend-autoplay", false, vm) }
        item { ListItem(headlineContent = { Text("蓝牙耳机") }, supportingContent = { Text("断开时暂停；重新连接后由你点播放。") }, leadingContent = { AppIcon(R.drawable.ic_play, "蓝牙播放策略") }) }
        item { TextButton({ context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }, Modifier.padding(horizontal = 18.dp)) { Text("打开应用后台与电池设置") } }
        item { SectionTitle("界面与动效") }
        item { SettingToggle("旋转唱片", "暂停时缓停；后台与锁屏不绘制动画。", "rotate", true, vm) }
        item { ListItem(headlineContent = { Text("主题") }, supportingContent = { Text(when (theme) { "dark" -> "深色"; "light" -> "浅色"; else -> "跟随系统" }) }, modifier = Modifier.clickable { showTheme = true }) }
        item { Text("系统关闭动画时，唱片、变色与页面动画也会减弱。", Modifier.padding(horizontal = 24.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { SectionTitle("网络与存储") }
        item { ListItem(headlineContent = { Text("音质") }, supportingContent = { Text("Wi-Fi：原始音质 · 移动网络：${App.getInstance().preferences.getString("max_bitrate_mobile", "256")} kbps") }, modifier = Modifier.clickable { showBitrate = true }) }
        item {
            var wifiOnly by remember { mutableStateOf(App.getInstance().preferences.getBoolean("download_wifi_only", true)) }
            ListItem(headlineContent = { Text("批量下载仅 Wi-Fi") }, supportingContent = { Text("关闭后允许使用移动网络下载。") }, trailingContent = { Switch(wifiOnly, { value -> wifiOnly = value; App.getInstance().preferences.edit().putBoolean("download_wifi_only", value).apply(); DownloadUtil.getDownloadManager(context).requirements = Requirements(if (value) Requirements.NETWORK_UNMETERED else Requirements.NETWORK) }) })
        }
        item { ListItem(headlineContent = { Text("播放缓存 · 2 GB 上限") }, supportingContent = { Text("已用 ${cacheSize / 1024 / 1024} MB；明确下载的音乐单独保留。") }, trailingContent = { TextButton({ scope.launch { withContext(Dispatchers.IO) { val cache = DownloadUtil.getStreamingCacheForPreload(context); cache.keys.toList().forEach { cache.removeResource(it) } }; cacheSize = 0; vm.message("播放缓存已清空") } }, enabled = !playback.playing) { Text("清空") } }) }
        item { SectionTitle("双语歌词 / DeepL") }
        item { SettingToggle("乱码歌词自动联网修复", "开启后，仅发现乱码时向 LRCLIB 发送歌名与歌手。关闭后仍可手动匹配；不改动服务器歌词。", "auto-repair-lyrics", true, vm) }
        item { Text("已有原词与译词会优先使用。手动翻译会把当前歌曲的原词发送给 DeepL，使用你自己的额度；密钥由 Android Keystore 加密保存。", Modifier.padding(horizontal = 24.dp), fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), label = { Text(if (storedKey) "已保存密钥，填写可替换" else "DeepL API 密钥") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()) }
        item { SettingToggle("使用 DeepL API Free", "付费 API Pro 账户请关闭此项。", "deepl-free", true, vm) }
        item { Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({ keyBusy = true; scope.launch { try { withContext(Dispatchers.IO) { vm.repo.vault.put("${vm.repo.account}.deepl", apiKey.trim()) }; apiKey = ""; storedKey = true; vm.message("翻译密钥已保存") } finally { keyBusy = false } } }, enabled = apiKey.isNotBlank() && !keyBusy) { Text("保存密钥") }
            TextButton({ vm.repo.vault.remove("${vm.repo.account}.deepl"); storedKey = false; apiKey = "" }) { Text("移除") }
        } }
        item { SectionTitle("连接与数据") }
        item { ListItem(headlineContent = { Text("音乐服务器") }, supportingContent = { Text("${vm.repo.server}\n${ui.connection}", fontSize = 12.sp) }) }
        item { Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ vm.test() }) { Text("测试连接") }; OutlinedButton({ vm.sync() }) { Text("同步曲库") } } }
        item { ListItem(headlineContent = { Text("待同步操作") }, supportingContent = { Text("${ui.pending} 项；网络恢复后自动重试，也可点同步曲库。") }) }
        item { TextButton({ clear = true }, Modifier.padding(horizontal = 18.dp)) { Text("清空收听记录与推荐偏好") } }
        item { Text("开源许可：GPL-3.0。家音基于 Tempus v4.30.0；完整源码和第三方许可随安装包提供。", Modifier.padding(24.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (showTheme) AlertDialog(onDismissRequest = { showTheme = false }, title = { Text("主题") }, text = { Column { listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (id, title) -> TextButton({ p.edit().putString("theme", id).apply(); setTheme(id); showTheme = false }) { Text(title) } } } }, confirmButton = { TextButton({ showTheme = false }) { Text("返回") } })
    if (showBitrate) AlertDialog(onDismissRequest = { showBitrate = false }, title = { Text("移动网络音质") }, text = { Column { listOf(128, 192, 256, 320).forEach { value -> TextButton({ App.getInstance().preferences.edit().putString("max_bitrate_mobile", value.toString()).apply(); showBitrate = false; vm.message("音质将在下一次起播时生效") }) { Text("$value kbps") } } } }, confirmButton = { TextButton({ showBitrate = false }) { Text("返回") } })
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清空收听偏好？") }, text = { Text("会重置本账户的收听记录与“不喜欢”反馈。收藏、歌单和下载保留。") }, confirmButton = { TextButton({ vm.clearHistory(); clear = false }) { Text("清空") } }, dismissButton = { TextButton({ clear = false }) { Text("取消") } })
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun SettingToggle(title: String, subtitle: String, key: String, default: Boolean, vm: JiayinViewModel) {
    var checked by remember { mutableStateOf(vm.repo.settings.getBoolean(key, default)) }
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle, fontSize = 12.sp) }, trailingContent = { Switch(checked, { checked = it; vm.repo.settings.edit().putBoolean(key, it).apply() }) })
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun DownloadsPage(ui: HomeState, vm: JiayinViewModel, menu: (Track) -> Unit, back: () -> Unit) {
    val byId = remember(ui.library.tracks) { ui.library.tracks.associateBy { it.id } }
    val downloaded = ui.downloads.filter { it.state == Download.STATE_COMPLETED }
    var remove by remember { mutableStateOf<Set<String>?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(ui.downloads.map { it.id }) { selected = selected.intersect(ui.downloads.map { it.id }.toSet()) }
    Column(Modifier.fillMaxSize()) {
        PageTitle("下载与离线", "${downloaded.size} 首已下载 · ${ui.downloads.sumOf { it.bytes } / 1024 / 1024} MB", back)
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Button({ vm.start(downloaded.mapNotNull { byId[it.id] }, source = "已下载") }) { Text("离线播放") }; OutlinedButton({ vm.start(downloaded.mapNotNull { byId[it.id] }, true, "下载随机") }) { AppIcon(R.drawable.ic_shuffle, "随机"); Text("随机播放") } }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            TextButton({ vm.pauseAllDownloads(true) }, enabled = ui.downloads.any { it.state != Download.STATE_COMPLETED }) { Text("全部暂停") }
            TextButton({ vm.pauseAllDownloads(false) }, enabled = ui.downloads.isNotEmpty()) { Text("全部继续") }
            TextButton({ selecting = !selecting; selected = emptySet() }) { Text(if (selecting) "取消选择" else "批量管理") }
        }
        if (selecting) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            TextButton({ selected = ui.downloads.map { it.id }.toSet() }) { Text("全选") }
            TextButton({ selected = emptySet() }) { Text("清空选择") }
            TextButton({ remove = selected.toSet() }, enabled = selected.isNotEmpty()) { Text("删除所选 ${selected.size}") }
            TextButton({ remove = ui.downloads.map { it.id }.toSet() }, enabled = ui.downloads.isNotEmpty()) { Text("删除全部") }
        }
        LazyColumn(Modifier.weight(1f)) {
        item { Text("默认仅 Wi-Fi 下载。已下载音乐和缓存歌词在无网络时仍可使用。", Modifier.padding(24.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (ui.downloads.isEmpty()) item { EmptyState("让音乐陪你离线", "在歌曲菜单或歌单页面点下载。") }
        items(ui.downloads, key = { it.id }) { d ->
            val t = byId[d.id]
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) Checkbox(d.id in selected, { selected = if (d.id in selected) selected - d.id else selected + d.id })
                    Cover(t?.cover.orEmpty(), Modifier.size(48.dp), 10)
                    Column(Modifier.weight(1f).clickable { if (selecting) selected = if (d.id in selected) selected - d.id else selected + d.id
                        else if (d.state == Download.STATE_COMPLETED && t != null) { val playable = downloaded.mapNotNull { byId[it.id] }; vm.start(playable, source = "已下载", index = playable.indexOfFirst { it.id == t.id }) } }.padding(horizontal = 12.dp, vertical = 16.dp)) {
                        Text(t?.title ?: d.id, maxLines = 1, fontSize = 14.sp)
                        Text(when (d.state) { Download.STATE_COMPLETED -> "已下载 · ${d.bytes / 1024 / 1024} MB"; Download.STATE_DOWNLOADING -> "下载中 ${d.progress.toInt()}%"; Download.STATE_FAILED -> "下载失败，点重试"; Download.STATE_STOPPED -> "已暂停"; Download.STATE_REMOVING -> "正在移除"; else -> "等待网络 / 排队中" }, Modifier.padding(top = 5.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (d.state == Download.STATE_FAILED) TextButton({ if (t != null) vm.download(listOf(t)) }) { Text("重试") }
                    else if (d.state != Download.STATE_COMPLETED) IconAction(if (d.state == Download.STATE_STOPPED) R.drawable.ic_play else R.drawable.ic_pause, "暂停或继续下载") { vm.pauseDownload(d.id, d.state != Download.STATE_STOPPED) }
                    IconAction(R.drawable.ic_close, "取消或删除下载") { remove = setOf(d.id) }
                }
                if (d.state == Download.STATE_DOWNLOADING) LinearProgressIndicator(progress = { d.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(start = 84.dp, end = 24.dp))
            }
        }
    }
    }
    remove?.let { ids -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("移除 ${ids.size} 首下载？") }, text = { Text("移除手机上的下载，NAS 原文件会保留。") }, confirmButton = { TextButton({ vm.removeDownloads(ids); selected = selected - ids; remove = null }) { Text("移除") } }, dismissButton = { TextButton({ remove = null }) { Text("取消") } }) }
}
