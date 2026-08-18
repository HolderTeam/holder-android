# holder-android
Holder Android App

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
