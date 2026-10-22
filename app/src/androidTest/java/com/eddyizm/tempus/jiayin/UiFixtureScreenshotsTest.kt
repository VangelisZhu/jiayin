package com.eddyizm.tempus.jiayin

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.SystemClock
import android.util.Base64
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.JiayinRepository
import com.eddyizm.tempus.jiayin.playback.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** UI-only fixture. Never contacts the NAS, requests audio focus or plays music.
 * Screenshots are visual evidence only, not device playback acceptance. */
@RunWith(AndroidJUnit4::class)
class UiFixtureScreenshotsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Test(timeout = 180000) fun captureNativeScreensWithExplicitFixtureData() = runBlocking {
        val component = ComponentName(context, JiayinMediaService::class.java)
        context.packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        val repo = JiayinRepository.get(context)
        repo.settings.edit().putBoolean("rotate", true).putBoolean("auto-repair-lyrics", true).commit()
        val account = "ui-fixture-only"
        repo.settings.edit().putString("account", account).putString("server", "https://127.0.0.1:9").putString("user", "家里的音乐").commit()
        repo.vault.put("$account.token", "fixture-not-a-credential"); repo.vault.put("$account.salt", "fixture")
        val cold = cover("coast", false)
        val warm = cover("sunset", true)
        val tracks = listOf(
            Track("demo-1", "Evening Letters", "Mira Vale", "海岸来信", "a1", cover = cold, duration = 248, genre = "流行", starred = true, created = 4),
            Track("demo-2", "日落之前", "林间乐队", "慢慢走", "a2", cover = warm, duration = 218, genre = "民谣", created = 3),
            Track("demo-3", "海辺の手紙", "テスト楽団", "夕暮れ", "a3", cover = cold, duration = 268, genre = "流行", created = 2),
            Track("demo-4", "山海之间", "周禾", "在远方", "a4", cover = warm, duration = 235, genre = "民谣", created = 1),
        )
        repo.saveLibrary(Library(tracks, listOf(MusicList("fixture-playlist", "一个人的傍晚", tracks.map { it.id })), System.currentTimeMillis()))
        repo.put("lyrics", "demo-1", Lyrics("demo-1", listOf(
            LyricLine(0, "The evening settles on the sea", "暮色轻轻落在海面上"),
            LyricLine(24000, "And all the roads lead back to you", "每一条归途，都通向你"),
            LyricLine(48000, "I keep a little light for you", "我为你，留着一盏小小的灯"),
            LyricLine(72000, "Until the sky turns blue again", "直到天空，再一次变蓝"),
            LyricLine(96000, "We'll find our way back home", "我们终会找到回家的路"),
        ), "eng", source = "UI 示例歌词"))
        repo.put("lyrics", "demo-2", Lyrics("demo-2", listOf(LyricLine(0, "在日落之前，慢慢走过街角"), LyricLine(60000, "让晚风，替我说一声晚安")), "zho", source = "UI 示例歌词"))
        repo.put("lyrics", "demo-3", Lyrics("demo-3", listOf(
            LyricLine(0, "海へ続く道を歩こう", "沿着通往海边的路走吧"),
            LyricLine(24000, "夕暮れの風がそっと歌う", "傍晚的风轻轻唱着歌"),
            LyricLine(48000, "遠い空に小さな光を見つけた", "我在远方的天空看见一束微光"),
            LyricLine(72000, "明日もここで会いましょう", "明天也在这里相见吧")), "jpn", source = "UI 原创示例歌词"))
        val damaged = Lyrics("demo-4", listOf(LyricLine(0, "Let me show \uFFFD\uFFFD")))
        repo.put("lyrics", "demo-4", damaged)
        repo.put("lyrics-repair-attempt", "demo-4:${LyricsEngine.originalHash(damaged)}", "NAS 歌词编码异常，联网修复暂不可用")
        val queue = QueueEngine.create(tracks.map { it.id }, "一个人的傍晚", false, tracks.associateBy { it.id }).copy(positionMs = 56000)
        try {
            val intent = Intent(context, JiayinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val scenario = ActivityScenario.launch<JiayinActivity>(intent)
            run {
                SystemClock.sleep(5000)
                instrumentation.runOnMainSync { PlaybackHub.mutable.value = PlaybackState(queue, tracks[0], playing = true, ready = true, cause = PauseCause.NONE) }
                SystemClock.sleep(1500)
                shot("01-home")
                val spinning = miniBitmap(); SystemClock.sleep(1200); val spun = miniBitmap()
                check(different(spinning, spun) > 100) { "Mini record must rotate during playback" }
                spinning.recycle(); spun.recycle()
                instrumentation.runOnMainSync { PlaybackHub.mutable.value = PlaybackHub.mutable.value.copy(playing = false) }
                SystemClock.sleep(1200)
                val stopped = miniBitmap(); SystemClock.sleep(900); val still = miniBitmap()
                check(different(stopped, still) == 0) { "Paused mini record must stop drawing rotation" }
                stopped.recycle(); still.recycle()
                instrumentation.runOnMainSync { PlaybackHub.mutable.value = PlaybackHub.mutable.value.copy(playing = true) }
                click("播放队列"); SystemClock.sleep(500)
                shot("09-mini-queue")
                check(findNode("重新随机") != null) { "Mini queue button must open the queue sheet" }
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                SystemClock.sleep(500)
                click("曲库"); SystemClock.sleep(1000)
                shot("02-library-before-navigation")
                // The full category navigation regression runs at its normal width.
                // Compact captures focus on player/lyrics layout; the horizontal tab
                // swipe behavior is covered by the separate preview.2 regression.
                if (context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density >= 360) verifyCategoryNavigation()
                shot("02-library")
                click("首页"); SystemClock.sleep(400)
                click("Evening Letters"); SystemClock.sleep(600)
                // Mini player title and continue-listening card both open the player.
                if (!contains("正在播放")) click("Evening Letters")
                SystemClock.sleep(1000); shot("03-vinyl-cool")
                click("歌词"); SystemClock.sleep(1000); shot("04-bilingual")
                click("队列"); SystemClock.sleep(600); shot("05-queue")
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                SystemClock.sleep(500)
                click("唱片")
                instrumentation.runOnMainSync { PlaybackHub.mutable.value = PlaybackState(queue.copy(index = 1, positionMs = 76000), tracks[1], playing = true, ready = true, cause = PauseCause.NONE) }
                SystemClock.sleep(5000); shot("06-vinyl-warm")
                instrumentation.runOnMainSync { PlaybackHub.mutable.value = PlaybackState(queue.copy(index = 2, positionMs = 10000), tracks[2], playing = true, ready = true, cause = PauseCause.NONE) }
                SystemClock.sleep(1500)
                click("打开歌词")
                SystemClock.sleep(1000); shot("07-japanese")
                check(findNode("海へ続く道を歩こう") != null) { "Valid Japanese must remain readable" }
                instrumentation.runOnMainSync { PlaybackHub.mutable.value = PlaybackState(queue.copy(index = 3, positionMs = 10000), tracks[3], playing = true, ready = true, cause = PauseCause.NONE) }
                SystemClock.sleep(1500); shot("08-corruption-fallback")
                check(findNode("重试自动修复") != null) { "Corruption must expose repair retry" }
                check(nodes().none { it.text?.contains('\uFFFD') == true }) { "Corrupted lyrics must never be rendered" }
            }
        } finally {
            context.packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        }
    }
    private fun miniBitmap(): Bitmap {
        val n = nodes().first { it.contentDescription?.toString() == "迷你唱片" }
        val bounds = Rect(); n.getBoundsInScreen(bounds)
        val screen = instrumentation.uiAutomation.takeScreenshot()
        val crop = Bitmap.createBitmap(screen, bounds.centerX() - 30, bounds.centerY() - 30, 60, 60)
        screen.recycle(); return crop
    }
    private fun different(a: Bitmap, b: Bitmap): Int = (0 until a.width).sumOf { x -> (0 until a.height).count { y -> a.getPixel(x, y) != b.getPixel(x, y) } }
    private fun shot(name: String) {
        val folder = File(context.getExternalFilesDir(null), "ui-fixture").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().also { bitmap -> File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>(); val pending = ArrayDeque<AccessibilityNodeInfo>(); pending.add(root)
        while (pending.isNotEmpty()) { val n = pending.removeFirst(); result.add(n); for (i in 0 until n.childCount) n.getChild(i)?.let { pending.add(it) } }
        return result
    }
    private fun contains(text: String) = nodes().any { it.text?.toString() == text || it.contentDescription?.toString() == text }
    private fun verifyCategoryNavigation() {
        val metrics = context.resources.displayMetrics
        val normalWidth = metrics.widthPixels / metrics.density >= 360 && context.resources.configuration.fontScale <= 1.3f
        val categories = listOf("专辑", "歌曲", "歌手", "歌单", "文件夹")
        fun swipeBar(forward: Boolean) {
            val visible = nodes().first { it.text?.toString() in categories && it.isVisibleToUser }
            var ancestor: AccessibilityNodeInfo? = visible.parent
            repeat(6) {
                if (ancestor?.isScrollable == true) {
                    val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    if (ancestor?.performAction(action) == true) { SystemClock.sleep(500); return }
                }
                ancestor = ancestor?.parent
            }
            val bounds = Rect(); visible.getBoundsInScreen(bounds)
            val from = if (forward) metrics.widthPixels * 4 / 5 else metrics.widthPixels / 5
            val to = metrics.widthPixels - from
            instrumentation.uiAutomation.executeShellCommand("input swipe $from ${bounds.centerY()} $to ${bounds.centerY()} 300")
                .use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
            SystemClock.sleep(350)
        }
        if (normalWidth) categories.forEach { label ->
            val node = findNode(label)
            check(node != null) { "Category missing: $label; visible=${nodes().mapNotNull { it.text?.toString() }.filter { it.isNotBlank() }}" }
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            check(!bounds.isEmpty && bounds.left >= 0 && bounds.right <= metrics.widthPixels) { "Category not fully visible: $label $bounds" }
        }
        categories.forEach { label ->
            repeat(3) {
                if (nodes().none { it.text?.toString() == label && it.isVisibleToUser }) {
                    swipeBar(true)
                }
            }
            click(label)
            var selected = false
            repeat(15) {
                if (!selected) {
                    SystemClock.sleep(150)
                    var parent: AccessibilityNodeInfo? = nodes().firstOrNull { it.text?.toString() == label }
                    repeat(4) { parent?.refresh(); selected = selected || parent?.isSelected == true; parent = parent?.parent }
                }
            }
            if (!selected) shot("failed-category-$label")
            check(selected) { "Category could not be selected: $label" }
        }
        // Restore the first tab, including when the bar had to scroll at a large font scale.
        repeat(4) { if (nodes().none { it.text?.toString() == "专辑" && it.isVisibleToUser }) swipeBar(false) }
        SystemClock.sleep(300)
        if (!contains("专辑")) shot("failed-return-to-albums")
        click("专辑"); SystemClock.sleep(350)
    }
    private fun findNode(text: String): AccessibilityNodeInfo? {
        repeat(20) {
            nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }?.let { return it }
            SystemClock.sleep(100)
        }
        return null
    }
    private fun click(text: String) {
        val found = findNode(text)
        if (found == null && text in listOf("歌词", "唱片", "队列")) {
            val display = context.resources.displayMetrics
            val x = (display.widthPixels * if (text == "队列") .52f else .18f).toInt()
            val y = (display.heightPixels * .91f).toInt()
            instrumentation.uiAutomation.executeShellCommand("input tap $x $y").use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
            return
        }
        check(found != null) { "UI element missing: $text" }
        // Send a real touch to the visible control. Accessibility actions may return true
        // while an old Compose node is being replaced by the continuous record animation.
        val bounds = Rect(); found.getBoundsInScreen(bounds)
        check(!bounds.isEmpty) { "UI element has no touch bounds: $text" }
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(instrumentation.uiAutomation.injectInputEvent(event, true)) { "Unable to inject touch: $text" }
            event.recycle(); SystemClock.sleep(60)
        }
    }
    private fun cover(name: String, warm: Boolean): String {
        val file = File(context.filesDir, "$name.png")
        val image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, 512f, if (warm) intArrayOf(0xFFDA9B6F.toInt(), 0xFFE9C89B.toInt(), 0xFF6A7770.toInt()) else intArrayOf(0xFF91B3CB.toInt(), 0xFFCEE0D8.toInt(), 0xFF406E8A.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 512f, 512f, paint); paint.shader = null
        paint.color = if (warm) 0xFFF3D7A8.toInt() else 0xFFDCE5DA.toInt(); canvas.drawCircle(if (warm) 346f else 390f, 155f, if (warm) 48f else 24f, paint)
        paint.color = if (warm) 0xFF707A6E.toInt() else 0xFF507F91.toInt()
        canvas.drawPath(Path().apply { moveTo(0f, 282f); cubicTo(120f, 230f, 200f, 398f, 512f, 275f); lineTo(512f, 512f); lineTo(0f, 512f); close() }, paint)
        paint.color = if (warm) 0xFF384D48.toInt() else 0xFF274E5A.toInt()
        canvas.drawPath(Path().apply { moveTo(0f, 355f); cubicTo(200f, 320f, 180f, 460f, 512f, 350f); lineTo(512f, 512f); lineTo(0f, 512f); close() }, paint)
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        return "ir_" + Base64.encodeToString(UriString(file).toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
    }
    private fun UriString(file: File) = android.net.Uri.fromFile(file).toString()
}
