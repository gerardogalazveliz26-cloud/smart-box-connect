plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Capturas del diseño (Paparazzi): solo cuando se pide con -Ppreviews, para no afectar la compilación del APK.
if (project.hasProperty("previews")) {
    apply(plugin = "app.cash.paparazzi")
}

val keystorePath: String? = System.getenv("NEXUMID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val defaultServer = (project.findProperty("nexumid.defaultServer") as String?).orEmpty()
val whatsapp = (project.findProperty("nexumid.whatsapp") as String?).orEmpty()

android {
    namespace = "com.nexumid.iptv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nexumid.iptv"
        minSdk = 21
        targetSdk = 34
        versionCode = 18
        versionName = "2.4.1"
        buildConfigField("String", "DEFAULT_SERVER", "\"$defaultServer\"")
        buildConfigField("String", "WHATSAPP", "\"$whatsapp\"")
        buildConfigField(
            "String",
            "CONFIG_URL",
            "\"https://raw.githubusercontent.com/gerardogalazveliz26-cloud/smart-box-connect/main/updates/nexumid-config.json\""
        )
        // Equipos reales (celulares y TV Box) son ARM: se omiten x86 para no agrandar el APK.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        buildConfigField(
            "String",
            "UPDATE_URL",
            "\"https://raw.githubusercontent.com/gerardogalazveliz26-cloud/smart-box-connect/main/updates/nexumid-iptv.json\""
        )
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("NEXUMID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NEXUMID_KEY_ALIAS")
                keyPassword = System.getenv("NEXUMID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // Decodificador de audio FFmpeg (AC-3, E-AC-3, MP2, DTS…) para canales que no suenan con el decodificador del equipo.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
