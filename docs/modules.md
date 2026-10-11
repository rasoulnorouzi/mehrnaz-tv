# Modules: how to add one

There are no convention plugins yet (deliberately simple; AGP 9 has built-in Kotlin). Every module
uses the same short build file, and versions come from `gradle/libs.versions.toml`.

## Current modules
| Module | Namespace | Purpose |
|---|---|---|
| `:app` | `app.bolbol.tv` | Activity, navigation, DI wiring. Release = R8 + debug-signed (private sideload) |
| `:core:designsystem` | `app.bolbol.tv.core.designsystem` | Theme (forces RTL), colours, type, spacing, components, `toPersianDigits()` |
| `:core:model` | `app.bolbol.tv.core.model` | **The contract**: domain types + repository interfaces. Change only via the manager |
| `:core:network` | `app.bolbol.tv.core.network` | Retrofit, DTOs, lenient JSON, mirror failover, mappers (internal) |
| `:core:database` | `app.bolbol.tv.core.database` | Room: progress, title snapshots, prefs, home cache (internal) |
| `:core:data` | `app.bolbol.tv.core.data` | Implements `CatalogRepository`, `WatchRepository`, `PrefsRepository` |
| `:core:player` | `app.bolbol.tv.core.player` | ExoPlayer factory, LoadControl, progress tracking, playback metrics |
| `:feature:home` | `app.bolbol.tv.feature.home` | Home screen |
| `:feature:details` | `app.bolbol.tv.feature.details` | Movie/series details |
| `:feature:playback` | `app.bolbol.tv.feature.playback` | Player screen + overlay |
| `:feature:search` | `app.bolbol.tv.feature.search` | Search: Persian/English TV keyboard, voice, results |
| `:feature:browse` | `app.bolbol.tv.feature.browse` | Category / "view all" grids with paging (Movies, Series tabs) |
| `:feature:settings` | `app.bolbol.tv.feature.settings` | Settings: subtitles (live preview), playback defaults, mirrors, performance overlay, about |
| `:feature:tvhome` | `app.bolbol.tv.feature.tvhome` | Publishes Continue Watching / next episode to the Android TV home (Watch Next) |
| `:core:lanremote` | `app.bolbol.tv.core.lanremote` | Phone/QR remote: tiny LAN HTTP server + QR code composable |

Planned: `:feature:account`, `:baselineprofile`, `:benchmark`.

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
