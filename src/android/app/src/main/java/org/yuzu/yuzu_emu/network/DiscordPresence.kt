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
import org.yuzu.yuzu_emu.features.settings.model.IntSetting
import org.yuzu.yuzu_emu.features.settings.model.StringSetting
import org.yuzu.yuzu_emu.utils.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Discord Rich Presence for Android, over three transports.
 *
 * There is no single "right" way to publish presence from Android, so the transport is a choice,
 * though one of them needs no choice made at all:
 *
 * [Mode.OAUTH] is the easy, default path — "Sign in with Discord", as ArchiveTune does. A
 * one-tap authorisation in the browser (PKCE, scopes `openid identify sdk.social_layer_presence`)
 * returns an access token with which the gateway is authenticated as "Bearer <token>". The
 * presence lands on the player's own profile with no pasted credential, and any public image URL
 * is registered on the fly as an application asset. See [DiscordOAuth].
 *
 * [Mode.SOCIAL_SDK] is Discord's official partner path. Only the public Application ID is needed
 * and the presence lands on the player's own profile, because the Discord app installed on the
 * device does the publishing. It requires `discord_partner_sdk.aar` and the app to be registered
 * with the Social SDK enabled, so it is negotiated at runtime rather than compiled against.
 *
 * [Mode.ACCOUNT_TOKEN] authenticates the gateway as the player's own account. This is what
 * Kizzy-style apps do; it also sets the player's own presence, needs no Discord app, and is a
 * self-bot, which Discord's Terms forbid. The user is warned in the UI and chooses it anyway.
 *
 * [Mode.BOT_TOKEN] is lawful but publishes on a bot account, visible only to members of a guild
 * the bot has been invited to.
 *
 * The token is read from settings on connect and is never logged, stored here, or written
 * anywhere else.
 */
object DiscordPresence {
    enum class Mode(val stored: Int) {
        SOCIAL_SDK(0),
        ACCOUNT_TOKEN(1),
        BOT_TOKEN(2),
        OAUTH(3);

        companion object {
            fun fromStored(value: Int): Mode = values().firstOrNull { it.stored == value } ?: SOCIAL_SDK
        }
    }

    private const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"

    // The OAuth/social-layer session emulates the embedded Discord client, which pins version 9
    // and identifies with a richer properties block than a plain self-bot session.
    private const val GATEWAY_URL_OAUTH = "wss://gateway.discord.gg/?v=9&encoding=json"

    /**
     * The project's own registered Discord application ("Sakura").
     *
     * An Application ID is a public identifier, not a credential: it is the value that appears in
     * every invite and OAuth link. Baking it in means Social SDK mode works out of the box, and the
     * settings field stays as an override for anyone who wants their own app and its assets.
     */
    const val DEFAULT_APPLICATION_ID = "1568619623023255604"

    // Bot and account tokens are both three dot-separated segments. Checking the shape keeps an
    // obviously wrong paste (a client secret is a single 32-character segment) from producing an
    // authentication loop the user cannot read.
    private const val MIN_TOKEN_LENGTH = 50

    // User-account identify requires a capability bitfield; 16381 is the set every self-bot client
    // sends and is what the gateway expects for a plain presence-only session.
    private const val ACCOUNT_CAPABILITIES = 16381

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

    @Volatile
    private var desiredTitle: String? = null

    @Volatile
    private var startedAt: Long = 0L

    @Volatile
    private var customisation: PresenceCustomization = PresenceCustomization.DEFAULT

    @Volatile
    private var mode: Mode = Mode.SOCIAL_SDK

    // ---- gateway state -----------------------------------------------------

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var heartbeatFuture: java.util.concurrent.ScheduledFuture<*>? = null

    @Volatile
    private var sequence: Long? = null

    @Volatile
    private var ready = false

    @Volatile
    private var reconnectAttempt = 0

    @Volatile
    private var closedByUs = false

    private val socialSdk = SocialSdkBridge()

    // Access token for the current OAuth session, held only in memory so the gateway listener can
    // authenticate and assets can be registered. Cleared with the connection.
    @Volatile
    private var oauthToken: String? = null

    // ---- public surface ----------------------------------------------------

    fun status(): String = lastStatus

    fun isEnabled(): Boolean = BooleanSetting.DISCORD_RICH_PRESENCE.getBoolean()

    fun currentMode(): Mode = Mode.fromStored(IntSetting.DISCORD_PRESENCE_MODE.getInt())

    /** True when the official transport can actually run on this build/device. */
    fun isSocialSdkAvailable(): Boolean = socialSdk.isAvailable

