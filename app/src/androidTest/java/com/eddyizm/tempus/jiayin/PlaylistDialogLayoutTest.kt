package com.eddyizm.tempus.jiayin

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run at the configured device size/font scale; assertions use actual accessible hit targets. */
@RunWith(AndroidJUnit4::class)
class PlaylistDialogLayoutTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private fun find(text: String): AccessibilityNodeInfo? {
        ins.uiAutomation.clearCache(); val queue = ArrayDeque<AccessibilityNodeInfo>()
        ins.uiAutomation.rootInActiveWindow?.let(queue::add)
        while (queue.isNotEmpty()) { val n = queue.removeFirst(); if (n.text?.toString() == text && n.isVisibleToUser) return n; repeat(n.childCount) { n.getChild(it)?.let(queue::add) } }
        return null
    }
    private fun node(text: String): AccessibilityNodeInfo { repeat(40) { find(text)?.let { return it }; SystemClock.sleep(100) }; error("Not visible: $text") }
    private fun click(text: String) { var n = node(text); while (!n.isClickable && n.parent != null) n = n.parent; assertTrue(n.isEnabled); assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK)); SystemClock.sleep(300) }
    @Test fun dialogsKeepButtonsReachableAndExposeDisabledMembership() {
        val song = Track("a", "Generated song", "Generated artist")
        var removes = 0; var adds = 0
        ActivityScenario.launch(JiayinActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                    var showRemove by remember { mutableStateOf(true) }
                    if (showRemove) PlaylistRemovalDialog("示例歌单", song, false, false, null, { removes++ }, { showRemove = false })
                    else PlaylistPicker(song, listOf(MusicList("has", "已收录歌单", listOf("a")), MusicList("new", "空歌单")),
                        { _, done -> adds++; done(Result.success(PlaylistEditResult.SAVED)) }, {}, {})
                }
            } }
            listOf("移除", "取消").forEach { label ->
                val bounds = Rect().also { node(label).getBoundsInScreen(it) }; assertTrue(bounds.width() > 0 && bounds.height() > 0)
            }
            click("取消"); assertEquals(0, removes)
            node("✓ 已在歌单")
            var disabled = node("已收录歌单"); var foundDisabled = !disabled.isEnabled
            while (disabled.parent != null) { disabled = disabled.parent; foundDisabled = foundDisabled || !disabled.isEnabled }
            assertTrue("Existing membership must expose disabled semantics", foundDisabled)
            node("完成"); assertEquals(0, adds)
        }
    }
}
