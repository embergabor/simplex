import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The release key lives outside the repo (~/.simplex-release, backed up by its owner). Without it,
// release builds fall back to the debug key, which can't update a TV that has a real release on it.
val releaseKey = File(System.getProperty("user.home"), ".simplex-release/keystore.properties")
    .takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) } }

// Every commit is a newer version, so the in-app update check can compare numbers.
val commitCount = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
    .standardOutput.asText.get().trim().toIntOrNull() ?: 1

android {
    namespace = "com.ember.simpleplex"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ember.simpleplex"
        minSdk = 26
        targetSdk = 37
        versionCode = commitCount
        versionName = "0.2.$commitCount"
    }

    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = File(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // The build that goes on the TV. Debug builds run Compose unoptimized, which makes focus
        // moves and scrolling stutter on the TV's slow CPU.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

val media3 = "1.9.4"

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")

    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // Prebuilt FFmpeg audio decoders (TrueHD, DTS, AC3, ...) for when passthrough isn't available.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")

    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")

    testImplementation("junit:junit:4.13.2")
    // The real org.json, in place of the Android stubs, for tests that parse Plex responses.
    testImplementation("org.json:json:20240303")
}
