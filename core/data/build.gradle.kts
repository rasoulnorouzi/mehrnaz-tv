import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.bolbol.tv.core.data"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    api(project(":core:model"))
    // DataGraph.okHttpClient is public API (shared with Coil and Media3), so OkHttp must be visible to :app.
    api(libs.okhttp)
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
