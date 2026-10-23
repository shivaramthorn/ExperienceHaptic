package com.example.experiencehaptic

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier.AudioClassifierOptions
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import java.nio.ByteOrder
import kotlin.math.max

data class SoundDetection(
    val timeMs: Long,
    val label: String,
    val confidence: Float
)

object SoundClassifier {
    private const val TARGET_RATE = 16000
    private const val WINDOW_SAMPLES = 15600 // YAMNet input: 0.975 seconds
    private const val MAX_SECONDS = 600

    fun classify(context: Context, uri: Uri): List<SoundDetection> {
        val samples = decodeToMono16k(context, uri)
        if (samples.isEmpty()) return emptyList()

        val options = AudioClassifierOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("yamnet.tflite")
                    .build()
            )
            .setRunningMode(RunningMode.AUDIO_CLIPS)
            .setMaxResults(5)
            .build()

        val classifier = AudioClassifier.createFromOptions(context, options)
        try {
            val detections = ArrayList<SoundDetection>()
            var offset = 0

            while (offset < samples.size) {
                val window = FloatArray(WINDOW_SAMPLES)
                val available = minOf(WINDOW_SAMPLES, samples.size - offset)
                System.arraycopy(samples, offset, window, 0, available)

                val audioData = AudioData.create(
                    AudioData.AudioDataFormat.builder()
                        .setNumOfChannels(1)
                        .setSampleRate(TARGET_RATE.toFloat())
                        .build(),
                    WINDOW_SAMPLES
                )
                audioData.load(window)

                val result = classifier.classify(audioData)
                result.classificationResults().forEach { classificationResult ->
                    // YAMNet describes this whole window, not a point event. Store the
                    // window midpoint so callers can compare it with nearby audio cues.
                    val timeMs = (offset.toLong() + WINDOW_SAMPLES / 2L) * 1000L / TARGET_RATE

                    classificationResult.classifications().forEach { head ->
                        head.categories().forEach { category ->
                            val label = category.categoryName()
                                ?.takeIf { it.isNotBlank() }
                                ?: category.displayName()
                                    ?.takeIf { it.isNotBlank() }

                            if (label != null && category.score() >= 0.25f) {
                                detections.add(
                                    SoundDetection(
                                        timeMs = timeMs,
                                        label = label,
                                        confidence = category.score()
                                    )
                                )
                            }
                        }
                    }
                }
                offset += WINDOW_SAMPLES
            }

            return detections.sortedBy { it.timeMs }
        } finally {
            classifier.close()
        }
    }

    private fun decodeToMono16k(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val mono = ArrayList<Float>()
        val maxSamples = TARGET_RATE * MAX_SECONDS

        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("The selected video has no audio track.")

            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("Unknown audio format.")

            var sourceRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sourceFrameCountTotal = 0L
            var nextTargetSample = 0L

            while (!outputDone && mono.size < maxSamples) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("Audio decoder input buffer unavailable.")
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex, 0, size, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, 10000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val changed = decoder.outputFormat
                        if (changed.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sourceRate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (changed.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }

                    outputIndex >= 0 -> {
                        val output = decoder.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0 && sourceRate > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val pcm = output.slice()
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer()
                            val channelCount = max(1, channels)
                            val frames = pcm.remaining() / channelCount

                            // Simple decimation/interpolation by nearest source frame.
                            // Works for typical decoded 16-bit PCM output.
                            for (frame in 0 until frames) {
                                val absoluteSourceFrame = sourceFrameCountTotal + frame
                                while (
                                    nextTargetSample.toDouble() * sourceRate / TARGET_RATE
                                    <= absoluteSourceFrame.toDouble() &&
                                    mono.size < maxSamples
                                ) {
                                    val localFrame = frame
                                    var sum = 0.0
                                    for (channel in 0 until channelCount) {
                                        sum += pcm.get(localFrame * channelCount + channel)
                                            .toInt() / 32768.0
                                    }
                                    mono.add((sum / channelCount).toFloat())
                                    nextTargetSample++
                                }
                                if (mono.size >= maxSamples) break
                            }
                            sourceFrameCountTotal += frames
                        }

                        outputDone =
                            (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            try {
                decoder?.stop()
            } catch (_: Exception) {
            }
            decoder?.release()
            extractor.release()
        }

        return mono.toFloatArray()
    }
}
