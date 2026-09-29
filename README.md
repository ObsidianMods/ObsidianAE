# Obsidian AE

Jetpack Compose template. Ready to open in Android Studio and build.

- Package: `com.obsidian.apkeditor`
- minSdk 30 (Android 11) · compileSdk 35 · targetSdk 35
- Gradle 8.13 · AGP 8.9.2 · Kotlin 2.1.20 · Compose BOM 2025.04.01
- AppCompat 1.7.0 · Material 1.12.0 · Material 3 (Compose)

## Open
Android Studio → File → Open → select this folder → let Gradle sync.
Needs JDK 17+ and Android SDK Platform 35 installed.

## CLI
On device use `sh gradlew` (noexec storage):
    sh gradlew assembleDebug

On CI (fast parallel):
    ./gradlew assembleRelease --parallel
