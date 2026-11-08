<div align="center">

<img src="docs/assets/sakura-banner.png" alt="Sakura — a suyu-based emulator" width="760">

**a suyu fork that actually cares how Mali GPUs feel** (´｡• ᵕ •｡`)

[![license](https://img.shields.io/badge/license-GPL--3.0--or--later-f2a4b8?style=flat-square)](LICENSE.txt)
[![based on suyu](https://img.shields.io/badge/based%20on-suyu-f2a4b8?style=flat-square)](https://github.com/suyu-emu/suyu-v0.0.4)
[![platforms](https://img.shields.io/badge/platforms-Android%20%C2%B7%20Windows%20%C2%B7%20Linux%20%C2%B7%20macOS-f2a4b8?style=flat-square)](#build-it)
[![CI](https://img.shields.io/badge/builds-GitHub%20Actions-f2a4b8?style=flat-square)](../../actions)

[features](#features) ·
[optimization](#the-genius-of-optimization) ·
[build](#build-it) ·
[legal](#the-legal-bit) ·
[credits](#credits)

</div>

---

Sakura is a fork of [suyu](https://github.com/suyu-emu/suyu-v0.0.4) — itself a
continuation of yuzu — built around the GPUs that usually get ignored: **Mali on
MediaTek phones**. It also has a UI that doesn't look like a spreadsheet, because
you stare at this thing for hours ~

It ships no games, no firmware, and no keys, and it isn't affiliated with
Nintendo. That's the whole disclaimer; the details are in [the legal bit](#the-legal-bit).

## features

**A library that looks like yours.** Grid or list, custom accent colours, Sakura
boot animations, and a settings tree that's actually searchable.

**Wallpapers that stay private.** Pick a background from your device or from an
online gallery. The downloaded image is stored **AES-GCM encrypted** with a key
held in the Android Keystore — only ciphertext touches disk. See
[THEME_BACKGROUNDS.md](THEME_BACKGROUNDS.md).

**A perf dashboard, not a guess.** Frame stats, a tweaks sheet, and adjustable
Mali memory limits without hunting through config files.

**Discord Rich Presence.** Optional, off by default, and it shows what you're
playing — not what you're doing.

**Firmware optional where it can be.** `fake_system_applets` (on by default)
serves missing library applets through the HLE frontend, so titles that don't
need firmware just boot. Titles that do need it are flagged, and launching
encrypted content without keys gives a clear error instead of a mystery crash.

**Runs where you are.** Android is the focus; Windows, Linux, and macOS builds
come out of the same tree.

## the genius of optimization

This is the part that isn't a settings pack. Each change is documented, and each
doc says what was measured and what wasn't — no "2x FPS!!" stickers ( >_< )

### the copy that isn't there anymore

On Mali with the Malikan/native-GPU path, the composition pass now renders
**directly into the image acquired from Android's Vulkan WSI** and hands that same
image to the compositor. The intermediate full-screen image, its framebuffer, the
`vkCmdCopyImage`/`vkCmdBlitImage` call, both surrounding barriers, and the
dedicated present thread are gone — deleted from the source, not made faster.
Every other driver keeps the old path untouched.

→ [ZERO_COPY_OVERDRIVE.md](ZERO_COPY_OVERDRIVE.md)

### one pipeline cache for the whole device

The Vulkan driver cache used to be per title, so a pipeline compiled in one game
was recompiled in the next. It's now device-wide
(`vulkan_pipelines_global_<salt>.bin`), merged with the per-title cache at boot
via `vkMergePipelineCaches`, salted per driver so blobs never leak between
drivers, and capped at 128 MiB.

### shaders warm up before you do

Cached pipelines are compiled while the game is still on the loading screen
instead of mid-frame, and the log tells you exactly what happened:

```
[ShaderWarmup] N pipelines ready in T ms: X from disk, Y compiled, Z failed
```

Drive `Y` to zero for a title and its stutters are gone.

### the small stuff that adds up

- Block-linear texture copies are split into cache-friendly segments instead of
  one giant strided copy.
- The ARMv8.2-a preset (`+dotprod+fp16+lse`) lets the CPU backend use the
  instructions the phone actually has.
- Duplicate pipeline keys are skipped before they reach a worker, so nothing is
  built twice in one boot.

→ [PERFORMANCE_REPORT.md](PERFORMANCE_REPORT.md) ·
[SHADER_WARMUP_AND_CACHE.md](SHADER_WARMUP_AND_CACHE.md)

## build it

### the lazy way (no compiler on your laptop)

Push, then **Actions → Android CI → Run workflow** and pick a flavor. The runner
builds it and hands you an APK artifact. Your fans stay quiet, and your laptop
gets to live (｡•̀ᴗ-)✧

```
assembleMainlineRelease
```

All four flavors (slower, ~40–90 min):

```
assembleMainlineRelease assembleLegacyRelease assembleChromeOSRelease assembleGenshinSpoofRelease
```

Private repos get 2,000 free Actions minutes a month, so iterate on Mainline and
save the full set for releases.

### the local way

Android Studio with the SDK/NDK versions pinned in
`src/android/app/build.gradle.kts`, plus a host `glslangValidator` (set
`GLSLANGVALIDATOR` to its full path if it isn't on `PATH`):

```powershell
cd src/android
.\gradlew.bat assembleMainlineRelease
```

A signed release needs `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASS`, and
`ANDROID_KEY_ALIAS`. Desktop builds go through CMake like upstream — see
[docs/Build.md](docs/Build.md).

## the legal bit

**Emulators are legal, ROMs you didn't dump are not.** That's the short version;
here's the rest.

*Sony Computer Entertainment v. Connectix* and *Sony v. Bleem* (both 9th Cir.
2000) held that reverse-engineering a console to build an emulator is fair use,
and that an emulator isn't a derivative work of the console firmware. Sakura
contains no Nintendo code, assets, or firmware — it reimplements documented
console behaviour.

**This repo ships:** source under GPL-3.0-or-later, and optionally APKs built
from that exact source.

**It never ships:** firmware, `prod.keys`/`title.keys`, ROMs (NSP/XCI/NCA),
updates, DLC, key generators, key databases, or anything that bypasses signature
or entitlement checks. Contributions adding any of that get rejected.

**You provide:** keys and firmware dumped from hardware you own, installed
through the app's own screens, and only software you're legally allowed to run.

**GPL-3.0-or-later:** if you distribute a build, publish the corresponding source
at the commit you built from. That's how this repo satisfies it, and how any fork
of it should too.

**Takedowns.** Everything here is public record — every commit is timestamped and
every change visible. A valid notice under 17 U.S.C. §512(c)(3) has to identify
the work, identify the specific material, and state a good-faith belief with a
signature; "this whole repo, because it's an emulator" isn't that. If lawful
material is removed, the owner can file a counter-notification under §512(g)(3),
and the material can go back after 10–14 business days unless the sender files
suit. Knowingly false notices expose the sender to liability under §512(f).
None of that is a magic shield — if a notice is valid, the material comes down.
Requests go to the repository owner, not contributors.

Full policy: [LEGAL_AND_COMPLIANCE.md](LEGAL_AND_COMPLIANCE.md). This is project
policy, not legal advice; if you're distributing commercially, ask a lawyer.

## credits

Standing on a lot of shoulders, and not shy about it: [suyu](https://github.com/suyu-emu/suyu-v0.0.4),
yuzu, Citra, and every library in `externals/` — SDL, Vulkan-Headers, dynarmic,
FFmpeg, mbedTLS, and friends. Also everyone who reported a crash with a log
instead of "it doesn't work" (´｡• ᵕ •｡`)

The Sakura name, icon, and UI art are original to this fork.

**Also try:** [NE0TR0N!!](https://www.roblox.com/games/78128522742858/NE0TR0N) —
a small game made by one dev. Different thing entirely, still neat ~

## license

**GPL-3.0-or-later.** See [LICENSE.txt](LICENSE.txt). Keep the SPDX headers, keep
the source available, and don't pretend your fork is this project.
