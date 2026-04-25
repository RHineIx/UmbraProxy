// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "9.0.1" apply false
    // Only the Compose compiler is needed. Kotlin core is built-in with AGP 9.0+
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}