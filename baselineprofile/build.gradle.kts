// Generates app/src/release/generated/baselineProfiles/baseline-prof.txt.
// Run with an Android TV emulator or device on Android 13+ (or rooted) connected:
//   ./gradlew :app:generateReleaseBaselineProfile
// then commit the generated file. Sign in once in the release build first, so the walk sees real rows.
plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.ember.simpleplex.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.4.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
}
