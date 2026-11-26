// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.features.settings.model.StringSetting
import org.yuzu.yuzu_emu.utils.GameMetadata
import org.yuzu.yuzu_emu.utils.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Discord Rich Presence for Android, over the Discord account (OAuth) transport.
 *
 * There is no single "right" way to publish presence from Android; the one chosen here is the
 * only one that is both lawful and needs nothing from the user: a one-tap authorisation in the
 * browser (PKCE, scopes `openid identify sdk.social_layer_presence`) returns an access token
 * with which the gateway is authenticated as "Bearer <token>". The presence lands on the
 * player's own profile with no pasted credential, and any public image URL is registered on the
 * fly as an application asset. See [DiscordOAuth].
 *
 * The presence itself is not configurable: it is always the running game — title, cover art and
 * an elapsed timer — published by the signed-in account.
 *
 * The token is read from settings on connect and is never logged, stored here, or written
 * anywhere else.
 */
object DiscordPresence {
    private const val GATEWAY_URL = "wss://gateway.discord.gg/?v=9&encoding=json"

    /**
     * The project's own registered Discord application ("Sakura").
     *
     * An Application ID is a public identifier, not a credential: it is the value that appears in
     * every invite and OAuth link. Baking it in means the presence carries the right application
     * context with no setup, and
     * settings field stays as an override for anyone who wants their own app and its assets.
     */
    const val DEFAULT_APPLICATION_ID = "1568619623023255604"

    // The OAuth session identifies as the embedded client; these are its capability and intent
    // bitfields (dedupe user objects, prioritised ready, auto call/lobby connect).
    private const val OAUTH_CAPABILITIES = (1 shl 4) or (1 shl 5) or (1 shl 12) or (1 shl 16)
    private const val OAUTH_INTENTS = (1 shl 12) or (1 shl 18) or (1 shl 19) or (1 shl 22) or
        (1 shl 23) or (1 shl 27) or (1 shl 28) or (1 shl 29)

    private const val OP_DISPATCH = 0
    private const val OP_HEARTBEAT = 1
    private const val OP_IDENTIFY = 2
    private const val OP_PRESENCE_UPDATE = 3
    private const val OP_RECONNECT = 7
    private const val OP_INVALID_SESSION = 9
    private const val OP_HELLO = 10
    private const val OP_HEARTBEAT_ACK = 11

