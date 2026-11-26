// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.network

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.yuzu.yuzu_emu.features.settings.model.StringSetting
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Hands the browser redirect from [activities.DiscordOAuthCallbackActivity] to whichever page is
 * waiting for it. One replay slot: the callback can beat the page into shape when the sign-in
 * happens while the activity is still starting up.
 */
object DiscordAuthCoordinator {
    val redirects = MutableSharedFlow<Uri>(replay = 1, extraBufferCapacity = 1)

    fun emit(uri: Uri) {
        redirects.tryEmit(uri)
    }
}

/**
 * "Sign in with Discord", the one-tap path to Rich Presence.
 *
 * This is what ArchiveTune does and why its setup feels effortless: the browser opens Discord's
 * own OAuth approval page, the user taps Authorise, and the app comes back with a short-lived
 * access token via a custom-scheme redirect. Nothing is ever pasted by hand.
 *
 * The scopes asked for are `openid identify sdk.social_layer_presence`. The last one is the
 * sanctioned way to publish a presence on the user's own profile; the access token then
 * authenticates the gateway as "Bearer ..." (see [DiscordPresence.OAUTH]). Because the flow is
 * an OAuth authorisation the user explicitly granted, it does not depend on self-bot semantics.
 *
 * The exchange is a public-client PKCE flow: no client secret exists or is needed.
 */
object DiscordOAuth {
    private const val AUTHORIZATION_ENDPOINT = "https://discord.com/oauth2/authorize"
    private const val TOKEN_ENDPOINT = "https://discord.com/api/oauth2/token"
    private const val CURRENT_USER_ENDPOINT = "https://discord.com/api/v10/users/@me"

    /**
     * The redirect must follow Discord's mobile-app convention, scheme `discord-<application id>`,
     * because that is the shape of custom scheme the developer portal accepts and the browser
     * dispatches back. The callback activity's intent-filter carries the default application id.
     */
    const val REDIRECT_PATH = "/authorize/callback"

    fun redirectScheme(): String = "discord-" + applicationId()

    fun redirectUri(): String = "${redirectScheme()}:$REDIRECT_PATH"

    private const val SCOPES = "openid identify sdk.social_layer_presence"

    /** Refresh before expiry rather than failing mid-session. */
    private const val EXPIRY_SKEW_MS = 60_000L

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    data class Session(
        val state: String,
        val codeVerifier: String,
        val authorizationUri: Uri
    )

    data class Account(
        val id: String,
        val username: String,
        val displayName: String
    )

    data class AuthSession(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtMillis: Long,
        val account: Account?
    )

    // ---- stored session ----------------------------------------------------

    private fun readStored(): AuthSession? {
        val raw = StringSetting.DISCORD_OAUTH_JSON.getString()
        if (raw.isBlank()) {
            return null
        }
        return runCatching {
            val json = JSONObject(raw)
            val token = json.optString("access_token", "")
            if (token.isBlank()) {
                return null
            }
            AuthSession(
                accessToken = token,
                refreshToken = json.optString("refresh_token", ""),
                expiresAtMillis = json.optLong("expires_at", 0L),
                account = Account(
                    id = json.optString("account_id", ""),
                    username = json.optString("account_username", ""),
                    displayName = json.optString("account_display_name", "")
                )
            )
        }.getOrNull()
    }

    fun saveSession(session: AuthSession) {
        StringSetting.DISCORD_OAUTH_JSON.setString(
            JSONObject()
                .put("access_token", session.accessToken)
                .put("refresh_token", session.refreshToken)
                .put("expires_at", session.expiresAtMillis)
                .put("account_id", session.account?.id ?: "")
                .put("account_username", session.account?.username ?: "")
                .put("account_display_name", session.account?.displayName ?: "")
                .toString()
        )
    }

    fun clearSession() {
        StringSetting.DISCORD_OAUTH_JSON.setString("")
    }

    // readStored() returns null whenever there is no document or the token inside is blank, so a
    // session "exists" exactly when it parses out with a usable token.
    fun isSignedIn(): Boolean = readStored() != null

    fun account(): Account? = readStored()?.account

    fun isExpired(): Boolean {
        val session = readStored() ?: return true
        return session.expiresAtMillis in 1 until System.currentTimeMillis() + EXPIRY_SKEW_MS
    }

    /** Never logs the token: the value is discarded after the check. */
    private fun tokenShapeOk(token: String): Boolean = token.isNotBlank()

    /**
     * A usable access token, refreshing first when the stored one is close to expiry.
     * Null means "not signed in" or "refresh failed", which the caller reports as
     * "sign in again" because both look identical at connect time.
     */
    fun validAccessToken(): String? {
        val session = readStored() ?: return null
        if (!tokenShapeOk(session.accessToken)) {
            return null
        }
        val expired = session.expiresAtMillis == 0L ||
            System.currentTimeMillis() + EXPIRY_SKEW_MS >= session.expiresAtMillis
        if (!expired) {
            return session.accessToken
        }
        if (session.refreshToken.isBlank()) {
            return null
        }
        return runCatching { refreshTokens(session.refreshToken) }
            .getOrNull()
            ?.also { saveSession(it) }
            ?.accessToken
    }

