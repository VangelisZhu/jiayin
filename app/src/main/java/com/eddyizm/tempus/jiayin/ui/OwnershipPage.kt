@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.eddyizm.tempus.jiayin.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eddyizm.tempus.jiayin.JiayinViewModel
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.*
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import java.util.UUID

internal fun ownershipStatus(status: String) = when (status) {
    "draft" -> "待提交"; "pending" -> "待管理员审核"; "published" -> "已发布"; "partial" -> "部分通过"
    "rejected" -> "已拒绝"; "withdrawn" -> "已撤回"; "conflict" -> "内容冲突，请重新预览"; "undone" -> "发布已撤销"
    else -> status
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun OwnershipPage(vm: JiayinViewModel, source: List<Track>, close: () -> Unit) {
    val repo = remember { vm.repo.scoped() }
    val store = remember { OwnershipRepository(repo) }
    val scope = rememberCoroutineScope()
    val ui by vm.state.collectAsState()
    var tracks by remember { mutableStateOf(source.distinctBy { it.id }) }
    var selected by remember { mutableStateOf(source.map { it.id }.toSet()) }
    var target by remember { mutableStateOf<ArtistRef?>(null) }
    var targetQuery by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(if (source.isEmpty()) "personal" else "edit") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<JsonObject?>(null) }
    var snapshots by remember { mutableStateOf<List<PersonalOwnership>>(emptyList()) }
    var requests by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var requestOffset by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<JsonObject?>(null) }
    var personal by remember { mutableStateOf<List<PersonalOwnership>>(emptyList()) }
    var shared by remember { mutableStateOf<List<SharedOwnership>>(emptyList()) }
    var retryId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var retryPayload by remember { mutableStateOf("") }
    var restoreConfirmation by remember { mutableStateOf<Set<String>?>(null) }
    fun launch(block: suspend () -> Unit) {
        if (busy) return
        busy = true; message = ""
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "操作未完成，可重试" }
            finally { busy = false }
        }
    }
    suspend fun reload() {
        withContext(Dispatchers.IO) { personal = store.personal(); shared = store.shared() }
        vm.refreshLocal()
    }
    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(page) {
        if (page == "requests") while (isActive) {
            delay(5000)
            if (!busy) try { requests = store.requests(requestOffset); store.refresh(); reload() }
            catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }
    val canConnect = !repo.vault.get("${repo.account}.manager-member").isNullOrBlank() && !repo.offline
    Dialog(onDismissRequest = { if (!busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                PageTitle("作品归属", "个人整理即时生效，共享修改需管理员审核", { if (!busy) close() })
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ page = "personal" }, enabled = !busy) { Text("个人整理") }
                    if (tracks.isNotEmpty()) TextButton({ page = "edit" }, enabled = !busy) { Text("编辑归属") }
                    TextButton({ launch { store.capabilities(); requestOffset = 0; requests = store.requests(); page = "requests" } }, enabled = !busy && canConnect) { Text("我的共享申请") }
                }
                if (message.isNotBlank()) Text(message, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary)
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (page == "personal") {
                        item { Text("仅当前手机和音乐账户使用。恢复默认后采用最新共享归属；没有共享设置时采用服务器原归属。") }
                        if (personal.isEmpty()) item { Text("还没有个人作品归属。从歌曲菜单或歌手主页开始整理。") }
                        items(personal, key = { it.trackId }) { row ->
                            val song = ui.index.tracks[row.trackId]
                            val inherited = shared.find { it.trackId == row.trackId }?.target
                            ListItem(headlineContent = { Text(song?.title ?: "歌曲暂未出现在曲库") }, supportingContent = { Text("个人：${row.target.name}\n默认：${inherited?.name ?: song?.performers()?.joinToString(" / ") { it.name }.orEmpty()}${if (inherited != null && inherited != row.target) " · 共享归属已更新" else ""}") },
                                trailingContent = { TextButton({ restoreConfirmation = setOf(row.trackId) }, enabled = !busy && song != null) { Text("恢复默认") } },
                                modifier = Modifier.clickable(enabled = !busy && song != null) { tracks = listOfNotNull(song); selected = setOf(row.trackId); target = row.target; page = "edit" })
                        }
                    } else if (page == "edit") {
                        item {
                            Text("选择现有歌手或新建归属分组。仅整理下面勾选的歌曲；原有合唱署名保留。")
                            Text("已选择 ${selected.size} / ${tracks.size} 首", Modifier.padding(top = 12.dp))
                            Row { TextButton({ selected = tracks.map { it.id }.toSet() }, enabled = !busy) { Text("全选") }; TextButton({ selected = emptySet() }, enabled = !busy) { Text("清空选择") } }
                            OutlinedTextField(targetQuery, { targetQuery = it }, label = { Text("搜索目标歌手／新分组名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                            Text("归入：${target?.name ?: "请选择歌手"}", Modifier.padding(top = 8.dp))
                        }
                        val options = ui.index.artists.filter { targetQuery.isNotBlank() && it.name.contains(targetQuery, true) }.take(12)
                        items(options, key = { "target:${it.id}" }) { artist ->
                            TextButton({ target = ArtistRef(artist.id.takeUnless { it.startsWith("name:") }.orEmpty(), artist.name) }, enabled = !busy) {
                                Text("${artist.name} · ${artist.tracks.size} 首" + if (options.count { it.name == artist.name } > 1) " · ID ${artist.id}" else "")
                            }
                        }
                        if (targetQuery.isNotBlank()) item { TextButton({ target = ArtistRef(name = targetQuery.trim()) }, enabled = !busy && targetQuery.trim().length <= 200) { Text("使用名称建立分组：${targetQuery.trim()}") } }
                        item {
                            OutlinedTextField(note, { note = it.take(1000) }, label = { Text("整理理由（提交共享时附带）") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                            Button({ launch { store.save(selected, target); reload(); message = "已保存个人整理，仅对当前账户生效；可另外提交共享审核" } }, enabled = !busy && selected.isNotEmpty() && target != null) { Text("保存个人整理") }
                            TextButton({ launch {
                                store.capabilities()
                                val payload = selected.sorted().joinToString("\u0000") + repo.gson.toJson(target) + note
                                if (payload != retryPayload) { retryId = UUID.randomUUID().toString(); retryPayload = payload }
                                snapshots = withContext(Dispatchers.IO) { store.personal().filter { it.trackId in selected && it.target == target } }
                                preview = store.preview(selected, requireNotNull(target), retryId, note); retryPayload = ""; page = "preview"
                            } }, enabled = !busy && selected.isNotEmpty() && selected.size <= 1000 && target != null && canConnect) { Text("预览并提交共享审核…") }
                            if (!canConnect) Text("个人整理可离线使用。共享审核请先在设置 → 资料纠正连接后台并获得申请权限。")
                            if (selected.size > 1000) Text("共享申请每次最多 1000 首，请分批勾选。个人整理不受此限制。")
                            TextButton({ restoreConfirmation = selected }, enabled = !busy && selected.isNotEmpty()) { Text("选中歌曲恢复默认归属…") }
                        }
                        items(tracks, key = { "song:${it.id}" }) { song ->
                            Row(Modifier.fillMaxWidth().toggleable(value = song.id in selected, enabled = !busy, role = Role.Checkbox,
                                onValueChange = { checked -> selected = if (checked) selected + song.id else selected - song.id })) {
                                Checkbox(song.id in selected, null)
                                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                    Text(song.title)
                                    val owner = ui.index.ownership[song.id]?.name ?: song.performers().joinToString(" / ") { it.name }
                                    Text("归属：$owner → ${target?.name ?: "待选择"}", style = MaterialTheme.typography.bodySmall)
                                    Text("演唱：${song.artist}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    } else if (page == "preview") {
                        item {
                            Text("核对共享建议。提交后等待管理员批准；这一步不会自动保存个人整理。")
                            Button({ launch {
                                val item = store.submit(requireNotNull(preview).str("id"), snapshots)
                                message = ownershipStatus(item.str("status")); requestOffset = 0; requests = store.requests(); page = "requests"
                            } }, enabled = !busy) { Text("提交管理员审核") }
                            TextButton({ page = "edit" }, enabled = !busy) { Text("返回修改") }
                        }
                        items(preview?.objects("entries").orEmpty(), key = { it.str("track_id") }) { entry -> OwnershipEntry(entry) }
                    } else if (page == "details") {
                        item { TextButton({ page = "requests" }, enabled = !busy) { Text("返回申请列表") } }
                        items(detail?.objects("entries").orEmpty(), key = { it.str("track_id") }) { OwnershipEntry(it) }
                    } else if (page == "requests") {
                        item { TextButton({ launch { requests = store.requests(requestOffset); store.refresh(); reload(); message = "已更新申请与共享归属" } }, enabled = !busy) { Text("刷新申请与共享归属") } }
                        if (requests.isEmpty()) item { Text("还没有共享归属申请。") }
                        items(requests, key = { it.str("id") }) { item ->
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(ownershipStatus(item.str("status")), style = MaterialTheme.typography.titleMedium)
                                Text("${item.get("entry_count")?.asInt ?: item.objects("entries").size} 首 · ${item.str("note")}")
                                if (item.str("reason").isNotBlank()) Text("审核说明：${item.str("reason")}")
                                item.objects("entries").forEach { OwnershipEntry(it) }
                                TextButton({ launch { detail = store.detail(item.str("id")); page = "details" } }, enabled = !busy) { Text("查看全部明细") }
                                if (item.str("status") == "draft") TextButton({ launch { preview = store.detail(item.str("id")); snapshots = emptyList(); page = "preview" } }, enabled = !busy) { Text("继续核对并提交") }
                                if (item.str("status") in listOf("draft", "pending", "conflict")) TextButton({ launch { store.withdraw(item.str("id")); requests = store.requests(requestOffset) } }, enabled = !busy) { Text("撤回申请") }
                            } }
                        }
                        item { Row {
                            TextButton({ launch { requestOffset = (requestOffset - 50).coerceAtLeast(0); requests = store.requests(requestOffset) } }, enabled = !busy && requestOffset > 0) { Text("较新申请") }
                            TextButton({ launch { requestOffset += 50; requests = store.requests(requestOffset) } }, enabled = !busy && requests.size == 50) { Text("更早申请") }
                        } }
                    }
                }
            }
        }
    }
    restoreConfirmation?.let { ids ->
        val defaults = tracks.filter { it.id in ids }.map { song -> shared.find { it.trackId == song.id }?.target?.name ?: song.performers().joinToString(" / ") { it.name } }.distinct()
        AlertDialog(onDismissRequest = { if (!busy) restoreConfirmation = null }, title = { Text("恢复 ${ids.size} 首歌曲的默认归属？") },
            text = { Text("将采用共享设置或服务器原归属${if (defaults.isNotEmpty()) "：${defaults.take(5).joinToString("、")}" else ""}。个人整理会清除。") },
            confirmButton = { TextButton({ launch { store.save(ids, null); reload(); restoreConfirmation = null; message = "已恢复默认归属" } }, enabled = !busy) { Text("恢复默认") } },
            dismissButton = { TextButton({ restoreConfirmation = null }, enabled = !busy) { Text("取消") } })
    }
}

@Composable private fun OwnershipEntry(entry: JsonObject) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(entry.str("title"))
        val before = entry.get("before_target")?.takeUnless { it.isJsonNull }?.asJsonObject?.str("name")
            ?: entry.getAsJsonArray("original_artists")?.joinToString(" / ") { it.asString }.orEmpty()
        Text("$before → ${entry.getAsJsonObject("target")?.str("name").orEmpty()}", style = MaterialTheme.typography.bodySmall)
        Text("演唱：${entry.str("credit")}", style = MaterialTheme.typography.bodySmall)
        if (entry.has("result")) Text(if (entry.str("result") == "published") "已通过" else "未通过", style = MaterialTheme.typography.labelMedium)
    }
}
