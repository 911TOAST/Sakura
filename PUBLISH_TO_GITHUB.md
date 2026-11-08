# Publishing this build to GitHub

This is a step-by-step guide for getting *this* tree (Sakura optimized suyu
fork, including the shader warmup work) onto GitHub without leaking build
output, signing keys, or personal data.

Git is **not installed on this machine right now** (`where git` fails), so step
0 is real work, not a formality.

---

## 0. Install Git

1. Download Git for Windows: <https://git-scm.com/download/win>
2. Install with the defaults (Git Credential Manager included).
3. Open a **new** PowerShell window and check:

```powershell
git --version
```

If it still is not found, add `C:\Program Files\Git\cmd` to `PATH`.

Set your identity once:

```powershell
git config --global user.name  "Your Name"
git config --global user.email "you@example.com"
```

---

## 1. Pre-flight: make sure no secrets are in the tree

The repository already has a `.gitignore` that covers the important cases
(`/secrets/`, `*.keystore`, `*.jks`, `keystore.pass`, `artifacts`, `build*/`,
`*.log`). Verify before the first push:

```powershell
cd C:\Users\Administrator\Documents\70020943\suyu-mtk

# What is tracked / untracked right now?
git status --short

# Confirm the keystore and password file are ignored:
git check-ignore -v ..\secrets\suyu-release.keystore
git check-ignore -v ..\secrets\keystore.pass

# Search the whole history for anything that looks like a key/password:
git log -p --all -- "*.keystore" "*.jks" "keystore.pass"
```

Rules:

- **Never commit** `secrets/`, the release keystore, the keystore password, or
  `secrets/keystore.pass`. If the keystore is already in history, do not just
  delete it in a new commit — rotate the key and rewrite history
  (`git filter-repo --path secrets --invert-paths`) before pushing.
- **Never commit** firmware, `prod.keys`/`title.keys`, ROMs, NSP/XCI/NCA files,
  or anything dumped from a console. `LEGAL_AND_COMPLIANCE.md` is the project
  policy on this; keep it in the repository.
- `artifacts/` (APKs) and `build/`, `.cxx/`, `vcpkg_installed/` are build output.
  They are ignored; keep it that way. Release APKs belong in GitHub **Releases**,
  not in the repository history.
- The empty files `main`, `master`, `part` at the repo root look accidental.
  Delete them:

```powershell
Remove-Item main, master, part
```

---

## 2. Check the current state of the repo

```powershell
git status
git log --oneline -5
git remote -v
```

- If `git log` already shows the upstream suyu history, good — you only need to
  add your performance work on a branch.
- If there is no commit yet, create the first one after the review below.
- If a remote already exists and points at upstream, keep it as `upstream` and
  push your work to your own repository (step 4).

Put your work on a branch so `main`/`master` stays clean:

```powershell
git switch -c perf/shader-warmup   # or: git checkout -b perf/shader-warmup
```

---

## 3. Review what you are about to publish

```powershell
git diff --stat
git diff
```

Files that carry the work in this tree:

- `src/video_core/renderer_vulkan/vk_pipeline_cache.{h,cpp}`
- `src/video_core/vulkan_common/vulkan_wrapper.{h,cpp}`
- `src/video_core/renderer_vulkan/vk_present_manager.{h,cpp}` (zero-copy)
- `src/video_core/renderer_vulkan/present/*` (zero-copy)
- `src/video_core/textures/decoders.cpp` (segment copies)
- `src/core/...` CPU pass files (NCE tick, priority queue, fiber, memory)
- `ZERO_COPY_OVERDRIVE.md`, `PERFORMANCE_REPORT.md`,
  `SHADER_WARMUP_AND_CACHE.md`
- `CMakeLists.txt`, `src/android/app/build.gradle.kts` (armv8.2 preset)

Do not commit local scratch files, screenshots (`screen1.png`, `screen_now.png`
in the parent folder), logs, or `.cxx` build trees.

Then commit:

```powershell
git add -A
git commit -m "perf: zero-copy present, CPU fast paths, shader warmup

- Vulkan present renders directly into the WSI swapchain image on Mali
- device-wide VkPipelineCache merged across titles at boot
- boot-time shader warmup accounting ([ShaderWarmup] log line)
- ARMv8.2 CPU codegen preset, NCE tick fast path, scheduler bitmask
- add performance and shader-cache documentation"
```

(Use a shorter message if you prefer. One commit is fine; several themed
commits are nicer to review.)

---

## 4. Create the GitHub repository

**Option A — web UI**

