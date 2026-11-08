# Sakura

Sakura is a community continuation of the Suyu emulator codebase, with Android and Mali-focused Vulkan work. It does not include games, firmware, or encryption keys.

## Library backgrounds

The Android library can use a picture from your device or from an online gallery of recommended
wallpapers. The online picture is downloaded once and stored **AES-GCM encrypted** with a key held
in the Android Keystore; only ciphertext is written to disk. See
[THEME_BACKGROUNDS.md](THEME_BACKGROUNDS.md) for the catalog JSON format, how to host your own
catalog, and what the encryption does and does not protect against.

## Firmware and keys

Retail Switch games are encrypted. To run them you must provide encryption keys
(`prod.keys`) and, for titles that use system applets or services, your own
firmware dump — dumped from a console you own. This project does not ship, link
to, or generate either one, and it does not bypass the console's encryption.

Firmware is optional for titles that do not need it: the `fake_system_applets`
setting (on by default) serves missing library applets through the HLE frontend.
Titles known to require firmware are flagged in the game list, and booting
encrypted content without keys shows an explicit "missing keys" error.

See [LEGAL_AND_COMPLIANCE.md](LEGAL_AND_COMPLIANCE.md) for the full policy.

## Android build

Install Android Studio with the Android SDK and NDK versions listed in `src/android/app/build.gradle.kts`. The native build also needs the host `glslangValidator` tool. Set `GLSLANGVALIDATOR` to its full path if it is not already on `PATH`.

```powershell
cd src/android
.\gradlew.bat assembleMainlineRelease
```

The release build needs a Sakura signing key before it can be distributed as an update. Set `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASS`, and `ANDROID_KEY_ALIAS` when building a signed release.

## Also try

[NE0TR0N!!](https://www.roblox.com/games/78128522742858/NE0TR0N) - a small game made by one dev.

## License

GPL-3.0-or-later. See [LICENSE.txt](LICENSE.txt).
