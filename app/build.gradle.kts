plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.keveny.legendajp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.keveny.legendajp"
        minSdk = 33          // EXTRA_AUDIO_SOURCE e sessões segmentadas exigem Android 13+
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
    }

    signingConfigs {
        // Keystore fixo para que cada APK novo instale por cima do anterior
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
