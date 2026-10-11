# Modules: how to add one

There are no convention plugins yet (deliberately simple; AGP 9 has built-in Kotlin). Every module
uses the same short build file, and versions come from `gradle/libs.versions.toml`.

## Current modules
| Module | Namespace | Purpose |
|---|---|---|
| `:app` | `app.bolbol.tv` | Activity, navigation, DI wiring. Release = R8 + debug-signed (private sideload) |
| `:core:designsystem` | `app.bolbol.tv.core.designsystem` | Theme (forces RTL), colours, type, spacing, `FocusCard`, `toPersianDigits()` |

Planned (create when the task starts, see `docs/plan.md`): `:core:model`, `:core:network`,
`:core:database`, `:core:player`, `:feature:home`, `:feature:details`, `:feature:playback`,
`:feature:search`, `:feature:account`, `:feature:tvhome`, `:baselineprofile`, `:benchmark`.

## Recipe: Android library module with Compose
1. Add `include(":feature:xyz")` to `settings.gradle.kts`.
2. Create `feature/xyz/build.gradle.kts`:
```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)      // omit for non-UI modules
}

android {
    namespace = "app.bolbol.tv.feature.xyz"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }           // omit for non-UI modules
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core:designsystem"))
}
```
3. Sources go in `src/main/kotlin/app/bolbol/tv/feature/xyz/`.
4. Rules: `feature/*` may depend only on `core/*`. Pure-Kotlin logic gets unit tests in `src/test/kotlin`.

## Build facts (learned 2026-10-11)
- AGP 9.4.1 + Gradle 9.8.1 + Kotlin 2.4.21. **compileSdk must be 37**: current AndroidX (core 1.19, lifecycle 2.11,
  Compose 1.12) refuses to compile against 36. targetSdk stays 36 and minSdk 29.
- No `org.jetbrains.kotlin.android` plugin: AGP 9 has Kotlin built in. Only the compose/serialization
  compiler plugins are applied.
- JDK 21 runs Gradle; bytecode targets Java 17 (no toolchain download needed).
- Release APK of the M0 skeleton: 1.6 MB.
