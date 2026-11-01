# Sakura

Sakura is a community continuation of the Suyu emulator codebase, with Android and Mali-focused Vulkan work. It does not include games, firmware, or encryption keys.

## Android build

Install Android Studio with the Android SDK and NDK versions listed in `src/android/app/build.gradle.kts`. The native build also needs the host `glslangValidator` tool. Set `GLSLANGVALIDATOR` to its full path if it is not already on `PATH`.

```powershell
cd src/android
.\gradlew.bat assembleMainlineRelease
```

The release build needs a Sakura signing key before it can be distributed as an update. Set `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASS`, and `ANDROID_KEY_ALIAS` when building a signed release.

## License

GPL-3.0-or-later. See [LICENSE.txt](LICENSE.txt).
