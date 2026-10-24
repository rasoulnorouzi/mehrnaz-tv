# Screenshot tests on the JVM (no emulator)

Added 2026-10-24 for cloud sessions, which have no emulator (no KVM) and no TV. Robolectric renders the
real Compose UI with native graphics; Roborazzi saves a PNG. It catches layout, RTL, text shaping, focus
rings and spacing problems. It does **not** measure performance, playback or real remote behaviour: those
still need the emulator or the TV (`tools/tv.sh`), and a screen is only "verified on device" after that.

## Size and density
`src/test/resources/robolectric.properties` in each module:
```
sdk=34
qualifiers=w960dp-h540dp-land-xhdpi-television
```
960 × 540 dp at xhdpi = a 1920 × 1080 PNG, the Mi TV P1's UI size.

## Add to a module
`build.gradle.kts`:
```kotlin
plugins {
    // ...
    alias(libs.plugins.roborazzi)
}
android {
    // ...
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
```
Test (see `core/designsystem/src/test/.../DesignSystemScreenshotTest.kt`):
```kotlin
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MyScreenScreenshotTest {
    @Test
    fun loaded() = captureRoboImage("build/outputs/roborazzi/myscreen_loaded.png") {
        BolbolTheme { MyScreen(state = fakeLoadedState, onClick = {}) }   // stateless content + fake data
    }
}
```
- Render the **stateless** screen content with fake `UiState` (no ViewModel, no network). Images: pass
  `null` URLs (cards draw their placeholder).
- Focus: to show a focused state, request focus in a `LaunchedEffect` with a `FocusRequester`, or use a
  `createComposeRule()` test and `onNodeWithText(...).requestFocus()` then
  `onRoot().captureRoboImage(path)`. D-pad: `onRoot().performKeyInput { pressKey(Key.DirectionRight) }`.

## Run and look
```bash
tools/gw.sh :feature:xyz:recordRoborazziDebug     # writes feature/xyz/build/outputs/roborazzi/*.png
```
Open every PNG (the Read tool shows images) and check RTL order, Persian shaping, digits, clipping and the
focus ring before calling the screen done. Copy the ones worth keeping to `shots/<date>/` for the report.
`testDebugUnitTest` also runs these tests (without recording), so keep them fast and deterministic.
