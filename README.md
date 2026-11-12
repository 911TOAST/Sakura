<div align="center">

<details>
<summary>
<img src="docs/assets/sakura-banner.png" alt="Sakura, a suyu-based Android emulator" width="760">
<sub>click the logo to see it move</sub>
</summary>

<img src="docs/assets/sakura-logo-loop.webp" alt="The Sakura logo animating: a glowing pink blossom over the wordmark, on a dark rounded card" width="760">

</details>

**a suyu fork built for Mali GPUs on MediaTek phones** (´｡• ᵕ •｡`)

[![license](https://img.shields.io/badge/license-GPL--3.0--or--later-f2a4b8?style=flat-square)](LICENSE.txt)
[![based on suyu](https://img.shields.io/badge/based%20on-suyu-f2a4b8?style=flat-square)](https://github.com/suyu-emu/suyu-v0.0.4)
[![platform](https://img.shields.io/badge/platform-Android%20only-f2a4b8?style=flat-square)](#build-it)
[![CI](https://img.shields.io/badge/builds-GitHub%20Actions-f2a4b8?style=flat-square)](../../actions)

[status](#status-experimental) ·
[what you get](#what-you-get) ·
[optimization](#the-optimization-work) ·
[build](#build-it) ·
[legal](#legal) ·
[credits](#credits)

</div>

---

Sakura is an Android fork of [suyu](https://github.com/suyu-emu/suyu-v0.0.4), which
continues yuzu. It aims at the GPUs that usually get skipped: Mali chips in
MediaTek phones. The interface is custom too, so opening the app doesn't feel
like reading a config file.

There are no games, firmware, or keys in here, and Nintendo has nothing to do
with it. The details live in [legal](#legal).

## status: experimental

Sakura is experimental and will stay that way. It crashes. Some games never get
past the loading screen, and a title that worked yesterday can break today
because a driver changed. If you want an emulator that behaves every single
time, this one will annoy you.

Performance is the part I'd defend. It's pretty great when a game runs, and the
notes below explain where that comes from.

## what you get

The library has grid and list views, adjustable accent colours, Sakura boot
animations, and a settings search that finds the setting you typed.

Wallpapers can come from your phone or from an online gallery. Downloaded ones
are stored AES-GCM encrypted with a key held in the Android Keystore, so only
ciphertext ends up on disk. See [THEME_BACKGROUNDS.md](THEME_BACKGROUNDS.md).

The performance dashboard shows frame stats, a tweaks sheet, and Mali memory
limits you can change without editing config files.

Discord Rich Presence is optional, off by default, and reports the game only.

`fake_system_applets` is on by default, so games that don't need firmware boot
without it. Games that do need it are marked in the list, and missing keys give
you a clear error instead of a mystery crash.

It runs on Android and nothing else. No desktop build to maintain.

## the optimization work

Each change below has a document behind it, including the parts that weren't
measured.

### presentation without the extra copy

On Mali with the Malikan/native-GPU path, the composition pass writes into the
image Android's Vulkan WSI gives back, then presents that same image. The
intermediate image, its framebuffer, the copy or blit, the barriers around them,
and the present thread are gone from the source. Other drivers keep the old path
untouched.

[ZERO_COPY_OVERDRIVE.md](ZERO_COPY_OVERDRIVE.md)

### one pipeline cache for the device

The Vulkan driver cache used to be per title, so a pipeline compiled in one game
was compiled again in the next. It's device-wide now
(`vulkan_pipelines_global_<salt>.bin`), merged with the per-title cache at boot
through `vkMergePipelineCaches`, salted per driver and capped at 128 MiB.

### shaders warm up on the loading screen

Cached pipelines compile while the game is still loading, before the frame loop
starts. The log line says what happened:

```
[ShaderWarmup] N pipelines ready in T ms: X from disk, Y compiled, Z failed
```

Get `Y` to zero for a title and its first-use stutter is gone.

### the small stuff

- Block-linear texture copies are split into cache-friendly segments.
- The ARMv8.2-a preset (`+dotprod+fp16+lse`) lets the CPU backend use the
  instructions the phone has.
- Duplicate pipeline keys are skipped before they reach a worker, so nothing
  builds twice in one boot.

[PERFORMANCE_REPORT.md](PERFORMANCE_REPORT.md) and
[SHADER_WARMUP_AND_CACHE.md](SHADER_WARMUP_AND_CACHE.md)

## build it

### in the cloud (what I do)

Open Actions, choose Android CI, then Run workflow and pick a flavor. The runner
builds it and leaves an APK artifact for you. The fans stay quiet and the laptop
lives (｡•̀ᴗ-)✧

My ZBook 15 G3 would not survive a four-flavor ninja build 💀 so the compiling
happens on GitHub's machines. The laptop only touches Kotlin and XML.

```
assembleMainlineRelease
```

All four flavors, which take roughly 40 to 90 minutes:

```
assembleMainlineRelease assembleLegacyRelease assembleChromeOSRelease assembleGenshinSpoofRelease
```

Private repos get 2,000 Actions minutes a month, so iterate on Mainline and save
the full set for releases.

### locally

Android Studio with the SDK and NDK versions pinned in
`src/android/app/build.gradle.kts`, plus a host `glslangValidator`. Set
`GLSLANGVALIDATOR` to its full path if it isn't on `PATH`.

```powershell
cd src/android
.\gradlew.bat assembleMainlineRelease
```

A signed release needs `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASS`, and
`ANDROID_KEY_ALIAS`. There is no desktop target to configure.

## legal

Emulators are legal in the US. *Sony Computer Entertainment v. Connectix* and
*Sony v. Bleem*, both 9th Circuit 2000, held that reverse-engineering a console
to build an emulator is fair use, and that an emulator isn't a derivative work of
the console firmware. Sakura contains no Nintendo code, assets, or firmware. It
reimplements documented console behaviour.

This repo ships source under GPL-3.0-or-later and, sometimes, APKs built from
that exact source. It never ships firmware, `prod.keys` or `title.keys`, ROMs,
updates, DLC, key generators, key databases, or anything that bypasses signature
and entitlement checks. Pull requests adding those get closed.

You supply keys and firmware dumped from hardware you own, installed through the
app's own screens, and you run only software you're allowed to run.

GPL-3.0-or-later means that if you distribute a build, you publish the
corresponding source at the commit you built from. That's how this repo does it,
and how a fork should.

On takedowns: every commit here is public and timestamped. A valid notice under
17 U.S.C. §512(c)(3) has to identify the copyrighted work, point at the specific
material, and state a good-faith belief under signature. "This whole repo,
because emulator" does not meet that. If lawful material gets removed, the owner
can file a counter-notification under §512(g)(3), and the material can return
after 10 to 14 business days unless the sender files suit. Knowingly false
notices expose the sender to liability under §512(f). If a notice is valid, the
material comes down.

The full policy is in [LEGAL_AND_COMPLIANCE.md](LEGAL_AND_COMPLIANCE.md). This is
project policy, not legal advice. If you distribute commercially, ask a lawyer.

## credits

[suyu](https://github.com/suyu-emu/suyu-v0.0.4), yuzu, Citra, and the libraries
under `externals/`: SDL, Vulkan-Headers, dynarmic, FFmpeg, mbedTLS and the rest.
Thanks also to everyone who reported a crash with a log attached
(´｡• ᵕ •｡`)

The Sakura name, icon, and UI art are original to this fork.

Also worth a look: [NE0TR0N!!](https://www.roblox.com/games/78128522742858/NE0TR0N),
a small game made by one dev ~

## license

GPL-3.0-or-later. See [LICENSE.txt](LICENSE.txt). Keep the SPDX headers, keep the
source available, and don't pass your fork off as this project.
