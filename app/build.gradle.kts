plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cote.farttrigger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cote.farttrigger"
        minSdk = 26
        targetSdk = 34
        // Each GitHub build gets a higher number so it installs as an update
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // One fixed key, so every new build can install over the old one
    signingConfigs {
        create("fixed") {
            storeFile = file("farttrigger.jks")
            storePassword = "farttrigger"
            keyAlias = "farttrigger"
            keyPassword = "farttrigger"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Offline speech recognition (runs entirely on the phone)
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
