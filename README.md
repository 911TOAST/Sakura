<div align="center">

<img src="docs/assets/sakura-banner.png" alt="Sakura — a suyu-based emulator" width="760">

**a suyu fork that actually cares how Mali GPUs feel** (´｡• ᵕ •｡`)

[![license](https://img.shields.io/badge/license-GPL--3.0--or--later-f2a4b8?style=flat-square)](LICENSE.txt)
[![based on suyu](https://img.shields.io/badge/based%20on-suyu-f2a4b8?style=flat-square)](https://github.com/suyu-emu/suyu-v0.0.4)
[![platforms](https://img.shields.io/badge/platforms-Android%20%C2%B7%20Windows%20%C2%B7%20Linux%20%C2%B7%20macOS-f2a4b8?style=flat-square)](#build-it)
[![CI](https://img.shields.io/badge/builds-GitHub%20Actions-f2a4b8?style=flat-square)](../../actions)

[what is this](#hiii--what-is-sakura) ·
[what's different](#so-whats-actually-different-_) ·
[build it](#build-it) ·
[legal stuff](#is-this-legal-) ·
[takedowns](#if-someone-files-a-takedown-_) ·
[credits](#credits)

</div>

---

## hiii ~ what is Sakura?

Sakura is a fork of [suyu](https://github.com/suyu-emu/suyu-v0.0.4) — itself a
continuation of yuzu — tuned for the GPUs that usually get ignored: **Mali on
MediaTek phones**. Not a settings pack, not a re-skin with a new icon. There is
real code here that changes how a frame reaches your screen, and a pile of
documentation that shows the receipts.

It also ships a UI that doesn't look like a spreadsheet, because you stare at
this thing for hours ~

Sakura is **not affiliated with Nintendo**, doesn't ship games, firmware, or
keys, and never will. More on that in [legal stuff](#is-this-legal-).

> **One honest thing up front:** this README doesn't have "2x FPS!!" stickers.
> Where a change has a measurable effect, the repo has a log line or a document
> for it. Where it doesn't, we say so. Weird, I know >_<

## so what's actually different? ( >_< )

The short list, with the caveat that matters:

| What | Where it applies | What actually changed |
|---|---|---|
| **Direct-to-swapchain present** | Mali + Malikan / native GPU path | The render pass writes into the image acquired from Android's Vulkan WSI and presents that same image. The full-screen copy, its barriers, and the separate present thread are gone — deleted from the source, not just made faster. Other drivers keep the old path untouched. |
| **Device-wide pipeline cache** | Vulkan | The driver's compiled pipeline cache is shared across titles (`vulkan_pipelines_global_<salt>.bin`), merged with the per-title cache at boot, capped at 128 MiB, salted per driver so blobs never leak between drivers. |
| **Boot-time shader warmup** | Vulkan | Cached pipelines are compiled while the game is still on the loading screen, and the log tells you exactly what happened: `[ShaderWarmup] N pipelines ready in T ms: X from disk, Y compiled, Z failed`. Drive `Y` to zero ~ |
| **Segmented block-linear copies** | Texture decoding | Large block-linear texture copies are split into cache-friendly segments instead of one giant strided copy. |
| **ARMv8.2 codegen preset** | Android arm64-v8a | Built with `armv8.2-a+dotprod+fp16+lse` so the CPU backend can use the instructions your phone actually has. |
| **Wallpaper engine** | Android UI | Library backgrounds from your device or an online gallery, stored **AES-GCM encrypted** with a key in the Android Keystore. Only ciphertext touches disk. Details in [THEME_BACKGROUNDS.md](THEME_BACKGROUNDS.md). |
| **Discord Rich Presence** | Android | Optional, off unless you turn it on. Shows what you're playing, not what you're doing. |
| **Perf dashboard + Mali controls** | Android UI | Frame stats, tweaks sheet, and adjustable Mali memory limits without digging through config files. |

Full write-ups, with the boring details that make them believable:

- [ZERO_COPY_OVERDRIVE.md](ZERO_COPY_OVERDRIVE.md) — the presentation rework
- [PERFORMANCE_REPORT.md](PERFORMANCE_REPORT.md) — dated notes, including what was *not* measured
- [SHADER_WARMUP_AND_CACHE.md](SHADER_WARMUP_AND_CACHE.md) — the cache layers, explained
- [THEME_BACKGROUNDS.md](THEME_BACKGROUNDS.md) — the wallpaper crypto, and its limits

## it still doesn't ship games (sorry ~)

Retail Switch games are encrypted. To run yours you need `prod.keys` and, for
some titles, a firmware dump **from a console you own**, installed through the
app's own screens. Sakura does not include, link to, or generate either, and it
does not bypass the console's encryption. Booting encrypted content without keys
gives you a clear "missing keys" error instead of a mystery crash (thank you,
past us).

Firmware is optional for titles that don't need it: `fake_system_applets` (on by
default) serves missing library applets through the HLE frontend. Titles known to
need firmware are flagged in the game list.

Read [LEGAL_AND_COMPLIANCE.md](LEGAL_AND_COMPLIANCE.md) for the whole policy.

## build it

### the lazy way (recommended, no compiler on your laptop)

Push, then **Actions → Android CI → Run workflow** and pick a flavor. The runner
builds it and hands you an APK artifact when it's done. Your fans stay quiet, and
your laptop gets to live (｡•̀ᴗ-)✧

Good for a quick loop:

```
assembleMainlineRelease
```

All four flavors (slower, ~40–90 min):

```
assembleMainlineRelease assembleLegacyRelease assembleChromeOSRelease assembleGenshinSpoofRelease
```

> Heads-up: private repos get 2,000 free Actions minutes/month. A full four-flavor
> run eats a chunk of that, so iterate on Mainline and save the big one for releases.

### the local way

Android Studio with the SDK/NDK versions pinned in
`src/android/app/build.gradle.kts`, plus a host `glslangValidator` (set
`GLSLANGVALIDATOR` to its full path if it isn't on `PATH`):

```powershell
cd src/android
.\gradlew.bat assembleMainlineRelease
```

A signed release needs `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASS`, and
`ANDROID_KEY_ALIAS` set.

Desktop builds go through CMake like upstream; see [docs/Build.md](docs/Build.md).

## is this legal? ( ? , )

Short version: **emulators are legal, ROMs you didn't dump are not.** Here's the
long version, because "trust me bro" isn't a legal theory.

**What the law actually says.** Emulators have been tested in US court more than
once. *Sony Computer Entertainment v. Connectix* (9th Cir. 2000) and *Sony v.
Bleem* (9th Cir. 2000) both held that reverse-engineering a console to build an
emulator is fair use, and that an emulator is not a derivative work of the
console's firmware. Sakura contains no Nintendo code, no Nintendo assets, and no
Nintendo firmware — it's an independent reimplementation of documented console
behaviour.

**What we ship:** source code under GPL-3.0-or-later, and optionally an APK built
from that exact source.

**What we do not ship, ever:** firmware, `prod.keys` / `title.keys`, ROMs
(NSP/XCI/NCA), updates, DLC, key generators, key databases, or anything designed
to bypass signature or entitlement checks. Contributions that add any of that
get rejected. If you ever find one of those things inside a build, that's a bug
and we want the issue.

**What you have to do:** dump keys and firmware from hardware you own, and only
run software you're legally allowed to run. That's on you, not on the emulator.

**GPL-3.0-or-later:** if you distribute a build, you have to publish the
corresponding source at the commit you built from. Publishing this repo is how we
satisfy that, and how any fork of it should too.

This is a project policy, not legal advice. If you're distributing builds
commercially or you're unsure about your local law, ask an actual lawyer.

## if someone files a takedown ( >_< )

Rights holders are allowed to send takedown notices. They are **not** allowed to
send knowingly false ones. Both of those are in the same statute, so this section
is short and boring on purpose:

1. **Everything here is public record.** Every commit is timestamped, every
   changed file is visible, and the diff history shows exactly what this project
   did and didn't add. There is no hidden payload to find.
2. **A valid notice has to be specific.** Under 17 U.S.C. §512(c)(3), a notice
   must identify the copyrighted work, identify the material claimed to be
   infringing with enough detail to locate it, and state that the sender has a
   good-faith belief the use isn't authorized. "This whole repo, because it's an
   emulator" is not that.
3. **A wrong notice can be answered.** If material is removed and it was lawful,
   the repository owner can file a counter-notification under §512(g)(3). Once
   that's in, the material can go back after 10–14 business days unless the
   sender files a court action — the ball goes back to them.
4. **Bad-faith notices have consequences.** Knowingly misrepresenting that
   material is infringing exposes the sender to liability under §512(f). That's
   not a threat, it's the reason the counter-notification process exists at all.

None of this is a magic shield — bad takedowns happen all the time, and big
companies have better lawyers than any hobby project. The real defense isn't
attitude, it's that the code is ours, the licence is GPL, the repo ships nothing
infringing, and the history proves it. If a notice is valid, we'll take the
material down. If it isn't, we'll use the process above (·•᷄ᴗ•᷅)

Takedown requests should go to the repository owner rather than individual
contributors. See [LEGAL_AND_COMPLIANCE.md](LEGAL_AND_COMPLIANCE.md).

## credits

Standing on a lot of shoulders, and not shy about it:

- [suyu](https://github.com/suyu-emu/suyu-v0.0.4) — the base this fork is built on
- yuzu, Citra, and the whole emulator lineage behind them
- every library in `externals/` — SDL, Vulkan-Headers, dynarmic, FFmpeg, mbedTLS, and friends
- everyone who reported a crash with a log instead of "it doesn't work"

The Sakura name, icon, and UI art are original to this fork.

## also try

[NE0TR0N!!](https://www.roblox.com/games/78128522742858/NE0TR0N) — a small game
made by one dev. Different thing entirely, still neat ~

## license

**GPL-3.0-or-later.** See [LICENSE.txt](LICENSE.txt). Keep the SPDX headers, keep
the source available, and don't pretend your fork is this project.
