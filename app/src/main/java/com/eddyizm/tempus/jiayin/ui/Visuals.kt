package com.eddyizm.tempus.jiayin.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.provider.Settings
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.palette.graphics.Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.eddyizm.tempus.R
import com.eddyizm.tempus.jiayin.core.digest
import com.eddyizm.tempus.jiayin.data.JiayinRepository
import com.eddyizm.tempus.glide.CustomGlideRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*
import java.util.concurrent.TimeUnit

val HomeGreen = Color(0xFF4F6F60)
val Paper = Color(0xFFF6F5F2)
private object VinylMemory { var angle = 0f }
data class CoverVisual(val bitmap: ImageBitmap, val color: Color, val hash: String)
object Covers {
    private val memory = object : android.util.LruCache<String, CoverVisual>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: CoverVisual) = value.bitmap.width * value.bitmap.height * 4
    }
    suspend fun load(context: Context, cover: String): CoverVisual? = withContext(Dispatchers.IO) {
        if (cover.isBlank()) return@withContext null
        val repo = JiayinRepository.get(context)
        val key = "${repo.account}:$cover"
        memory.get(key)?.let { return@withContext it }
        runCatching {
            // A Glide content:// request whose provider starts another Glide request deadlocks
            // once the source pool fills with pending cover reads. Load the source directly.
            val source = if (cover.startsWith("ir_")) Uri.parse(String(Base64.decode(cover.removePrefix("ir_"), Base64.URL_SAFE or Base64.NO_WRAP)))
                else Uri.parse(CustomGlideRequest.createUrl(cover, 512))
            val target = Glide.with(context).asBitmap().load(source).diskCacheStrategy(DiskCacheStrategy.ALL).submit(512, 512)
            try {
                val bitmap = target.get(20, TimeUnit.SECONDS).copy(Bitmap.Config.ARGB_8888, false)
                val palette = Palette.from(bitmap).maximumColorCount(12).generate()
                val color = palette.lightVibrantSwatch?.rgb ?: palette.vibrantSwatch?.rgb ?: palette.dominantSwatch?.rgb ?: 0xFF718D91.toInt()
                val pixels = IntArray(64)
                for (i in pixels.indices) pixels[i] = bitmap.getPixel((i % 8 * bitmap.width / 8), (i / 8 * bitmap.height / 8))
                CoverVisual(bitmap.asImageBitmap(), Color(color), digest(pixels.joinToString())).also { memory.put(key, it) }
            } finally { Glide.with(context).clear(target) }
        }.getOrNull()
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun coverVisual(cover: String): CoverVisual? {
    val context = LocalContext.current
    val visual by produceState<CoverVisual?>(null, cover) { value = Covers.load(context, cover) }
    return visual
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun Cover(cover: String, modifier: Modifier = Modifier, radius: Int = 14) {
    val visual = coverVisual(cover)
    Box(modifier.clip(RoundedCornerShape(radius.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (visual != null) Image(visual.bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(painterResource(R.drawable.ic_albums), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f))
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun AppIcon(id: Int, label: String, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(id), label, modifier.size(24.dp), tint)
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun IconAction(id: Int, label: String, tint: Color = LocalContentColor.current, action: () -> Unit) {
    IconButton(onClick = action) { AppIcon(id, label, tint = tint) }
}
fun subdued(color: Color, dark: Boolean): Color {
    val lab = color.convert(ColorSpaces.Oklab)
    return Color(if (dark) .31f else .86f, lab.green.coerceIn(-.045f, .045f), lab.blue.coerceIn(-.045f, .045f), 1f, ColorSpaces.Oklab).convert(ColorSpaces.Srgb)
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun AmbientBackground(visual: CoverVisual?, dark: Boolean, lyrics: Boolean, reduced: Boolean, modifier: Modifier = Modifier) {
    var target by remember { mutableStateOf(subdued(Color(0xFF7B8E85), dark)) }
    LaunchedEffect(visual?.hash, dark) { target = subdued(visual?.color ?: Color(0xFF7B8E85), dark) }
    // Compose's Color animation converts to Oklab; cancellation starts from the current value.
    val color by animateColorAsState(target, tween(if (reduced) 0 else 900), label = "cover atmosphere")
    Canvas(modifier.fillMaxSize()) {
        val top = lerp(color, Color.White, if (dark) .055f else .18f)
        val bottom = lerp(color, Color.Black, if (dark) .25f else .075f)
        drawRect(Brush.verticalGradient(listOf(top, color, bottom)))
        drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = if (dark) .045f else .16f), Color.Transparent), Offset(size.width * .2f, size.height * .23f), size.height * .55f))
        drawRect(Brush.radialGradient(listOf(Color.Black.copy(alpha = if (dark) .12f else .025f), Color.Transparent), Offset(size.width, size.height * .82f), size.height * .6f))
        if (lyrics) drawRect(if (dark) Color.Black.copy(alpha = .14f) else Color.White.copy(alpha = .12f))
    }
}
@Composable fun screenResumed(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return resumed
}

/** Drawing reads the angle directly; pauses keep the same angle and hidden views stop frames. */
@Composable private fun recordRotation(playing: Boolean, enabled: Boolean, reduced: Boolean, initial: Float = 0f): State<Float> {
    val angle = rememberSaveable { mutableFloatStateOf(initial) }
    val speed by animateFloatAsState(if (playing && enabled && !reduced) 1f else 0f,
        tween(if (playing) 300 else 350, easing = LinearEasing), label = "record speed")
    val latestSpeed by rememberUpdatedState(speed)
    LaunchedEffect(enabled, reduced, playing) {
        if (!enabled || reduced) return@LaunchedEffect
        var last = 0L
        do {
            withFrameNanos { now ->
                if (last > 0) angle.floatValue = (angle.floatValue + (now - last).coerceAtMost(50_000_000) / 1_000_000_000f * 18f * latestSpeed) % 360
                last = now
            }
        } while (playing || latestSpeed > .001f)
    }
    return angle
}

@Composable fun MiniRecord(visual: CoverVisual?, playing: Boolean, enabled: Boolean, reduced: Boolean, modifier: Modifier = Modifier) {
    val angle = recordRotation(playing, enabled, reduced)
    Box(modifier.aspectRatio(1f).graphicsLayer { rotationZ = angle.value }.clip(CircleShape).background(Color(0xFF1D2021)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(Color(0xFF35393A), Color(0xFF141718))), r)
            for (i in 0..5) drawCircle(Color.White.copy(alpha = .075f), r * (.76f + i * .037f), style = Stroke(.55.dp.toPx()))
        }
        androidx.compose.animation.Crossfade(visual, animationSpec = tween(if (reduced) 0 else 250), label = "mini sleeve", modifier = Modifier.fillMaxSize(.7f).clip(CircleShape)) { cover ->
            if (cover != null) Image(cover.bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize().background(HomeGreen))
        }
        Canvas(Modifier.size(4.dp)) { drawCircle(Color(0xFF1A201D)); drawCircle(Color.White.copy(alpha = .6f), radius = size.minDimension * .18f) }
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun animationsReduced(): Boolean {
    val context = LocalContext.current
    var reduced by remember { mutableStateOf(Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return reduced
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun Vinyl(visual: CoverVisual?, playing: Boolean, enabled: Boolean, reduced: Boolean, modifier: Modifier = Modifier) {
    val angle = recordRotation(playing, enabled, reduced, VinylMemory.angle)
    DisposableEffect(Unit) { onDispose { VinylMemory.angle = angle.value } }
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(9.dp)) {
            val tint = visual?.color ?: HomeGreen
            drawCircle(Brush.radialGradient(0f to tint.copy(alpha = .02f), .82f to tint.copy(alpha = .07f), 1f to Color.White.copy(alpha = .2f)))
            drawCircle(Color.White.copy(alpha = .18f), style = Stroke(1.dp.toPx()))
        }
        Box(Modifier.fillMaxSize().padding(20.dp).graphicsLayer { rotationZ = angle.value }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                drawCircle(Brush.radialGradient(listOf(Color(0xFF2D3032), Color(0xFF101214), Color(0xFF25282A))), r)
                for (i in 0..31) drawCircle(Color.White.copy(alpha = if (i % 4 == 0) .08f else .035f), r * (.59f + i * .012f), style = Stroke(.7.dp.toPx()))
                drawArc(Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = .08f), Color.Transparent, Color.White.copy(alpha = .06f), Color.Transparent)), -35f, 70f, false)
                drawCircle(Color.Black.copy(alpha = .35f), r * .59f, style = Stroke(3.dp.toPx()))
            }
            androidx.compose.animation.Crossfade(visual, animationSpec = tween(if (reduced) 0 else 250), label = "record sleeve", modifier = Modifier.fillMaxSize(.56f).clip(CircleShape)) { cover ->
                if (cover != null) Image(cover.bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Box(Modifier.fillMaxSize().background(Color(0xFF75857D)), contentAlignment = Alignment.Center) { AppIcon(R.drawable.ic_albums, "暂无封面", tint = Color.White) }
            }
            Canvas(Modifier.fillMaxSize(.045f)) { drawCircle(Color(0xFF121617)); drawCircle(Color(0xFFB7BDBA), radius = size.minDimension * .18f) }
        }
        val arm by animateFloatAsState(if (playing) 0f else -14f, tween(if (reduced) 0 else 350), label = "tonearm")
        Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = arm; transformOrigin = TransformOrigin(.86f, .06f) }) {
            val pivot = Offset(size.width * .86f, size.height * .06f)
            val bend = Offset(size.width * .84f, size.height * .39f)
            val tip = Offset(size.width * .72f, size.height * .48f)
            drawLine(Color.Black.copy(alpha = .14f), pivot + Offset(3f, 4f), bend + Offset(3f, 4f), 6.dp.toPx())
            drawLine(Color(0xFFC4CBC8), pivot, bend, 5.dp.toPx(), StrokeCap.Round)
            drawLine(Color(0xFFC4CBC8), bend, tip, 5.dp.toPx(), StrokeCap.Round)
            drawLine(Color(0xFF5B6564), tip, tip + Offset(-size.width * .035f, size.height * .035f), 9.dp.toPx(), StrokeCap.Round)
            drawCircle(Color(0xFF8D9994), 11.dp.toPx(), pivot); drawCircle(Color(0xFFDCE0DB), 5.dp.toPx(), pivot)
        }
    }
}
fun timeLabel(ms: Long): String = "%d:%02d".format(ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)