    /**
     * Called when the emulated session starts or stops. [title] is the game's display name, or
     * null when no game is running; [titleId] is the game's canonical hex title id when known.
     */
    fun onSessionGameChanged(title: String?, titleId: String? = null) {
        sessionTitle = title
        sessionTitleId = titleId?.takeIf { it.isNotBlank() && it != "0" }
        if (title.isNullOrBlank()) {
            disconnect()
            return
        }
        if (!isEnabled()) {
            publish("Rich Presence is off")
            return
        }
        connect(title)
    }

    /**
     * Re-reads every setting and (re)connects. Used by the Discord page's buttons, where the user
     * expects an immediate effect instead of waiting for the next game launch.
     */
    fun reconnectWithCurrentSettings() {
        disconnectInternal(keepTitle = true)
        val title = sessionTitle
        if (!isEnabled()) {
            publish("Rich Presence is off")
            return
        }
        if (title.isNullOrBlank()) {
            publish("No game running; settings saved")
            return
        }
        connect(title)
    }

    fun disconnect() {
        // Keeps the session title: the user may reconnect from the page while the same game is
        // still running, and clearing it here made that report "no game running".
        disconnectInternal(keepTitle = true)
        publish("Disconnected")
    }

    /**
     * One-tap setup: called by the Discord page once the browser authorisation round-trip has
     * produced tokens. Switches the transport to it — signing in *is* choosing the transport —
     * and connects immediately, so the user never visits a mode selector.
     */
    fun onSignedIn() {
        IntSetting.DISCORD_PRESENCE_MODE.setInt(Mode.OAUTH.stored)
        reconnectWithCurrentSettings()
    }

    /** Removes the stored authorisation and clears the presence if it was publishing via it. */
    fun onSignedOut() {
        DiscordOAuth.clearSession()
        if (currentMode() == Mode.OAUTH) {
            disconnectInternal(keepTitle = true)
            publish("Signed out of Discord")
        }
    }

    // ---- dispatch ---------------------------------------------------------

    private fun connect(title: String) {
        customisation = PresenceCustomization.load()
        mode = currentMode()
        desiredTitle = customisation.name.ifBlank { title }.take(128)
        startedAt = System.currentTimeMillis()

        when (mode) {
            Mode.SOCIAL_SDK -> connectSocialSdk()
            Mode.OAUTH -> connectOAuth()
            Mode.ACCOUNT_TOKEN, Mode.BOT_TOKEN -> connectGateway()
        }
    }

    private fun connectOAuth() {
        val token = DiscordOAuth.validAccessToken()
        if (token.isNullOrBlank()) {
            publish("Discord session expired; sign in again")
            return
        }
        oauthToken = token
        connectGateway()
        // connectGateway publishes "Connecting…" from here; the sign-in identity is shown in the
        // READY message, once the gateway confirms the session really works.
    }

    private fun connectSocialSdk() {
        // Empty field means "use the built-in application", not "misconfigured".
        val applicationId =
            StringSetting.DISCORD_APPLICATION_ID.getString().trim().ifEmpty { DEFAULT_APPLICATION_ID }
        if (!socialSdk.isAvailable) {
            // Not an error the user can fix from the UI, so say exactly what is missing.
            publish("Social SDK unavailable: bundle discord_partner_sdk.aar")
            return
        }
        val ok = socialSdk.connect(applicationId)
        if (!ok) {
            publish("Social SDK: could not reach the Discord app")
            return
        }
        refreshSocialSdkPresence()
        publish("Publishing via Social SDK (official)")
    }

    private fun connectGateway() {
        val isOAuth = mode == Mode.OAUTH
        val token: String
        if (isOAuth) {
            token = oauthToken ?: run {
                publish("Discord session expired; sign in again")
                return
            }
        } else {
            token = StringSetting.DISCORD_TOKEN.getString()
            if (token.isBlank()) {
                publish(
                    if (mode == Mode.BOT_TOKEN) "Bot mode: token missing" else "Account mode: token missing"
                )
                return
            }
            if (token.length < MIN_TOKEN_LENGTH || token.count { it == '.' } != 2) {
                // Says nothing about the value: a partially pasted or wrong-kind token must not be
                // echoed into a log the user may share.
                publish("Token is not shaped like a Discord token")
                Log.error("[DiscordPresence] token rejected by shape check")
                return
            }
        }

        closedByUs = false
        // The embedded-client session pins gateway version 9; plain tokens stay on v10.
        val url = if (isOAuth) GATEWAY_URL_OAUTH else GATEWAY_URL
        val request = Request.Builder().url(url).build()
        socket = httpClient.newWebSocket(request, GatewayListener(token, isOAuth))
        publish(
            when {
                isOAuth -> "Connecting via Discord account…"
                mode == Mode.BOT_TOKEN -> "Connecting as bot…"
                else -> "Connecting as account (self-bot)…"
            }
        )
    }

