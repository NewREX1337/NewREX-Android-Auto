import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// İmza bilgileri repoya girmez; keystore.properties yalnızca geliştiricinin bilgisayarında durur.
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
        versionCode = 13
        versionName = "1.5.0"
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

    // full: Samsung/Pixel (King Installer) için, iki kapı da var. park: Xiaomi/Tecno gibi King'in çalışmadığı
    // Android 15+ telefonlar için; içinde Android Auto araç uygulaması tanımı yok, sadece park kapısı var.
    flavorDimensions += "door"
    productFlavors {
        create("full") {
            dimension = "door"
            buildConfigField("boolean", "PARK_ONLY", "false")
        }
        create("park") {
            dimension = "door"
            versionNameSuffix = "-park"
            buildConfigField("boolean", "PARK_ONLY", "true")
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