    /** How long to wait before re-sending a presence whose art was registered moments ago. */
    private const val ASSET_REFRESH_DELAY_MS = 5000L

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // The gateway requires its own heartbeat interval; OkHttp's ping is redundant.
            .pingInterval(0, TimeUnit.SECONDS)
            .build()
    }

    private val scheduler: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "discord-presence").apply { isDaemon = true }
        }
    }

    private val lock = Any()

    /** Consumed by the Discord page; set while the page is in front. */
    @Volatile
    var statusListener: ((String) -> Unit)? = null

    @Volatile
    private var lastStatus: String = ""

    @Volatile
    private var sessionTitle: String? = null

    /** Canonical hex title id of the running game, or blank; used for automatic box art. */
    @Volatile
    private var sessionTitleId: String? = null

    /** Path of the running game's file; the icon is extracted from it for the presence art. */
    @Volatile
    private var sessionGamePath: String? = null

    @Volatile
    private var desiredTitle: String? = null

    @Volatile
    private var startedAt: Long = 0L

    @Volatile
    private var customisation: PresenceCustomization = PresenceCustomization.DEFAULT

    // ---- gateway state -----------------------------------------------------

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var heartbeatFuture: java.util.concurrent.ScheduledFuture<*>? = null

    @Volatile
    private var assetRefreshFuture: java.util.concurrent.ScheduledFuture<*>? = null

    @Volatile
    private var sequence: Long? = null

    @Volatile
    private var ready = false

    @Volatile
    private var reconnectAttempt = 0

    @Volatile
    private var closedByUs = false

    /** Set when a heartbeat is on the wire, cleared by the gateway's ack or a disconnect. */
    @Volatile
    private var awaitingHeartbeatAck = false

    // Access token for the current OAuth session, held only in memory so the gateway listener can
    // authenticate and assets can be registered. Cleared with the connection.
    @Volatile
    private var oauthToken: String? = null

    // ---- public surface ----------------------------------------------------

    /** What the presence would say right now, for the page's live preview. */
    data class Preview(
        val title: String?,
        val titleId: String?,
        val accountName: String?
    )

    fun livePreview(): Preview = Preview(
        title = sessionTitle,
        titleId = sessionTitleId,
        accountName = DiscordOAuth.account()?.displayName?.takeIf { it.isNotBlank() }
    )

    fun status(): String = lastStatus

    fun isEnabled(): Boolean = BooleanSetting.DISCORD_RICH_PRESENCE.getBoolean()

    /**
     * Called when the emulated session starts or stops. [title] is the game's display name, or
     * null when no game is running; [titleId] is the game's canonical hex title id when known.
     */
    fun onSessionGameChanged(title: String?, titleId: String? = null, gamePath: String? = null) {
        sessionTitle = title
        sessionTitleId = titleId?.takeIf { it.isNotBlank() && it != "0" }
        sessionGamePath = gamePath?.takeIf { it.isNotBlank() }
        if (title.isNullOrBlank()) {
            // The game is gone: forget the title so the next launch restarts the elapsed timer,
            // and any connect still queued sees no game and does nothing.
            disconnectInternal(keepTitle = false)
            publish("Disconnected")
            return
        }
        if (!isEnabled()) {
            publish("Rich Presence is off")
            return
        }
        scheduleConnect()
    }

    /**
     * Re-reads every setting and (re)connects. Used by the Discord page's buttons, where the user
     * expects an immediate effect instead of waiting for the next game launch.
     */
    fun reconnectWithCurrentSettings() {
        disconnectInternal(keepTitle = true)
        if (!isEnabled()) {
            publish("Rich Presence is off")
            return
        }
        if (sessionTitle.isNullOrBlank()) {
            publish("No game running; settings saved")
            return
        }
        scheduleConnect()
    }

    /**
     * All of connect()'s real work blocks (token refresh, HTTP) or uses the gateway, and the
     * callers above run on the main thread; the scheduler thread must own it all, or a due token
     * refresh becomes an NetworkOnMainThreadException on the game-start path.
     */
    private fun scheduleConnect() {
        scheduler.execute { connect() }
    }

    fun disconnect() {
        // Keeps the session title: the user may reconnect from the page while the same game is
        // still running, and clearing it here made that report "no game running".
        disconnectInternal(keepTitle = true)
        publish("Disconnected")
    }

    /**
     * One-tap setup: called by the Discord page once the browser authorisation round-trip has
     * produced tokens. Connects immediately, so the presence starts without any further step.
     */
    fun onSignedIn() {
        reconnectWithCurrentSettings()
    }

    /** Removes the stored authorisation and clears the presence. */
    fun onSignedOut() {
        DiscordOAuth.clearSession()
        disconnectInternal(keepTitle = true)
        publish("Signed out of Discord")
    }

    // ---- dispatch ---------------------------------------------------------

    private fun connect() {
        val title = sessionTitle
        // A connect can be queued behind the game that started it stopping; in that window the
        // honest answer is to do nothing rather than publish a game that no longer runs.
        if (title.isNullOrBlank() || !isEnabled()) {
            return
        }
        // The presence is never user-configured: it is always the running game. The headline is
        // the game title, the large image falls back to the game's cover art, and where an
        // account is signed in, its display name fills the state line.
        // The elapsed time survives a reconnect (same game still running) but restarts with a
        // new session; disconnectInternal(keepTitle = false) clears desiredTitle on game stop.
        if (title != desiredTitle || startedAt == 0L) {
            startedAt = System.currentTimeMillis()
        }
        desiredTitle = title.take(128)
        // The state line is constant branding, and the button points at the project. The
        // signed-in display name still shows in the READY status message on the settings page.
        customisation = PresenceCustomization(
            state = "using Sakura emulator",
            button1Label = "View on GitHub",
            button1Url = "https://github.com/911TOAST/Sakura"
        )

        connectOAuth()
    }

    private fun connectOAuth() {
        val token = DiscordOAuth.validAccessToken()
        if (token.isNullOrBlank()) {
            publish("Discord session expired; sign in again")
            return
        }
        oauthToken = token
        connectGateway()
        // connectGateway publishes "Connecting…"; the sign-in identity is shown in the READY
        // message, once the gateway confirms the session really works.
    }

    private fun connectGateway() {
        val token = oauthToken ?: run {
            publish("Discord session expired; sign in again")
            return
        }

        closedByUs = false
        val request = Request.Builder().url(GATEWAY_URL).build()
        socket = httpClient.newWebSocket(request, GatewayListener(token))
        publish("Connecting via Discord account…")
    }

    private fun disconnectInternal(keepTitle: Boolean) {
        synchronized(lock) {
            // Closing the socket alone leaves the last activity showing until Discord times the
            // session out; an explicit empty presence clears it while the connection is still
            // there, so "always disconnect" means now, not in a minute.
            socket?.takeIf { ready }?.send(
                JSONObject()
                    .put("op", OP_PRESENCE_UPDATE)
                    .put(
                        "d",
                        JSONObject()
                            .put("since", JSONObject.NULL)
                            .put("activities", JSONArray())
                            .put("status", "invisible")
                            .put("afk", false)
                    )
                    .toString()
            )
            if (!keepTitle) {
                desiredTitle = null
                sessionTitle = null
            }
            ready = false
            awaitingHeartbeatAck = false
            closedByUs = true
            heartbeatFuture?.cancel(false)
            heartbeatFuture = null
            assetRefreshFuture?.cancel(false)
            assetRefreshFuture = null
            socket?.close(1000, null)
            socket = null
        }
        oauthToken = null
        DiscordAssetRegistrar.clearCache()
    }

    private fun publish(message: String) {
        lastStatus = message
        statusListener?.invoke(message)
    }

    // ---- Gateway ----------------------------------------------------------

    // Not 'inner': this object is a singleton, so there is no outer instance to capture. Every
    // state access is qualified back through DiscordPresence instead.
    private class GatewayListener(
        private val token: String
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.info("[DiscordPresence] gateway connected")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
            message.optLong("s", -1L).takeIf { it >= 0L }?.let { DiscordPresence.sequence = it }

            when (message.optInt("op", -1)) {
                OP_HELLO -> {
                    val interval =
                        message.optJSONObject("d")?.optLong("heartbeat_interval", 0L) ?: 0L
                    DiscordPresence.startHeartbeat(webSocket, interval)
                    DiscordPresence.sendIdentify(webSocket, token)
                }

                OP_HEARTBEAT_ACK -> {
                    // Proof the connection is alive; releases the watchdog in startHeartbeat.
                    awaitingHeartbeatAck = false
                }

                OP_DISPATCH -> {
                    if (message.optString("t") == "READY") {
                        DiscordPresence.ready = true
                        DiscordPresence.reconnectAttempt = 0
                        DiscordPresence.sendPresenceUpdate(webSocket)
                        DiscordPresence.publish(
                            DiscordOAuth.account()?.displayName
                                ?.takeIf { it.isNotBlank() }
                                ?.let { name -> "Connected as $name" }
                                ?: "Connected as your Discord account"
                        )
                    }
                }

                OP_RECONNECT -> {
                    Log.info("[DiscordPresence] gateway asked for reconnect")
                    webSocket.close(1000, null)
                }

                OP_INVALID_SESSION -> {
                    // A resume carries nothing worth preserving here: the session holds only
                    // presence, so a clean re-identify is simpler and always correct.
                    Log.warning("[DiscordPresence] session invalidated; re-identifying")
                    DiscordPresence.ready = false
                    webSocket.close(1000, null)
                }
            }
        }

        /**
         * Nulls [webSocket] out as the live socket and reports whether it was something else —
         * either a socket replaced by a newer connect, or one already torn down by disconnect.
         * Reacting to a replaced socket's close would schedule a duplicate reconnect on top of
         * the live one, so stale callbacks must die here.
         */
        fun retireSocket(webSocket: WebSocket): Boolean = synchronized(DiscordPresence.lock) {
            val stale = DiscordPresence.socket !== webSocket
            if (!stale) {
                DiscordPresence.socket = null
            }
            stale
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (retireSocket(webSocket)) {
                return
            }
            DiscordPresence.ready = false
            DiscordPresence.stopHeartbeat()
            if (DiscordPresence.closedByUs || DiscordPresence.desiredTitle == null) {
                return
            }
            DiscordPresence.publish("Gateway error: ${t.message}")
            DiscordPresence.scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (retireSocket(webSocket)) {
                return
            }
            DiscordPresence.ready = false
            DiscordPresence.stopHeartbeat()
            if (DiscordPresence.closedByUs || DiscordPresence.desiredTitle == null) {
                return
            }
            // A 4004 is "authentication failed": a stale OAuth token or a revoked authorisation.
            // Reconnect will retry a refresh first when one is possible.
            if (code == 4004) {
                DiscordPresence.publish("Discord rejected the session; sign in again if this repeats")
            }
            // Re-read settings on the next attempt: the user may have just fixed the token.
            DiscordPresence.scheduleReconnect()
        }
    }

    private fun sendIdentify(webSocket: WebSocket, token: String) {
        val data = JSONObject()

        // The access token authenticates as "Bearer", not raw: the gateway resolves an OAuth
        // token through the authorisation it came from (this is what the embedded client does).
        data.put("token", "Bearer $token")
        data.put("capabilities", OAUTH_CAPABILITIES)
        data.put("intents", OAUTH_INTENTS)
        data.put(
            "properties",
            JSONObject()
                .put("os", "Android")
                .put("browser", "Discord Embedded")
                .put("device", "Android")
                .put("browser_user_agent", "Discord Embedded")
                .put("browser_version", "1.0")
                .put("client_version", "1.0")
        )

        webSocket.send(JSONObject().put("op", OP_IDENTIFY).put("d", data).toString())
    }

    private fun sendPresenceUpdate(webSocket: WebSocket) {
        val token = oauthToken ?: return
        val title = desiredTitle ?: return
        val activities = JSONArray()
        if (title.isNotEmpty()) {
            val activity = PresenceCustomization.buildActivity(customisation, title, startedAt)

            // The authorisation carries the application context: the activity carries the
            // app id, the large image falls back to the game's cover art (registered on the
            // fly if it is a plain URL), and buttons use the canonical label + metadata form.
            activity.put(
                "application_id",
                StringSetting.DISCORD_APPLICATION_ID.getString().trim().ifEmpty {
                    DEFAULT_APPLICATION_ID
                }
            )
            var large = customisation.largeImage
            if (large.isBlank()) {
                // Zero-config art: the game's own icon, extracted from the running file and
                // uploaded once to catbox (no account, no key), then registered through the
                // external-assets API — the same flow ArchiveTune uses. The tinfoil cover URL
                // is only a fallback for games whose icon cannot be extracted; a missing cover
                // simply means no image, never a failed presence.
                sessionTitleId?.takeIf { it.length >= 14 }?.let { id ->
                    large = DiscordArtUploader.cachedUrl(id)
                        ?: DiscordArtUploader.uploadGameIcon(id, gameIconBytes())
                        ?: "https://tinfoil.media/ti/${id.lowercase()}/256/256/"
                }
            }
            val resolved = DiscordAssetRegistrar.resolveImages(token, large, "")
            val assets = JSONObject()
            if (resolved.first.isNotEmpty()) {
                assets.put("large_image", resolved.first.take(256))
                // Hover text on the cover art is the game title itself.
                assets.put("large_text", title.take(128))
            }
            if (assets.length() > 0) {
                activity.put("assets", assets)
                Log.warning("[DiscordPresence] assets sent: large=${resolved.first.take(64)}")
                // A freshly registered asset key is handed back before Discord's servers have
                // finished fetching the image itself, so the first presence can render a
                // placeholder. Re-send once the fetch has had time to land; Discord re-renders
                // the card on every update. Cached art skips this entirely.
                if (DiscordAssetRegistrar.consumeFreshRegistration()) {
                    scheduleAssetRefresh(webSocket)
                }
            } else {
                Log.warning("[DiscordPresence] no assets resolved for '$title' (large was '$large')")
            }

            activities.put(activity)
        }

        val payload = JSONObject().put("op", OP_PRESENCE_UPDATE).put(
            "d",
            JSONObject()
                .put("since", JSONObject.NULL)
                .put("activities", activities)
                .put("status", "online")
                .put("afk", false)
        )
        webSocket.send(payload.toString())
    }

    /** The running game's icon bytes, extracted on the calling (scheduler) thread; null on any failure. */
    private fun gameIconBytes(): ByteArray? {
        val path = sessionGamePath ?: return null
        return runCatching { GameMetadata.getIcon(path) }
            .onFailure {
                Log.warning("[DiscordPresence] icon extraction failed: ${it.javaClass.simpleName}")
            }
            .getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * One delayed re-send of the presence, giving Discord's asset fetcher a window to finish
     * before the card is rendered again. Only fires while the same socket is still live.
     */    private fun scheduleAssetRefresh(webSocket: WebSocket) {
        assetRefreshFuture?.cancel(false)
        assetRefreshFuture = scheduler.schedule({
            val live = synchronized(lock) {
                if (ready && socket === webSocket) webSocket else null
            }
            if (live != null) {
                Log.warning("[DiscordPresence] re-sending presence for freshly registered art")
                sendPresenceUpdate(live)
            }
        }, ASSET_REFRESH_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun startHeartbeat(webSocket: WebSocket, intervalMs: Long) {
        stopHeartbeat()
        awaitingHeartbeatAck = false
        if (intervalMs <= 0L) {
            return
        }
        // The first beat must land inside the interval, per the gateway protocol.
        val initialDelay = (intervalMs * Math.random()).toLong()
        heartbeatFuture = scheduler.scheduleAtFixedRate(
            {
                // The gateway must acknowledge every beat. If the previous one was not
                // acknowledged, the TCP connection is a zombie — a dead Wi-Fi, a NAT drop — and
                // no amount of waiting on this socket will revive it. Cancelling it surfaces the
                // death to the reconnect logic, which is the only path back to healthy.
                if (awaitingHeartbeatAck) {
                    Log.warning("[DiscordPresence] heartbeat unacknowledged; forcing reconnect")
                    awaitingHeartbeatAck = false
                    runCatching { webSocket.cancel() }
                    return@scheduleAtFixedRate
                }
                awaitingHeartbeatAck = true
                val beat = JSONObject().put("op", OP_HEARTBEAT)
                    .put("d", sequence ?: JSONObject.NULL)
                webSocket.send(beat.toString())
            },
            initialDelay,
            intervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun stopHeartbeat() {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
    }

    private fun scheduleReconnect() {
        val attempt = ++reconnectAttempt
        if (attempt > 5) {
            publish("Gave up after $attempt reconnect attempts")
            return
        }
        // 2s, 4s, 8s, 16s, 32s: Discord rate-limits aggressive reconnects, and a rejected token
        // would otherwise spin the socket forever in the background.
        val delaySeconds = 1L shl attempt
        scheduler.schedule(
            { connect() },
            delaySeconds,
            TimeUnit.SECONDS
        )
    }
}
