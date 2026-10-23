Experience Haptic - corrected source files

Copy:
- MainActivity.kt
- SoundClassifier.kt

into:
app/src/main/java/com/example/experiencehaptic/

Important:
1. Keep both files in the same package: com.example.experiencehaptic.
2. Ensure app/build.gradle.kts has namespace = "com.example.experiencehaptic".
3. Add this dependency inside dependencies { } for the Compose Icons used by MainActivity:
   implementation("androidx.compose.material:material-icons-extended")
4. Ensure app/src/main/assets/yamnet.tflite exists. SoundClassifier requires this model file.
5. The AAR metadata error is a separate Gradle dependency/SDK compatibility issue; the source files alone do not resolve it. If it remains, copy the expanded :app:checkDebugAarMetadata error details.
