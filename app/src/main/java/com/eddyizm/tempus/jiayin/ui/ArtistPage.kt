@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.eddyizm.tempus.jiayin.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddyizm.tempus.R
import com.eddyizm.tempus.jiayin.*
import com.eddyizm.tempus.jiayin.core.*

@Composable internal fun FilterChips(values: List<String>, selected: String, select: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { label -> FilterChip(selected == label, { select(label) }, label = { Text(label) }) }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun ArtistPage(list: MusicList, ui: HomeState, vm: JiayinViewModel, back: () -> Unit, menu: (Track) -> Unit, collection: (MusicList) -> Unit, search: (SearchScope) -> Unit = {}, organize: (List<Track>) -> Unit = {}, correct: (List<Track>) -> Unit = {}) {
    val byId = remember(ui.library.tracks) { ui.library.tracks.associateBy { it.id } }
    val tracks = list.tracks.mapNotNull { byId[it] }
    val first = tracks.firstOrNull()
    val artistId = list.id.removePrefix("artist:")
    val profile = ui.artists[artistId] ?: ArtistProfile(artistId, list.name)
    val resolvedRegion = ui.index.artists.firstOrNull { it.id == profile.id }?.region ?: profile.region
    var category by rememberSaveable(list.id) { mutableStateOf("歌曲") }
    var sortingName by remember { mutableStateOf(false) }
    var query by rememberSaveable(list.id) { mutableStateOf("") }
    var classification by remember { mutableStateOf(false) }
    LaunchedEffect(list.id) { vm.loadArtist(list) }
    val popular = ui.topSongs[list.id].orEmpty().mapNotNull { byId[it] }.filter { it.id in list.tracks }
    val ordered = remember(tracks, popular, query) { (if (popular.isNotEmpty()) popular + tracks.filterNot { t -> popular.any { it.id == t.id } } else tracks.sortedWith(compareByDescending<Track> { it.releaseDate }.thenBy { it.album }.thenBy { it.disc }.thenBy { it.number }.thenBy { it.title })).filter { "${it.title} ${it.album}".contains(query, true) } }
    AdaptiveBrowserPage(header = {
        PageTitle(list.name, "${tracks.size} 首 · $resolvedRegion · ${profile.type}", back) {
            IconAction(R.drawable.ic_search, "搜索歌手作品") { search(SearchScope(artist = profile.id)) }
            IconAction(if (profile.starred) R.drawable.ic_favorite else R.drawable.ic_favorites_outlined, if (profile.starred) "取消收藏歌手" else "收藏歌手") { vm.favoriteArtist(profile) }
        }
        FlowRow(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({ classification = true }) { Text("设置歌手分类") }
            TextButton({ sortingName = true }) { Text("设置排序名") }
            TextButton({ organize(tracks) }) { Text("整理作品归属") }
            TextButton({ correct(tracks) }) { Text("纠正作品资料") }
            TextButton({ vm.start(ordered, true, list.name) }) { Text("随机播放") }
        }
        FilterChips(listOf("歌曲", "本人专辑", "参与作品"), category) { category = it }
    }, content = {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (category == "歌曲") {
                item {
                    val label = if (popular.isNotEmpty()) "热门歌曲" else "按发行日期"
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                        Column(Modifier.weight(1f)) { Text(label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Text(if (popular.isNotEmpty()) "服务器热门排序 · 后续为其他作品" else "无热门榜时使用发行日期；缺少日期的作品排在后面", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        IconAction(R.drawable.ic_play, "播放歌手歌曲") { vm.start(ordered, source = list.name) }
                    }
                }
                itemsIndexed(ordered, key = { _, t -> t.id }) { i, t -> SongRow(t, i, action = { vm.start(ordered, source = list.name, index = i) }, more = { menu(t) }) }
            } else {
                val albumIds = tracks.filter { t -> t.album.contains(query, true) &&
                    ((category == "本人专辑") == t.albumCredits().any { it.key == artistId }) }.map(::albumKey).toSet()
                val albums = ui.library.tracks.filter { albumKey(it) in albumIds }.groupBy(::albumKey).values.sortedByDescending { songs -> songs.maxOf { it.releaseDate } }
                items(albums, key = { albumKey(it.first()) }) { songs ->
                    val t = songs.first()
                    ListItem(headlineContent = { Text(t.album.ifBlank { "未命名专辑" }) }, supportingContent = { Text("${songs.size} 首 · ${t.albumCredits().joinToString(" / ") { it.name }}" + if (t.releaseDate > 0) " · ${t.releaseDate / 10000}" else " · 发行日期未知") }, leadingContent = { Cover(t.cover, Modifier.size(64.dp), 12) }, modifier = Modifier.clickable { collection(MusicList("album:${albumKey(t)}", t.album, songs.sortedWith(compareBy<Track> { it.disc }.thenBy { it.number }).map { it.id }, t.cover)) })
                }
            }
        }
    })
    if (sortingName) {
        var value by remember(profile.id) { mutableStateOf(profile.sortName.orEmpty()) }
        AlertDialog(onDismissRequest = { sortingName = false }, title = { Text("设置排序名") }, text = { Column { Text("仅影响拼音／字母排序，不修改显示名称和 NAS。留空恢复默认。", fontSize = 12.sp); OutlinedTextField(value, { value = it }, label = { Text("排序名，例如 Lin Jian") }, singleLine = true) } }, confirmButton = { TextButton({ vm.setArtistSortName(profile, value); sortingName = false }) { Text("保存") } }, dismissButton = { TextButton({ sortingName = false }) { Text("取消") } })
    }
    if (classification) {
        var region by remember(profile.id) { mutableStateOf(profile.region) }
        var type by remember(profile.id) { mutableStateOf(profile.type) }
        AlertDialog(onDismissRequest = { classification = false }, title = { Text("歌手分类") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) { Text("个人分类按账户保存在本机。选择未分类可恢复服务器／目录分类，当前生效地区：$resolvedRegion。", fontSize = 12.sp)
                Text("地区", Modifier.padding(top = 16.dp)); FilterChips(listOf("华语", "欧美", "日本", "韩国", "其他", "未分类"), region) { region = it }
                Text("类型", Modifier.padding(top = 16.dp)); FilterChips(listOf("男", "女", "乐队/组合", "未分类"), type) { type = it }
            }
        }, confirmButton = { TextButton({ vm.classifyArtist(profile, region, type); classification = false }) { Text("保存") } }, dismissButton = { TextButton({ classification = false }) { Text("取消") } })
    }
}