    private fun disconnectInternal(keepTitle: Boolean) {
        synchronized(lock) {
            if (!keepTitle) {
                desiredTitle = null
                sessionTitle = null
            }
            ready = false
            closedByUs = true
            heartbeatFuture?.cancel(false)
            heartbeatFuture = null
            socket?.close(1000, null)
            socket = null
        }
        oauthToken = null
        DiscordAssetRegistrar.clearCache()
        socialSdk.disconnect()
    }

    private fun publish(message: String) {
        lastStatus = message
        statusListener?.invoke(message)
    }

    // ---- Social SDK (reflective) ------------------------------------------

    /**
     * Adapter for `discord_partner_sdk.aar`.
     *
     * The SDK is a native library shipped as an AAR and cannot be vendored here, so it is looked
     * up reflectively: with the AAR in `app/libs` these calls reach the real bindings, and without
     * it every entry point reports unavailable instead of failing the build or crashing. Method
     * names are matched case- and underscore-insensitively because the Java binding has shipped
     * with both `SetApplicationId` and `setApplicationId` spellings.
     */
    private class SocialSdkBridge {
        private var client: Any? = null
        private var activityClass: Class<*>? = null

        val isAvailable: Boolean
            get() = runCatching { Class.forName(CLIENT_CLASS) }.isSuccess

        fun connect(applicationId: String): Boolean {
            return runCatching {
                if (client == null) {
                    val clazz = Class.forName(CLIENT_CLASS)
                    client = clazz.getDeclaredConstructor().newInstance()
                    activityClass = Class.forName(ACTIVITY_CLASS)
                }
                val idValue: Any = applicationId.toLongOrNull() ?: applicationId
                invoke(client, arrayOf("setApplicationId"), arrayOf(idValue))
                true
            }.getOrElse {
                Log.error("[DiscordPresence] Social SDK connect failed: ${it.javaClass.simpleName}")
                false
            }
        }

        fun update(customisation: PresenceCustomization, title: String, startedAt: Long) {
            runCatching {
                val target = client ?: return
                val activity = activityClass?.getDeclaredConstructor()?.newInstance() ?: return

                customisation.name.ifBlank { title }.let { name ->
                    if (name.isNotEmpty()) {
                        invoke(activity, arrayOf("setName"), arrayOf(name))
                    }
                }
                if (customisation.details.isNotBlank()) {
                    invoke(activity, arrayOf("setDetails"), arrayOf(customisation.details))
                }
                if (customisation.state.isNotBlank()) {
                    invoke(activity, arrayOf("setState"), arrayOf(customisation.state))
                }
                if (customisation.showTimestamps) {
                    invoke(activity, arrayOf("setStartTimestamp"), arrayOf(startedAt))
                }
                if (customisation.largeImage.isNotBlank()) {
                    invoke(activity, arrayOf("setLargeImage"), arrayOf(customisation.largeImage))
                }
                if (customisation.smallImage.isNotBlank()) {
                    invoke(activity, arrayOf("setSmallImage"), arrayOf(customisation.smallImage))
                }

                // One-argument form first; the two-argument form needs a callback proxy whose
                // interface cannot be named without the AAR, so it is left to the SDK's default.
                val updated = invoke(target, arrayOf("updateRichPresence"), arrayOf(activity))
                if (updated == null) {
                    Log.warning("[DiscordPresence] Social SDK updateRichPresence not found")
                }
            }.onFailure {
                Log.error("[DiscordPresence] Social SDK update failed: ${it.javaClass.simpleName}")
            }
        }

        fun disconnect() {
            runCatching { invoke(client, arrayOf("disconnect", "clearRichPresence"), emptyArray()) }
            client = null
        }

        private fun invoke(target: Any?, names: Array<String>, args: Array<out Any?>): Any? {
            if (target == null) {
                return null
            }
            val wanted = names.map { it.lowercase().replace("_", "") }
            val method = target.javaClass.methods.firstOrNull { candidate ->
                candidate.parameterCount == args.size &&
                    wanted.contains(candidate.name.lowercase().replace("_", ""))
            } ?: return null
            return method.invoke(target, *args)
        }

        private companion object {
            const val CLIENT_CLASS = "discordpp.Client"
            const val ACTIVITY_CLASS = "discordpp.Activity"
        }
    }

    private fun refreshSocialSdkPresence() {
        val title = desiredTitle ?: return
        socialSdk.update(customisation, title, startedAt)
    }

