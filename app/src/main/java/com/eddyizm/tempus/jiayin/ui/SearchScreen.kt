@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.eddyizm.tempus.jiayin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddyizm.tempus.R
import com.eddyizm.tempus.jiayin.*
import com.eddyizm.tempus.jiayin.core.*
import kotlinx.coroutines.delay

@Composable fun HighlightedLabel(title: String, query: String) {
    val at = title.indexOf(query.trim(), ignoreCase = true)
    val value = buildAnnotatedString {
        append(title)
        if (at >= 0 && query.isNotBlank()) addStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, background = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .55f)), at, at + query.trim().length)
    }
    Text(value, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun DedicatedSearch(ui: HomeState, state: SearchUiState, vm: JiayinViewModel, select: (SearchDocument) -> Unit, menu: (Track) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var scopePicker by remember { mutableStateOf(false) }
    val resultKey = "${state.query}\u0000${state.scope}\u0000${state.category}"
    var count by rememberSaveable(resultKey) { mutableIntStateOf(50) }
    val resultScroll = rememberLazyListState()
    var previousResultKey by rememberSaveable { mutableStateOf(resultKey) }
    LaunchedEffect(resultKey) { if (previousResultKey != resultKey) { resultScroll.scrollToItem(0); previousResultKey = resultKey } }
    val ime = WindowInsets.isImeVisible
    val exit = { keyboard?.hide(); vm.hideSearch() }
    BackHandler { if (ime) keyboard?.hide() else exit() }
    LaunchedEffect(Unit) { if (state.focusKeyboard) { delay(100); focus.requestFocus(); keyboard?.show() } }
    // Register this overlay as the hit target without cancelling its children's scrolling.
    Surface(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Final) } }) {
        AdaptiveBrowserPage(header = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(R.drawable.ic_arrow_back, "返回搜索来源") { if (ime) keyboard?.hide() else exit() }
                BasicTextField(state.query, { vm.updateSearch(it) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp)).focusRequester(focus).semantics { contentDescription = "搜索关键词" }, singleLine = true, textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm.rememberSearch(); keyboard?.hide() }), decorationBox = { field ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(R.drawable.ic_search, "搜索"); Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) { if (state.query.isEmpty()) Text("搜索歌曲、歌手、专辑…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1); field() }
                        if (state.query.isNotEmpty()) IconAction(R.drawable.ic_close, "清空搜索") { vm.updateSearch("") } else Spacer(Modifier.width(12.dp))
                    }
                })
                TextButton(exit) { Text("取消", fontSize = 13.sp) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip({ scopePicker = true }, label = { Text("${state.scope.label} ▾", fontSize = 12.sp) })
                if (state.busy) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(16.dp), strokeWidth = 2.dp)
            }
            val kinds = when {
                state.scope.kind != SearchKind.ALL -> listOf(state.scope.kind)
                state.scope.folder != null -> listOf(SearchKind.ALL, SearchKind.SONGS, SearchKind.FOLDERS)
                state.scope.artist != null -> listOf(SearchKind.ALL, SearchKind.SONGS, SearchKind.ALBUMS)
                else -> SearchKind.entries
            }
            FilterChips(kinds.map { it.label }, (if (state.scope.kind != SearchKind.ALL) state.scope.kind else state.category).label) { label -> vm.updateSearch(state.query, category = SearchKind.entries.first { it.label == label }) }
        }, content = {
            LazyColumn(Modifier.fillMaxSize(), state = resultScroll, contentPadding = PaddingValues(bottom = 24.dp)) {
                if (state.query.isBlank()) {
                    item { Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text("最近搜索", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); TextButton({ vm.deleteSearchHistory() }, enabled = state.history.isNotEmpty()) { Text("清空记录") } } }
                    items(state.history, key = { normalizeSearch(it.query) }) { h -> ListItem(headlineContent = { Text(h.query) }, trailingContent = { IconAction(R.drawable.ic_close, "删除搜索记录：${h.query}") { vm.deleteSearchHistory(h.query) } }, modifier = Modifier.clickable { vm.updateSearch(h.query) }) }
                    if (state.history.isEmpty()) item { EmptyState("找一首想听的歌", "搜索仅使用已同步的本地曲库信息。") }
                } else if (!state.busy && state.hits.isEmpty()) item { EmptyState("当前范围没有找到", if (state.scope != SearchScope()) "可切换到全部曲库，或换一个关键词。" else "试试歌曲名、歌手、专辑或文件夹名称。") }
                else if (state.category == SearchKind.ALL && state.scope.kind == SearchKind.ALL) {
                    SearchKind.entries.filter { it != SearchKind.ALL }.forEach { kind ->
                        val rows = state.hits.filter { it.kind == kind }
                        if (rows.isNotEmpty()) {
                            item(key = "heading:$kind") { Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text(kind.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); TextButton({ vm.updateSearch(state.query, category = kind); keyboard?.hide() }) { Text("查看全部 ${rows.size}", fontSize = 12.sp) } } }
                            items(rows.take(3), key = { "${it.kind}:${it.id}" }) { hit -> SearchResultRow(hit, state.query, { vm.rememberSearch(); keyboard?.hide(); select(hit) }, { ui.index.tracks[hit.id]?.let(menu) }) }
                        }
                    }
                } else {
                    item { Text("找到 ${state.hits.size} 项", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), fontSize = 12.sp) }
                    items(state.hits.take(count), key = { "${it.kind}:${it.id}" }) { hit -> SearchResultRow(hit, state.query, { vm.rememberSearch(); keyboard?.hide(); select(hit) }, { ui.index.tracks[hit.id]?.let(menu) }) }
                    if (state.hits.size > count) item { TextButton({ count += 50 }, Modifier.fillMaxWidth()) { Text("加载更多") } }
                }
            }
        })
    }
    if (scopePicker) AlertDialog(onDismissRequest = { scopePicker = false }, title = { Text("搜索范围") }, text = { Column {
        Text(state.scope.label)
        state.scope.folder?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp) }
        TextButton({ vm.updateSearch(state.query, SearchScope(), SearchKind.ALL); scopePicker = false }) { Text("切换到全部曲库") }
    } }, confirmButton = { TextButton({ scopePicker = false }) { Text("返回") } })
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun SearchResultRow(hit: SearchDocument, query: String, select: () -> Unit, menu: () -> Unit) {
    ListItem(headlineContent = { HighlightedLabel(hit.title, query) }, supportingContent = { Text(hit.subtitle, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }, leadingContent = {
        if (hit.kind == SearchKind.FOLDERS) AppIcon(R.drawable.ic_folder, "文件夹") else Cover(hit.cover, Modifier.size(48.dp), if (hit.kind == SearchKind.ARTISTS) 24 else 10)
    }, trailingContent = { if (hit.kind == SearchKind.SONGS) IconAction(R.drawable.ic_more_vert, "歌曲操作", action = menu) else Text("›", fontSize = 24.sp) }, modifier = Modifier.semantics { contentDescription = "搜索结果：${hit.kind}:${hit.id}" }.clickable(onClick = select))
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun SearchBrowser(ui: HomeState, vm: JiayinViewModel, collection: (MusicList) -> Unit, search: (SearchScope) -> Unit) {
    var category by rememberSaveable { mutableStateOf("曲风") }
    Column(Modifier.fillMaxSize()) {
        CompactTitle("搜索") { IconAction(R.drawable.ic_search, "搜索全部曲库") { search(SearchScope()) } }
        FilterChips(listOf("曲风", "歌手"), category) { category = it }
        if (category == "歌手") ArtistBrowser(ui, vm, collection, search)
        else {
            val genres = remember(ui.library.tracks) { ui.library.tracks.groupBy { it.genre.ifBlank { "未分类" } }.toSortedMap() }
            LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.weight(1f), contentPadding = PaddingValues(24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) { Text("来自你的曲库", style = MaterialTheme.typography.titleMedium) }
                items(genres.entries.toList(), key = { it.key }) { (name, tracks) ->
                    Surface(Modifier.fillMaxWidth().heightIn(min = 118.dp).clickable { collection(MusicList("genre:$name", name, tracks.map { it.id }, tracks.firstOrNull()?.cover.orEmpty())) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(name, fontSize = 16.sp, maxLines = 2); Text("${tracks.size} 首", Modifier.padding(top = 6.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Cover(tracks.firstOrNull()?.cover.orEmpty(), Modifier.size(42.dp), 10) }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { SheetAction(R.drawable.ic_favorite, "我喜欢") { collection(MusicList("favorites", "我喜欢", ui.library.tracks.filter { it.starred }.map { it.id })) } }
                item(span = { GridItemSpan(maxLineSpan) }) { SheetAction(R.drawable.ic_shuffle, "随便听听 · 按喜好发现新歌") { vm.discover() } }
            }
        }
    }
}
