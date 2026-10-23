package com.example.experiencehaptic

import kotlin.math.abs

/**
 * SoundSense event gate.
 *
 * YAMNet classifies approximately 0.975-second windows rather than isolated transients.
 * We therefore use a generous time neighbourhood around each prediction. Music/instrument
 * predictions suppress generic energy peaks, but a nearby positive impact/vehicle label
 * overrides that suppression so mixed music + effects can still produce haptics.
 */
object SoundSensePolicy {
    private const val MUSIC_CONFIDENCE = 0.48f
    private const val IMPACT_CONFIDENCE = 0.30f
    private const val VEHICLE_CONFIDENCE = 0.27f
    private const val MUSIC_WINDOW_MS = 520L
    private const val IMPACT_WINDOW_MS = 850L
    private const val VEHICLE_WINDOW_MS = 1_600L

    // Broad recall-oriented list: YAMNet labels vary between related vehicle sounds.
    // These terms include small/quiet road events, not just loud engine or crash labels.
    private val vehicleTerms = listOf(
        "engine", "vehicle", "motor vehicle", "car", "automobile", "truck", "bus",
        "motorcycle", "motorbike", "accelerating", "acceleration", "revving", "idling",
        "starting engine", "engine starting", "engine knocking", "engine sound", "diesel",
        "tire", "tyre", "tire noise", "tyre noise", "skidding", "screech", "brake",
        "braking", "air brake", "horn", "car horn", "road", "roadway", "traffic",
        "road traffic", "vehicle noise", "car passing", "passing car", "race car", "racing",
        "gear shift", "gear changing", "reversing", "reverse alarm", "car alarm",
        "turn signal", "indicator", "suspension", "exhaust", "gravel", "train"
    )

    private val impactTerms = listOf(
        "impact", "crash", "collision", "explosion", "gunshot", "fireworks",
        "thump", "bang", "boom", "slam", "punch", "punching", "breaking", "smash",
        "shatter", "glass breaking", "glass shattering", "knock", "door",
        "footstep", "clatter", "metal", "hit", "whack"
    )

    // YAMNet may describe the whole window using an instrument or music genre instead
    // of the generic "Music" label, so include common instrument/genre categories too.
    private val musicTerms = listOf(
        "music", "musical instrument", "singing", "singer", "song", "melody",
        "guitar", "piano", "keyboard music", "drum kit", "drum machine", "orchestra",
        "violin", "cello", "flute", "trumpet", "saxophone", "bass guitar", "synthesizer",
        "hip hop", "pop music", "rock music", "electronic music", "techno", "disco",
        "musical", "rapping", "beatboxing"
    )

    fun buildHapticHits(
        energyHits: List<HapticHit>,
        detections: List<SoundDetection>
    ): List<HapticHit> {
        val positive = detections.filter { detection ->
            val label = detection.label.normalized()
            (vehicleTerms.any { term -> label.contains(term) } && detection.confidence >= VEHICLE_CONFIDENCE) ||
                (isImpactLabel(label) && detection.confidence >= IMPACT_CONFIDENCE)
        }

        val music = detections.filter { detection ->
            val label = detection.label.normalized()
            musicTerms.any { term -> label.contains(term) } && detection.confidence >= MUSIC_CONFIDENCE
        }

        val classifiedHits = positive.mapNotNull { detection ->
            val label = detection.label.normalized()
            when {
                vehicleTerms.any { term -> label.contains(term) } && detection.confidence >= VEHICLE_CONFIDENCE ->
                    HapticHit(
                        timeMs = detection.timeMs,
                        strength = (105 + detection.confidence * 145f).toInt().coerceIn(95, 240),
                        isCarRumble = true,
                        durationMs = if (label.contains("engine") || label.contains("motor") || label.contains("traffic") || label.contains("road")) 520L else 320L
                    )
                isImpactLabel(label) && detection.confidence >= IMPACT_CONFIDENCE ->
                    HapticHit(
                        timeMs = detection.timeMs,
                        strength = (85 + detection.confidence * 170f).toInt().coerceIn(80, 255),
                        durationMs = 70L
                    )
                else -> null
            }
        }

        // A positive event in the same analysis window wins over a music prediction.
        // This is the key mixed-audio rule: do not discard a crash simply because music
        // is playing at the same time.
        val gatedEnergy = energyHits.filter { hit ->
            val confirmedEventNearby = positive.any { detection ->
                val label = detection.label.normalized()
                val window = if (vehicleTerms.any { term -> label.contains(term) }) VEHICLE_WINDOW_MS else IMPACT_WINDOW_MS
                abs(detection.timeMs - hit.timeMs) <= window
            }
            val musicOnlyNearby = music.any { detection ->
                abs(detection.timeMs - hit.timeMs) <= MUSIC_WINDOW_MS
            } && !confirmedEventNearby

            // Recall-first gate: suppress weak energy bumps during confident music,
            // but keep stronger transients that may be a quiet crash/gear/brake event
            // mixed under the soundtrack. This deliberately favours fewer missed events.
            !musicOnlyNearby || hit.strength >= 145
        }

        // Merge classifier detections and allowed energy transients. If two events are
        // nearly coincident, retain the stronger event; don't merge separate car segments.
        val combined = (gatedEnergy + classifiedHits).sortedBy { it.timeMs }
        val merged = ArrayList<HapticHit>()
        for (hit in combined) {
            val previous = merged.lastOrNull()
            if (previous != null && !hit.isCarRumble && !previous.isCarRumble &&
                abs(hit.timeMs - previous.timeMs) < 120L
            ) {
                if (hit.strength > previous.strength) merged[merged.lastIndex] = hit
            } else if (previous == null || hit.isCarRumble || previous.isCarRumble ||
                abs(hit.timeMs - previous.timeMs) >= 120L
            ) {
                merged.add(hit)
            }
        }
        return merged
    }

    private fun String.normalized(): String = lowercase().replace('_', ' ').trim()

    /**
     * FSD50K distinguishes actual breakage (Shatter) from the broad Glass class,
     * which also includes benign sounds such as coins dropping into glass. Its
     * Crash cymbal class also shows why a substring match on "crash" is unsafe.
     * Keep those musical/object labels from becoming cinematic impact cues.
     */
    private fun isImpactLabel(label: String): Boolean {
        if (label.contains("cymbal")) return false
        if (label.contains("glass")) {
            return listOf("break", "shatter", "smash", "crash")
                .any { action -> label.contains(action) }
        }
        return impactTerms.any { term -> label.contains(term) }
    }
}
