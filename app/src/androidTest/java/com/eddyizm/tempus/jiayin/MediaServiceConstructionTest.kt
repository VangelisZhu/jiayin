package com.eddyizm.tempus.jiayin

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.data.JiayinRepository
import com.eddyizm.tempus.jiayin.playback.JiayinMediaService
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Construction smoke check only. Does not request focus or play audio. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class MediaServiceConstructionTest {
    @Test(timeout = 25000) fun mediaSessionCanBeConstructedAndConnectedWhilePaused() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val component = ComponentName(context, JiayinMediaService::class.java)
        context.packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        val repo = JiayinRepository.get(context)
        repo.dao.delete(repo.account, "queue", "active")
        var future: ListenableFuture<MediaController>? = null
        instrumentation.runOnMainSync { future = MediaController.Builder(context, SessionToken(context, component)).buildAsync() }
        val controller = future!!.get(12, TimeUnit.SECONDS)
        instrumentation.runOnMainSync {
            assertFalse(controller.isPlaying)
            assertEquals(0, controller.mediaItemCount)
            MediaController.releaseFuture(future!!)
            context.stopService(Intent(context, JiayinMediaService::class.java))
        }
    }
}
