// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.yuzu.yuzu_emu.features.settings.model.StringSetting
import org.yuzu.yuzu_emu.utils.Log
import java.util.concurrent.TimeUnit

/**
 * Puts the running game's own icon where Discord's image proxy can reach it.
 *
 * Discord's external-asset fetcher cannot retrieve arbitrary hosts — tinfoil.media, catbox.moe,
 * x0.at and proxy CDNs all fail server-side while raw.githubusercontent.com, jsDelivr and Imgur
 * serve fine — so the game's extracted icon is uploaded once to Imgur and the resulting URL is
 * cached per title id. The upload needs an Imgur application client id (same setup as Kizzy's
 * custom RPC); without one, callers fall back to the tinfoil cover URL.
 */
object DiscordImageHost {
    private const val UPLOAD_URL = "https://api.imgur.com/3/image"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun clientId(): String = StringSetting.DISCORD_IMGUR_CLIENT_ID.getString().trim()

    /** The cached Imgur URL for [titleId], when this game was uploaded before. */
    fun cachedUrl(titleId: String): String? {
        if (titleId.isBlank()) {
            return null
        }
        return artCache()[titleId.lowercase()]
    }

    /**
     * Uploads [iconBytes] as the art for [titleId] and caches the URL. Returns the cached URL
     * when the game was uploaded before, and null when there is no client id, no icon, or the
     * upload failed — callers then fall back to the cover-art URL.
     */
    fun uploadGameIcon(titleId: String, iconBytes: ByteArray?): String? {
        if (titleId.isBlank()) {
            return null
        }
        val id = titleId.lowercase()
        artCache()[id]?.let { return it }
        val bytes = iconBytes?.takeIf { it.isNotEmpty() } ?: return null
        val client = clientId().takeIf { it.isNotBlank() } ?: return null

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", "icon.png", bytes.toRequestBody("image/png".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(UPLOAD_URL)
            .header("Authorization", "Client-ID $client")
            .post(body)
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string().orEmpty())
                val url = json.optJSONObject("data")?.optString("link").orEmpty()
                if (!response.isSuccessful || !url.startsWith("https://")) {
                    Log.warning("[DiscordImageHost] imgur upload HTTP ${response.code}")
                    return@use null
                }
                val cache = artCache().toMutableMap()
                cache[id] = url
                StringSetting.DISCORD_ART_CACHE_JSON.setString(JSONObject(cache).toString())
                Log.warning("[DiscordImageHost] uploaded game icon -> $url")
                url
            }
        }.onFailure {
            Log.warning(
                "[DiscordImageHost] imgur upload failed: ${it.message ?: it.javaClass.simpleName}"
            )
        }.getOrNull()
    }

    private fun artCache(): Map<String, String> {
        val raw = StringSetting.DISCORD_ART_CACHE_JSON.getString()
        if (raw.isBlank()) {
            return emptyMap()
        }
        return runCatching {
            val json = JSONObject(raw)
            val map = mutableMapOf<String, String>()
            json.keys().forEach { key -> map[key] = json.optString(key) }
            map
        }.getOrDefault(emptyMap())
    }
}
