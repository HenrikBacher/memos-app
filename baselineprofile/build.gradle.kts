plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

/**
 * Generates a Baseline Profile for :app — an ahead-of-time compilation hint
 * that measurably cuts cold-start time and first-scroll jank for Compose
 * apps, which do a lot of interpreted work on the first run of each code path.
 *
 * Generation needs a rooted emulator or a userdebug device:
 *   ./gradlew :app:generateBaselineProfile
 * The result is written into app/src/<variant>/generated/baselineProfiles and
 * is committed, so ordinary release builds just consume it. CI does not run
 * this — it has no device.
 */
android {
    namespace = "nu.bacher.memos.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 34
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
}

kotlin {
    jvmToolchain(25)
}

baselineProfile {
    // One variant's profile is enough; the app has no product flavors.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
