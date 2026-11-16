// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // Only the Compose compiler is needed. Kotlin core is built-in with AGP 9.0+
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}