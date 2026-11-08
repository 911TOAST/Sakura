# Legal and compliance notes

Sakura is a modified fork of the suyu emulator, licensed under
GPL-3.0-or-later. This page describes what the project ships, what it does not,
and what users are expected to provide. It is a project policy, not legal
advice; if you plan to distribute builds commercially or are unsure about your
local law, talk to a lawyer.

## What this project ships

- Emulator source code (GPL-3.0-or-later).
- Optionally, a compiled APK of that source, published on the Releases page.

## What this project does NOT ship

- No Nintendo Switch firmware.
- No `prod.keys`, `title.keys`, or any other encryption keys.
- No game ROMs, NSP/XCI/NCA files, updates, or DLC.
- No Nintendo code, fonts, logos, or other copyrighted assets.
- No key generators, key databases, or tools for extracting keys from a console.

If you find any of the above inside a build of this project, it does not belong
there; open an issue.

## Firmware and keys

Encrypted retail software is encrypted with console keys. The emulator must be
given those keys (and, for titles that use system applets or services, a
firmware dump) to run it. That is a property of the content, not a missing
feature, and this project does not and will not include a way around it.

Users are expected to:

- dump firmware and keys from a console they own,
- install them through the app's own "Install keys / Install firmware" screens,
- only run software they are legally entitled to use.

The emulator does boot without firmware where the title does not need it: the
`fake_system_applets` setting (default on) serves missing library applets
through the HLE frontend instead of failing the launch. Titles that are known to
require firmware are marked in the game list, and launching encrypted content
without keys produces an explicit "missing keys" error.

## Anti-circumvention

This project implements documented console behaviour and HLE services. It does
not implement, and contributions adding any of the following will be rejected:

- generating, deriving, or guessing console keys,
- bypassing signature or ticket checks for content the user does not own,
- decrypting content without the user-supplied keys,
- patching out licence or entitlement checks in retail software.

## Trademarks

Sakura/suyu are not affiliated with, endorsed by, or sponsored by Nintendo.
"Nintendo Switch" is used only to describe what the software is compatible
with. Do not use Nintendo logos, icons, or naming in a way that suggests
endorsement. If you fork this project, use your own name and icon.

## GPL-3.0-or-later

- Every source file keeps its `SPDX-FileCopyrightText` and
  `SPDX-License-Identifier` headers.
- If you distribute a binary (including an APK), you must make the complete
  corresponding source available under the same licence. Publishing the exact
  commit the binary was built from on GitHub satisfies this.
- Mark modified files/behaviour in your fork's README; do not present a fork as
  the upstream project.

## Privacy

The emulator does not include a telemetry or analytics SDK. Game data, saves,
firmware and keys stay on the device; logs are written locally and only leave
the device if the user exports them.

The optional online library-background gallery is the one feature that makes
network requests outside of game content: it fetches a catalog and the picture
the user explicitly picks. The chosen picture is stored AES-GCM encrypted with a
key held in the Android Keystore, and only ciphertext is written to disk. The
catalog host can see thumbnail and catalog requests; the picture is downloaded
once. Nothing about the user's library, saves or gameplay is sent. See
[THEME_BACKGROUNDS.md](THEME_BACKGROUNDS.md).

## Reporting a compliance problem

If you believe a build or a contribution violates this policy or applicable
law, contact the repository owner. Takedown requests from rights holders should
go to the repository owner, not to contributors.
