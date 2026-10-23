EXPERIENCE HAPTIC — ANDROID STUDIO PROJECT

Contents
- app/src/main/java/com/example/experiencehaptic/MainActivity.kt
- app/src/main/java/com/example/experiencehaptic/SoundClassifier.kt
- Android manifest and Gradle build configuration
- Landscape-transition fix: configChanges in AndroidManifest.xml and rememberSaveable state for the selected URI, title, and full-screen flag.

OPENING
1. Extract this ZIP.
2. Open the extracted ExperienceHaptic_AndroidStudio_Project folder in Android Studio.
3. Allow Gradle sync. Use JDK 17 and an Android SDK that includes API 37 (or change compileSdk to an installed API level).
4. Run the app on a device with a vibrator.

IMPORTANT MODEL FILE
SoundClassifier.kt loads a MediaPipe model named yamnet.tflite from app/src/main/assets/. That model binary was not present among the project files supplied for this ZIP, so the assets folder is intentionally empty. To use sound classification, place a compatible YAMNet TFLite model at:
app/src/main/assets/yamnet.tflite

Without that model, video import/playback and haptic energy analysis may work, but sound classification will fail when analysis reaches SoundClassifier.classify(). The model file is not fabricated or substituted in this ZIP.

LANDSCAPE FIX
The Activity handles orientation/screen-size changes in the manifest so changing requestedOrientation should not recreate MainActivity. rememberSaveable is also used for the picked URI, title, and landscape mode. This is a source-level fix and has not been verified by a Gradle build in this environment.

NOTE
No Gradle wrapper JAR or compiled APK is included. Open the project in Android Studio so it can use its configured Gradle installation and download dependencies.


Sound-sensing noise rejection update
------------------------------------
- Energy peaks use a robust median / median-absolute-deviation noise estimate and must stand out from neighbouring buckets.
- Sustained low-frequency rumble requires a higher low-band ratio and energy above the clip's baseline, reducing triggers from steady background hum.
- SoundSense event gating uses confidence thresholds for impact and vehicle categories, plus music/instrument suppression around generic energy peaks.
- These are heuristic thresholds and should be tuned against sample videos from your target use case. Very quiet impacts may be missed as a trade-off for fewer false triggers.

Model requirement
----------------
The project ZIP does not include the binary YAMNet model. Add a compatible `yamnet.tflite` to `app/src/main/assets/` before running classification. The app source has not been compiled or device-tested in this environment.


SoundSense event-selective mode (latest update)
-----------------------------------------------
- Music/instrument/genre detections suppress generic energy peaks in a nearby time window.
- High-confidence impact and vehicle detections are retained, even when music is also present.
- Energy peaks outside music-classified windows remain eligible, preserving non-music effects when YAMNet is uncertain.
- This is event classification/gating, not true source separation: overlapping music and effects are not separated into isolated audio stems.
- The source has not been compiled or device-tested in this environment; test against clips with music-only sections and clips with impacts/vehicle sounds over music.


SoundSense recall-first vehicle / impact update
----------------------------------------------
- Lowered vehicle and impact classification thresholds to retain quieter related events.
- Expanded vehicle labels to include traffic/road sounds, idling, braking, gears, exhaust, tires, horns, reversing and other subtle vehicle-related labels where YAMNet exposes them.
- Reduced the music suppression window and kept stronger transients even when music is present, to avoid losing mixed sound effects.
- Made the RMS transient and low-frequency rumble detector more sensitive, shortened minimum rumble segment length, and allowed closer transient cues.
- This is a recall-oriented tuning pass intended to move the cue count upward; 70–90 cues cannot be guaranteed without testing the same video. Some musical percussion or road-like low-frequency noise may now produce extra cues.
- YAMNet cannot identify every minor car sound; labels depend on the actual model output. This is classification/gating, not source separation.
- No compatible yamnet.tflite binary was supplied; add it at app/src/main/assets/yamnet.tflite. Not compiled/device-tested in this environment.
