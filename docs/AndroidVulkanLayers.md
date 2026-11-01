# Android Vulkan layers

Sakura can enable Vulkan layers that Android's Vulkan loader reports for the app. The in-game Quick Tweaks panel lists those layers and saves the selected names for the next game launch.

Android builds bundle `VK_LAYER_KHRONOS_validation` by default through the `YUZU_DOWNLOAD_ANDROID_VVL` CMake option. The layer appears in Quick Tweaks when Android's loader reports it.

To add another app-owned layer, place its Android ARM64 library in:

```text
src/android/app/src/main/jniLibs/arm64-v8a/libVkLayer_<name>.so
```

Android discovers app-owned layers from the APK's native library directory. It does not use Vulkan JSON layer manifests on Android. Production apps also cannot load arbitrary layer libraries copied into app storage; injected layers are restricted by Android's Vulkan security model. The Vulkan loader and Android platform guides describe these constraints:

- https://android.googlesource.com/platform/frameworks/native/+/d317ecf/vulkan/doc/implementors_guide/implementors_guide.html
- https://source.android.com/static/docs/compatibility/9/android-9-cdd.pdf

Only package layers built for `arm64-v8a` and implementing Android's Vulkan layer interface. Layers unavailable from the active GPU driver or app package are omitted by the engine.