1. Go to <https://github.com/new>.
2. Name it something like `sakura-suyu-mtk`.
3. Choose **Private** while you check the contents, switch to public later.
4. Do **not** initialise with a README/`.gitignore` (you already have history).
5. Copy the repository URL.

**Option B — GitHub CLI** (if installed: `winget install GitHub.cli`)

```powershell
gh auth login
gh repo create sakura-suyu-mtk --private --source=. --remote=origin
```

---

## 5. Push

```powershell
# If you used the web UI:
git remote add origin https://github.com/<your-user>/sakura-suyu-mtk.git

# If a remote already exists and points at the old upstream, rename it first:
# git remote rename origin upstream

git push -u origin perf/shader-warmup
```

To make this branch the default branch, open the repository on GitHub →
**Settings → Branches** (or merge the branch into `main` first):

```powershell
git switch main
git merge --ff-only perf/shader-warmup
git push -u origin main
```

---

## 6. Licensing and attribution (required, not optional)

This tree is GPL-3.0-or-later, derived from suyu/yuzu. Publishing a fork means:

- keep `LICENSE.txt` and every `SPDX-FileCopyrightText` / `SPDX-License-Identifier`
  header exactly as they are;
- state clearly in the README that this is a **modified fork** of suyu and link
  upstream, so nobody thinks the upstream project endorses it;
- publish the complete corresponding source for any APK you distribute
  (GPL-3.0 §5–6). A GitHub repository at the same commit you built from
  satisfies this;
- do not redistribute Nintendo ROMs, keys, or firmware, and do not bundle a
  copyrighted BIOS/firmware with the APK.

A short README section is enough:

```markdown
## This fork

Sakura optimized build of suyu (GPL-3.0-or-later), based on upstream suyu.
Changes: direct-to-swapchain presentation, CPU fast paths, shader warmup and a
device-wide Vulkan pipeline cache. See ZERO_COPY_OVERDRIVE.md,
PERFORMANCE_REPORT.md and SHADER_WARMUP_AND_CACHE.md.
```

Be aware that emulator projects have received DMCA takedowns in the past.
Publishing source and signed APKs is a legal decision you make as the
repository owner; nothing here removes that risk.

---

## 7. Releases (optional but useful)

Tag the build you verified and attach the APK to a GitHub Release:

```powershell
git tag -a v0.1-shader-warmup -m "Shader warmup + device-wide pipeline cache"
git push origin v0.1-shader-warmup
```

Then **Releases → Draft a new release → choose the tag**, and attach:

- `Sakura-optimized-*.apk`
- a `SHA256SUMS.txt` (`Get-FileHash <apk> -Algorithm SHA256`)
- the matching `SHADER_WARMUP_AND_CACHE.md` / `PERFORMANCE_REPORT.md` section

Never attach the keystore or the keystore password. If someone needs to verify
a build, a SHA-256 and the signing certificate fingerprint are enough.

---

## 8. Optional: CI build

A full Android build needs the NDK, CMake, Ninja, submodules and roughly the
same 30–60 minutes the local build takes. A minimal workflow is:

```yaml
# .github/workflows/android.yml
name: Android
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          submodules: recursive
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - name: Build
        working-directory: src/android
        run: ./gradlew :app:assembleOptimizedRelease --no-daemon
      - uses: actions/upload-artifact@v4
        with:
          name: apk
          path: src/android/app/build/outputs/apk/**/*.apk
```

Use **repository secrets** for the keystore (base64-encoded) and password; never
commit them. Expect to iterate on NDK/SDK versions the first time.

---

## 9. Day-to-day

```powershell
git switch perf/shader-warmup
# edit, build, test
git add -A
git commit -m "perf: ..."
git push
```

To pull upstream changes later:

```powershell
git remote add upstream https://github.com/suyu-emu/suyu.git   # once
git fetch upstream
git rebase upstream/master        # or upstream/main, whatever upstream uses
```

Rebasing will conflict in the files listed in step 3; resolve them by hand and
re-run the local Ninja build before pushing.

---

## Quick checklist

- [ ] Git installed, identity configured
- [ ] `secrets/`, keystore, password ignored and never committed
- [ ] no firmware, keys, ROMs or `LEGAL_AND_COMPLIANCE.md` violations in history
- [ ] `artifacts/`, build trees, screenshots, logs not staged
- [ ] branch created, work committed with a clear message
- [ ] remote points at **your** repository
- [ ] README says "modified fork", upstream linked, firmware section present
- [ ] GPL-3.0 headers untouched
- [ ] release notes state that no firmware/keys/ROMs are included
- [ ] any self-hosted background catalog uses https and only images you may distribute
- [ ] release APK attached with SHA-256, keystore not attached
