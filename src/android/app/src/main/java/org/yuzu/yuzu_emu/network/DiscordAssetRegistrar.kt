// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.yuzu.yuzu_emu.features.settings.model.StringSetting
import org.yuzu.yuzu_emu.utils.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Makes every image URL "just work", the way ArchiveTune's images do.
 *
 * A presence asset is normally an asset key from the Discord developer portal, which would mean
 * the user maintaining an app and uploading art by hand. Instead, an authorisation-scoped access
 * token can register any public https URL against the application on the fly:
 * `POST /applications/:id/external-assets`. The registration returns a portable
 * `external_assets/...` key that is used prefixed with `mp:`.
 *
 * Values that are already assets (snowflakes, `mp:`-style platform keys, Discord CDN links) are
 * passed through without a round trip. Results are cached per URL so a running session registers
 * each image once.
 */
object DiscordAssetRegistrar {
    private const val API_BASE = "https://discord.com/api/v10"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val cache = ConcurrentHashMap<String, String>()

    fun clearCache() {
        cache.clear()
    }

    /**
     * Resolves both images in one pass; returns them already in asset form, or null when a value
     * is unusable. Asset-shaped values never fail here, so a user who configured proper asset
     * keys keeps working even when registration is impossible.
     */
    fun resolveImages(accessToken: String, largeImage: String, smallImage: String): Pair<String, String> {
        val largeExternal = isExternalUrl(largeImage)
        val smallExternal = isExternalUrl(smallImage)

        val pending = mutableListOf<String>()
        if (largeExternal && !cache.containsKey(largeImage)) {
            pending.add(largeImage)
        }
        if (smallExternal && !cache.containsKey(smallImage)) {
            pending.add(smallImage)
        }
        if (pending.isNotEmpty()) {
            registerBatch(accessToken, pending).forEachIndexed { index, key ->
                if (key != null) {
                    cache[pending[index]] = key
                }
            }
        }

        fun resolve(image: String, external: Boolean): String? {
            if (image.isBlank()) {
                return null
            }
            if (external) {
                return cache[image]?.let { "mp:$it" }
            }
            return when {
                isSnowflake(image) -> image
                hasPlatformPrefix(image) -> image
                isDiscordCdn(image) -> "mp:" + image
                    .replace("https://cdn.discordapp.com/", "", ignoreCase = true)
                    .replace("http://cdn.discordapp.com/", "", ignoreCase = true)
                    .replace("https://media.discordapp.net/", "", ignoreCase = true)
                    .replace("http://media.discordapp.net/", "", ignoreCase = true)
                else -> image
            }
        }

        return resolve(largeImage, largeExternal).orEmpty() to
            resolve(smallImage, smallExternal).orEmpty()
    }

    private fun isSnowflake(value: String) = Regex("^[0-9]{17,19}$").matches(value)

    private fun hasPlatformPrefix(value: String): Boolean =
        listOf("mp:", "spotify:", "twitch:", "youtube:").any { value.startsWith(it) }

    private fun isDiscordCdn(value: String): Boolean =
        listOf(
            "https://cdn.discordapp.com/",
            "http://cdn.discordapp.com/",
            "https://media.discordapp.net/",
            "http://media.discordapp.net/"
        ).any { value.startsWith(it) }

    /**
     * Only plain http(s) URLs of the world-wide web go through registration; a Discord CDN link is
     * already an asset reference, and anything else is passed through so the gateway can judge it.
     */
    private fun isExternalUrl(value: String): Boolean {
        if (!value.startsWith("https://") && !value.startsWith("http://")) {
            return false
        }
        return !isDiscordCdn(value)
    }

    private fun registerBatch(accessToken: String, urls: List<String>): List<String?> {
        if (urls.isEmpty()) {
            return urls.map { null }
        }
        val payload = JSONObject().put("urls", JSONArray(urls))
        val request = Request.Builder()
            .url("$API_BASE/applications/${applicationId()}/external-assets")
            .header("Authorization", "Bearer $accessToken")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            val response = httpClient.newCall(request).execute()
            response.use {
                if (!response.isSuccessful) {
                    throw IllegalStateException("external-assets returned HTTP ${response.code}")
                }
                val json = JSONObject(response.body?.string().orEmpty())
                val array = json.optJSONArray("assets")
                urls.indices.map { index ->
                    array?.optString(index, "")?.takeIf { it.isNotBlank() }
                }
            }
        }.onFailure {
            Log.warning(
                "[DiscordAssetRegistrar] external-assets registration failed: ${it.javaClass.simpleName}"
            )
        }.getOrDefault(urls.map { null })
    }

    private fun applicationId(): String =
        StringSetting.DISCORD_APPLICATION_ID.getString().trim().ifEmpty {
            DiscordPresence.DEFAULT_APPLICATION_ID
        }
}
