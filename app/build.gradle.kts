import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.bolbol.tv"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.bolbol.tv"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        release {
            // Optimised build used for every measurement (docs/perf-budget.md).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Private sideloaded app: sign release with the local debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
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
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:player"))
    implementation(project(":feature:home"))
    implementation(project(":feature:details"))
    implementation(project(":feature:playback"))
    implementation(project(":feature:browse"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:tvhome"))
    implementation(project(":feature:search"))
    implementation(project(":core:lanremote"))
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.profileinstaller)
    debugImplementation(libs.compose.ui.tooling)
}
