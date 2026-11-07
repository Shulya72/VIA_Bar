plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "party.shulya.freewrite"
    compileSdk = 35

    defaultConfig {
        applicationId = "party.shulya.freewrite"
        // HarmonyOS 4.x даёт приложениям Android 12 (API 31); 26 — запас для других планшетов.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Google ML Kit Digital Ink Recognition: распознаёт штрихи пера на самом устройстве.
    // Языковая модель (~20 МБ) скачивается один раз, дальше работает без интернета.
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")
}
