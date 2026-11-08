# Library backgrounds: online gallery and encrypted storage

The Android library screen can draw a picture behind the game grid. Two sources are supported:

- **On device**: a picture picked with the system document picker (the original behaviour).
- **Online gallery**: a grid of recommended wallpapers fetched from a catalog.

The chosen online wallpaper is stored **encrypted at rest**; only ciphertext touches the disk.

## Where it lives in the UI

`Home → Settings → Library background → Browse online backgrounds…`

The gallery has category chips (`Recommended`, `Nature`, `City`, `Abstract` by default), a
thumbnail grid, and a `Catalog source…` button for pointing the app at a self-hosted catalog.

## Encryption

`SecureBackgroundStore` (Kotlin, `utils/background/`) does the following:

1. Downloads the full picture into memory (https only, 24 MiB cap).
2. Generates an AES-256-GCM key in the **Android Keystore** on first use. The key material never
   leaves the keystore and cannot be exported.
3. Encrypts the bytes and writes `files/secure_background/background.bin`:
   `"SYBG1" | iv_size (1 byte) | iv | ciphertext+tag`.
4. Writes the picture to a temporary file first, then renames it, so a failed write cannot leave
   a half-image behind.
5. Stores only a sentinel (`suyu-secure://library-background`) in `library_background_uri`, never a
   URL or a plaintext path.

When the library draws the background, the file is decrypted **in memory only** and cached as one
`Bitmap` for the process. No plaintext copy is written to disk or to the config.

What this does protect against: someone copying the app's data directory, an `adb backup`, a
rooted file manager, or a stolen device backup reading the wallpaper.

What it does **not** protect against: someone observing the network at the moment you download a
picture (they can see the URL), or code running inside the app's own process. The store is not a
defence against a compromised OS.

Deleting the background (`Use theme background`) removes the ciphertext and its metadata.

## Catalog format

Point the app at any https URL that returns JSON:

```json
{
  "categories": [
    {
      "name": "Recommended",
      "items": [
        {
          "id": "aurora-01",
          "title": "Aurora",
          "author": "Photographer",
          "thumb": "https://example.com/aurora-thumb.jpg",
          "full": "https://example.com/aurora-1920.jpg",
          "source": "https://example.com/photo-page"
        }
      ]
    }
  ]
}
```

Rules enforced by the client:

- Every `thumb` and `full` URL must use `https://`.
- `full` must differ from `thumb` (so the app cannot silently use a thumbnail as the wallpaper).
- The catalog itself must be served over `https://` and be at most 2 MiB.
- Unknown fields are ignored, so the schema can grow.
- Invalid entries are dropped; an empty catalog after filtering is treated as an error.

If the catalog is unreachable or empty, the picker falls back to the built-in picks and says so.
The built-in picks use [Lorem Picsum](https://picsum.photos), which serves freely usable photos
without an API key. They are downloaded only when chosen.

## Adding a hosted catalog

1. Host the JSON (GitHub Pages, an object store, your own server — any static https host).
2. In the picker, tap `Catalog source…` and paste the https URL.
3. The URL is stored in the app's private preferences; it is not part of the emulator config and
   is not shared.

Only ship images you have the right to distribute. The catalog stores `author` and `source` so
attribution can be shown, and the built-in provider is credited in this file and in the picker.

## Privacy summary

- No analytics or third-party SDK is involved.
- Thumbnails are fetched directly from the catalog's `thumb` URLs by Coil; the host can see those
  requests. The chosen full image is fetched once, then never again.
- The encrypted file is app-private storage (`/data/data/<package>/files/secure_background/`).
- Uninstalling the app, or clearing its data, removes the picture and the Keystore key.
