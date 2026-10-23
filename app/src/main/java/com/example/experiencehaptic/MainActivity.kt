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
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

private val Ink = Color(0xFF10141D)
private val Panel = Color(0xFF191F2C)
private val Accent = Color(0xFFB6F36A)
private val Muted = Color(0xFF9BA5B5)

data class HapticHit(
    val timeMs: Long,
    val strength: Int,
    val isCarRumble: Boolean = false,
    val durationMs: Long = 70L
)

@UnstableApi
class MainActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var hapticJobRunning = false
    private var immersiveModeEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        player = ExoPlayer.Builder(this).build()

        setContent {
            ExperienceHapticScreen(this)
        }
    }

    @Composable
    private fun ExperienceHapticScreen(context: Context) {
        var uri by remember { mutableStateOf<Uri?>(null) }
        var title by remember { mutableStateOf<String?>(null) }
        var hits by remember { mutableStateOf<List<HapticHit>>(emptyList()) }
        var analyzing by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var playing by remember { mutableStateOf(false) }
        var landscapeMode by remember { mutableStateOf(false) }

        BackHandler(enabled = landscapeMode) {
            landscapeMode = false
            toggleLandscape(false)
        }

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { picked ->
            if (picked != null) {
                uri = picked
                title = picked.lastPathSegment?.substringAfterLast('/')
                    ?: "Selected video"

                hits = emptyList()
                error = null

                player?.setMediaItem(MediaItem.fromUri(picked))
                player?.prepare()
            }
        }

        DisposableEffect(Unit) {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying
                }
            }

            player?.addListener(listener)

            onDispose {
                player?.removeListener(listener)
            }
        }

        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Accent,
                background = Ink,
                surface = Panel
            )
        ) {
            if (landscapeMode && uri != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    AndroidView(
                        factory = { viewContext ->
                            PlayerView(viewContext).apply {
                                player = this@MainActivity.player
                                useController = true
                                controllerShowTimeoutMs = 3500
                                setShowBuffering(
                                    PlayerView.SHOW_BUFFERING_WHEN_PLAYING
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                        color = Color(0xCC10141D),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        TextButton(
                            onClick = {
                                landscapeMode = false
                                toggleLandscape(false)
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = Accent
                            )
                        ) {
                            Icon(
                                Icons.Default.Fullscreen,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "EXIT FULL SCREEN",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Ink)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp)
                ) {
                    Spacer(Modifier.height(24.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Vibration,
                            contentDescription = null,
                            tint = Accent,
                            modifier = Modifier.size(22.dp)
                        )

                        Spacer(Modifier.width(9.dp))

                        Text(
                            "EXPERIENCE HAPTIC",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )

                        Spacer(Modifier.weight(1f))

                        Text(
                            "BETA 01",
                            color = Muted,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp
                        )
                    }

                    Spacer(Modifier.height(36.dp))

                    Text(
                        "Feel the\nvideo.",
                        color = Color.White,
                        fontSize = 42.sp,
                        lineHeight = 46.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(10.dp))

                    Text(
                        "Turn a clip’s sound into a synchronized haptic track.",
                        color = Muted,
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    )

                    Spacer(Modifier.height(24.dp))

                    if (uri == null) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    Panel,
                                    RoundedCornerShape(20.dp)
                                )
                                .padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = Accent,
                                modifier = Modifier.size(36.dp)
                            )

                            Spacer(Modifier.height(12.dp))

                            Text(
                                "Start with a video",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 17.sp
                            )

                            Spacer(Modifier.height(6.dp))

                            Text(
                                "Choose a clip from your device to generate its haptic rhythm.",
                                color = Muted,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )

                            Spacer(Modifier.height(18.dp))

                            Button(
                                onClick = {
                                    picker.launch(arrayOf("video/*"))
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Accent,
                                    contentColor = Ink
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "IMPORT VIDEO",
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    } else {
                        Text(
                            "YOUR CLIP",
                            color = Muted,
                            fontSize = 11.sp,
                            letterSpacing = 1.5.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(10.dp))

                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(210.dp)
                                .background(
                                    Panel,
                                    RoundedCornerShape(18.dp)
                                )
                        ) {
                            AndroidView(
                                factory = { viewContext ->
                                    PlayerView(viewContext).apply {
                                        player = this@MainActivity.player
                                        useController = true
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = {
                                landscapeMode = true
                                toggleLandscape(true)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Accent
                            )
                        ) {
                            Icon(
                                Icons.Default.Fullscreen,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "LANDSCAPE · FULL SCREEN",
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        Text(
                            title ?: "Video",
                            color = Color.White,
                            fontSize = 14.sp,
                            maxLines = 1
                        )

                        Spacer(Modifier.height(18.dp))

                        Button(
                            onClick = {
                                if (hits.isEmpty()) {
                                    analyzing = true
                                    error = null

                                    lifecycleScope.launch {
                                        val selectedUri = uri

                                        if (selectedUri == null) {
                                            error = "Please select a video first."
                                            analyzing = false
                                            return@launch
                                        }

                                        val result = withContext(Dispatchers.IO) {
                                            runCatching {
                                                val energyHits = analyzeAudio(
                                                    context,
                                                    selectedUri
                                                )

                                                val detections =
                                                    SoundClassifier.classify(
                                                        context,
                                                        selectedUri
                                                    )

                                                val soundHits =
                                                    detections.mapNotNull { detection ->
                                                        val label =
                                                            detection.label.lowercase()

                                                        when {
                                                            listOf(
                                                                "engine",
                                                                "vehicle",
                                                                "motor",
                                                                "truck",
                                                                "bus",
                                                                "car",
                                                                "rumble"
                                                            ).any { it in label } -> {
                                                                HapticHit(
                                                                    timeMs = detection.timeMs,
                                                                    strength = 150,
                                                                    isCarRumble = true,
                                                                    durationMs = 975L
                                                                )
                                                            }

                                                            listOf(
                                                                "explosion",
                                                                "gunshot",
                                                                "crash",
                                                                "thump",
                                                                "bang",
                                                                "slam",
                                                                "impact"
                                                            ).any { it in label } -> {
                                                                HapticHit(
                                                                    timeMs = detection.timeMs,
                                                                    strength = (
                                                                            100 +
                                                                                    detection.confidence *
                                                                                    155f
                                                                            ).toInt().coerceIn(80, 255)
                                                                )
                                                            }

                                                            else -> null
                                                        }
                                                    }

                                                val combined =
                                                    (energyHits + soundHits)
                                                        .sortedBy { it.timeMs }

                                                val cleaned = ArrayList<HapticHit>()

                                                for (hit in combined) {
                                                    val previous =
                                                        cleaned.lastOrNull()

                                                    if (
                                                        previous != null &&
                                                        !hit.isCarRumble &&
                                                        !previous.isCarRumble &&
                                                        hit.timeMs - previous.timeMs < 90L
                                                    ) {
                                                        if (
                                                            hit.strength >
                                                            previous.strength
                                                        ) {
                                                            cleaned[cleaned.lastIndex] =
                                                                hit
                                                        }
                                                    } else if (
                                                        previous == null ||
                                                        hit.isCarRumble ||
                                                        previous.isCarRumble ||
                                                        hit.timeMs -
                                                        previous.timeMs >= 90L
                                                    ) {
                                                        cleaned.add(hit)
                                                    }
                                                }

                                                cleaned
                                            }
                                        }

                                        result.onSuccess {
                                            hits = it
                                        }.onFailure { exception ->
                                            Log.e(
                                                "ExperienceHaptic",
                                                "Audio analysis failed",
                                                exception
                                            )

                                            error = "Analysis failed: ${
                                                exception.message
                                                    ?: exception.javaClass.simpleName
                                            }"
                                        }

                                        analyzing = false
                                    }
                                } else {
                                    if (player?.isPlaying == true) {
                                        player?.pause()
                                    } else {
                                        player?.seekTo(0)
                                        player?.play()
                                        playHaptics(hits)
                                    }
                                }
                            },
                            enabled = !analyzing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Accent,
                                contentColor = Ink
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            if (analyzing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Ink
                                )
                            } else {
                                Icon(
                                    if (hits.isNotEmpty() && playing) {
                                        Icons.Default.Refresh
                                    } else {
                                        Icons.Default.PlayArrow
                                    },
                                    contentDescription = null
                                )

                                Spacer(Modifier.width(8.dp))

                                Text(
                                    if (hits.isEmpty()) {
                                        "ANALYZE & GENERATE"
                                    } else if (playing) {
                                        "PLAYING WITH HAPTICS"
                                    } else {
                                        "PLAY WITH HAPTICS"
                                    },
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                        }

                        error?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                it,
                                color = Color(0xFFFF8B8B),
                                fontSize = 13.sp
                            )
                        }

                        if (hits.isNotEmpty()) {
                            Spacer(Modifier.height(22.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "HAPTIC TRACK",
                                    color = Muted,
                                    fontSize = 11.sp,
                                    letterSpacing = 1.5.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(Modifier.weight(1f))

                                Text(
                                    "${hits.size} cues",
                                    color = Accent,
                                    fontSize = 12.sp
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            HapticTimeline(hits)

                            Spacer(Modifier.height(10.dp))

                            Text(
                                "Cues combine YAMNet sound-category predictions with audio-energy analysis. Predictions may be imperfect.",
                                color = Muted,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )

                            TextButton(
                                onClick = {
                                    picker.launch(arrayOf("video/*"))
                                }
                            ) {
                                Text(
                                    "Choose another video",
                                    color = Accent
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { vibrateTest() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Accent
                        )
                    ) {
                        Icon(
                            Icons.Default.Vibration,
                            contentDescription = null
                        )

                        Spacer(Modifier.width(8.dp))

                        Text(
                            "TEST VIBRATION · 250",
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .background(
                                Color(0xFF191F2C),
                                RoundedCornerShape(8.dp)
                            )
                            .border(
                                1.dp,
                                Color(0xFF667085),
                                RoundedCornerShape(8.dp)
                            )
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        Log.i(
                                            "ExperienceHapticHaptics",
                                            "hold button PRESSED"
                                        )

                                        startContinuousHaptic()

                                        try {
                                            awaitRelease()
                                        } finally {
                                            stopContinuousHaptic()
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Vibration,
                                contentDescription = null,
                                tint = Color.White
                            )

                            Spacer(Modifier.width(8.dp))

                            Text(
                                "HOLD TO FEEL CONTINUOUS HAPTIC",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.4.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(5.dp))

                    Text(
                        "Press and hold to feel a steady repeating vibration. Release to stop.",
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )

                    Spacer(Modifier.height(22.dp))

                    Text(
                        "AUTOMATIC HAPTICS · ON-DEVICE ANALYSIS",
                        color = Muted,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp
                    )

                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun toggleLandscape(enabled: Boolean) {
        requestedOrientation = if (enabled) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        immersiveModeEnabled = enabled

        window.decorView.systemUiVisibility = if (enabled) {
            (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
        } else {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun playHaptics(events: List<HapticHit>) {
        if (hapticJobRunning) return

        hapticJobRunning = true

        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as Vibrator)
        }

        lifecycleScope.launch {
            var previousPosition = -1L
            var activeRumbleStart: Long? = null

            val impacts = events.filterNot { it.isCarRumble }
            val rumbles = events.filter {
                it.isCarRumble && it.durationMs > 0L
            }

            Log.i(
                "ExperienceHapticHaptics",
                "scheduler started: cues=${events.size}, " +
                        "rumbles=${rumbles.size}, impacts=${impacts.size}"
            )

            try {
                var waitMs = 0

                while (
                    player?.isPlaying != true &&
                    player?.playWhenReady == true &&
                    waitMs < 5000
                ) {
                    delay(20.milliseconds)
                    waitMs += 20
                }

                if (player?.isPlaying != true) {
                    Log.w(
                        "ExperienceHapticHaptics",
                        "Playback did not start; scheduler exiting after ${waitMs}ms"
                    )
                    return@launch
                }

                previousPosition =
                    (player?.currentPosition ?: 0L) - 1L

                Log.i(
                    "ExperienceHapticHaptics",
                    "playback detected at ${player?.currentPosition}ms"
                )

                while (
                    player?.playWhenReady == true &&
                    player?.playbackState != Player.STATE_ENDED
                ) {
                    val currentPlayer = this@MainActivity.player ?: break
                    val position = currentPlayer.currentPosition

                    if (!currentPlayer.isPlaying) {
                        vibrator.cancel()
                        activeRumbleStart = null
                        delay(20.milliseconds)
                        continue
                    }

                    if (
                        previousPosition >= 0L &&
                        position + 40L < previousPosition
                    ) {
                        vibrator.cancel()
                        activeRumbleStart = null
                        previousPosition = position - 1L
                    }

                    val activeRumble = rumbles.firstOrNull {
                        position in (
                                it.timeMs until
                                        (it.timeMs + it.durationMs)
                                )
                    }

                    if (activeRumble != null) {
                        if (activeRumbleStart != activeRumble.timeMs) {
                            vibrator.cancel()

                            val timings = longArrayOf(
                                0L, 45L, 25L, 70L, 25L, 45L
                            )

                            val amplitudes =
                                if (vibrator.hasAmplitudeControl()) {
                                    intArrayOf(110, 175, 135, 210, 150, 180)
                                } else {
                                    intArrayOf(180, 180, 180, 180, 180, 180)
                                }

                            vibrator.vibrate(
                                VibrationEffect.createWaveform(
                                    timings,
                                    amplitudes,
                                    0
                                )
                            )

                            activeRumbleStart = activeRumble.timeMs

                            Log.i(
                                "ExperienceHapticHaptics",
                                "RUMBLE START at ${position}ms; " +
                                        "segment=${activeRumble.timeMs}-" +
                                        "${activeRumble.timeMs + activeRumble.durationMs}ms"
                            )
                        }
                    } else if (activeRumbleStart != null) {
                        vibrator.cancel()

                        Log.i(
                            "ExperienceHapticHaptics",
                            "RUMBLE STOP at ${position}ms"
                        )

                        activeRumbleStart = null
                    }

                    if (activeRumble == null && previousPosition >= 0L) {
                        impacts.firstOrNull {
                            it.timeMs > previousPosition &&
                                    it.timeMs <= position
                        }?.let { hit ->
                            vibrator.cancel()

                            vibrator.vibrate(
                                VibrationEffect.createOneShot(
                                    90L,
                                    hit.strength.coerceIn(80, 255)
                                )
                            )

                            Log.d(
                                "ExperienceHapticHaptics",
                                "IMPACT at ${hit.timeMs}ms " +
                                        "strength=${hit.strength}"
                            )
                        }
                    }

                    previousPosition = position
                    delay(15.milliseconds)
                }
            } catch (exception: Exception) {
                Log.e(
                    "ExperienceHapticHaptics",
                    "Haptic scheduler error",
                    exception
                )
            } finally {
                vibrator.cancel()
                activeRumbleStart = null
                hapticJobRunning = false

                Log.i(
                    "ExperienceHapticHaptics",
                    "scheduler stopped"
                )
            }
        }
    }

    private fun startContinuousHaptic() {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as Vibrator)
        }

        if (!vibrator.hasVibrator()) {
            Log.w(
                "ExperienceHapticHaptics",
                "No vibrator is available"
            )
            return
        }

        val timings = longArrayOf(0L, 120L, 45L, 120L, 45L)

        val amplitudes = if (vibrator.hasAmplitudeControl()) {
            intArrayOf(0, 190, 0, 190, 0)
        } else {
            intArrayOf(0, 255, 0, 255, 0)
        }

        vibrator.vibrate(
            VibrationEffect.createWaveform(
                timings,
                amplitudes,
                0
            )
        )

        Log.i(
            "ExperienceHapticHaptics",
            "Continuous hold test STARTED; " +
                    "amplitudeControl=${vibrator.hasAmplitudeControl()}"
        )
    }

    private fun stopContinuousHaptic() {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as Vibrator)
        }

        vibrator.cancel()

        Log.i(
            "ExperienceHapticHaptics",
            "Continuous hold test STOPPED on release"
        )
    }

    private fun vibrateTest() {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as Vibrator)
        }

        vibrator.vibrate(
            VibrationEffect.createOneShot(250L, 250)
        )

        Log.i(
            "ExperienceHapticHaptics",
            "Test call completed; hasVibrator=${vibrator.hasVibrator()}, " +
                    "amplitudeControl=${vibrator.hasAmplitudeControl()}"
        )
    }

    override fun onDestroy() {
        player?.release()
        player = null

        if (immersiveModeEnabled) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }

        super.onDestroy()
    }
}

@Composable
private fun HapticTimeline(events: List<HapticHit>) {
    val maxTime = (events.lastOrNull()?.timeMs ?: 1L)
        .coerceAtLeast(1L)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .background(Panel, RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        val center = size.height / 2f

        drawLine(
            Color(0xFF3A4352),
            Offset(0f, center),
            Offset(size.width, center),
            strokeWidth = 1.dp.toPx()
        )

        events.forEach { hit ->
            val x = size.width * hit.timeMs / maxTime
            val height = size.height * (hit.strength / 255f)

            drawLine(
                Accent,
                Offset(x, center - height / 2),
                Offset(x, center + height / 2),
                strokeWidth = 3.dp.toPx()
            )
        }
    }
}

private fun analyzeAudio(
    context: Context,
    uri: Uri
): List<HapticHit> {
    val extractor = MediaExtractor()

    try {
        extractor.setDataSource(context, uri, null)

        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        } ?: throw IllegalArgumentException("No audio track found in this video.")

        extractor.selectTrack(track)

        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalArgumentException("Audio format is missing.")

        val decoder = MediaCodec.createDecoderByType(mime)

        try {
            decoder.configure(format, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            val buckets = ArrayList<Pair<Long, Double>>()
            val lowBuckets = ArrayList<Double>()

            var inputDone = false
            var outputDone = false

            var sampleRate =
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else {
                    44100
                }

            var bucketSamples = max(1, sampleRate / 12)
            var sumSquares = 0.0
            var lowSumSquares = 0.0
            var lowPass = 0.0
            var sampleCount = 0
            var sampleIndex = 0L

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)

                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException(
                                "Unable to access audio decoder input."
                            )

                        val size = extractor.readSampleData(input, 0)

                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )

                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                size,
                                extractor.sampleTime,
                                0
                            )

                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)

                if (outputIndex >= 0) {
                    val output = decoder.getOutputBuffer(outputIndex)

                    if (output != null && info.size > 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)

                        val pcm = output
                            .slice()
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()

                        while (pcm.hasRemaining()) {
                            val value = pcm.get().toInt() / 32768.0

                            val alpha =
                                1.0 - exp(
                                    -2.0 * Math.PI * 180.0 / sampleRate
                                )

                            lowPass += alpha * (value - lowPass)

                            sumSquares += value * value
                            sampleCount++
                            sampleIndex++

                            lowSumSquares += lowPass * lowPass

                            if (sampleCount >= bucketSamples) {
                                buckets.add(
                                    (sampleIndex * 1000L / sampleRate) to
                                            sqrt(sumSquares / sampleCount)
                                )

                                lowBuckets.add(
                                    sqrt(lowSumSquares / sampleCount)
                                )

                                sumSquares = 0.0
                                sampleCount = 0
                                lowSumSquares = 0.0
                            }
                        }
                    }

                    outputDone =
                        info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

                    decoder.releaseOutputBuffer(outputIndex, false)
                } else if (
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                ) {
                    val changed = decoder.outputFormat

                    if (changed.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate =
                            changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        bucketSamples = max(1, sampleRate / 12)
                    }
                }
            }

            if (buckets.size < 2) {
                throw IllegalArgumentException(
                    "Not enough audio data was decoded. Try a different video."
                )
            }

            val rms = buckets.map { it.second }
            val average = rms.average()
            val averageLow = lowBuckets.average()

            val threshold = max(0.045, average * 1.35)

            val raw = buckets.indices.filter { index ->
                val local = rms[index]

                local > threshold &&
                        local >= (rms.getOrNull(index - 1) ?: 0.0) &&
                        local >= (rms.getOrNull(index + 1) ?: 0.0)
            }

            val selected = ArrayList<HapticHit>()

            for (index in raw) {
                val time = buckets[index].first

                if (
                    selected.isEmpty() ||
                    time - selected.last().timeMs >= 140L
                ) {
                    selected.add(
                        HapticHit(
                            time,
                            min(
                                255,
                                max(80, (rms[index] * 640).toInt())
                            )
                        )
                    )
                } else if (
                    index > 0 &&
                    rms[index] > rms[index - 1]
                ) {
                    selected[selected.lastIndex] = HapticHit(
                        time,
                        min(
                            255,
                            max(80, (rms[index] * 640).toInt())
                        )
                    )
                }
            }

            val rumbleHits = ArrayList<HapticHit>()
            val rumbleThreshold = max(0.008, averageLow * 0.8)

            var segmentStart = -1L
            var segmentEnd = -1L
            var lastQualifyingTime = -1L

            fun closeRumbleSegment() {
                if (
                    segmentStart >= 0L &&
                    segmentEnd - segmentStart >= 250L
                ) {
                    rumbleHits.add(
                        HapticHit(
                            timeMs = segmentStart,
                            strength = 150,
                            isCarRumble = true,
                            durationMs = (segmentEnd - segmentStart)
                                .coerceAtLeast(250L)
                        )
                    )
                }

                segmentStart = -1L
                segmentEnd = -1L
                lastQualifyingTime = -1L
            }

            for (index in buckets.indices) {
                val total = rms[index]
                val low = lowBuckets.getOrElse(index) { 0.0 }
                val lowBandRatio = low / max(total, 1e-6)
                val qualifies =
                    low > rumbleThreshold &&
                            total > 0.012 &&
                            lowBandRatio >= 0.55

                val time = buckets[index].first

                if (qualifies) {
                    if (segmentStart < 0L) {
                        segmentStart = time
                    }

                    if (
                        lastQualifyingTime >= 0L &&
                        time - lastQualifyingTime > 180L
                    ) {
                        closeRumbleSegment()
                        segmentStart = time
                    }

                    segmentEnd = time + 83L
                    lastQualifyingTime = time
                } else if (
                    segmentStart >= 0L &&
                    time - lastQualifyingTime > 180L
                ) {
                    closeRumbleSegment()
                }
            }

            closeRumbleSegment()

            val combined = (
                    rumbleHits + selected.filter { hit ->
                        rumbleHits.none { rumble ->
                            hit.timeMs in (
                                    rumble.timeMs until
                                            (rumble.timeMs + rumble.durationMs)
                                    )
                        }
                    }
                    ).sortedBy { it.timeMs }

            val merged = ArrayList<HapticHit>()

            for (hit in combined) {
                val last = merged.lastOrNull()

                if (
                    last != null &&
                    !hit.isCarRumble &&
                    !last.isCarRumble &&
                    hit.timeMs - last.timeMs < 90L
                ) {
                    if (hit.strength > last.strength) {
                        merged[merged.lastIndex] = hit
                    }
                } else {
                    merged.add(hit)
                }
            }

            return merged
        } finally {
            try {
                decoder.stop()
            } catch (_: Exception) {
                // Decoder may already have stopped after a failure.
            }

            decoder.release()
        }
    } finally {
        extractor.release()
    }
}