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
@Composable fun LibraryPage(ui: HomeState, vm: JiayinViewModel, collection: (MusicList) -> Unit, menu: (Track) -> Unit) {
    var category by rememberSaveable { mutableStateOf("专辑") }
    var sort by rememberSaveable { mutableStateOf("名称") }
    val tracks = ui.library.tracks
    val sorted = remember(tracks, sort) { if (sort == "最近加入") tracks.sortedByDescending { it.created } else tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }) }
    val categoryStates = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        PageTitle("曲库", "${tracks.size} 首歌曲 · ${tracks.map { it.albumId }.distinct().size} 张专辑") { IconAction(R.drawable.ic_refresh, "同步曲库") { vm.sync() } }
        LibraryCategoryTabs(category) { category = it }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("你的收藏，慢慢听", Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ sort = if (sort == "名称") "最近加入" else "名称" }) { AppIcon(R.drawable.ic_sort_list, "排序"); Text(sort, fontSize = 12.sp) }
        }
        categoryStates.SaveableStateProvider(category) { when (category) {
            "歌曲" -> {
                val scroll = rememberLazyListState()
                LazyColumn(state = scroll) {
                    item { Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button({ vm.start(sorted, source = "全部歌曲") }) { AppIcon(R.drawable.ic_play, "播放"); Text("播放全部") }
                        OutlinedButton({ vm.start(sorted, true, "全库随机") }) { AppIcon(R.drawable.ic_shuffle, "随机"); Text("随机播放") }
                    } }
                    items(sorted, key = { it.id }) { t -> SongRow(t, action = { vm.start(sorted, source = "全部歌曲", index = sorted.indexOf(t)) }, more = { menu(t) }) }
                }
            }
            "专辑" -> {
                val albums = remember(sorted) { sorted.groupBy { it.albumId.ifBlank { "${it.album}:${it.artist}" } }.values.toList() }
                val scroll = rememberLazyGridState()
                LazyVerticalGrid(GridCells.Fixed(2), state = scroll, contentPadding = PaddingValues(24.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    items(albums, key = { it.first().albumId.ifBlank { "${it.first().album}:${it.first().artist}" } }) { album ->
                        val t = album.first(); val ordered = album.sortedWith(compareBy<Track> { it.disc }.thenBy { it.number })
                        Column(Modifier.clickable { collection(MusicList("album:${t.albumId}", t.album.ifBlank { "未命名专辑" }, ordered.map { it.id }, t.cover)) }) {
                            Cover(t.cover, Modifier.fillMaxWidth().aspectRatio(1f), 16)
                            Text(t.album.ifBlank { "未命名专辑" }, Modifier.padding(top = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(t.artist, Modifier.padding(top = 5.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            "歌单" -> LazyColumn {
                items(ui.library.playlists, key = { it.id }) { p -> ListItem(headlineContent = { Text(p.name) }, supportingContent = { Text("${p.tracks.size} 首${if (p.pending) " · 待同步" else ""}") }, leadingContent = { PlaylistCover(p, tracks, Modifier.size(64.dp)) }, modifier = Modifier.clickable { collection(p) }) }
                if (ui.library.playlists.isEmpty()) item { EmptyState("还没有歌单", "在首页或“我的”中创建，也可以从歌曲菜单加入。") }
            }
            else -> {
                val folders = remember(sorted, category) { sorted.groupBy { if (category == "歌手") it.artist else it.path.substringBeforeLast('/', "music") }.toSortedMap() }
                LazyColumn {
                    items(folders.entries.toList(), key = { it.key }) { group -> ListItem(headlineContent = { Text(group.key, maxLines = 2) }, supportingContent = { Text("${group.value.size} 首歌曲") }, leadingContent = { if (category == "歌手") Cover(group.value.first().cover, Modifier.size(54.dp), 27) else AppIcon(R.drawable.ic_folder, "文件夹") }, modifier = Modifier.clickable { collection(MusicList("$category:${group.key}", group.key, group.value.map { it.id }, group.value.first().cover)) }) }
                }
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
                    .background(if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface, shape)
                    .border(1.dp, if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.outlineVariant, shape)
                    .selectable(active, role = Role.Tab, onClick = { onSelect(label) })
                    .padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = style, maxLines = 1, softWrap = false,
                        color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun SearchPage(ui: HomeState, vm: JiayinViewModel, collection: (MusicList) -> Unit, menu: (Track) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    LaunchedEffect(query) { kotlinx.coroutines.delay(180); debounced = query.trim() }
    val results = remember(ui.library.tracks, debounced) { if (debounced.isBlank()) emptyList() else ui.library.tracks.filter { "${it.title} ${it.artist} ${it.album}".contains(debounced, true) } }
    Column {
        PageTitle("搜索", "找一首，想听的歌")
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp), singleLine = true, placeholder = { Text("歌曲、歌手、专辑") }, leadingIcon = { AppIcon(R.drawable.ic_search, "搜索") }, trailingIcon = { if (query.isNotEmpty()) IconAction(R.drawable.ic_close, "清空") { query = "" } }, shape = RoundedCornerShape(18.dp))
        if (debounced.isBlank()) {
            SectionTitle("随时想听")
            SheetActionPadded("我喜欢", R.drawable.ic_favorite) { collection(MusicList("favorites", "我喜欢", ui.library.tracks.filter { it.starred }.map { it.id })) }
            SheetActionPadded("全库随机", R.drawable.ic_shuffle) { vm.start(ui.library.tracks, true, "全库随机") }
        } else LazyColumn(contentPadding = PaddingValues(top = 16.dp)) {
            item { Text("找到 ${results.size} 首歌曲", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (results.isEmpty()) item { EmptyState("没有找到", "试试歌曲名、歌手或专辑名的一部分。") }
            items(results, key = { it.id }) { t -> SongRow(t, action = { vm.start(results, source = "搜索：$debounced", index = results.indexOf(t)) }, more = { menu(t) }) }
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
