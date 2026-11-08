// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils.background

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class WallpaperRateLimitException : IOException("Wallpaper service is cooling down. Please try again shortly.")

object WallpaperHttp {
    const val MAX_JSON_BYTES = 2 * 1024 * 1024
    private val permits = Semaphore(2)
    private val rateMutex = Mutex()
    private val starts = ArrayDeque<Long>()
    @Volatile private var cooldownUntil = 0L

    val client = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply { maxRequests = 2; maxRequestsPerHost = 2 })
        .retryOnConnectionFailure(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val url = chain.request().url
            if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) {
                throw IOException("HTTPS without credentials is required")
            }
            chain.proceed(chain.request())
        }
        .build()

    private fun clock() = System.nanoTime() / 1_000_000

    private suspend fun reserveApiCall() {
        while (true) {
            val wait = rateMutex.withLock {
                val now = clock()
                if (now < cooldownUntil) throw WallpaperRateLimitException()
                while (starts.isNotEmpty() && now - starts.first >= 60_000) starts.removeFirst()
                if (starts.size < 30) {
                    starts.addLast(now)
                    0L
                } else {
                    (60_000 - (now - starts.first)).coerceAtLeast(1L)
                }
            }
            if (wait == 0L) return
            delay(wait)
        }
    }

    private fun request(url: String, image: Boolean): Request {
        val parsed = url.toHttpUrl()
        require(parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty())
        return Request.Builder().url(parsed)
            .header("Accept", if (image) "image/jpeg,image/png,image/webp" else "application/json")
            .build()
    }

    suspend fun get(url: String, image: Boolean = false, api: Boolean = false): ByteArray =
        permits.withPermit {
            if (api) reserveApiCall()
            suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request(url, image))
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val bytes = response.use { readResponse(it, image) }
                            if (continuation.isActive) continuation.resume(bytes)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                })
            }
        }

    private fun readResponse(response: Response, image: Boolean): ByteArray {
        require(response.request.url.isHttps)
        if (response.code == 429) {
            val wait = retryAfterMillis(response.header("Retry-After"), System.currentTimeMillis())
            synchronized(this) { cooldownUntil = maxOf(cooldownUntil, clock() + wait) }
            throw WallpaperRateLimitException()
        }
        if (!response.isSuccessful) throw IOException("Wallpaper HTTP ${response.code}")
        val body = response.body ?: throw IOException("Empty wallpaper response")
        val limit = if (image) OnlineBackgroundCatalog.MAX_IMAGE_BYTES else MAX_JSON_BYTES
        require(body.contentLength() <= limit) { "Response is too large" }
        if (image) {
            val type = body.contentType()?.toString()?.substringBefore(';')
            require(type == "image/jpeg" || type == "image/png" || type == "image/webp") {
                "Unsupported image format"
            }
        }
        return body.byteStream().use { readBounded(it, limit) }
    }

    fun retryAfterMillis(value: String?, now: Long): Long {
        value?.toLongOrNull()?.let { return it.coerceIn(1, Long.MAX_VALUE / 4000) * 1000 }
        return runCatching {
            (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now)
                .coerceAtLeast(1000)
        }.getOrDefault(60_000L)
    }

    fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Response is too large" }
            output.write(buffer, 0, count)
        }
        require(total > 0) { "Empty response" }
        return output.toByteArray()
    }
}
