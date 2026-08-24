Use the native Android testing stack. Do not duplicate libholder/C API tests in Kotlin.

1. **C/C++ tests**
   - Keep existing holder-core Catch2/C API coverage for business logic, Git, crypto, cards, projects, search, etc.

2. **Kotlin unit tests**
   - JUnit for ViewModels, state handling, validation and Kotlin↔native result mapping.
   - Prefer plain JVM tests where Android framework APIs are not required.

3. **Compose UI tests**
   - Use `androidx.compose.ui.test`.
   - Test screens and user behaviour through the Compose semantics tree.
   - Cover project list, card list, card view/edit, search, save/discard, navigation and wikilinks.
   - This is the preferred replacement for Behave-style frontend tests.

4. **Android/JNI integration tests**
   - Put in `androidTest`.
   - Verify Kotlin → JNI → libholder → Android filesystem/native dependencies → Kotlin.
   - Test representative round trips, UTF-8, errors, persistence, Git operations, Android Keystore integration and reconstruction of the disposable SQLite projection.
   - Do not rerun every C API test through JNI.

5. **Whole-app smoke tests**
   - Small number of critical flows, e.g.:
     - launch app
     - open Home project
     - create card
     - edit/save
     - reopen and verify persistence

   - Use Compose UI Test; use UI Automator only where interaction outside the Compose app is required.

6. **Device matrix**
   - Use Gradle Managed Devices in CI.
   - Minimum:
     - API 28, Holder's minimum Android version
     - one current Android API

   - Do not test every API level.

7. **Screenshot tests**
   - Add after core behavioural tests.
   - Capture major screens in light/dark mode and useful size/font configurations.
   - Intended to catch visual regressions such as theme flashes, broken layouts and contrast issues.

8. **Physical-device smoke testing**
   - Before releases, test at least:
     - old supported Android device
     - current Pixel
     - non-Google OEM device

9. **Later**
   - Robolectric only where simulated Android framework behaviour is genuinely useful.
   - Macrobenchmark/Baseline Profiles for startup, scrolling, search and large-project performance once functionality stabilises.

Target testing pyramid:

`C API tests >> Kotlin tests > Compose tests > JNI/integration tests > full-app E2E tests`
