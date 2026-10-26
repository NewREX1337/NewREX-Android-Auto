import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Ä°mza bilgileri repoya girmez; keystore.properties yalnÄ±zca geliÅŸtiricinin bilgisayarÄ±nda durur.
val keystoreFile = rootProject.file("keystore.properties")
val keystore = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}

android {
    namespace = "com.newrex.auto"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.newrex.auto"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "1.5.1"
    }

    signingConfigs {
        create("release") {
            if (keystoreFile.exists()) {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (keystoreFile.exists()) "release" else "debug")
        }
    }

    buildFeatures { buildConfig = true }

    // full: Samsung/Pixel (King Installer) iÃ§in, iki kapÄ± da var. park: Xiaomi/Tecno gibi King'in Ã§alÄ±ÅŸmadÄ±ÄŸÄ±
    // Android 15+ telefonlar iÃ§in; iÃ§inde Android Auto araÃ§ uygulamasÄ± tanÄ±mÄ± yok, sadece park kapÄ±sÄ± var.
    flavorDimensions += "door"
    productFlavors {
        create("full") {
            dimension = "door"
            buildConfigField("boolean", "PARK_ONLY", "false")
            buildConfigField("boolean", "AUDIO_ONLY", "false")
        }
        create("park") {
            dimension = "door"
            versionNameSuffix = "-park"
            buildConfigField("boolean", "PARK_ONLY", "true")
            buildConfigField("boolean", "AUDIO_ONLY", "false")
        }
        // ses: arabanÄ±n mÃ¼zik ekranÄ±nda gÃ¶rÃ¼nen ayrÄ± uygulama. Android Auto "Bilinmeyen kaynaklar" medya
        // uygulamalarÄ±na iÅŸler, sÃ¼rÃ¼ÅŸte de Ã§alÄ±ÅŸÄ±r (sadece ses). Park sÃ¼rÃ¼mÃ¼yle yan yana kurulabilir.
        create("ses") {
            dimension = "door"
            applicationIdSuffix = ".ses"
            versionNameSuffix = "-ses"
            buildConfigField("boolean", "PARK_ONLY", "false")
            buildConfigField("boolean", "AUDIO_ONLY", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-projected:1.4.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
