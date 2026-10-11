import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Baseline/Startup Profile generator (M1.8). Instrumentation-only module: it drives the app (module
// :app, variant nonMinifiedRelease) through the journeys and writes the profile into :app.
// Run it with `tools/gw.sh :app:generateBaselineProfile` (recipe: docs/perf-budget.md §How to run).
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "app.bolbol.tv.baselineprofile"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    targetProjectPath = ":app"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").kotlin.srcDir("src/journeys/kotlin") // shared with :benchmark
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

baselineProfile {
    // No Gradle Managed Device: AGP 9.4.1 cannot create TV images (its systemImageSource accepts only
    // aosp, aosp-atd, google, google-atd). The profile is generated on the running TV_API34 emulator.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro)
}
