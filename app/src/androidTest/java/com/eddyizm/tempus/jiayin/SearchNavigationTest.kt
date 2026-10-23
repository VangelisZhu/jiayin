package com.eddyizm.tempus.jiayin

import android.content.*
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.JiayinRepository
import com.eddyizm.tempus.jiayin.playback.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SearchNavigationTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val ui get() = ins.uiAutomation
    private var scenarioActivity: JiayinActivity? = null
    @Test(timeout = 180000) fun nativeSearchAlphabetFoldersHistoryAndReturnPositions() = runBlocking {
        val component = ComponentName(context, JiayinMediaService::class.java)
        context.packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        val names = listOf("Amber Vale", "陈屿", "江南", "林间乐队", "周禾", "さくら", "同名", "同名")
        val tracks = names.flatMapIndexed { n, name -> (1..2).map { number -> Track("nav-$n-$number", "${if (number == 1) "晚风" else "清晨"} ${n+1}", name, "专辑 $number", "album-$n-$number", "artist-$n", cover = "fixture-cover-$n", duration = 30, genre = if (n % 2 == 0) "民谣" else "流行", path = if (n == 5) "music/日本/さくら/Best/$number.wav" else "music/华语/$name/Best/$number.wav") } } + Track("nav-missing", "无路径示例", "未知歌手")
        val repo = JiayinRepository.get(context)
        try { MediaPipelineTest.MediaServer(supplied = tracks).use { server ->
            repo.login("http://localhost:${server.port}", "fixture", "fixture-password"); repo.saveLibrary(Library(tracks))
            val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context, JiayinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            lateinit var vm: JiayinViewModel
            scenario.onActivity { scenarioActivity = it; vm = ViewModelProvider(it)[JiayinViewModel::class.java] }
            try {
                await("indexed catalog") { !vm.state.value.loading && vm.state.value.index.tracks.size == tracks.size }
                val queue = QueueEngine.create(tracks.map { it.id }, "UI 检查", false, tracks.associateBy { it.id })
                ins.runOnMainSync { PlaybackHub.mutable.value = PlaybackState(queue, tracks[0], ready = true) }
                SystemClock.sleep(3500)
                click("搜索"); node("搜索全部曲库"); assertFalse(nodes().any { it.isEditable }); shot("01-search-browser")
                click("曲库"); node("搜索当前曲库分类"); click("歌手"); node("字母快速定位")
                val rail = rect(node("字母快速定位"))
                dragRail(rail, 2, 11); node("林间乐队")
                touch(rail.centerX().toFloat(), rail.top + rail.height() * 25.5f / 27)
                node("周禾"); shot("02-artist-index")
                val before = PlaybackHub.state.value.queue
                click("搜索当前曲库分类")
                await("artist scope") { vm.search.value.active && vm.search.value.scope.kind == SearchKind.ARTISTS }
                setQuery("江南"); await("search result") { !vm.search.value.busy && vm.search.value.hits.any { it.title == "江南" } }
                var inset = 0
                await("software keyboard visible") { ins.runOnMainSync { inset = androidx.core.view.ViewCompat.getRootWindowInsets(scenarioActivity!!.window.decorView)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0 }; inset > 0 }
                SystemClock.sleep(400)
                val mini = rect(node("迷你唱片"))
                assertTrue("Mini player is below the keyboard: $mini, inset=$inset", mini.bottom <= context.resources.displayMetrics.heightPixels - inset + 4)
                shot("03-search-keyboard")
                click("返回搜索来源"); assertTrue("Back should hide keyboard before leaving search", vm.search.value.active)
                click("江南")
                await("artist opened") { !vm.search.value.active }; node("搜索歌手作品")
                back(); await("search restored") { vm.search.value.active && vm.search.value.query == "江南" }
                shot("04-search-results")
                click("取消"); node("周禾") // Preserve the original Z section after the round trip.
                assertEquals(before.current?.key, PlaybackHub.state.value.queue.current?.key)
                assertTrue(repo.dao.list(repo.account, "command").isEmpty())
                assertTrue(repo.list("search-history", SearchHistory::class.java).any { it.query == "江南" })
                click("文件夹"); click("music"); click("华语"); reveal("陈屿"); shot("05-folder-hierarchy")
                click("平铺"); reveal("Best"); shot("06-folder-flat")
                click("层级"); click("设置此目录地区"); click("欧美")
                await("directory rule") { vm.state.value.index.regions["nav-1-1"] == "欧美" }
                click("设置此目录地区"); click("清除设置，使用自动识别")
                await("directory rule cleared") { vm.state.value.index.regions["nav-1-1"] == "华语" }
                SystemClock.sleep(400)
                val folderAnchor = nodes().first { it.isVisibleToUser && it.contentDescription?.startsWith("目录：") == true }.contentDescription.toString()
                click("搜索当前曲库分类"); setQuery("Best")
                await("folder scoped search") { vm.search.value.scope.folder == "music/华语" && !vm.search.value.busy && vm.search.value.hits.count { it.kind == SearchKind.FOLDERS } >= 3 }
                shot("07-folder-search"); click("返回搜索来源")
                click("查看全部 6")
                swipeResults()
                val searchAnchor = nodes().first { it.isVisibleToUser && it.contentDescription?.startsWith("搜索结果：FOLDERS:") == true }.contentDescription.toString()
                click(searchAnchor); await("folder opened") { !vm.search.value.active }; node("当前目录歌曲 · 2 首")
                back(); await("folder search restored") { vm.search.value.active && vm.search.value.query == "Best" }
                SystemClock.sleep(600)
                assertEquals(searchAnchor, nodes().first { it.isVisibleToUser && it.contentDescription?.startsWith("搜索结果：FOLDERS:") == true }.contentDescription.toString())
                click("取消"); node(folderAnchor)
                assertEquals("music/华语", repo.settings.getString("${repo.account}.browse-folder", null))
                click("搜索当前曲库分类"); setQuery("obsolete"); setQuery("晚风")
                await("latest search only") { !vm.search.value.busy && vm.search.value.query == "晚风" && vm.search.value.hits.isNotEmpty() && vm.search.value.hits.all { it.title.contains("晚风") } }
                click("${vm.search.value.scope.label} ▾"); click("切换到全部曲库")
                await("global scope") { vm.search.value.scope == SearchScope() }
                assertEquals(0, server.downloads.get()); assertTrue(repo.dao.list(repo.account, "command").isEmpty())
                click("迷你唱片"); node("收起播放器"); click(tracks[0].artist); node("搜索歌手作品")
                back(); await("player artist returns to search") { vm.search.value.active && vm.search.value.query == "晚风" }
                click("取消")
                for (number in 0..24) {
                    var job: Job? = null; ins.runOnMainSync { job = vm.rememberSearch("history-$number") }; job!!.join()
                }
                val history = repo.list("search-history", SearchHistory::class.java)
                assertEquals(20, history.size); assertTrue(history.any { it.query == "history-24" }); assertFalse(history.any { it.query == "history-0" })
                var job: Job? = null; ins.runOnMainSync { job = vm.deleteSearchHistory("history-24") }; job!!.join()
                assertEquals(19, repo.list("search-history", SearchHistory::class.java).size)
                ins.runOnMainSync { job = vm.deleteSearchHistory() }; job!!.join(); assertTrue(repo.list("search-history", SearchHistory::class.java).isEmpty())
                val profile = vm.artistProfile(tracks[0])
                ins.runOnMainSync { job = vm.setArtistSortName(profile, "Zz Test") }; job!!.join()
                repo.synchronize(); vm.refreshLocal()
                assertEquals("Zz Test", vm.state.value.artists[profile.id]?.sortName)
                assertEquals("Z", vm.state.value.index.artists.first { it.id == profile.id }.section)
            } finally { scenario.close(); ins.runOnMainSync { PlaybackHub.mutable.value = PlaybackState() } }
        } } finally { context.packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP) }
    }
    private suspend fun await(label: String, check: () -> Boolean) { val end = SystemClock.elapsedRealtime()+15000; while (!check()) { check(SystemClock.elapsedRealtime()<end) { "$label timed out" }; delay(100) } }
    private fun nodes(): List<AccessibilityNodeInfo> { if (android.os.Build.VERSION.SDK_INT >= 33) ui.clearCache(); val root=ui.rootInActiveWindow ?: return emptyList(); val result=mutableListOf<AccessibilityNodeInfo>(); val q=ArrayDeque<AccessibilityNodeInfo>(); q.add(root); while(q.isNotEmpty()) { val n=q.removeFirst(); result.add(n); repeat(n.childCount) { n.getChild(it)?.let(q::add) } }; return result }
    private fun node(label: String): AccessibilityNodeInfo { repeat(40) { nodes().firstOrNull { (it.text?.toString()==label || it.contentDescription?.toString()==label) && it.isVisibleToUser && !it.isEditable }?.let { return it }; SystemClock.sleep(100) }; error("Missing $label: ${nodes().mapNotNull { it.text ?: it.contentDescription }}") }
    private fun reveal(label: String) {
        repeat(8) {
            if (nodes().any { (it.text?.toString() == label || it.contentDescription?.toString() == label) && it.isVisibleToUser }) return
            swipeResults()
        }
        node(label)
    }
    private fun swipeResults() {
            val metrics = context.resources.displayMetrics
            val x = metrics.widthPixels * .45f
            val from = metrics.heightPixels * .65f
            val to = metrics.heightPixels * .48f
            val at = SystemClock.uptimeMillis()
            for (step in 0..10) {
                val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 10 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                val event = MotionEvent.obtain(at, SystemClock.uptimeMillis(), action, x, from + (to - from) * step / 10, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN; ui.injectInputEvent(event, true); event.recycle(); SystemClock.sleep(35)
            }
            SystemClock.sleep(250)
    }
    private fun rect(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }
    private fun click(label: String) {
        var bounds = rect(node(label))
        // IME and modal transitions can move the mini player after semantics first appear.
        repeat(6) { SystemClock.sleep(100); val latest = rect(node(label)); if (latest == bounds) return@repeat; bounds = latest }
        touch(bounds.centerX().toFloat(), bounds.centerY().toFloat()); SystemClock.sleep(350)
    }
    private fun touch(x: Float,y: Float) { val at=SystemClock.uptimeMillis(); listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP).forEach { action -> val e=MotionEvent.obtain(at,SystemClock.uptimeMillis(),action,x,y,0); e.source=InputDevice.SOURCE_TOUCHSCREEN; ui.injectInputEvent(e,true); e.recycle(); SystemClock.sleep(60) } }
    private fun dragRail(r: Rect, from: Int, to: Int) {
        val at = SystemClock.uptimeMillis()
        fun send(action: Int, index: Float) { val e = MotionEvent.obtain(at, SystemClock.uptimeMillis(), action, r.centerX().toFloat(), r.top + r.height() * (index + .5f) / 27, 0); e.source = InputDevice.SOURCE_TOUCHSCREEN; ui.injectInputEvent(e, true); e.recycle() }
        send(MotionEvent.ACTION_DOWN, from.toFloat()); SystemClock.sleep(100)
        repeat(10) { step -> send(MotionEvent.ACTION_MOVE, from + (to-from)*(step+1)/10f); SystemClock.sleep(40) }
        shot("02a-index-drag"); send(MotionEvent.ACTION_UP, to.toFloat()); SystemClock.sleep(250)
    }
    private fun setQuery(value: String) { val edit = nodes().firstOrNull { it.isEditable } ?: error("Missing search input"); val args=Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value) }; check(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)); SystemClock.sleep(100) }
    private fun back() { ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); SystemClock.sleep(400) }
    private fun shot(name: String) { val folder=File(context.getExternalFilesDir(null),"preview7-ui").apply { mkdirs() }; ui.takeScreenshot().also { image -> File(folder,"$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; image.recycle() } }
}
