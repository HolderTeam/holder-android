# holder-android
Holder Android App

## What is Holder?

Holder is a card management application designed to let users follow their own workflow rather than imposing one methodology. It can support Zettelkasten, wiki-style note systems, the Snowflake Method, personal knowledge management, research projects, family/shared projects through Git sync, and other card-based workflows.

For Android, the first goal is not desktop feature parity. The phone should make Holder useful wherever the user is: create, read, edit, search and sync cards, while keeping the core data model in libholder.

A persistent search bar can sit at the top of many primary views. Most other interactions can use full-screen phone views rather than trying to reproduce the desktop layout.


## Local Development

### Quickstart

The path from a fresh checkout to Holder running on your own devices:

1. **Clone with submodules** (or, if already cloned, fetch them now):

   ```bash
   git submodule update --init --recursive
   ```

2. **Install prerequisites**: Android Studio (gives you the SDK, an
   emulator, and Device Manager -- or install just the SDK command-line
   tools if you'd rather skip the IDE), and NDK `28.2.13676358`
   specifically (installable via Android Studio's SDK Manager -- the exact
   version this project's native build expects).

   On Ubuntu, [vcpkg](https://vcpkg.io) also needs autotools for some
   Android dependency ports:

   ```bash
   sudo apt install autoconf autoconf-archive automake libtool
   ```

   Then clone and bootstrap vcpkg itself, anywhere on your machine:

   ```bash
   git clone https://github.com/microsoft/vcpkg.git
   ./vcpkg/bootstrap-vcpkg.sh -disableMetrics
   ```

3. **Point the build at your SDK, vcpkg, and target ABIs** -- see
   [Configuring the SDK, vcpkg, and target ABIs](#configuring-the-sdk-vcpkg-and-target-abis)
   below for the two ways to do this.

4. **First build**: `./gradlew :app:assembleDebug`, or just let step 6 do
   it for you. This is where vcpkg fetches and compiles the native
   dependencies for whichever ABIs you configured, so it is slow (minutes,
   longer for two ABIs); every build after that is fast.

5. **Get a device to test on**: for a real phone, enable Developer Options
   and USB debugging, plug it in, and accept the RSA fingerprint prompt on
   the device itself (`adb devices` should report it as `device`, not
   `unauthorized`). For an emulator, create at least one AVD in Android
   Studio's Device Manager first -- neither script here creates one -- then:

   ```bash
   scripts/start-emulators.sh
   ```

6. **Deploy**:

   ```bash
   scripts/deploy-all.sh
   ```

### Configuring the SDK, vcpkg, and target ABIs

Two ways to tell the build where your SDK and vcpkg are, and which ABIs to
build native dependencies for:

- **Environment variables** (recommended -- portable across every project on
  your machine, and needs no per-checkout file):

  ```bash
  export ANDROID_HOME=~/Android/Sdk   # wherever Android Studio put it
  export VCPKG_ROOT=~/vcpkg
  ```

  and, once, in `~/.gradle/gradle.properties` (Gradle's own per-user, all-
  projects settings file -- never checked into any repo):

  ```properties
  holder.android.abis=arm64-v8a,x86_64
  ```

- **`local.properties`**: Android Studio already writes `sdk.dir` here for
  you on first open. Add `vcpkg.dir` and `holder.android.abis` yourself:

  ```properties
  vcpkg.dir=/path/to/vcpkg
  holder.android.abis=arm64-v8a,x86_64
  ```

  `local.properties` is gitignored and machine-specific by Android tooling
  convention (Android Studio rewrites `sdk.dir` in it on its own, so it
  should never be checked in) -- set values here only if you are not using
  the environment variables above; either an env var or a Gradle project
  property (`findProperty`, e.g. via `-Pholder.android.abis=...`) takes
  precedence over the matching `local.properties` value where both exist.

Set `holder.android.abis` to whichever ABI(s) you actually need: `x86_64`
for the emulators above, plus `arm64-v8a` for most real Android phones.
Building for both, as in the examples above, is the friendliest default if
you plan to test on real devices as well as emulators -- it is also what
the build already falls back to if `holder.android.abis` is left unset
entirely. Restricting it to a single ABI you're actively testing makes
native builds faster, at the cost of `scripts/deploy-all.sh` failing
(`INSTALL_FAILED_NO_MATCHING_ABIS`) against any device that ABI doesn't
cover.

### Deploying to every connected device

```bash
scripts/start-emulators.sh
scripts/deploy-all.sh
```

`scripts/deploy-all.sh` builds the debug APK once, then installs and
launches Holder on every connected phone and running emulator `adb` can see
-- a quick way to manually compatibility-test a change across several real
devices/API levels at once instead of repeating build+install+launch by hand
for each one. Devices that are offline or unauthorized are skipped and
reported, not treated as failures.

`scripts/start-emulators.sh` starts every configured Android Virtual Device
that isn't already running (skipping ones that are, never starting
duplicates). Since emulators are meant to stay running while you develop,
you normally only need this once per work session to bring your local
emulator set up; `scripts/deploy-all.sh` is the one you run repeatedly
afterward.
