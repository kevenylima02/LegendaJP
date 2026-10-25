import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Chave do Gemini fora do git: local.properties (gemini.key=...) ou variável GEMINI_API_KEY (segredo do Actions)
val geminiKey: String = run {
    val f = rootProject.file("local.properties")
    val props = Properties()
    if (f.exists()) f.inputStream().use { props.load(it) }
    (props.getProperty("gemini.key") ?: System.getenv("GEMINI_API_KEY") ?: "").trim()
}

android {
    namespace = "com.keveny.legendajp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.keveny.legendajp"
        minSdk = 33          // EXTRA_AUDIO_SOURCE e sessões segmentadas exigem Android 13+
        targetSdk = 34
        versionCode = 22
        versionName = "0.22"
        buildConfigField("String", "GEMINI_KEY", "\"$geminiKey\"")
    }

    buildFeatures {
        buildConfig = true
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

    // Um APK por arquitetura: o S26 Ultra usa arm64-v8a; x86_64 só para o emulador.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
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
    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/*.md", "META-INF/*.txt")
    }
}

dependencies {
    // Analisador morfológico de japonês (forma de dicionário, classe, conjugação, leitura). Puro Java.
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    // Tradução japonês → português offline (modelo baixado na primeira vez)
    implementation("com.google.mlkit:translate:17.0.3")
    // Reconhecimento de escrita à mão (modelo de japonês baixado na primeira vez)
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")
    // Gemini Nano no aparelho (AICore), para as explicações com IA
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}