    // ---- authorisation flow ------------------------------------------------

    fun createAuthorizationSession(): Session {
        val state = randomUrlSafe(32)
        val verifier = randomUrlSafe(64)
        val challenge = sha256Base64Url(verifier)

        val uri = Uri.parse(AUTHORIZATION_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", applicationId())
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri())
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        return Session(state, verifier, uri)
    }

    /**
     * Turns a browser redirect back into tokens. Every check here is a check against
     * CSRF or a forged redirect, so a mismatch fails closed.
     */
    fun completeAuthorization(session: Session, redirect: Uri): Result<AuthSession> {
        return runCatching {
            check(redirect.scheme == redirectScheme()) { "Unexpected redirect scheme" }
            check(redirect.path == REDIRECT_PATH) { "Unexpected redirect target" }
            check(redirect.getQueryParameter("state") == session.state) {
                "Authorisation state mismatch"
            }
            redirect.getQueryParameter("error")?.let { error ->
                if (error == "invalid_scope") {
                    // Discord only grants sdk.social_layer_presence to applications that have
                    // Social SDK access enabled in the developer portal, so this error means the
                    // application needs that opt-in — not that the user did anything wrong.
                    throw IllegalStateException(
                        "Discord declined this application's sign-in: its Social SDK access is " +
                            "not enabled. Enable the Social SDK for the application in the " +
                            "Discord developer portal, then sign in again."
                    )
                }
                throw IllegalStateException(redirect.getQueryParameter("error_description") ?: error)
            }
            val code = checkNotNull(redirect.getQueryParameter("code")) {
                "Authorisation code is missing"
            }
            val token = exchangeCode(code, session.codeVerifier)
            fetchAccount(token.accessToken).getOrNull().let { token.copy(account = it) }
        }
    }

    /** True when the URI looks like our redirect, so the callback activity can ignore the rest. */
    fun isRedirectUri(uri: Uri?): Boolean =
        uri != null && uri.scheme == redirectScheme() && uri.path == REDIRECT_PATH

    // ---- HTTP --------------------------------------------------------------

    /**
     * The application ID is pinned. Older builds let it be typed into a settings field, and any
     * stale value left there would silently sign in against the wrong Discord application, whose
     * scopes are rejected with "invalid OAuth2 scope". There is nothing to configure any more.
     */
    private fun applicationId(): String = DiscordPresence.DEFAULT_APPLICATION_ID

    /** No client secret: this is a public client identified by its ID, held in check by PKCE. */
    private fun exchangeCode(code: String, verifier: String): AuthSession =
        tokenRequest(
            FormBody.Builder()
                .add("client_id", applicationId())
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", redirectUri())
                .add("code_verifier", verifier)
                .build()
        )

    private fun refreshTokens(refreshToken: String): AuthSession =
        tokenRequest(
            FormBody.Builder()
                .add("client_id", applicationId())
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build()
        )

    private fun tokenRequest(body: FormBody): AuthSession {
        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(body)
            .build()
        val response = httpClient.newCall(request).execute()
        response.use {
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Token endpoint returned HTTP ${response.code}")
            }
            val json = JSONObject(text)
            val accessToken = json.optString("access_token", "")
            if (accessToken.isBlank()) {
                throw IllegalStateException("Token endpoint returned no access token")
            }
            val expiresIn = json.optLong("expires_in", 0L)
            val refreshToken = json.optString("refresh_token", "")
            return AuthSession(
                accessToken = accessToken,
                // Discord does not always rotate; keep the old value when none comes back.
                refreshToken = refreshToken.ifBlank { currentRefreshToken() },
                expiresAtMillis = if (expiresIn > 0L) {
                    System.currentTimeMillis() + expiresIn * 1000L
                } else {
                    0L
                },
                account = null
            )
        }
    }

    private fun currentRefreshToken(): String = readStored()?.refreshToken.orEmpty()

    private fun fetchAccount(accessToken: String): Result<Account> = runCatching {
        val request = Request.Builder()
            .url(CURRENT_USER_ENDPOINT)
            .header("Authorization", "Bearer $accessToken")
            .build()
        val response = httpClient.newCall(request).execute()
        response.use {
            if (!response.isSuccessful) {
                throw IllegalStateException("users/@me returned HTTP ${response.code}")
            }
            val json = JSONObject(response.body?.string().orEmpty())
            val id = json.optString("id", "")
            val username = json.optString("username", id)
            val displayName = json.optString("global_name", username).ifBlank { username }
            Account(id, username, displayName)
        }
    }

    // ---- PKCE helpers ------------------------------------------------------

    private fun randomUrlSafe(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun sha256Base64Url(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
