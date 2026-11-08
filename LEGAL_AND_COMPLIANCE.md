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

## Takedown requests and counter-notification

This project responds to valid notices and pushes back on invalid ones. The
process below is the one in 17 U.S.C. §512, and it applies to any host that
wants safe-harbour protection.

**What the repository contains.** Source code and documentation, all under
GPL-3.0-or-later, plus build configuration. It contains no Nintendo firmware,
no encryption keys, no game content, and no Nintendo code or assets. That is
verifiable from the public git history: every commit is timestamped and every
change is visible.

**What a valid notice must contain** (§512(c)(3)):

- identification of the copyrighted work claimed to be infringed;
- identification of the material claimed to be infringing, with enough
  information to locate it (a URL or file path — not "the whole repository");
- the complainant's contact details;
- a statement of good-faith belief that the use is not authorised;
- a statement, under penalty of perjury, that the information is accurate and
  that the complainant is authorised to act for the rights holder;
- the complainant's physical or electronic signature.

**Counter-notification** (§512(g)(3)). If material is removed and the
repository owner believes it was lawful, the owner may send a
counter-notification to the host's designated agent containing:

- identification of the removed material and where it was before removal;
- a statement under penalty of perjury of a good-faith belief that the material
  was removed by mistake or misidentification;
- name, address, and a phone number, plus consent to the jurisdiction of the
  appropriate federal district court (or, for users outside the US, any
  judicial district in which the host may be found);
- consent to accept service of process from the complainant.

**What happens next.** The host forwards the counter-notification to the
complainant and, unless the complainant notifies the host that it has filed a
court action, may restore the material after **10 to 14 business days**. The
complainant then has to decide whether to litigate.

**Misrepresentation** (§512(f)). A party that knowingly materially misrepresents
that material is infringing, or that material was removed by mistake, may be
liable for damages. This is what makes the counter-notification process more
than paperwork.

**Limits.** A counter-notification does not stop a lawsuit, does not create
immunity, and is not a substitute for counsel. It is a procedural right, and
this section is a description of it, not legal advice.

Takedown requests should go to the repository owner rather than to individual
contributors.

