// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils.background

import android.content.Context
import android.graphics.Bitmap
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject
import org.yuzu.yuzu_emu.features.settings.model.StringSetting

/**
 * Stores the library background on disk as AES-GCM ciphertext.
 *
 * The key lives in the Android Keystore and is generated on first use. It never leaves the
 * keystore, so a copy of the app's files directory (adb backup, a stolen backup, a file manager
 * with root) does not reveal the picture. Decryption only happens in this process, and only in
 * memory; no plaintext copy is ever written to disk.
 *
 * This protects the picture at rest. It does not hide the fact that a picture was downloaded
 * from a particular URL from someone watching the network at that moment.
 */
object SecureBackgroundStore {
    /**
     * Sentinel stored in `library_background_uri`. The real picture lives in [selectionFile],
     * encrypted, and never in the settings/config.
     */
    const val SELECTION_URI = "suyu-secure://library-background"

    private const val PREFS = "secure_library_background"
    private const val PREF_METADATA = "metadata"

    private const val KEY_ALIAS = "suyu_library_background_v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private const val MAGIC = "SYBG1"
    private const val FILE_NAME = "background.bin"
    private const val DIRECTORY = "secure_background"

    private val bitmapCache = object : LruCache<String, Bitmap>(1) {}

    fun selectionFile(context: Context): File = File(File(context.filesDir, DIRECTORY), FILE_NAME)

    fun isEncryptedSelection(uri: String): Boolean = uri == SELECTION_URI ||
        (uri.startsWith("$SELECTION_URI/") && VERSION.matches(uri.substringAfterLast('/')))

    private val VERSION = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    private fun activeUri(): String = StringSetting.LIBRARY_BACKGROUND_URI.getString(true)

    private fun fileFor(uri: String): File {
        require(isEncryptedSelection(uri))
        return if (uri == SELECTION_URI) selectionFile(context())
        else File(selectionFile(context()).parentFile, "${uri.substringAfterLast('/')}.bin")
    }

    private fun metadataKey(uri: String): String = if (uri == SELECTION_URI) PREF_METADATA else uri

    fun isActive(): Boolean = activeUri().let { isEncryptedSelection(it) && fileFor(it).exists() }

    /** Immutable encrypted blobs plus an atomic config pointer keep the previous selection intact. */
    @Synchronized
    fun commitOnline(bytes: ByteArray, metadata: BackgroundMetadata, commitSettings: (String) -> Unit): String {
        val uri = "$SELECTION_URI/${UUID.randomUUID()}"
        val file = fileFor(uri)
        commitWallpaperSelection(
            commitImage = {
                writeEncrypted(file, bytes)
                check(writeMetadata(metadata, uri)) { "Could not save image attribution" }
            },
            commitSettings = { commitSettings(uri) },
            rollback = {
                file.delete()
                preferences().edit().remove(metadataKey(uri)).commit()
            }
        )
        bitmapCache.evictAll()
        return uri
    }

    private fun writeEncrypted(file: File, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= OnlineBackgroundCatalog.MAX_IMAGE_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(bytes)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(MAGIC.toByteArray(Charsets.US_ASCII))
                out.write(cipher.iv.size)
                out.write(cipher.iv)
                out.write(ciphertext)
                out.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    /** Called only after a successful commit. Abandoned encrypted drafts are safe to remove. */
    @Synchronized
    fun pruneUnselected(uri: String) {
        val keep = if (isEncryptedSelection(uri)) fileFor(uri).name else null
        selectionFile(context()).parentFile?.listFiles()?.forEach { file ->
            if (file.name != keep && (file.extension == "bin" || file.extension == "tmp")) file.delete()
        }
        val keepKey = if (keep != null) metadataKey(uri) else null
        val editor = preferences().edit()
        preferences().all.keys.filter { it == PREF_METADATA || it.startsWith("$SELECTION_URI/") }
            .filter { it != keepKey }.forEach { editor.remove(it) }
        editor.apply()
        bitmapCache.evictAll()
    }

    /** Encrypts [bytes] and replaces the stored background. Never writes plaintext. */
    @Synchronized
    fun save(bytes: ByteArray, metadata: BackgroundMetadata): Boolean {
        return runCatching {
            WallpaperImages.decode(bytes).recycle()
            writeEncrypted(selectionFile(context()), bytes)
            bitmapCache.evictAll()
            writeMetadata(metadata, SELECTION_URI)
        }.getOrDefault(false)
    }

    /** Decrypts and decodes the stored picture. Returns null when there is none. */
    @Synchronized
    fun loadBitmap(uri: String = activeUri()): Bitmap? {
        if (!isEncryptedSelection(uri)) return null
        val file = fileFor(uri)
        if (!file.exists()) {
            return null
        }
        bitmapCache.get(file.absolutePath)?.let { cached ->
            // A recycled bitmap must never be handed back to an ImageView; drop it and re-decode.
            if (!cached.isRecycled) return cached
            bitmapCache.remove(file.absolutePath)
        }
        val bitmap = runCatching {
            val raw = file.inputStream().use {
                WallpaperHttp.readBounded(it, OnlineBackgroundCatalog.MAX_IMAGE_BYTES + 64)
            }
            val header = MAGIC.length
            require(raw.size > header + 1) { "truncated background" }
            require(String(raw, 0, header, Charsets.US_ASCII) == MAGIC) { "bad magic" }
            val ivSize = raw[header].toInt() and 0xFF
            require(ivSize == IV_BYTES) { "bad iv" }
            require(raw.size > header + 1 + ivSize) { "truncated background" }
            val iv = raw.copyOfRange(header + 1, header + 1 + ivSize)
            val ciphertext = raw.copyOfRange(header + 1 + ivSize, raw.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext)

            WallpaperImages.decode(plaintext)
        }.getOrNull() ?: return null
        bitmapCache.put(file.absolutePath, bitmap)
        return bitmap
    }

    @Synchronized
    fun clear() {
        pruneUnselected("")
    }

    @Synchronized
    fun readMetadata(uri: String = activeUri()): BackgroundMetadata? {
        if (!isEncryptedSelection(uri)) return null
        val raw = preferences().getString(metadataKey(uri), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            BackgroundMetadata(
                id = json.optString("id"),
                title = json.optString("title"),
                author = json.optString("author"),
                source = json.optString("source")
            )
        }.getOrNull()
    }

    @Synchronized
    private fun writeMetadata(metadata: BackgroundMetadata, uri: String): Boolean {
        val json = JSONObject().apply {
            put("id", metadata.id)
            put("title", metadata.title)
            put("author", metadata.author)
            put("source", metadata.source)
        }
        return preferences().edit().putString(metadataKey(uri), json.toString()).commit()
    }

    private fun context(): Context = org.yuzu.yuzu_emu.YuzuApplication.appContext

    private fun preferences() =
        context().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

/** Source attribution stored in private preferences; full image bytes alone are encrypted. */
data class BackgroundMetadata(
    val id: String,
    val title: String,
    val author: String,
    val source: String
)
