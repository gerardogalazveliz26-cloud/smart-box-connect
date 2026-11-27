plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePath: String? = System.getenv("NEXUMID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

// El panel web (../../panel/index.html) se empaqueta dentro de la app.
val panelAssets = layout.buildDirectory.dir("generated/panelAssets")
val copyPanel by tasks.registering(Copy::class) {
    from(rootProject.file("../panel")) { include("index.html") }
    into(panelAssets.map { it.dir("panel") })
}

android {
    namespace = "com.nexumid.panel"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nexumid.panel"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "2.3.0"
    }

    sourceSets["main"].assets.srcDir(panelAssets)

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
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

tasks.named("preBuild") { dependsOn(copyPanel) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
}
