@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.eddyizm.tempus.jiayin.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eddyizm.tempus.jiayin.HomeState
import com.eddyizm.tempus.jiayin.core.*

@Composable fun SongInformationSheet(info: SongInformation, ui: HomeState, dismiss: () -> Unit,
    open: (MusicList) -> Unit, playlist: () -> Unit, organize: () -> Unit, correct: () -> Unit) {
    val song = info.track
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("歌曲信息", style = MaterialTheme.typography.titleLarge)
                TextButton(dismiss) { Text("关闭") }
            }
            ListItem(headlineContent = { Text(song.title) }, supportingContent = { Text(song.artist.ifBlank { "未提供" }) }, leadingContent = { Cover(song.cover, Modifier.size(56.dp), 12) })
            song.performers().forEach { ref ->
                val original = SongInformationTargets.performer(ref, ui.library.tracks, song.cover)
                val target = original?.copy(id = "performer:${ref.key}")
                InformationRow("演唱者", ref.name, target?.let { { open(it) } })
            }
            info.owner?.let { owner ->
                val group = ui.index.artists.find { it.id == owner.key }
                InformationRow(if (info.personal) "作品归属 · 个人整理" else "作品归属 · 共享整理", owner.name, group?.let { { open(MusicList("artist:${owner.key}", owner.name, it.tracks.map { t -> t.id }, song.cover)) } })
            }
            val album = SongInformationTargets.album(song, ui.library.tracks)
            InformationRow("专辑", song.album, album?.let { { open(it) } })
            InformationRow("曲风", song.genreNames().joinToString(" / "))
            InformationRow("发行年份", if (song.releaseDate > 0) (song.releaseDate / 10000).toString() else "")
            InformationRow("时长", if (song.duration > 0) timeLabel(song.duration * 1000L) else "")
            InformationRow("曲目 / 碟号", listOf(song.number, song.disc).joinToString(" / ") { if (it > 0) it.toString() else "未提供" })
            InformationRow("音乐来源", info.source)
            HorizontalDivider()
            TextButton(playlist, Modifier.heightIn(min = 48.dp)) { Text("加入歌单") }
            TextButton(organize, Modifier.heightIn(min = 48.dp)) { Text("修改作品归属") }
            TextButton(correct, Modifier.heightIn(min = 48.dp)) { Text("纠正歌曲信息") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
@Composable private fun InformationRow(label: String, value: String, click: (() -> Unit)? = null) {
    ListItem(headlineContent = { Text(value.ifBlank { "未提供" }) }, overlineContent = { Text(label) },
        trailingContent = if (click != null) { { Text("›") } } else null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (click == null) Modifier else Modifier.clickable(onClick = click)))
}
