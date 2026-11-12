plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    `maven-publish`
}

// The Plex connection and the player, used by Simplex and PlexVerse. Built and published from a Mac
// into the simplex-maven repository (tools/publish-core.sh), which GitHub serves as plain files.
val coreVersion = providers.gradleProperty("coreVersion").orElse("0.0.0-local").get()

android {
    namespace = "com.embergabor.simplex.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
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

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

val media3 = "1.9.4"

dependencies {
    api(platform("androidx.compose:compose-bom:2026.09.00"))
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.ui:ui")
    api("androidx.tv:tv-material:1.1.0")
    api("androidx.activity:activity-compose:1.13.0")
    api("androidx.core:core-ktx:1.19.1")

    api("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    api("androidx.media3:media3-ui:$media3")
    // Prebuilt FFmpeg audio decoders (TrueHD, DTS, AC3, ...) for when passthrough isn't available.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")

    api("io.coil-kt.coil3:coil-compose:3.6.3")
    api("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    api("com.squareup.okhttp3:okhttp:5.5.1")

    testImplementation("junit:junit:4.13.2")
    // The real org.json, in place of the Android stubs, for tests that parse Plex responses.
    testImplementation("org.json:json:20240303")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.embergabor"
            artifactId = "simplex-core"
            version = coreVersion
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        // A checkout of github.com/embergabor/simplex-maven.
        maven {
            name = "simplexMaven"
            url = uri(providers.gradleProperty("simplexMaven").orElse("${rootDir}/../simplex-maven").get())
        }
    }
}
