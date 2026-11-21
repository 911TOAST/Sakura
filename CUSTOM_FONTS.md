# Custom app fonts

Sakura can change the font used by its Android UI at runtime. Adding a font needs **no rebuild**.

## Where fonts come from

`Settings → Interface → App font` lists:

- **Google Sans (default)** — leaves the bundled type scale exactly as shipped, including the
  Sakura display accents on the library title and About screen.
- **Sakura Display** — the bundled display face applied to the whole UI.
- Any `.ttf`, `.otf` or `.ttc` file found in the custom font folder, sorted by file name.

The folder is picked once with `Settings → Interface → Custom font folder` using the Android
document picker. The app keeps the read permission, so later additions to that folder show up
in `App font` the next time the settings list is opened (rotate the screen or revisit the page
if it is already open).

If no folder has been chosen, the app uses its own external files directory:
`Android/data/<package>/files/fonts`. Create that folder and push fonts there with `adb` or a
file manager to use it without the picker.

Only ship fonts you are allowed to redistribute (for example SIL OFL). The project policy in
[LEGAL_AND_COMPLIANCE.md](LEGAL_AND_COMPLIANCE.md) forbids bundling copyrighted assets.

## What the setting does

- XML screens: every `TextView` in an Activity and in dialogs gets the chosen face. Bold and
  italic styling is preserved through synthetic variants of the file. Views that are explicitly
  monospace (log/system-info code blocks, the crash report) are left alone.
- Compose screens (dashboard, playtime stats, tweaks sheet): the Material 3 typography is rebuilt
  from the same face.
- Changing the font recreates the current Activity so the new face is applied immediately.

A popup menu is a separate system window and keeps the platform font. A font file that cannot be
read (bad format, revoked folder permission) is ignored and the app falls back to its default.
