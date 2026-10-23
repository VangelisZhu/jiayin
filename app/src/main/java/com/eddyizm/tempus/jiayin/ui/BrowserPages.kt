package com.eddyizm.tempus.jiayin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddyizm.tempus.R
import com.eddyizm.tempus.jiayin.*
import com.eddyizm.tempus.jiayin.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun ArtistBrowser(ui: HomeState, vm: JiayinViewModel, open: (MusicList) -> Unit, search: (SearchScope) -> Unit, browserSearch: Boolean = false, onScopeChanged: (SearchScope) -> Unit = {}) {
    var region by rememberSaveable { mutableStateOf(vm.repo.settings.getString("${vm.repo.account}.artist-region", "全部") ?: "全部") }
    var type by rememberSaveable { mutableStateOf(vm.repo.settings.getString("${vm.repo.account}.artist-type", "全部") ?: "全部") }
    val artistFilterKey = listOf(ui.index, region, type)
    val artistResult by produceState<Pair<List<Any>, List<IndexedSection<ArtistEntry>>>?>(null, artistFilterKey) {
        value = artistFilterKey to withContext(Dispatchers.Default) { LibraryIndex.sections(ui.index.artists.filter { (region == "全部" || it.region == region) && (type == "全部" || it.profile.type == type) }) { it.section } }
    }
    val entries = artistResult?.takeIf { it.first == artistFilterKey }?.second
    val scrollStates = rememberSaveableStateHolder()
    LaunchedEffect(region, type) { vm.repo.settings.edit().putString("${vm.repo.account}.artist-region", region).putString("${vm.repo.account}.artist-type", type).apply(); onScopeChanged(SearchScope(SearchKind.ARTISTS, region = region, artistType = type)) }
    Column(Modifier.fillMaxSize()) {
        if (browserSearch) CompactTitle("搜索") { IconAction(R.drawable.ic_search, "搜索全部曲库") { search(SearchScope()) } }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ChoiceFilter("", if (region == "全部") "全部地区" else region, listOf("全部地区") + DirectoryRegions.choices.drop(1)) { region = if (it == "全部地区") "全部" else it }
            ChoiceFilter("", if (type == "全部") "全部类型" else type, listOf("全部类型", "男", "女", "乐队/组合", "未分类")) { type = if (it == "全部类型") "全部" else it }
            Spacer(Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)) { Text("按拼音 / A–Z", Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${entries.orEmpty().sumOf { it.entries.size }} 位歌手", fontSize = 12.sp) }
        if (entries?.isEmpty() == true) EmptyState("暂无符合条件的歌手", "可调整地区或类型；在歌手主页补充分类。")
        scrollStates.SaveableStateProvider("$region:$type") {
        val state = rememberLazyListState()
        if (entries != null) IndexedBrowser(entries.orEmpty(), state, { it.id }, Modifier.weight(1f)) { artist ->
            ListItem(headlineContent = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text("${artist.tracks.size} 首 · ${artist.region}", fontSize = 12.sp) }, leadingContent = { Cover(artist.tracks.firstOrNull()?.cover.orEmpty(), Modifier.size(48.dp), 24) }, trailingContent = { IconAction(if (artist.profile.starred) R.drawable.ic_favorite else R.drawable.ic_favorites_outlined, if (artist.profile.starred) "取消收藏${artist.name}" else "收藏${artist.name}") { vm.favoriteArtist(artist.profile) } }, modifier = Modifier.clickable { open(MusicList("artist:${artist.id}", artist.name, artist.tracks.map { it.id }, artist.tracks.firstOrNull()?.cover.orEmpty())) })
        }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun FolderBrowser(ui: HomeState, vm: JiayinViewModel, menu: (Track) -> Unit, search: (SearchScope) -> Unit, location: String, setLocation: (String) -> Unit, returnSearchPath: String? = null, returnSearch: () -> Unit = {}, onScopeChanged: (SearchScope) -> Unit = {}) {
    var flat by rememberSaveable { mutableStateOf(vm.repo.settings.getBoolean("${vm.repo.account}.folder-flat", false)) }
    var region by rememberSaveable { mutableStateOf(vm.repo.settings.getString("${vm.repo.account}.folder-region", "全部") ?: "全部") }
    var classify by remember { mutableStateOf(false) }
    val scrollStates = rememberSaveableStateHolder()
    val folder = ui.index.folders[location]
    val currentLocation = location.takeIf { folder != null } ?: ""
    LaunchedEffect(currentLocation, region, flat) { vm.repo.settings.edit().putString("${vm.repo.account}.folder-region", region).putBoolean("${vm.repo.account}.folder-flat", flat).apply(); onScopeChanged(SearchScope(folder = currentLocation, region = region)) }
    val folderFilterKey = listOf(ui.index, currentLocation, flat, region)
    val folderResult by produceState<Pair<List<Any>, List<IndexedSection<FolderNode>>>?>(null, folderFilterKey) {
        value = folderFilterKey to withContext(Dispatchers.Default) {
            val children = ui.index.folders[currentLocation]?.children.orEmpty().toSet()
            val nodes = ui.index.folderOrder.filter { path -> if (flat) path != currentLocation && DirectoryRegions.contains(currentLocation, path) else path in children }
                .mapNotNull { ui.index.folders[it] }.filter { f -> region == "全部" || f.allTracks.any { ui.index.regions[it] == region } }
            LibraryIndex.sections(nodes) { it.section }
        }
    }
    val filtered = folderResult?.takeIf { it.first == folderFilterKey }?.second
    val songs = remember(ui.index, currentLocation, region) { ui.index.folderTracks(currentLocation, false, region) }
    val allSongs = remember(ui.index, currentLocation, region) { ui.index.folderTracks(currentLocation, true, region) }
    BackHandler(currentLocation.isNotEmpty() || returnSearchPath != null) {
        if (currentLocation == returnSearchPath || currentLocation.isBlank() && returnSearchPath != null) returnSearch() else setLocation(folder?.parent.orEmpty())
    }
    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
            FilterChip(!flat, { flat = false }, { Text("层级") }); FilterChip(flat, { flat = true }, { Text("平铺") })
            ChoiceFilter("地区：", region, DirectoryRegions.choices) { region = it }
            if (currentLocation.isNotBlank() && currentLocation != DirectoryRegions.MISSING) IconAction(R.drawable.ic_more_vert, "设置此目录地区") { classify = true }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ setLocation("") }) { Text("音乐文件夹", fontSize = 12.sp) }
            if (currentLocation == DirectoryRegions.MISSING) Text(" › 未提供路径", fontSize = 12.sp)
            else currentLocation.split('/').filter { it.isNotBlank() }.forEachIndexed { i, part ->
                Text("›", fontSize = 12.sp); TextButton({ setLocation(currentLocation.split('/').take(i + 1).joinToString("/")) }) { Text(part, fontSize = 12.sp) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${filtered.orEmpty().sumOf { it.entries.size }} 个文件夹 · ${allSongs.size} 首歌曲", Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ vm.start(allSongs, source = "包含子目录：${folder?.name ?: "全部目录"}") }) { Text("包含子目录播放", fontSize = 11.sp) }
        }
        scrollStates.SaveableStateProvider("$currentLocation:$flat:$region") {
            val state = rememberLazyListState()
            if (filtered != null) IndexedBrowser(filtered.orEmpty(), state, { it.path }, Modifier.weight(1f), footer = {
                item(key = "direct-title") { Text("当前目录歌曲 · ${songs.size} 首", Modifier.padding(horizontal = 24.dp, vertical = 16.dp), fontSize = 13.sp) }
                if (songs.isNotEmpty()) item(key = "direct-play") { TextButton({ vm.start(songs, source = folder?.name ?: "当前目录") }, Modifier.padding(horizontal = 16.dp)) { Text("播放当前目录") } }
                items(songs, key = { "song:${it.id}" }) { t -> SongRow(t, action = { vm.start(songs, source = folder?.name ?: "当前目录", index = songs.indexOf(t)) }, more = { menu(t) }) }
                if (songs.isEmpty() && filtered.orEmpty().isEmpty()) item { EmptyState("此处没有符合条件的音乐", "可返回上级或切换地区；缺少路径的歌曲在“未提供路径”中。") }
            }) { child ->
                ListItem(headlineContent = { Text(child.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text((if (flat) "${child.parent.orEmpty()}\n" else "") + "${child.allTracks.count { region == "全部" || ui.index.regions[it] == region }} 首 · ${ui.index.folderRegion(child.path)}", fontSize = 11.sp) }, leadingContent = { AppIcon(R.drawable.ic_folder, "文件夹") }, trailingContent = { Text("›", fontSize = 24.sp) }, modifier = Modifier.semantics { contentDescription = "目录：${child.path}" }.clickable { setLocation(child.path) })
            }
        }
    }
    if (classify) AlertDialog(onDismissRequest = { classify = false }, title = { Text("设置此目录地区") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) { Text("仅保存在本机，子目录继承；手动歌手分类优先。不会移动或修改 NAS 文件。", fontSize = 12.sp)
            DirectoryRegions.choices.drop(1).forEach { value -> TextButton({ vm.setFolderRegion(currentLocation, value); classify = false }) { Text(value) } }
            TextButton({ vm.setFolderRegion(currentLocation, null); classify = false }) { Text("清除设置，使用自动识别") }
        }
    }, confirmButton = { TextButton({ classify = false }) { Text("取消") } })
}
