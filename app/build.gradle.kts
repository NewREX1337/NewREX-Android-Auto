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
        versionCode = 18
        versionName = "1.6.0"
        // Harita kütüphanesi her işlemci türü için ayrı dosya getiriyor; telefonlarda sadece ARM kullanılıyor
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
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

    buildFeatures { buildConfig = true; aidl = true }

    // full: Samsung/Pixel (King Installer) için, iki kapı da var. park: Xiaomi/Tecno gibi King'in çalışmadığı
    // Android 15+ telefonlar için; içinde Android Auto araç uygulaması tanımı yok, sadece park kapısı var.
    flavorDimensions += "door"
    // 360° dönen araç kişisel (gizli) klasörde durur; klasör yoksa herkese açık tek fotoğraflı sürüm derlenir
    sourceSets["main"].java.srcDir(if (file("src/myself").exists() && !project.hasProperty("ikinci")) "src/myself/java" else "src/public/java")

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

    // -Pikinci: paylaşılan sürümü mevcut NewREX'e dokunmadan telefona ayrı bir uygulama olarak kurmak için
    if (project.hasProperty("ikinci")) {
        defaultConfig.applicationIdSuffix = ".ikinci"
        sourceSets.getByName("full").res.srcDir("src/ikinci/res")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Shizuku: telefon uygulamalarını yansıtmasız araç ekranında açmak için (kullanıcı Shizuku'yu açıp izin verirse)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Shizuku'suz: NewREX telefonun kendi kablosuz hata ayıklamasına telefonun içinden bağlanır (açık kaynak, Apache-2.0)
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
    // Araç sayfasındaki canlı harita (OpenFreeMap vektör haritası, ücretsiz, anahtar gerekmez)
    implementation("org.maplibre.gl:android-sdk:11.13.1")
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-projected:1.4.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("io.coil-kt:coil:2.7.0")
    // Araç görselinin arka planını silmek için: Google'ın telefonda çalışan modeli, görsel hiçbir yere gönderilmez
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
