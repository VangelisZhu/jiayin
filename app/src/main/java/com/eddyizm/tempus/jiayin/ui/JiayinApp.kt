@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.eddyizm.tempus.jiayin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddyizm.tempus.R
import com.eddyizm.tempus.jiayin.*
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.playback.PlaybackHub
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map


@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun JiayinApp(vm: JiayinViewModel, openPlayer: Boolean) {
    val revision by vm.sessionRevision.collectAsState()
    val serverState by vm.servers.collectAsState()
    val pageStates = rememberSaveableStateHolder()
    val knownPages = remember { mutableSetOf<String>() }
    val ids = serverState.profiles.map { it.id }.toSet()
    LaunchedEffect(ids) {
        knownPages.filter { it.substringBefore(":") !in ids }.toList().forEach { pageStates.removeState(it); knownPages.remove(it) }
    }
    key(revision) { JiayinSessionApp(vm, if (revision == 0L) openPlayer else false, pageStates) { key -> knownPages.add(key); key } }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun JiayinSessionApp(vm: JiayinViewModel, openPlayer: Boolean, pageStates: androidx.compose.runtime.saveable.SaveableStateHolder, pageKey: (String) -> String) {
    val serverUi by vm.servers.collectAsState()
    val ui by vm.state.collectAsState()
    val searchState by vm.search.collectAsState()
    var searchReturn by remember { mutableStateOf(vm.searchOrigin) }
    val playback by PlaybackHub.state.collectAsState()
    var route by rememberSaveable { mutableStateOf("home") }
    var previousRoute by rememberSaveable { mutableStateOf("home") }
    var player by rememberSaveable { mutableStateOf(openPlayer) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val showPlayer = { keyboard?.hide(); focus.clearFocus(); player = true }
    var miniQueue by remember { mutableStateOf(false) }
    SideEffect { vm.searchOrigin = searchReturn }
    val playerRequest by vm.playerRequests.collectAsState()
    LaunchedEffect(playerRequest) { if (playerRequest > 0) showPlayer() }
    var collection by remember { mutableStateOf(vm.selectedCollection) }
    var parentCollection by remember { mutableStateOf<MusicList?>(null) }
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    var playlistTarget by remember { mutableStateOf<Track?>(null) }
    var newPlaylist by remember { mutableStateOf(false) }
    var renameList by remember { mutableStateOf<MusicList?>(null) }
    val snack = remember { SnackbarHostState() }
    val darkSystem = isSystemInDarkTheme()
    var theme by remember { mutableStateOf(vm.repo.settings.getString("theme", "system")) }
    val dark = theme == "dark" || (theme == "system" && darkSystem)
    val window = LocalActivity.current?.window
    SideEffect { window?.let { WindowCompat.getInsetsController(it, it.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark } } }
    val colors = if (dark) darkColorScheme(surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF202923), surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF2A352D), surfaceTint = HomeGreen, primary = androidx.compose.ui.graphics.Color(0xFFADCAB7), primaryContainer = androidx.compose.ui.graphics.Color(0xFF2D4538), secondaryContainer = androidx.compose.ui.graphics.Color(0xFF2D4538), onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFDCE9DD), onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFDCE9DD), background = androidx.compose.ui.graphics.Color(0xFF171C19), surface = androidx.compose.ui.graphics.Color(0xFF171C19), surfaceVariant = androidx.compose.ui.graphics.Color(0xFF29312C))
        else lightColorScheme(surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFF0F2EC), surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFFE7ECE4), surfaceTint = HomeGreen, primary = HomeGreen, primaryContainer = androidx.compose.ui.graphics.Color(0xFFDDE7DB), secondaryContainer = androidx.compose.ui.graphics.Color(0xFFE2E9DF), onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF253C2F), onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF253C2F), background = Paper, surface = Paper, surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE6EAE3), onBackground = androidx.compose.ui.graphics.Color(0xFF20241F), onSurface = androidx.compose.ui.graphics.Color(0xFF20241F))
    LaunchedEffect(ui.message) { ui.message?.let { snack.showSnackbar(it); vm.consumedMessage() } }
    LaunchedEffect(route) { vm.watchDownloads(route == "downloads") }
    val restoreSearch: () -> Unit = {
        searchReturn?.let { origin ->
            route = origin.route; previousRoute = origin.previous; collection = origin.collection; vm.selectedCollection = collection; parentCollection = origin.parent
            vm.restoreBrowser(origin.category, origin.folder)
        }
        searchReturn = null; vm.resumeSearch()
    }
    val openSearch: (SearchScope) -> Unit = { pageStates.removeState(vm.repo.account + ":independent-search"); searchReturn = null; vm.openSearch(it) }
    val backCollection: () -> Unit = {
        val parent = parentCollection
        if (parent != null) { collection = parent; vm.selectedCollection = parent; parentCollection = null }
        else if (searchReturn != null) restoreSearch()
        else route = previousRoute.takeIf { it != "detail" } ?: "library"
    }
    val openCollection: (MusicList) -> Unit = {
        if (searchState.active) {
            searchReturn = SearchReturnLocation(route, previousRoute, collection, parentCollection, vm.repo.settings.getString("${vm.repo.account}.browse-library-category", "专辑") ?: "专辑", vm.repo.settings.getString("${vm.repo.account}.browse-folder", "").orEmpty())
            vm.hideSearch(); previousRoute = route; parentCollection = null
        } else if (route == "detail") parentCollection = collection else { previousRoute = route; parentCollection = null }
        collection = it; vm.selectedCollection = it; route = "detail"
    }
    val openRoute: (String) -> Unit = { previousRoute = route; route = it }
    LaunchedEffect(route, collection) { if (route == "detail" && collection == null) route = "library" }
    BackHandler(player || searchState.active || searchReturn != null || route !in listOf("home", "library", "search", "my")) {
        if (player) player = false else if (searchState.active) vm.hideSearch() else if (route == "detail") backCollection() else route = previousRoute.takeIf { it != "detail" } ?: "home"
    }
    MaterialTheme(colorScheme = colors, typography = Typography()) {
        Surface(Modifier.fillMaxSize()) {
            if (ui.preparationError != null) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp), verticalArrangement = Arrangement.Center) {
                    Text("本地数据准备未完成", style = MaterialTheme.typography.headlineSmall)
                    Text(ui.preparationError.orEmpty(), Modifier.padding(vertical = 18.dp))
                    Text("原下载会保留。请检查手机空间后重试；也可清空临时播放缓存腾出空间，已下载歌曲不会因此删除。", fontSize = 14.sp)
                    Button({ vm.prepareLocalData() }, Modifier.padding(top = 20.dp)) { Text("重试本地准备") }
                    TextButton({ vm.prepareLocalData(true) }) { Text("清空播放缓存并重试") }
                }
            } else if (!ui.configured) {
                if (serverUi.profiles.isEmpty()) LoginScreen(ui, vm)
                else ServerManagementPage(vm, allowBack = false)
            }
            else Box(Modifier.fillMaxSize()) {
                Scaffold(modifier = if (player) Modifier.clearAndSetSemantics { } else Modifier,
                    snackbarHost = { SnackbarHost(snack) },
                    bottomBar = {
                        Column(if (searchState.active) Modifier.imePadding().navigationBarsPadding() else Modifier) {
                            if (playback.current != null) MiniPlayer(playback, showPlayer, { vm.command("toggle") }, { miniQueue = true }, visible = !player)
                            if (!searchState.active) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                                listOf(Triple("home", "首页", R.drawable.ic_home), Triple("library", "曲库", R.drawable.ic_albums), Triple("search", "搜索", R.drawable.ic_search), Triple("my", "我的", R.drawable.ic_favorite)).forEach { (id, label, icon) ->
                                    NavigationBarItem(route == id, { searchReturn = null; route = id }, icon = { AppIcon(icon, label) }, label = { Text(label, fontSize = 12.sp) })
                                }
                            }
                        }
                    }
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        Box(if (searchState.active) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize()) {
                        if (!searchState.active) pageStates.SaveableStateProvider(pageKey(vm.repo.account + ":" + if (route == "detail") "detail:${collection?.id}" else route)) { when (route) {
                            "home" -> HomePage(ui, vm, openCollection, openRoute, showPlayer, { menuTrack = it }, { newPlaylist = true })
                            "library" -> LibraryPage(ui, vm, openCollection, { menuTrack = it }, openSearch, searchReturn?.entryFolder, restoreSearch)
                            "search" -> SearchBrowser(ui, vm, openCollection, openSearch)
                            "my" -> MyPage(ui, vm, openCollection, openRoute, { newPlaylist = true })
                            "recommend" -> RecommendationPage(ui, vm, { menuTrack = it })
                            "detail" -> collection?.let { c ->
                                val actual = ui.library.playlists.find { it.id == c.id } ?: c
                                if (actual.id.startsWith("artist:") || actual.id.startsWith("歌手:")) ArtistPage(actual, ui, vm, backCollection, { menuTrack = it }, openCollection, openSearch)
                                else CollectionPage(actual, ui, vm, backCollection, { menuTrack = it }, { renameList = actual }, { collection = it })
                            }
                            "history" -> TrackPage("最近听过", ui.library.tracks.sortedByDescending { ui.records[it.id]?.lastHeard ?: 0 }.filter { (ui.records[it.id]?.lastHeard ?: 0) > 0 }, ui, vm, { route = "my" }, { menuTrack = it })
                            "settings" -> SettingsPage(vm, ui, theme ?: "system", { theme = it }, { route = "my" }, { route = "servers" })
                            "servers" -> ServerManagementPage(vm, back = { route = "settings" })
                            "downloads" -> DownloadsPage(ui, vm, { menuTrack = it }, { route = "my" })
                        } }
                        }
                        if (searchState.active) pageStates.SaveableStateProvider(pageKey(vm.repo.account + ":independent-search")) { DedicatedSearch(ui, searchState, vm, { hit ->
                            if (hit.kind == SearchKind.SONGS) {
                                val songs = searchState.hits.filter { it.kind == SearchKind.SONGS }.mapNotNull { ui.index.tracks[it.id] }
                                vm.start(songs, source = "搜索：${searchState.query}", index = songs.indexOfFirst { it.id == hit.id }.coerceAtLeast(0))
                            } else {
                                searchReturn = SearchReturnLocation(route, previousRoute, collection, parentCollection, vm.repo.settings.getString("${vm.repo.account}.browse-library-category", "专辑") ?: "专辑", vm.repo.settings.getString("${vm.repo.account}.browse-folder", "").orEmpty(), hit.folder.takeIf { hit.kind == SearchKind.FOLDERS })
                                vm.hideSearch()
                                if (hit.kind == SearchKind.FOLDERS) { route = "library"; vm.requestFolder(hit.folder.orEmpty()) }
                                else {
                                    val target = when (hit.kind) {
                                        SearchKind.ARTISTS -> MusicList("artist:${hit.id}", hit.title, hit.tracks, hit.cover)
                                        SearchKind.PLAYLISTS -> ui.library.playlists.find { it.id == hit.id } ?: MusicList(hit.id, hit.title, hit.tracks, hit.cover)
                                        else -> MusicList("album:${hit.id}", hit.title, hit.tracks, hit.cover)
                                    }
                                    previousRoute = route; parentCollection = null; collection = target; vm.selectedCollection = target; route = "detail"
                                }
                            }
                        }, { menuTrack = it }) }
                        if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                    }
                }
                AnimatedVisibility(player, enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 6 }, exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { it / 6 }) {
                    FullPlayer(ui, playback, vm, dark, { player = false }, { playlistTarget = it }, { song -> player = false; openCollection(artistCollection(song, ui.library.tracks)) })
                }
                if (player) SnackbarHost(snack, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp))
                if (miniQueue) QueueSheet(ui, playback, vm) { miniQueue = false }
            }
        }
        menuTrack?.let { song ->
            ModalBottomSheet(onDismissRequest = { menuTrack = null }) {
                Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 30.dp)) {
                    Text(song.title, style = MaterialTheme.typography.titleLarge); Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    SheetAction(R.drawable.ic_skip_next, "下一首播放") { vm.command("insert") { putString("track", song.id); putBoolean("next", true) }; menuTrack = null }
                    SheetAction(R.drawable.ic_playlist_add, "加入播放队列") { vm.command("insert") { putString("track", song.id) }; menuTrack = null }
                    SheetAction(R.drawable.ic_favorite, if (song.starred) "取消喜欢" else "我喜欢") { vm.favorite(song); menuTrack = null }
                    SheetAction(R.drawable.ic_playlist, "加入歌单") { playlistTarget = song; menuTrack = null }
                    SheetAction(R.drawable.ic_download, "下载到手机") { vm.download(listOf(song)); menuTrack = null }
                    SheetAction(R.drawable.ic_refresh, "从这首继续听") { vm.recommendations(song); route = "recommend"; menuTrack = null }
                    SheetAction(R.drawable.ic_close, "不喜欢，不再推荐") { vm.dislike(song); menuTrack = null }
                }
            }
        }
        playlistTarget?.let { song ->
            AlertDialog(onDismissRequest = { playlistTarget = null }, title = { Text("加入歌单") }, text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item { TextButton({ newPlaylist = true }) { Text("＋ 新建歌单") } }
                    items(ui.library.playlists, key = { it.id }) { p -> ListItem(headlineContent = { Text(p.name) }, supportingContent = { Text("${p.tracks.size} 首") }, modifier = Modifier.clickable { vm.savePlaylist(p.id, p.name, p.tracks + song.id); playlistTarget = null }) }
                }
            }, confirmButton = { TextButton({ playlistTarget = null }) { Text("完成") } })
        }
        if (serverUi.busy && (serverUi.blocking || !ui.configured)) androidx.compose.ui.window.Dialog(onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Surface(shape = RoundedCornerShape(24.dp)) { Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(28.dp)); Text(serverUi.status, Modifier.padding(start = 18.dp)) } }
        }
        if (newPlaylist || renameList != null) {
            var name by remember(renameList) { mutableStateOf(renameList?.name.orEmpty()) }
            AlertDialog(onDismissRequest = { newPlaylist = false; renameList = null }, title = { Text(if (renameList == null) "新建歌单" else "歌单改名") }, text = { OutlinedTextField(name, { name = it }, label = { Text("歌单名称") }, singleLine = true) },
                confirmButton = { TextButton({
                    vm.savePlaylist(renameList?.id, name, renameList?.tracks ?: listOfNotNull(playlistTarget?.id)); newPlaylist = false; renameList = null; playlistTarget = null
                }, enabled = name.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton({ newPlaylist = false; renameList = null }) { Text("取消") } })
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun LoginScreen(ui: HomeState, vm: JiayinViewModel) {
    var address by rememberSaveable { mutableStateOf(vm.repo.server) }
    var user by rememberSaveable { mutableStateOf(vm.repo.user) }
    var password by remember { mutableStateOf("") }
    var automaticLyrics by rememberSaveable { mutableStateOf(vm.repo.settings.getBoolean("auto-repair-lyrics", false)) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("家音", fontSize = 42.sp, fontWeight = FontWeight.Bold, color = HomeGreen)
        Text("让家里的音乐，陪你出门。", Modifier.padding(top = 10.dp, bottom = 40.dp), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(address, { address = it }, label = { Text("音乐服务器地址") }, placeholder = { Text("https://music.example.com") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(user, { user = it }, label = { Text("账户") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(password, { password = it }, label = { Text("密码") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(automaticLyrics, { automaticLyrics = it })
            Text("允许乱码歌词自动联网修复", fontSize = 13.sp)
        }
        Text("开启后，仅发现乱码时向 LRCLIB 发送歌名和歌手；正常歌词不会自动匹配。可随时在设置关闭。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        Button({ vm.repo.settings.edit().putBoolean("auto-repair-lyrics", automaticLyrics).apply(); vm.login(address, user, password) }, Modifier.fillMaxWidth().height(54.dp), enabled = !ui.loading && address.isNotBlank() && user.isNotBlank() && password.isNotEmpty()) { Text(if (ui.loading) ui.status else "连接我的音乐") }
        serverUiError(vm)?.let { Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error) }
        ui.message?.let { Text(it, Modifier.padding(top = 18.dp), color = MaterialTheme.colorScheme.error) }
        Text("填写你自己的 Navidrome 或兼容 Subsonic 的音乐服务地址与账户，不要填写 NAS 管理页面地址。认证信息加密保存在本机。家音不提供公共曲库或共享账户。", Modifier.padding(top = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun PageTitle(title: String, subtitle: String? = null, back: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = if (back == null) 24.dp else 6.dp, end = 16.dp, top = 12.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) IconAction(R.drawable.ic_arrow_back, "返回", action = back)
        Column(Modifier.weight(1f)) { Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold); if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp)) }
        trailing()
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 22.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onAction) { Text(action, fontSize = 12.sp) }
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun SheetAction(icon: Int, label: String, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = action).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) { AppIcon(icon, label); Spacer(Modifier.width(20.dp)); Text(label) }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun SongRow(track: Track, index: Int? = null, playing: Boolean = false, reason: String? = null, action: () -> Unit, more: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(onClick = action).padding(start = 24.dp, end = 12.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        if (index == null) Cover(track.cover, Modifier.size(52.dp), 10) else Text(if (playing) "♫" else (index + 1).toString(), Modifier.width(32.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = if (index == null) 14.dp else 4.dp)) {
            Text(track.title, fontWeight = FontWeight.Medium, color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(reason ?: "${track.artist} · ${track.album}", Modifier.padding(top = 5.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (track.starred) AppIcon(R.drawable.ic_favorite, "已喜欢", Modifier.size(14.dp), MaterialTheme.colorScheme.primary)
        if (more != null) IconAction(R.drawable.ic_more_vert, "歌曲操作", action = more)
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun HomePage(ui: HomeState, vm: JiayinViewModel, collection: (MusicList) -> Unit, route: (String) -> Unit, player: () -> Unit, menu: (Track) -> Unit, newPlaylist: () -> Unit) {
    val tracks = ui.library.tracks
    val current by remember { PlaybackHub.state.map { it.current }.distinctUntilChanged() }.collectAsState(initial = null)
    val newest = remember(tracks) { tracks.sortedByDescending { it.created } }
    LazyColumn(Modifier.fillMaxSize()) {
        item { PageTitle("家音", if (ui.loading) ui.status else "音乐在家，随时随地听") { IconAction(R.drawable.ic_search, "搜索") { route("search") } } }
        if (tracks.isEmpty()) item { EmptyState(if (ui.loading) "正在整理你的曲库…" else "曲库还没有同步", if (ui.loading) ui.status else "连接 NAS 后即可浏览家里的音乐") { vm.sync() } }
        else {
            current?.let { t -> item {
                Row(Modifier.padding(horizontal = 24.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = player).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("继续听", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary); Text(t.title, Modifier.padding(top = 9.dp), fontSize = 22.sp, maxLines = 1); Text(t.artist, Modifier.padding(top = 6.dp), fontSize = 12.sp) }
                    Cover(t.cover, Modifier.size(86.dp), 18)
                }
            } }
            item {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton({ vm.discover(); player() }, Modifier.weight(1f).height(52.dp)) { AppIcon(R.drawable.ic_shuffle, "随机"); Spacer(Modifier.width(8.dp)); Text("随便听听") }
                    OutlinedButton({ collection(MusicList("favorites", "我喜欢", tracks.filter { it.starred }.map { it.id })) }, Modifier.weight(1f).height(52.dp)) { AppIcon(R.drawable.ic_favorite, "喜欢"); Spacer(Modifier.width(8.dp)); Text("我喜欢") }
                }
            }
            item { SectionTitle("为你挑选", "查看全部") { route("recommend") } }
            item {
                Row(Modifier.padding(horizontal = 24.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.primaryContainer).clickable { route("recommend") }.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("今日推荐", fontSize = 26.sp, fontWeight = FontWeight.SemiBold); Text("熟悉的旋律，也有一点新鲜", Modifier.padding(top = 10.dp), fontSize = 12.sp); Text("${ui.recommendations.size} 首 · 只来自你的曲库", Modifier.padding(top = 18.dp), fontSize = 11.sp) }
                    IconButton({ vm.start(ui.recommendations.mapNotNull { r -> tracks.find { it.id == r.id } }, source = "今日推荐"); player() }, Modifier.size(54.dp).background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)) { AppIcon(R.drawable.ic_play, "播放今日推荐", tint = MaterialTheme.colorScheme.onPrimary) }
                }
            }
            item { SectionTitle("我的歌单", "新建", newPlaylist) }
            if (ui.library.playlists.isEmpty()) item { Text("给喜欢的歌，留一个位置。", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) }
            item { LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(ui.library.playlists, key = { it.id }) { p -> Column(Modifier.width(136.dp).clickable { collection(p) }) {
                    PlaylistCover(p, tracks, Modifier.size(136.dp)); Text(p.name, Modifier.padding(top = 9.dp), maxLines = 1, fontSize = 14.sp); Text("${p.tracks.size} 首${if (p.pending) " · 待同步" else ""}", Modifier.padding(top = 4.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            } }
            item { SectionTitle("最近加入", "全部") { collection(MusicList("new", "最近加入", newest.map { it.id })) } }
            items(newest.take(6), key = { it.id }) { t -> SongRow(t, action = { vm.start(listOf(t)); player() }, more = { menu(t) }) }
            item { Text("${tracks.size} 首音乐，安心收在家里", Modifier.fillMaxWidth().padding(24.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun PlaylistCover(list: MusicList, tracks: List<Track>, modifier: Modifier) {
    val covers = remember(list.tracks, tracks) { val byId = tracks.associateBy { it.id }; list.tracks.asSequence().mapNotNull { id -> byId[id]?.cover }.distinct().take(4).toList() }
    if (covers.size < 4) Cover(covers.firstOrNull() ?: list.cover, modifier)
    else Column(modifier.clip(RoundedCornerShape(14.dp))) { for (row in 0..1) Row(Modifier.weight(1f)) { for (column in 0..1) Cover(covers[row * 2 + column], Modifier.weight(1f).fillMaxHeight(), 0) } }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun EmptyState(title: String, text: String, retry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 54.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AppIcon(R.drawable.ic_albums, title, Modifier.size(54.dp), MaterialTheme.colorScheme.primary)
        Text(title, Modifier.padding(top = 20.dp), fontSize = 20.sp); Text(text, Modifier.padding(top = 12.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (retry != null) TextButton(retry, Modifier.padding(top = 14.dp)) { Text("重新连接 / 同步") }
    }
}

@Composable private fun serverUiError(vm: JiayinViewModel): String? { val s by vm.servers.collectAsState(); return s.error }
