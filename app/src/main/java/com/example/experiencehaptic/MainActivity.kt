package com.example.experiencehaptic

import android.content.Context
import android.content.pm.ActivityInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

private val Ink = Color(0xFF10141D)
private val Panel = Color(0xFF191F2C)
private val Accent = Color(0xFFB6F36A)
private val Muted = Color(0xFF9BA5B5)
private val SurfaceRaised = Color(0xFF171F2D)

data class HapticHit(
    val timeMs: Long,
    val strength: Int,
    val isCarRumble: Boolean = false,
    val durationMs: Long = 70L
)

@UnstableApi
class MainActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var hapticJob: Job? = null
    private var hapticGeneration = 0L
    private var activeHapticEvents: List<HapticHit> = emptyList()
    private var immersiveModeEnabled = false
    private var hapticsEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        setContent { ExperienceHapticScreen(this) }
    }

    /** Keep the display awake while playback is requested, including buffering. */
    private fun setKeepScreenOn(keepOn: Boolean) {
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onResume() {
        super.onResume()
        updateKeepScreenOn()
    }

    override fun onPause() {
        // Do not keep the display awake when the app is no longer in the foreground.
        setKeepScreenOn(false)
        super.onPause()
    }

    @Composable
    private fun ExperienceHapticScreen(context: Context) {
        var uri by rememberSaveable { mutableStateOf<Uri?>(null) }
        var title by rememberSaveable { mutableStateOf<String?>(null) }
        var hits by remember { mutableStateOf<List<HapticHit>>(emptyList()) }
        var hasAnalyzed by remember { mutableStateOf(false) }
        var analyzing by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var playing by remember { mutableStateOf(false) }
        var hapticsOn by rememberSaveable { mutableStateOf(true) }
        var playbackPosition by remember { mutableStateOf(0L) }
        hapticsEnabled = hapticsOn
        var landscapeMode by rememberSaveable { mutableStateOf(false) }

        BackHandler(enabled = landscapeMode) {
            landscapeMode = false
            toggleLandscape(false)
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
            if (picked != null) {
                uri = picked
                title = picked.lastPathSegment?.substringAfterLast('/') ?: "Selected video"
                hits = emptyList()
                hasAnalyzed = false
                activeHapticEvents = emptyList()
                stopHaptics()
                error = null
                player?.setMediaItem(MediaItem.fromUri(picked))
                player?.prepare()
            }
        }
        DisposableEffect(Unit) {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying
                    Log.i(
                        "ExperienceHapticHaptics",
                        "player state isPlaying=$isPlaying cues=${activeHapticEvents.size}"
                    )
                    if (isPlaying) startHaptics() else stopHaptics()
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    updateKeepScreenOn()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    updateKeepScreenOn()
                }
            }
            player?.addListener(listener)
            onDispose { player?.removeListener(listener) }
        }
        LaunchedEffect(playing) {
            while (playing) {
                playbackPosition = player?.currentPosition ?: 0L
                delay(100L)
            }
            playbackPosition = player?.currentPosition ?: 0L
        }

        MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = Panel)) {
            if (landscapeMode && uri != null) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        factory = { viewContext ->
                            PlayerView(viewContext).apply {
                                player = this@MainActivity.player
                                useController = true
                                controllerShowTimeoutMs = 3500
                                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                        color = Color(0xCC10141D),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        TextButton(
                            onClick = {
                                landscapeMode = false
                                toggleLandscape(false)
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = Accent)
                        ) {
                            Icon(Icons.Default.Fullscreen, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("EXIT FULL SCREEN", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0A0F19), Color(0xFF111B2B), Color(0xFF0A0F19)))).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
                    Spacer(Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GraphicEq, null, tint = Accent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(9.dp))
                        Text("EXPERIENCE HAPTIC", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Spacer(Modifier.weight(1f))
                        Surface(color = Color(0xFF182231), shape = RoundedCornerShape(30.dp)) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).background(if (hasAnalyzed) Accent else Color(0xFF718096), CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text(if (hasAnalyzed) "READY" else "ON DEVICE", color = Color(0xFFD8E2F0), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(36.dp))
                    Text("CINEMA,\nIN YOUR HANDS.", color = Color.White, fontSize = 35.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                    Spacer(Modifier.height(10.dp))
                    Text("Feel the moments that make a scene unforgettable.", color = Color(0xFFB3C0D1), fontSize = 15.sp, lineHeight = 22.sp)
                    Spacer(Modifier.height(24.dp))

                    if (uri == null) {
                            Column(Modifier.fillMaxWidth().background(SurfaceRaised, RoundedCornerShape(24.dp)).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.GraphicEq, null, tint = Accent, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Bring your video to life", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                            Spacer(Modifier.height(6.dp))
                            Text("SoundSense reads the audio on your phone and creates a synchronized haptic experience.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                            Spacer(Modifier.height(18.dp))
                            Button(onClick = { picker.launch(arrayOf("video/*")) }, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("CHOOSE A VIDEO", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            }
                        }
                    } else {
                        Text("YOUR CLIP", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.fillMaxWidth().height(210.dp).background(Panel, RoundedCornerShape(18.dp))) {
                            AndroidView(factory = { viewContext ->
                                PlayerView(viewContext).apply { player = this@MainActivity.player; useController = hasAnalyzed }
                            }, modifier = Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                landscapeMode = true
                                toggleLandscape(true)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent)
                        ) {
                            Icon(Icons.Default.Fullscreen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("WATCH FULL SCREEN", fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(title ?: "Selected video", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Spacer(Modifier.height(18.dp))
                        Button(
                            onClick = {
                                if (!hasAnalyzed) {
                                    analyzing = true; error = null
                                    lifecycleScope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching {
                                                // SoundSense event-selective mode: keep genuine impact/vehicle
                                                // events, but suppress generic energy peaks that occur during
                                                // music/instrument-only sections.
                                                val energyHits = analyzeAudio(context, uri!!)
                                                val detections = runCatching {
                                                    SoundClassifier.classify(context, uri!!)
                                                }.onFailure {
                                                    Log.w("ExperienceHapticHaptics", "Sound classification failed; using audio-energy cues", it)
                                                }.getOrDefault(emptyList())
                                                SoundSensePolicy.buildHapticHits(energyHits, detections)
                                            }
                                        }
                                        result.onSuccess {
                                            hits = it
                                            hasAnalyzed = true
                                            activeHapticEvents = it
                                            player?.seekTo(0L)
                                            player?.play()
                                        }.onFailure { error = "We could not analyze this video. Try another clip." }
                                        analyzing = false
                                    }
                                } else {
                                    if (player?.isPlaying == true) player?.pause() else {
                                        activeHapticEvents = hits
                                        if (player?.playbackState == Player.STATE_ENDED) player?.seekTo(0L)
                                        player?.play()
                                    }
                                }
                            },
                            enabled = !analyzing,
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            if (analyzing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Ink)
                            else { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(if (!hasAnalyzed) "START EXPERIENCE" else if (playing) "PAUSE EXPERIENCE" else "PLAY WITH HAPTICS", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
                        }
                        error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Color(0xFFFF8B8B), fontSize = 13.sp) }
                        if (hasAnalyzed) {
                            Spacer(Modifier.height(22.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = SurfaceRaised)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("EXPERIENCE HAPTICS", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
                                        Spacer(Modifier.height(3.dp))
                                        Text(if (hapticsOn) "Feel the soundtrack as you watch" else "Video will play without vibration", color = Muted, fontSize = 11.sp)
                                    }
                                    Switch(
                                        checked = hapticsOn,
                                        onCheckedChange = { enabled ->
                                            hapticsOn = enabled
                                            hapticsEnabled = enabled
                                            if (enabled && player?.isPlaying == true) startHaptics()
                                            else if (!enabled) stopHaptics()
                                        }
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("HAPTIC TRACK", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f)); Text("${hits.size} haptic cues", color = Accent, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(12.dp))
                            val duration = (player?.duration ?: 0L).takeIf { it > 0L }
                                ?: (hits.lastOrNull()?.let { it.timeMs + it.durationMs } ?: 1L)
                            if (hits.isNotEmpty()) {
                                HapticTimeline(hits, playbackPosition, duration) { seekMs -> player?.seekTo(seekMs) }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(formatPlaybackTime(playbackPosition), color = Muted, fontSize = 10.sp)
                                    Text("${hits.size} CUES Â· TAP TO SEEK", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                                    Text(formatPlaybackTime(duration), color = Muted, fontSize = 10.sp)
                                }
                            } else Text("No strong haptic moments were found in this clip. You can still enjoy watching it.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                            Spacer(Modifier.height(10.dp))
                            Text("Haptics follow the sound and effects in your clip.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                            TextButton(onClick = { picker.launch(arrayOf("video/*")) }) { Text("Choose another video", color = Accent) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Spacer(Modifier.height(22.dp))
                }
            }
        }
    }

    private fun updateKeepScreenOn() {
        val currentPlayer = player
        val keepOn = currentPlayer != null && currentPlayer.playWhenReady &&
            currentPlayer.playbackState != Player.STATE_IDLE &&
            currentPlayer.playbackState != Player.STATE_ENDED
        setKeepScreenOn(keepOn)
    }

    @Suppress("DEPRECATION")
    private fun toggleLandscape(enabled: Boolean) {
        requestedOrientation = if (enabled) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        immersiveModeEnabled = enabled
        if (enabled) {
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun stopHaptics() {
        hapticGeneration++
        hapticJob?.cancel()
        hapticJob = null
        vibrator().cancel()
    }

    private fun vibrator(): Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        (getSystemService(VIBRATOR_SERVICE) as Vibrator)
    }

    private fun startHaptics() {
        val currentPlayer = player ?: return
        if (!hapticsEnabled) return
        if (activeHapticEvents.isEmpty()) {
            Log.w("ExperienceHapticHaptics", "scheduler not started: there are no generated cues")
            return
        }
        if (hapticJob?.isActive == true) {
            Log.w("ExperienceHapticHaptics", "scheduler not started: a scheduler job is already active")
            return
        }
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as Vibrator)
        }

        val generation = ++hapticGeneration
        hapticJob = lifecycleScope.launch {
            var previousPosition = currentPlayer.currentPosition - 1L
            var activeRumbleStart: Long? = null
            val events = activeHapticEvents.sortedBy { it.timeMs }
            val impacts = events.filterNot { it.isCarRumble }
            val rumbles = ArrayList<HapticHit>()
            for (rumble in events.filter { it.isCarRumble && it.durationMs > 0L }) {
                val previous = rumbles.lastOrNull()
                if (previous != null && rumble.timeMs < previous.timeMs + previous.durationMs) {
                    val mergedEnd = max(
                        previous.timeMs + previous.durationMs,
                        rumble.timeMs + rumble.durationMs
                    )
                    rumbles[rumbles.lastIndex] = previous.copy(
                        strength = max(previous.strength, rumble.strength),
                        durationMs = mergedEnd - previous.timeMs
                    )
                } else {
                    rumbles.add(rumble)
                }
            }
            try {
                Log.i("ExperienceHapticHaptics", "scheduler started: cues=${events.size}, rumbles=${rumbles.size}, impacts=${impacts.size}")
                while (currentPlayer.isPlaying && currentPlayer.playbackState != Player.STATE_ENDED) {
                    val position = currentPlayer.currentPosition

                    // Resynchronize on seeks or large player-position jumps. Do not replay
                    // cues from the skipped section after playback resumes.
                    if (position + 80L < previousPosition || position - previousPosition > 500L) {
                        vibrator.cancel()
                        activeRumbleStart = null
                        previousPosition = position
                    }

                    val activeRumble = rumbles.firstOrNull {
                        position in it.timeMs until (it.timeMs + it.durationMs)
                    }

                    // A main-thread delay can jump over a short rumble segment. Recover
                    // it with a pulse instead of silently dropping the segment.
                    val crossedRumbles = if (activeRumble == null && previousPosition >= 0L) {
                        rumbles.filter {
                            it.timeMs > previousPosition && it.timeMs <= position &&
                                it.timeMs + it.durationMs <= position
                        }
                    } else {
                        emptyList()
                    }

                    if (activeRumble != null) {
                        if (activeRumbleStart != activeRumble.timeMs) {
                            vibrator.cancel()
                            val timings = longArrayOf(0L, 45L, 25L, 70L, 25L, 45L)
                            val amplitudes = if (vibrator.hasAmplitudeControl()) {
                                intArrayOf(110, 175, 135, 210, 150, 180)
                            } else {
                                intArrayOf(180, 180, 180, 180, 180, 180)
                            }
                            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 0))
                            activeRumbleStart = activeRumble.timeMs
                            Log.i("ExperienceHapticHaptics", "RUMBLE START cue=${activeRumble.timeMs}ms dispatch=${position}ms late=${(position - activeRumble.timeMs).coerceAtLeast(0L)}ms; segmentEnd=${activeRumble.timeMs + activeRumble.durationMs}ms")
                        }
                    } else if (activeRumbleStart != null) {
                        vibrator.cancel()
                        Log.i("ExperienceHapticHaptics", "RUMBLE STOP at ${position}ms")
                        activeRumbleStart = null
                    }
                    if (crossedRumbles.isNotEmpty()) {
                        val recovered = crossedRumbles.maxByOrNull { it.strength }!!
                        vibrator.cancel()
                        vibrator.vibrate(
                            VibrationEffect.createOneShot(65L, recovered.strength.coerceIn(80, 255))
                        )
                        Log.w(
                            "ExperienceHapticHaptics",
                            "RUMBLE RECOVERED cue=${recovered.timeMs}ms dispatch=${position}ms late=${position - recovered.timeMs}ms skippedSegments=${crossedRumbles.size}"
                        )
                    }

                    // Drain every impact crossed since the last tick. The old code chose
                    // only the strongest event in this interval and silently discarded
                    // all other crossed cues, which caused missed haptics after UI stalls.
                    if (previousPosition >= 0L) {
                        val due = impacts.filter { it.timeMs > previousPosition && it.timeMs <= position }
                        if (due.isNotEmpty()) {
                            vibrator.cancel()
                            if (due.size == 1) {
                                val hit = due.single()
                                vibrator.vibrate(
                                    VibrationEffect.createOneShot(70L, hit.strength.coerceIn(80, 255))
                                )
                            } else {
                                // One waveform command preserves all crossed events; rapid
                                // repeated vibrate() calls would cancel one another.
                                val timings = LongArray(due.size * 2 + 1)
                                val amplitudes = IntArray(timings.size)
                                for (index in due.indices) {
                                    val onIndex = index * 2 + 1
                                    timings[onIndex] = 65L
                                    amplitudes[onIndex] = due[index].strength.coerceIn(80, 255)
                                    val nextGap = due.getOrNull(index + 1)?.let {
                                        (it.timeMs - due[index].timeMs - 65L).coerceAtLeast(20L)
                                    } ?: 0L
                                    timings[onIndex + 1] = nextGap
                                }
                                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                            }
                            // Let the impact read clearly, then resume an overlapping rumble
                            // on the next scheduler tick.
                            if (activeRumble != null) activeRumbleStart = null
                            Log.d(
                                "ExperienceHapticHaptics",
                                "IMPACT dispatch=${position}ms due=${due.size} cueTimes=${due.joinToString { it.timeMs.toString() }} lateMs=${due.map { (position - it.timeMs).coerceAtLeast(0L) }}"
                            )
                        }
                    }

                    previousPosition = position
                    delay(8.milliseconds)
                }
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    Log.e("ExperienceHapticHaptics", "haptic scheduler error", t)
                }
            } finally {
                vibrator.cancel()
                activeRumbleStart = null
                // A canceled run can finish after a rapid resume has already started a
                // replacement. Only clear the handle if this is still the current run.
                if (hapticGeneration == generation) hapticJob = null
                Log.i("ExperienceHapticHaptics", "scheduler stopped")
            }
        }
    }

    override fun onDestroy() {
        setKeepScreenOn(false)
        player?.release(); player = null
        if (immersiveModeEnabled) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        super.onDestroy()
    }
}

private fun formatPlaybackTime(timeMs: Long): String {
    val totalSeconds = timeMs.coerceAtLeast(0L) / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val millis = timeMs.coerceAtLeast(0L) % 1_000L
    return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
}

@Composable
private fun HapticTimeline(
    events: List<HapticHit>,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit
) {
    val maxTime = durationMs.coerceAtLeast(1L)
    Canvas(
        Modifier.fillMaxWidth().height(76.dp)
            .background(Panel, RoundedCornerShape(12.dp)).padding(10.dp)
            .pointerInput(maxTime) {
                detectTapGestures { point -> onSeek((point.x / size.width * maxTime.toFloat()).toLong().coerceIn(0L, maxTime)) }
            }
    ) {
        val center = size.height / 2f
        drawLine(Color(0xFF3A4352), Offset(0f, center), Offset(size.width, center), strokeWidth = 1.dp.toPx())
        val stride = (events.size / 100).coerceAtLeast(1)
        events.forEachIndexed { index, hit ->
            if (index % stride != 0) return@forEachIndexed
            val x = size.width * hit.timeMs.coerceIn(0L, maxTime).toFloat() / maxTime.toFloat()
            val h = size.height * (hit.strength / 255f)
            drawLine(if (hit.isCarRumble) Color(0xFF4BC7D1) else Accent, Offset(x, center - h / 2), Offset(x, center + h / 2), strokeWidth = 3.dp.toPx())
        }
        val progressX = size.width * positionMs.coerceIn(0L, maxTime).toFloat() / maxTime.toFloat()
        drawLine(Color.White.copy(alpha = 0.82f), Offset(progressX, 0f), Offset(progressX, size.height), strokeWidth = 1.5.dp.toPx())
    }
}

private fun analyzeAudio(context: Context, uri: Uri): List<HapticHit> {
    val extractor = MediaExtractor()
    extractor.setDataSource(context, uri, null)
    val track = (0 until extractor.trackCount).firstOrNull {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    } ?: throw IllegalArgumentException("No audio track")
    extractor.selectTrack(track)
    val format = extractor.getTrackFormat(track)
    val mime = format.getString(MediaFormat.KEY_MIME)!!
    val decoder = MediaCodec.createDecoderByType(mime)
    decoder.configure(format, null, null, 0)
    decoder.start()
    val info = MediaCodec.BufferInfo()
    val buckets = ArrayList<Pair<Long, Double>>()
    val lowBuckets = ArrayList<Double>()
    var inputDone = false
    var outputDone = false
    var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
    val bucketSamples = max(1, sampleRate / 12)
    var sumSquares = 0.0
    var lowSumSquares = 0.0
    var lowPass = 0.0
    var sampleCount = 0
    var sampleIndex = 0L
    while (!outputDone) {
        if (!inputDone) {
            val inputIndex = decoder.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val input = decoder.getInputBuffer(inputIndex)!!
                val size = extractor.readSampleData(input, 0)
                if (size < 0) {
                    decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }
        val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
        if (outputIndex >= 0) {
            val output = decoder.getOutputBuffer(outputIndex)
            if (output != null && info.size > 0) {
                output.position(info.offset); output.limit(info.offset + info.size)
                val pcm = output.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                while (pcm.hasRemaining()) {
                    val value = pcm.get().toInt() / 32768.0
                    // Track the low-frequency envelope, where engine/exhaust rumble is strongest.
                    val alpha = 1.0 - exp(-2.0 * Math.PI * 180.0 / sampleRate)
                    lowPass += alpha * (value - lowPass)
                    sumSquares += value * value; sampleCount++; sampleIndex++
                    lowSumSquares += lowPass * lowPass
                    if (sampleCount >= bucketSamples) {
                        buckets.add((sampleIndex * 1000L / sampleRate) to sqrt(sumSquares / sampleCount))
                        lowBuckets.add(sqrt(lowSumSquares / sampleCount))
                        sumSquares = 0.0; sampleCount = 0
                        lowSumSquares = 0.0
                    }
                }
            }
            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            decoder.releaseOutputBuffer(outputIndex, false)
        } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            val changed = decoder.outputFormat
            if (changed.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        }
    }
    decoder.stop(); decoder.release(); extractor.release()
    if (buckets.size < 2) return emptyList()
    val rms = buckets.map { it.second }
    val average = rms.average()
    val averageLow = lowBuckets.average()

    // Robust noise floor: median + median absolute deviation is less affected by
    // a few loud effects than a plain clip average. The local comparison also
    // rejects continuous hiss, music beds and steady machinery hum.
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }
    val noiseFloor = median(rms)
    val deviations = rms.map { kotlin.math.abs(it - noiseFloor) }
    val noiseMad = median(deviations)
    // Lower adaptive threshold: the previous absolute floor and MAD multiplier
    // discarded quieter but meaningful impacts in compressed movie audio.
    val transientThreshold = max(0.018, max(noiseFloor * 1.22, noiseFloor + noiseMad * 1.65))
    val raw = buckets.indices.filter { i ->
        val local = rms[i]
        val previous = rms.getOrNull(i - 1) ?: 0.0
        val next = rms.getOrNull(i + 1) ?: 0.0
        val neighbourFloor = (previous + next) / 2.0
        local > transientThreshold &&
            local >= previous && local >= next &&
            local >= max(0.010, neighbourFloor * 1.04)
    }
    val selected = ArrayList<HapticHit>()
    for (index in raw) {
        val time = buckets[index].first
        if (selected.isEmpty() || time - selected.last().timeMs >= 70) {
            selected.add(HapticHit(time, min(255, max(80, (rms[index] * 640).toInt()))))
        } else if (rms[index] > rms[index - 1]) {
            selected[selected.lastIndex] = HapticHit(time, min(255, max(80, (rms[index] * 640).toInt())))
        }
    }
    // Group consecutive low-frequency energy buckets into sustained engine-like segments.
    // Each bucket is about 83 ms, so short gaps are tolerated to avoid choppy vibration.
    // This remains a heuristic: low-frequency sound is not guaranteed to be an engine.
    val rumbleHits = ArrayList<HapticHit>()
    val lowMedian = median(lowBuckets)
    val lowMad = median(lowBuckets.map { kotlin.math.abs(it - lowMedian) })
    // Require low-band energy to rise above the typical background level; a
    // constant fan/road hum should not become a continuous vibration.
    val rumbleThreshold = max(0.0045, max(lowMedian * 1.10, lowMedian + lowMad * 1.25))
    var segmentStart = -1L
    var segmentEnd = -1L
    var lastQualifyingTime = -1L

    fun closeRumbleSegment() {
        if (segmentStart >= 0L && segmentEnd - segmentStart >= 165L) {
            rumbleHits.add(
                HapticHit(
                    timeMs = segmentStart,
                    strength = 150,
                    isCarRumble = true,
                    durationMs = (segmentEnd - segmentStart).coerceAtLeast(250L)
                )
            )
        }
        segmentStart = -1L
        segmentEnd = -1L
        lastQualifyingTime = -1L
    }

    for (i in buckets.indices) {
        val total = rms[i]
        val low = lowBuckets.getOrElse(i) { 0.0 }
        val lowBandRatio = low / max(total, 1e-6)
        val qualifies = low > rumbleThreshold && total > max(0.010, noiseFloor * 1.015) && lowBandRatio >= 0.40
        val time = buckets[i].first

        if (qualifies) {
            if (segmentStart < 0L) segmentStart = time
            if (lastQualifyingTime >= 0L && time - lastQualifyingTime > 250L) {
                closeRumbleSegment()
                segmentStart = time
            }
            segmentEnd = time + 83L
            lastQualifyingTime = time
        } else if (segmentStart >= 0L && time - lastQualifyingTime > 250L) {
            closeRumbleSegment()
        }
    }
    closeRumbleSegment()

    // Keep sustained rumble segments intact; suppress generic peaks that fall inside them.
    val combined = (rumbleHits + selected.filter { hit ->
        rumbleHits.none { rumble -> hit.timeMs in rumble.timeMs until (rumble.timeMs + rumble.durationMs) }
    }).sortedBy { it.timeMs }
    val merged = ArrayList<HapticHit>()
    for (hit in combined) {
        val last = merged.lastOrNull()
        if (last != null && !hit.isCarRumble && !last.isCarRumble && hit.timeMs - last.timeMs < 70L) {
            if (hit.strength > last.strength) merged[merged.lastIndex] = hit
        } else {
            merged.add(hit)
        }
    }
    return merged
}
