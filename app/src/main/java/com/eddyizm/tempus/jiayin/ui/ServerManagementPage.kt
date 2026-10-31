@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.eddyizm.tempus.jiayin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddyizm.tempus.R
import com.eddyizm.tempus.jiayin.JiayinViewModel
import com.eddyizm.tempus.jiayin.data.ServerProfile
import kotlinx.coroutines.launch

@Composable fun ServerManagementPage(vm: JiayinViewModel, allowBack: Boolean = true, back: () -> Unit = {}) {
    val state by vm.servers.collectAsState()
    val home by vm.state.collectAsState()
    val active = if (home.configured) vm.repo.account else null


    var menu by remember { mutableStateOf<ServerProfile?>(null) }
    var rename by remember { mutableStateOf<ServerProfile?>(null) }
    var logout by remember { mutableStateOf<ServerProfile?>(null) }
    var remove by remember { mutableStateOf<ServerProfile?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.refreshServers() }
    LazyColumn(Modifier.fillMaxSize().then(if (allowBack) Modifier else Modifier.safeDrawingPadding()), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { PageTitle("音乐服务器", "你的音乐，各自安放", if (allowBack) back else null) { IconAction(R.drawable.ic_add, "添加服务器") { if (!state.busy) vm.openServerEditor() } } }
        item { Text("切换会暂停当前播放和下载，保留进度。", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.busy) item { Column(Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.status, Modifier.padding(top = 8.dp), fontSize = 13.sp) } }
        if (state.error != null) item { Surface(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) { Text(state.error.orEmpty(), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer) } }
        for ((label, values) in listOf(
            "当前使用" to state.profiles.filter { it.id == active },
            "其他服务器" to state.profiles.filter { it.id != active && it.authenticated },
            "已退出的账户" to state.profiles.filter { !it.authenticated },
        )) {
            if (values.isNotEmpty()) item { SectionTitle(label) }
            items(values, key = { it.id }) { profile ->
                val current = profile.id == active
                val size = state.footprints[profile.id]
                Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), shape = RoundedCornerShape(22.dp),
                    color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                                Text("${profile.address}\n${profile.username}", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (current) Text("使用中", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                            IconAction(R.drawable.ic_more_vert, "${profile.name} 更多操作") { if (!state.busy) menu = profile }
                        }
                        Text(when { !profile.authenticated -> "需要重新登录 · 本地数据已保留"; current -> home.connection; else -> "已保存" }, Modifier.padding(top = 12.dp), fontSize = 13.sp)
                        if ((size?.syncedAt ?: 0) > 0) Text("上次同步 " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(size!!.syncedAt)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("手机下载 ${size?.count ?: 0} 首 · ${(size?.bytes ?: 0) / 1024 / 1024} MB${if ((size?.pending ?: 0) > 0) " · ${size?.pending} 项待同步" else ""}", Modifier.padding(top = 5.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (current && vm.repo.offline) TextButton({ vm.switchServer(profile) }, enabled = !state.busy) { Text("连接并同步") }
                        if (!current) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton({ if (profile.authenticated) vm.switchServer(profile) else vm.openServerEditor(profile) }, enabled = !state.busy) { Text(if (profile.authenticated) "切换" else "重新登录") }
                        }
                    }
                }
            }
        }
        item { OutlinedButton({ vm.openServerEditor() }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), enabled = !state.busy) { Text("＋ 添加音乐服务器") } }
        if (!home.configured) item { Text("选择已保存的服务器，或连接新的 Navidrome / Subsonic 服务。退出过的账户需重新登录。", Modifier.padding(horizontal = 24.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    menu?.let { profile ->
        AlertDialog(onDismissRequest = { menu = null }, title = { Text(profile.name) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton({ menu = null; rename = profile }) { Text("重命名") }
                if (profile.authenticated) TextButton({ menu = null; vm.testServer(profile) }) { Text("测试连接") }
                TextButton({ menu = null; vm.openServerEditor(profile) }) { Text(if (profile.authenticated) "编辑连接" else "重新登录") }
                if (profile.authenticated) TextButton({ menu = null; logout = profile }) { Text("退出登录") }
                TextButton({ menu = null; remove = profile }) { Text("移除账户及本地数据", color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton({ menu = null }) { Text("关闭") } })
    }
    rename?.let { profile ->
        var name by remember(profile) { mutableStateOf(profile.name) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("服务器名称") }, text = { OutlinedTextField(name, { name = it }, singleLine = true) }, confirmButton = { TextButton({ vm.renameServer(profile, name); rename = null }, enabled = name.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton({ rename = null }) { Text("取消") } })
    }
    logout?.let { profile ->
        AlertDialog(onDismissRequest = { logout = null }, title = { Text("退出 ${profile.name}？") }, text = { Text("删除该账户的登录凭据，暂停其下载。手机上的曲库、下载、播放进度、歌词和待同步修改均保留，重新登录后恢复。NAS 内容不会改变。") }, confirmButton = { TextButton({ logout = null; vm.logoutServer(profile) }) { Text("退出登录") } }, dismissButton = { TextButton({ logout = null }) { Text("取消") } })
    }
    remove?.let { profile ->
        val size = state.footprints[profile.id]
        AlertDialog(onDismissRequest = { remove = null }, title = { Text("移除 ${profile.name}？") }, text = { Text("将删除此账户的登录信息、手机曲库记录、${size?.count ?: 0} 首下载（${(size?.bytes ?: 0) / 1024 / 1024} MB）及缓存。${size?.pending ?: 0} 项未同步修改将丢失。\n\n仅清理手机数据，不删除 NAS 音乐或服务器歌单。") }, confirmButton = { TextButton({ remove = null; vm.logoutServer(profile, true) }) { Text("移除本地数据", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton({ remove = null }) { Text("取消") } })
    }
    state.offlineTarget?.let { profile ->
        AlertDialog(onDismissRequest = vm::dismissOfflineOffer, title = { Text("暂时无法连接 ${profile.name}") }, text = { Text("可以保留当前账户并重试，也可以进入这个账户的离线曲库。离线时仅播放手机已下载的歌曲。") }, confirmButton = { TextButton({ vm.switchServer(profile, true) }) { Text("进入离线曲库") } }, dismissButton = { TextButton(vm::dismissOfflineOffer) { Text("保留当前账户") } })
    }
}
