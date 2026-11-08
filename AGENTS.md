# AGENTS.md — working on Sakura

Instructions for any AI agent (or human) editing this repository. If you are an
AI assistant, read this before touching anything, then follow it literally.

## What this repo is

- **`911TOAST/sakura-suyu-mtk`** — Sakura, a fork of
  [suyu](https://github.com/suyu-emu/suyu-v0.0.4) (GPL-3.0-or-later).
- Android-first emulator with a Kotlin/Jetpack UI and a C++ core. Desktop builds
  exist too, but Android/Mali is the focus.
- `main` is the default branch and the only long-lived one. An `upstream` remote
  may point at the original suyu repo for syncing; never push there.

## Step 1 — get access (do this first, every session)

```bash
gh auth status
```

If that fails, log in through the browser device flow — never ask the user to
paste a token into the chat:

```bash
gh auth login -h github.com -p https -s repo,workflow
```

Then clone:

```bash
gh repo clone 911TOAST/sakura-suyu-mtk
cd sakura-suyu-mtk
git config user.name  "$(gh api user --jq .login)"
git config user.email "$(gh api user --jq .id)+$(gh api user --jq .login)@users.noreply.github.com"
```

If `gh` is unavailable, `git clone https://github.com/911TOAST/sakura-suyu-mtk.git`
works, but the user must supply credentials out-of-band (`GH_TOKEN` in the
environment or a credential manager). **Never** echo, log, commit, or hardcode a
token. If you cannot authenticate without seeing the secret, stop and say so.

## Step 2 — orient yourself

Read, in this order: `README.md`, `LEGAL_AND_COMPLIANCE.md`, then whichever of
these matches the task:

- `ZERO_COPY_OVERDRIVE.md` — Vulkan presentation path
- `PERFORMANCE_REPORT.md` — what changed, what was measured, what wasn't
- `SHADER_WARMUP_AND_CACHE.md` — pipeline/shader caches
- `THEME_BACKGROUNDS.md` — wallpaper engine and its crypto
- `docs/Build.md` — desktop and Android build notes

Useful directories:

| Path | Contents |
|---|---|
| `src/android/app/src/main/java/org/yuzu/yuzu_emu/` | Kotlin UI, settings, features |
| `src/android/app/src/main/res/` | layouts, drawables, animations, strings |
| `src/video_core/` | Vulkan renderer, shaders, textures |
| `src/core/` | emulation core |
| `externals/` | 27 git submodules |
| `docs/` | design notes and build guides |

## Step 3 — edit

- Submodules are **not** needed for docs, Kotlin, or resource edits. Only run
  `git submodule update --init --recursive --depth=1` if you actually need to
  compile C++ locally.
- Do not run a full local build. Compilation happens in GitHub Actions (see
  below); a local four-flavor build is slow and is exactly what this setup
  exists to avoid.
- Match the style of surrounding code. Kotlin files use the project's existing
  naming and formatting; C++ files keep their `SPDX-FileCopyrightText` and
  `SPDX-License-Identifier` headers **unchanged**.
- New source files need SPDX headers consistent with the project.
- If you add or change a user-visible setting, update `strings.xml` and the
  relevant settings fragment, not just the default value.

## Hard rules

- **Never commit:** game ROMs (NSP/XCI/NCA), updates, DLC, firmware,
  `prod.keys`, `title.keys`, any key file, keystores (`*.keystore`, `*.jks`,
  `keystore.pass`), `secrets/`, build output, `*.log`, or anything dumped from a
  console. `.gitignore` covers most of it — verify with `git status` anyway.
- **Never** add key generation, signature/ticket bypassing, decryption without
  user-supplied keys, or licence-check patching. See `LEGAL_AND_COMPLIANCE.md`.
- **Never** force-push, rewrite history, delete branches, or change repository
  visibility/topics/description without explicit approval from the user.
- **Never** invent benchmarks, FPS numbers, testimonials, or feature claims.
  If a change has no measurement, say so. Link the doc that backs any claim.
- Keep the tree GPL-compliant: the complete corresponding source for any
  distributed APK must be published at the commit it was built from.

## Step 4 — commit and push

```bash
git switch -c <short-kebab-branch>     # or stay on main for tiny doc fixes
git add -A
git diff --cached --check              # a pre-commit hook rejects trailing whitespace
git status --short                     # confirm only intended files
git commit -m "<area>: <imperative summary>" -m "- bullet
- bullet"
git push -u origin HEAD
```

Commit message style: `docs:`, `feat:`, `fix:`, `perf:`, `ci:` — lowercase area,
imperative summary, body bullets for anything non-obvious. Keep commits focused.

Open a pull request only when the user asks for one. Otherwise push to the
branch and report it.

## Step 5 — build without a laptop

The cloud build is the point of this repo. Two workflows:

- **`android-ci.yml`** — fast, manual or on push to `main` when `src/**` changes.
  Input `flavors` selects Gradle tasks (`assembleMainlineRelease` by default).
- **`release.yml`** — the full multi-platform release pipeline. Slow; only for
  actual releases.

```bash
gh workflow run android-ci.yml -R 911TOAST/sakura-suyu-mtk --ref main
gh run list -R 911TOAST/sakura-suyu-mtk --limit 3
gh run watch <run-id> -R 911TOAST/sakura-suyu-mtk
gh run download <run-id> -R 911TOAST/sakura-suyu-mtk -n android-apk-<run-number>
```

A cold build takes roughly 40–90 minutes; a Mainline-only build is at the lower
end. Private-repo Actions minutes are limited (2,000/month on the free plan), so
do not trigger builds for docs-only changes.

## Step 6 — report back

When you finish, tell the user:

1. the commit SHA and branch,
2. a short list of files/behaviour changed,
3. the Actions run URL (if you triggered one) and its final status,
4. anything you could not verify (be explicit — no guesses),
5. any risk or breaking change they should know about.

## If something is ambiguous

Ask. Do not guess at product decisions, and do not "helpfully" refactor
unrelated code. A small correct change beats a large speculative one.