    // ---- Gateway ----------------------------------------------------------

    // Not 'inner': this object is a singleton, so there is no outer instance to capture. Every
    // state access is qualified back through DiscordPresence instead.
    private class GatewayListener(
        private val token: String,
        private val oauth: Boolean
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
                    DiscordPresence.sendIdentify(webSocket, token, oauth)
                }

                OP_HEARTBEAT_ACK -> Unit

                OP_DISPATCH -> {
                    if (message.optString("t") == "READY") {
                        DiscordPresence.ready = true
                        DiscordPresence.reconnectAttempt = 0
                        DiscordPresence.sendPresenceUpdate(webSocket)
                        DiscordPresence.publish(
                            when {
                                oauth -> {
                                    val name = DiscordOAuth.account()?.displayName
                                        ?.takeIf { it.isNotBlank() }
                                    if (name != null) {
                                        "Connected as $name"
                                    } else {
                                        "Connected as your Discord account"
                                    }
                                }
                                DiscordPresence.mode == Mode.BOT_TOKEN ->
                                    "Connected as bot (visible only in its own servers)"
                                else -> "Connected as your account (self-bot)"
                            }
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

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(DiscordPresence.lock) { DiscordPresence.socket = null }
            DiscordPresence.ready = false
            DiscordPresence.stopHeartbeat()
            if (DiscordPresence.closedByUs || DiscordPresence.desiredTitle == null) {
                return
            }
            DiscordPresence.publish("Gateway error: ${t.message}")
            DiscordPresence.scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(DiscordPresence.lock) { DiscordPresence.socket = null }
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

    private fun sendIdentify(webSocket: WebSocket, token: String, oauth: Boolean) {
        val data = JSONObject()

        if (oauth) {
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
        } else {
            // Bot and account tokens both go in raw here; the "Bot " prefix belongs to the REST
            // API only.
            data.put("token", token)
            data.put(
                "properties",
                JSONObject()
                    .put("os", "Android")
                    .put("browser", "suyu")
                    .put("device", "suyu")
            )
            data.put("presence", JSONObject().put("status", "online").put("afk", false))

            if (mode == Mode.ACCOUNT_TOKEN) {
                data.put("capabilities", ACCOUNT_CAPABILITIES)
                data.put("compress", false)
            } else {
                // Intents stay at zero: a bot needs none to set its own presence, and asking for
                // privileged intents it has not enabled would close the connection.
                data.put("intents", 0)
            }
        }

        webSocket.send(JSONObject().put("op", OP_IDENTIFY).put("d", data).toString())
    }

    private fun sendPresenceUpdate(webSocket: WebSocket) {
        val title = desiredTitle ?: return
        val activities = JSONArray()
        if (title.isNotEmpty()) {
            val activity = PresenceCustomization.buildActivity(customisation, title, startedAt)

            if (mode == Mode.OAUTH) {
                val token = oauthToken ?: return
                // With the authorisation comes the application context: the activity carries the
                // app id, large/small images can be any public URL (registered on the fly), and
                // minified button placement is the canonical label + metadata form.
                activity.put(
                    "application_id",
                    StringSetting.DISCORD_APPLICATION_ID.getString().trim().ifEmpty {
                        DEFAULT_APPLICATION_ID
                    }
                )
                var large = customisation.largeImage
                var small = customisation.smallImage
                if (large.isBlank()) {
                    // Zero-config art: the URL is built locally from the title id and fetched by
                    // Discord's servers during registration, so the user never picks or pastes
                    // anything. A missing cover simply means no image, never a failed presence.
                    sessionTitleId?.takeIf { it.length >= 14 }?.let { id ->
                        large = "https://tinfoil.media/ti/${id.lowercase()}/"
                    }
                }
                val resolved = DiscordAssetRegistrar.resolveImages(token, large, small)
                val assets = JSONObject()
                if (resolved.first.isNotEmpty()) {
                    assets.put("large_image", resolved.first.take(256))
                }
                if (resolved.second.isNotEmpty()) {
                    assets.put("small_image", resolved.second.take(256))
                }
                if (assets.length() > 0) {
                    activity.put("assets", assets)
                }
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

    private fun startHeartbeat(webSocket: WebSocket, intervalMs: Long) {
        stopHeartbeat()
        if (intervalMs <= 0L) {
            return
        }
        // The first beat must land inside the interval, per the gateway protocol.
        val initialDelay = (intervalMs * Math.random()).toLong()
        heartbeatFuture = scheduler.scheduleAtFixedRate(
            {
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
            { if (desiredTitle != null) connect(desiredTitle!!) },
            delaySeconds,
            TimeUnit.SECONDS
        )
    }
}
