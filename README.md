# holder-android
Holder Android App

## What is Holder?

Holder is a card management application designed to let users follow their own workflow rather than imposing one methodology. It can support Zettelkasten, wiki-style note systems, the Snowflake Method, personal knowledge management, research projects, family/shared projects through Git sync, and other card-based workflows.

For Android, the first goal is not desktop feature parity. The phone should make Holder useful wherever the user is: create, read, edit, search and sync cards, while keeping the core data model in libholder.

A persistent search bar can sit at the top of many primary views. Most other interactions can use full-screen phone views rather than trying to reproduce the desktop layout.


## Local Development

Check out submodules before opening the project in Android Studio:

```bash
git submodule update --init --recursive
```

Install or clone vcpkg somewhere on your machine, then add its path to
`local.properties`:

```properties
vcpkg.dir=/path/to/vcpkg
holder.android.abis=x86_64
```

Android Studio already writes `sdk.dir` to the same file. The project uses that
SDK path to find NDK `28.2.13676358` and set up vcpkg's Android toolchain.
Set `holder.android.abis` to your emulator or device ABI to avoid building
native dependencies for ABIs you are not currently testing.

On Ubuntu, vcpkg also needs autotools for some Android dependency ports:

```bash
sudo apt install autoconf autoconf-archive automake libtool
```

### Deploying to every connected device

```bash
./start-emulators
./deploy-all
```

`./deploy-all` builds the debug APK once, then installs and launches Holder
on every connected phone and running emulator `adb` can see -- a quick way to
manually compatibility-test a change across several real devices/API levels
at once instead of repeating build+install+launch by hand for each one.
Devices that are offline or unauthorized are skipped and reported, not
treated as failures.

`./start-emulators` starts every configured Android Virtual Device that
isn't already running (skipping ones that are, never starting duplicates).
Since emulators are meant to stay running while you develop, you normally
only need this once per work session to bring your local emulator set up;
`./deploy-all` is the one you run repeatedly afterward.
