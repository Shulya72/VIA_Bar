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
        versionCode = 2
        versionName = "0.1.1"
    }

    signingConfigs {
        getByName("debug") {
            // В GitHub Actions подписываем постоянным отладочным ключом (лежит в кэше Actions,
            // в репозиторий не попадает), чтобы новые версии ставились поверх старых.
            // Пароли — стандартные для отладочного ключа Android, это не секрет.
            val ks = System.getenv("FREEWRITE_DEBUG_KEYSTORE")
            if (!ks.isNullOrBlank() && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
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
