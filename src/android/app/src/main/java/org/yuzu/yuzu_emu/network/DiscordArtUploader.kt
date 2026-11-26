// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.yuzu.yuzu_emu.utils.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Puts the running game's own icon where Discord can fetch it, with no setup.
 *
 * The icon lives only inside the ROM, so it needs a public URL before
 * [DiscordAssetRegistrar] can register it. Catbox (catbox.moe) accepts anonymous uploads with
 * no account and no API key — the exact opposite of the Imgur client-id dance this replaced —
 * and the returned URL is registered against the application through the external-assets API,
 * so Discord's proxy never has to like catbox itself. Results are cached per title id for the
 * life of the process: each game is uploaded once.
 */
object DiscordArtUploader {
    private const val UPLOAD_URL = "https://catbox.moe/user/api.php"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val cache = ConcurrentHashMap<String, String>()

    fun cachedUrl(titleId: String): String? = cache[titleId.lowercase()]

    /**
     * Uploads [iconBytes] as the art for [titleId] and returns its public URL, or null on any
     * failure — callers fall back to the cover-art URL, never a failed presence.
     */
    fun uploadGameIcon(titleId: String, iconBytes: ByteArray?): String? {
        if (titleId.isBlank()) {
            return null
        }
        val id = titleId.lowercase()
        cache[id]?.let { return it }
        val bytes = iconBytes?.takeIf { it.isNotEmpty() } ?: return null

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("reqtype", "fileupload")
            .addFormDataPart("fileToUpload", "$id.png", bytes.toRequestBody("image/png".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(UPLOAD_URL)
            .post(body)
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                val url = response.body?.string()?.trim().orEmpty()
                if (!response.isSuccessful || !url.startsWith("https://files.catbox.moe/")) {
                    Log.warning("[DiscordArtUploader] catbox upload HTTP ${response.code}")
                    return@use null
                }
                cache[id] = url
                Log.warning("[DiscordArtUploader] uploaded game icon -> $url")
                url
            }
        }.onFailure {
            Log.warning(
                "[DiscordArtUploader] catbox upload failed: ${it.message ?: it.javaClass.simpleName}"
            )
        }.getOrNull()
    }
}
