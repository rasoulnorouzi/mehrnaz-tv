import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.bolbol.tv.core.designsystem"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.tv.material)
    // Images: Coil is part of the public API (bolbolImageLoader returns a coil3.ImageLoader).
    api(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // Domain types for the convenience overloads (PosterCard(TitleSummary), WideCard(ContinueWatchingItem)).
    api(project(":core:model"))
    implementation(libs.androidx.core.ktx) // ResourcesCompat.getFont for vazirmatnTypeface()
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    // Debug-only component gallery (src/debug).
    debugImplementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
}
