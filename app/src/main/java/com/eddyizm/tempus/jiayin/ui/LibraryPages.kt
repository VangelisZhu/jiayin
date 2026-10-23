@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.eddyizm.tempus.jiayin.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddyizm.tempus.R
import com.eddyizm.tempus.jiayin.*
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.playback.PlaybackHub
import sh.calvin.reorderable.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun LibraryPage(ui: HomeState, vm: JiayinViewModel, collection: (MusicList) -> Unit, menu: (Track) -> Unit, search: (SearchScope) -> Unit = {}, returnSearchPath: String? = null, returnSearch: () -> Unit = {}) {
    val scopedRepo = vm.repo
    var category by rememberSaveable { mutableStateOf(scopedRepo.settings.getString("${scopedRepo.account}.browse-library-category", "专辑") ?: "专辑") }
    var sort by rememberSaveable { mutableStateOf("名称") }
    var folder by rememberSaveable { mutableStateOf(scopedRepo.settings.getString("${scopedRepo.account}.browse-folder", "").orEmpty()) }
    val request by vm.folderRequest.collectAsState()
    var requestSeen by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(request) { if (request.request > requestSeen) { requestSeen = request.request; category = request.category; folder = request.path } }
    LaunchedEffect(category, folder) { scopedRepo.settings.edit().putString("${scopedRepo.account}.browse-library-category", category).putString("${scopedRepo.account}.browse-folder", folder).apply() }
    var contextualScope by remember { mutableStateOf(SearchScope()) }
    val scope = when (category) { "歌手", "文件夹" -> contextualScope; "歌曲" -> SearchScope(SearchKind.SONGS); "专辑" -> SearchScope(SearchKind.ALBUMS); else -> SearchScope(SearchKind.PLAYLISTS) }
    val tracks = ui.library.tracks
    val sorted = remember(tracks, sort) { if (sort == "最近加入") tracks.sortedByDescending { it.created } else tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }) }
    val categoryStates = rememberSaveableStateHolder()
    var options by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        CompactTitle("曲库") {
            IconAction(R.drawable.ic_search, "搜索当前曲库分类") { search(scope) }
            Box { IconAction(R.drawable.ic_more_vert, "曲库选项") { options = true }
                DropdownMenu(options, { options = false }) { DropdownMenuItem(text = { Text("同步曲库") }, onClick = { options = false; vm.sync() }) }
            }
        }
        LibraryCategoryTabs(category) { category = it }
        if (category !in listOf("歌手", "文件夹")) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${tracks.size} 首歌曲", Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ sort = if (sort == "名称") "最近加入" else "名称" }) { AppIcon(R.drawable.ic_sort_list, "排序"); Text(sort, fontSize = 12.sp) }
        }
        categoryStates.SaveableStateProvider(category) { when (category) {
            "歌手" -> ArtistBrowser(ui, vm, collection, search, onScopeChanged = { contextualScope = it })
            "文件夹" -> FolderBrowser(ui, vm, menu, search, folder, { folder = it }, returnSearchPath, returnSearch, { contextualScope = it })
            "歌曲" -> {
                val scroll = rememberLazyListState()
                LazyColumn(state = scroll) {
                    item { FlowRow(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button({ vm.start(sorted, source = "全部歌曲") }) { AppIcon(R.drawable.ic_play, "播放"); Text("播放全部") }
                        OutlinedButton({ vm.start(sorted, true, "全库随机") }) { AppIcon(R.drawable.ic_shuffle, "随机"); Text("随机播放") }
                    } }
                    items(sorted, key = { it.id }) { t -> SongRow(t, action = { vm.start(sorted, source = "全部歌曲", index = sorted.indexOf(t)) }, more = { menu(t) }) }
                }
            }
            "专辑" -> {
                val albums = remember(sorted) { sorted.groupBy { it.albumId.ifBlank { "${it.album}:${artistKey(it)}" } }.values.toList() }
                val scroll = rememberLazyGridState()
                LazyVerticalGrid(GridCells.Fixed(2), state = scroll, contentPadding = PaddingValues(24.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    items(albums, key = { it.first().albumId.ifBlank { "${it.first().album}:${artistKey(it.first())}" } }) { album ->
                        val t = album.first(); val ordered = album.sortedWith(compareBy<Track> { it.disc }.thenBy { it.number })
                        Column(Modifier.clickable { collection(MusicList("album:${t.albumId.ifBlank { artistKey(t) + t.album }}", t.album.ifBlank { "未命名专辑" }, ordered.map { it.id }, t.cover)) }) {
                            Cover(t.cover, Modifier.fillMaxWidth().aspectRatio(1f), 16)
                            Text(t.album.ifBlank { "未命名专辑" }, Modifier.padding(top = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                            Text(t.artist, Modifier.padding(top = 5.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            else -> LazyColumn {
                items(ui.library.playlists, key = { it.id }) { p -> ListItem(headlineContent = { Text(p.name) }, supportingContent = { Text("${p.tracks.size} 首${if (p.pending) " · 待同步" else ""}") }, leadingContent = { PlaylistCover(p, tracks, Modifier.size(64.dp)) }, modifier = Modifier.clickable { collection(p) }) }
                if (ui.library.playlists.isEmpty()) item { EmptyState("还没有歌单", "从歌曲菜单加入歌单。") }
            }
        } }
    }
}

/** Fit the whole category bar when possible; retain full labels and touch targets at large fonts. */
@Composable internal fun LibraryCategoryTabs(selected: String, onSelect: (String) -> Unit) {
    val categories = remember { listOf("专辑", "歌曲", "歌手", "歌单", "文件夹") }
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val minimums = categories.map { label ->
            with(density) { measurer.measure(label, style, softWrap = false).size.width.toDp() + 24.dp }.coerceAtLeast(48.dp)
        }
        val gap = 6.dp
        val required = minimums.fold(0.dp) { sum, width -> sum + width } + gap * (categories.size - 1)
        val fits = required <= maxWidth
        val extra = if (fits) (maxWidth - required) / categories.size else 0.dp
        val widths = minimums.map { it + extra }
        val viewport = maxWidth
        LaunchedEffect(selected, viewport, density.fontScale) {
            if (fits) scroll.scrollTo(0) else {
                val index = categories.indexOf(selected)
                val start = widths.take(index).fold(0.dp) { sum, width -> sum + width } + gap * index
                val target = with(density) { (start + widths[index] / 2 - viewport / 2).roundToPx() }
                scroll.animateScrollTo(target.coerceAtLeast(0))
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            categories.forEachIndexed { index, label ->
                val active = selected == label
                val shape = RoundedCornerShape(14.dp)
                Box(Modifier.width(widths[index]).heightIn(min = 48.dp).clip(shape)
                    .selectable(active, role = Role.Tab, onClick = { onSelect(label) })
                    .padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = style, maxLines = 1, softWrap = false,
                        color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (active) Box(Modifier.align(Alignment.BottomCenter).offset(y = 10.dp).width(26.dp).height(3.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun SheetActionPadded(label: String, icon: Int, action: () -> Unit) { Box(Modifier.padding(horizontal = 24.dp)) { SheetAction(icon, label, action) } }
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun TrackPage(title: String, tracks: List<Track>, ui: HomeState, vm: JiayinViewModel, back: () -> Unit, menu: (Track) -> Unit) {
    Column {
        PageTitle(title, "${tracks.size} 首", back)
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button({ vm.start(tracks, source = title) }) { Text("播放全部") }
            OutlinedButton({ vm.start(tracks, true, title) }) { AppIcon(R.drawable.ic_shuffle, "随机播放"); Text("随机播放") }
        }
        LazyColumn { itemsIndexed(tracks, key = { i, t -> "$i:${t.id}" }) { i, t -> SongRow(t, action = { vm.start(tracks, source = title, index = i) }, more = { menu(t) }) } }
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun CollectionPage(list: MusicList, ui: HomeState, vm: JiayinViewModel, back: () -> Unit, menu: (Track) -> Unit, rename: () -> Unit, update: (MusicList) -> Unit) {
    val byId = remember(ui.library.tracks) { ui.library.tracks.associateBy { it.id } }
    val tracks = list.tracks.mapNotNull { byId[it] }
    val editable = ui.library.playlists.any { it.id == list.id }
    var editing by remember(list.id) { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var editIds by remember(list.id, list.tracks) { mutableStateOf(list.tracks) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    val scroll = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(scroll) { from, to ->
        val source = from.index
        val destination = to.index
        editIds = editIds.toMutableList().apply { add(destination, removeAt(source)) }; selected = emptySet()
    }
    Column {
        PageTitle(if (editing) "管理歌单" else "", if (editing) "已选 ${selected.size} 首" else null, back) {
            if (editing) TextButton({ vm.savePlaylist(list.id, list.name, editIds); editing = false }) { Text("保存") }
            else if (editable) IconAction(R.drawable.ic_more_vert, "管理歌单") { editing = true; editIds = list.tracks }
        }
        if (editing) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton({ adding = true }) { Text("批量加入") }
                TextButton({ editIds = editIds.filterIndexed { i, _ -> i !in selected }; selected = emptySet() }) { Text("移除所选") }
                TextButton(rename) { Text("改名") }
                TextButton({ delete = true }) { Text("删除歌单") }
                TextButton({ editing = false }) { Text("取消") }
            }
            LazyColumn(state = scroll) {
                itemsIndexed(editIds, key = { i, id -> "$id#${editIds.take(i).count { it == id }}" }, contentType = { _, _ -> "edit" }) { i, id ->
                    ReorderableItem(reorder, key = "$id#${editIds.take(i).count { it == id }}") { _ ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(i in selected, { selected = if (i in selected) selected - i else selected + i })
                            Text(byId[id]?.title ?: id, Modifier.weight(1f), maxLines = 1)
                            IconButton({}, Modifier.draggableHandle()) { AppIcon(R.drawable.ic_drag_handle, "拖动排序") }
                        }
                    }
                }
            }
        } else LazyColumn {
            item {
                Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PlaylistCover(list, ui.library.tracks, Modifier.size(172.dp)); Text(list.name, Modifier.padding(top = 20.dp), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    Text("${tracks.size} 首${if (list.pending) " · 本地已保存，等待同步" else ""}", Modifier.padding(top = 10.dp, bottom = 22.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button({ vm.start(tracks, source = list.name) }, Modifier.weight(1f).height(48.dp)) { AppIcon(R.drawable.ic_play, "播放全部"); Spacer(Modifier.width(6.dp)); Text("播放全部") }
                        OutlinedButton({ vm.start(tracks, true, list.name) }, Modifier.weight(1f).height(48.dp)) { AppIcon(R.drawable.ic_shuffle, "随机播放"); Spacer(Modifier.width(6.dp)); Text("随机播放") }
                    }
                    TextButton({ vm.download(tracks, list.takeIf { editable }) }) { AppIcon(R.drawable.ic_download, "下载"); Text("下载此歌单") }
                }
            }
            itemsIndexed(tracks, key = { i, t -> "$i:${t.id}" }) { i, t -> SongRow(t, i, action = { vm.start(tracks, source = list.name, index = i) }, more = { menu(t) }) }
            if (tracks.isEmpty()) item { EmptyState("还没有歌曲", "从歌曲右侧菜单加入，或打开歌单管理。") }
        }
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("删除“${list.name}”？") }, text = { Text("只删除歌单，音乐文件会保留。") }, confirmButton = { TextButton({ vm.deletePlaylist(list.id); delete = false; back() }) { Text("删除") } }, dismissButton = { TextButton({ delete = false }) { Text("取消") } })
    if (adding) {
        var query by remember { mutableStateOf("") }; var ids by remember { mutableStateOf(setOf<String>()) }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("选择要加入的歌曲") }, text = {
            Column { OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("搜索歌曲或歌手") })
                LazyColumn(Modifier.heightIn(max = 360.dp)) { items(ui.library.tracks.filter { "$it".contains(query, true) }, key = { it.id }) { t -> Row(Modifier.clickable { ids = if (t.id in ids) ids - t.id else ids + t.id }, verticalAlignment = Alignment.CenterVertically) { Checkbox(t.id in ids, null); Column { Text(t.title, maxLines = 1); Text(t.artist, fontSize = 11.sp) } } } }
            }
        }, confirmButton = { TextButton({ editIds = editIds + ids; adding = false }) { Text("加入 ${ids.size} 首") } }, dismissButton = { TextButton({ adding = false }) { Text("取消") } })
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun RecommendationPage(ui: HomeState, vm: JiayinViewModel, menu: (Track) -> Unit) {
    val byId = remember(ui.library.tracks) { ui.library.tracks.associateBy { it.id } }
    val tracks = ui.recommendations.mapNotNull { byId[it.id] }
    LazyColumn {
        item { PageTitle("为你挑选", "只听你的曲库，越听越懂你") { IconAction(R.drawable.ic_refresh, "换一批") { vm.recommendations() } } }
        item { Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Button({ vm.start(tracks, source = "今日推荐") }, Modifier.weight(1f)) { Text("播放推荐") }; OutlinedButton({ vm.start(tracks, true, "今日推荐") }, Modifier.weight(1f)) { AppIcon(R.drawable.ic_shuffle, "随机播放"); Text("随机播放") } } }
        item { Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { AssistChip({ vm.recommendations() }, label = { Text("今日推荐") }); AssistChip({ vm.rediscover() }, label = { Text("冷门再发现") }) } }
        itemsIndexed(ui.recommendations, key = { _, r -> r.id }) { i, recommendation -> byId[recommendation.id]?.let { t -> SongRow(t, reason = recommendation.reason, action = { vm.start(tracks, source = "为你挑选", index = i) }, more = { menu(t) }) } }
    }
}
