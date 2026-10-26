@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.eddyizm.tempus.jiayin.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eddyizm.tempus.jiayin.JiayinViewModel
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.ManagerCorrections
import com.eddyizm.tempus.jiayin.data.str
import com.eddyizm.tempus.jiayin.data.objects
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.*

data class CorrectionTarget(val tracks: List<Track> = emptyList(), val kind: String = "song", val title: String = "曲库整理")

@Composable fun CorrectionPage(vm: JiayinViewModel, target: CorrectionTarget, close: () -> Unit) {
    val repo = remember { vm.repo.scoped() }
    val client = remember { ManagerCorrections(repo) }
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(repo.settings.getString("${repo.account}.manager-url", "").orEmpty()) }
    var member by remember { mutableStateOf<JsonObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var page by remember { mutableStateOf("connect") }
    var query by remember { mutableStateOf(target.tracks.firstOrNull()?.let { when (target.kind) { "artist" -> it.artist; "album" -> "${it.album} ${it.artist}"; else -> "${it.title} ${it.artist}" } }.orEmpty()) }
    var kind by remember { mutableStateOf(target.kind) }
    var candidates by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var selected by remember { mutableStateOf<JsonObject?>(null) }
    var request by remember { mutableStateOf<JsonObject?>(null) }
    var requests by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var bulk by remember { mutableStateOf(false) }
    val editable = remember { mutableStateMapOf<String, String>() }
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    var lyrics by remember { mutableStateOf("") }
    var translation by remember { mutableStateOf("") }
    var useLyrics by remember { mutableStateOf(false) }
    var useTranslation by remember { mutableStateOf(false) }
    var useCover by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val labels = linkedMapOf("title" to "歌名", "artists" to "演唱者（每行一位）", "album" to "专辑", "album_artists" to "专辑歌手（每行一位）", "date" to "发行日期", "genres" to "曲风（每行一个）", "language" to "语种", "region" to "整理地区", "album_version" to "版本")
    fun launch(block: suspend () -> Unit) { if (!busy) { busy = true; message = ""; scope.launch { try { block() } catch (e: Exception) { message = e.message ?: "操作未完成" } finally { busy = false } } } }
    fun readable(value: JsonElement?): String = if (value == null || value.isJsonNull) "空" else if (value.isJsonArray) value.asJsonArray.joinToString(" / ") { it.asString } else value.asString
    fun values(t: Track) = mapOf("title" to t.title, "artists" to t.performers().joinToString("\n") { it.name }, "album" to t.album, "album_artists" to t.albumCredits().joinToString("\n") { it.name }, "genres" to t.genreNames().joinToString("\n"), "language" to t.language.orEmpty(), "region" to t.sharedRegion.orEmpty(), "album_version" to t.albumVersion.orEmpty(), "date" to "")
    fun edit(item: JsonObject?) {
        selected = item; item?.getAsJsonArray("warnings")?.let { message = it.joinToString("\n") { warning -> warning.asString } }; checked.clear(); editable.clear(); useLyrics = false; useTranslation = false; useCover = false
        target.tracks.firstOrNull()?.let { editable.putAll(values(it)) }
        item?.getAsJsonObject("values")?.entrySet()?.forEach { (key, value) -> if (key in labels) editable[key] = if (value.isJsonArray) value.asJsonArray.joinToString("\n") { it.asString } else value.asString }
        lyrics = item?.str("lyrics").orEmpty(); translation = item?.str("translation").orEmpty(); page = "edit"
    }
    LaunchedEffect(Unit) {
        if (!repo.vault.get("${repo.account}.manager-member").isNullOrBlank()) launch {
            member = client.request("me").asJsonObject.getAsJsonObject("member"); page = "home"
        }
    }
    LaunchedEffect(page) {
        if (page == "requests") while (isActive) {
            delay(5000)
            try { requests = client.request("corrections").asJsonArray.map { it.asJsonObject }.reversed() }
            catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                PageTitle(target.title, "共享资料 · 修改影响家庭曲库", close)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
                    if (page == "connect") {
                        Text("连接自己的曲库后台，使用当前音乐账户申请整理权限。未连接时仍可正常听歌。")
                        OutlinedTextField(address, { address = it }, label = { Text("后台地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button({ launch { val r = client.pair(address); member = r.getAsJsonObject("member"); page = "home" } }, enabled = !busy && address.isNotBlank()) { Text("连接当前账户") }
                    } else {
                        val approved = member?.get("approved")?.asBoolean == true
                        val role = member?.str("role").orEmpty()
                        Text("${repo.user} · ${if (!approved) "等待管理员批准加入" else when (role) { "direct" -> "可直接修改"; "request" -> "提交修改申请"; else -> "只读" }}")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({ page = "home" }, enabled = !busy) { Text("纠正资料") }
                            TextButton({ launch { requests = client.request("corrections").asJsonArray.map { it.asJsonObject }.reversed(); page = "requests" } }, enabled = !busy && approved) { Text("我的申请") }
                            TextButton({ launch { member = client.request("me").asJsonObject.getAsJsonObject("member"); vm.refreshCorrections(); if (page == "requests") requests = client.request("corrections").asJsonArray.map { it.asJsonObject }.reversed(); message = "已刷新权限，正在更新已核对资料" } }, enabled = !busy) { Text("刷新") }
                            TextButton({ launch { client.request("logout", JsonObject()); repo.vault.remove("${repo.account}.manager-member"); member = null; page = "connect" } }, enabled = !busy) { Text("断开整理") }
                        }
                        if (!approved) Text("请管理员打开网页后台 → 成员与权限，批准此账户。")
                        else if (page == "home") {
                            if (target.tracks.isEmpty()) Text("从播放页、歌曲菜单、专辑或歌手主页打开“纠正信息”。")
                            else if (role == "readonly") Text("当前只有只读权限，请联系管理员调整。")
                            else {
                                OutlinedTextField(query, { query = it }, label = { Text("歌名／歌手／专辑") }, modifier = Modifier.fillMaxWidth())
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("song" to "歌曲", "album" to "专辑", "artist" to "歌手").forEach { (key, label) -> FilterChip(kind == key, { kind = key }, label = { Text(label) }) }
                                }
                                Button({ launch { val term = query; val selectedKind = kind; candidates = emptyList(); coroutineScope { listOf("qq", "netease").forEach { source -> this.launch { try { val r = client.request("sources/search", JsonObject().apply { addProperty("query", term); addProperty("kind", selectedKind); addProperty("source", source) }).asJsonObject; candidates = candidates + r.objects("items"); val errors = r.objects("errors").joinToString("\n") { it.str("source") + " · " + it.str("message") }; if (errors.isNotBlank()) message += errors + "\n" } catch (e: Exception) { message += source + " 暂不可用\n" } } } } } }, enabled = !busy && query.isNotBlank()) { Text("检索 QQ / 网易云") }
                                TextButton({ edit(null) }, enabled = !busy) { Text("手动纠正") }
                                candidates.forEach { item -> OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                    Text(item.str("title")); Text(item.str("source") + " · " + item.getAsJsonObject("values").let { v -> v.getAsJsonArray("artists")?.joinToString(" / ") { it.asString }.orEmpty() + " · " + v.str("album") })
                                    TextButton({ launch { edit(client.request("sources/" + item.str("id")).asJsonObject) } }, enabled = !busy) { Text("查看候选") }
                                } } }
                            }
                        } else if (page == "edit") {
                            Text("勾选需要修改的字段。只改变当前选中的曲目；不会自动移动目录。")
                            target.tracks.firstOrNull()?.let { Text("当前：${it.title} · ${it.artist} · ${it.album}\n时长 ${it.duration} 秒 / 来源 ${selected?.get("duration") ?: "未知"} 秒") }
                            if (target.tracks.size > 1) Row { Checkbox(bulk, { bulk = it }); Text("应用到此页面全部 ${target.tracks.size} 首（谨慎核对范围）") }
                            labels.forEach { (key, label) ->
                                if (!(bulk && key == "title")) {
                                    Row { Checkbox(checked[key] == true, { checked[key] = it }); Text(label) }
                                    Text("原值：" + target.tracks.firstOrNull()?.let { values(it)[key] }.orEmpty(), style = MaterialTheme.typography.bodySmall)
                                    OutlinedTextField(editable[key].orEmpty(), { editable[key] = it; checked[key] = true }, modifier = Modifier.fillMaxWidth(), label = { Text(label) })
                                }
                            }
                            if (!bulk) {
                                Row { Checkbox(useLyrics, { useLyrics = it }); Text("更新原文歌词") }
                                OutlinedTextField(lyrics, { lyrics = it }, Modifier.fillMaxWidth(), label = { Text("原文歌词") }, maxLines = 8)
                                Row { Checkbox(useTranslation, { useTranslation = it }); Text("更新中文译文（独立轨道）") }
                                OutlinedTextField(translation, { translation = it }, Modifier.fillMaxWidth(), label = { Text("中文译文") }, maxLines = 6)
                            }
                            if (!selected?.str("cover_url").isNullOrBlank()) {
                                Cover("ir_" + android.util.Base64.encodeToString(selected!!.str("cover_url").toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP), Modifier.size(144.dp))
                                Row { Checkbox(useCover, { useCover = it }); Text("替换所选歌曲内嵌封面") }
                            }
                            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("说明（可选）") })
                            Button({ launch {
                                val body = JsonObject().apply {
                                    add("ids", JsonArray().apply { (if (bulk) target.tracks else target.tracks.take(1)).forEach { add(it.id) } })
                                    add("values", JsonObject().apply { labels.keys.filter { checked[it] == true && !(bulk && it == "title") }.forEach { key -> if (key in listOf("artists", "album_artists", "genres")) add(key, JsonArray().apply { editable[key].orEmpty().lines().map(String::trim).filter(String::isNotBlank).forEach { add(it) } }) else addProperty(key, editable[key].orEmpty()) } })
                                    addProperty("candidate_id", selected?.str("id").orEmpty()); addProperty("cover", useCover); addProperty("note", note)
                                    if (!bulk && useLyrics) addProperty("lyrics", lyrics)
                                    if (!bulk && useTranslation) addProperty("translation", translation)
                                }
                                request = client.request("corrections/preview", body).asJsonObject; page = "preview"
                            } }, enabled = !busy) { Text("生成修改预览") }
                        } else if (page == "preview") {
                            Text("以下修改会写回家庭共享音乐文件；执行前会建立备份。")
                            request?.objects("preview")?.forEach { p -> Text(p.str("title")); p.getAsJsonObject("values")?.entrySet()?.forEach { (key, value) -> Text((labels[key] ?: if (key == "lyrics") "歌词" else "关联信息") + "\n原值：" + readable(p.getAsJsonObject("before")?.get(key)) + "\n改为：" + readable(value)) }; if (p.get("cover")?.asBoolean == true) Text("包含封面替换") }
                            Button({ launch { val playing = com.eddyizm.tempus.jiayin.playback.PlaybackHub.state.value
                                if (playing.playing) client.request("playback", JsonObject().apply { add("ids", JsonArray().apply { playing.current?.let { add(it.id) } }) })
                                val r = client.request("corrections/${request!!.str("id")}/submit", JsonObject()).asJsonObject; page = "home"; message = if (r.str("status") == "pending") "已提交申请，等待管理员审核" else "已确认，后台任务正在处理；请在我的申请查看进度" } }, enabled = !busy) { Text(if (role == "direct") "确认并应用" else "提交修改申请") }
                            TextButton({ page = "edit" }, enabled = !busy) { Text("返回调整") }
                        } else if (page == "requests") {
                            Text("最近 50 条记录，每 5 秒刷新；完整历史可在网页后台查看。", style = MaterialTheme.typography.bodySmall)
                            if (requests.isEmpty()) Text("尚无修改申请。")
                            requests.forEach { r -> OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(r.objects("preview").map { it.str("title") }.distinct().joinToString("、"))
                                Text(when (r.str("status")) { "draft" -> "草稿"; "pending" -> "待审核"; "approved" -> "已批准"; "executing" -> "正在执行"; "waiting_playback" -> "等待歌曲播放结束"; "awaiting_scan" -> "已写入，等待扫描核对"; "verified" -> "已完成核对"; "undone" -> "已回退"; "rejected" -> "已拒绝"; "paused" -> "已暂停"; else -> "处理未完成" })
                                if (r.str("stage").isNotBlank() && r.str("status") == "executing") Text(r.str("stage"))
                                r.getAsJsonObject("review")?.let { Text("审核：" + it.str("reason")) }; if (r.str("error").isNotBlank()) Text(r.str("error"))
                            } } }
                        }
                    }
                }
            }
        }
    }
}
