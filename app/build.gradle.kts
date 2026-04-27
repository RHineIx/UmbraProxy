import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.umbraproxy"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.umbraproxy"
        // 🚀 Bumping minSdk to 26 to fix adaptive-icon AAPT error and modernize the app
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            // 🚀 Explicitly limit to targeted ABIs to save build time and final APK size
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }

        // 🚀 Strip all unused languages from libraries to heavily reduce APK size
        resourceConfigurations.addAll(listOf("en", "ar"))
    }

    splits {
        abi {
            isEnable = true
            reset()
            // 🚀 Limit split generation
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localProperties.load(localPropertiesFile.inputStream())
    }

    signingConfigs {
        create("release") {
            val keyFile = localProperties.getProperty("KEYSTORE_FILE")
            if (keyFile != null) {
                val resolvedFile = if (keyFile.startsWith("..")) {
                    file(rootDir.resolve(keyFile.substring(3)))
                } else {
                    file(keyFile)
                }
                if (resolvedFile.exists()) {
                    storeFile = resolvedFile
                    storePassword = localProperties.getProperty("KEYSTORE_PASSWORD")
                    keyAlias = localProperties.getProperty("KEY_ALIAS")
                    keyPassword = localProperties.getProperty("KEY_PASSWORD")
                }
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            // 🚀 Strip unused XMLs and drawables from the final APK
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val keyFile = localProperties.getProperty("KEYSTORE_FILE")
            val resolvedFile = if (keyFile != null && keyFile.startsWith("..")) {
                file(rootDir.resolve(keyFile.substring(3)))
            } else if (keyFile != null) {
                file(keyFile)
            } else null

            if (resolvedFile != null && resolvedFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Exclude unnecessary kotlin meta files from production
            excludes += "/**/*.kotlin_module"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.setSrcDirs(listOf("src/main/jniLibs"))
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    
    // LeakCanary is restricted to debug builds only, preventing massive memory overhead in production
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
    implementation("androidx.compose.material:material-icons-extended")
}
