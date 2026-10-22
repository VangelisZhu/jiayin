package com.eddyizm.tempus.jiayin

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.jiayin.ui.JiayinApp

@UnstableApi
class JiayinActivity : ComponentActivity() {
    private lateinit var model: JiayinViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[JiayinViewModel::class.java]
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != android.content.pm.PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        if (permissions.isNotEmpty()) ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 12)
        setContent { JiayinApp(model, intent?.action == "jiayin.player") }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.action == "jiayin.player") model.requestPlayer()
    }
    override fun onResume() { super.onResume(); if (::model.isInitialized) model.foreground() }
}
