// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.network.DiscordAuthCoordinator
import org.yuzu.yuzu_emu.network.DiscordOAuth
import org.yuzu.yuzu_emu.network.DiscordPresence
import org.yuzu.yuzu_emu.utils.NativeConfig

/**
 * Discord Rich Presence, built around "sign in once, never touch it again".
 *
 * The whole page is a switch, a sign-in card and a live preview of what the presence says right
 * now. There is nothing to configure: the presence is always the running game's title and cover
 * art, published by the signed-in account. The switch saves itself; there is no save button.
 */
class DiscordPresenceActivity : AppCompatActivity() {

    private lateinit var switchEnable: MaterialSwitch
    private lateinit var buttonSignIn: MaterialButton
    private lateinit var cardSignedIn: MaterialCardView
    private lateinit var buttonSignOut: MaterialButton
    private lateinit var textSignedIn: TextView
    private lateinit var imagePreviewLarge: ShapeableImageView
    private lateinit var textPreviewHeadline: TextView
    private lateinit var textPreviewState: TextView
    private lateinit var textStatus: TextView

    private val statusListener: (String) -> Unit = { message ->
        runOnUiThread { textStatus.text = message }
    }

    /** The authorisation currently in flight in the browser, or null between attempts. */
    private var authorizationSession: DiscordOAuth.Session? = null

    /** True while the switch is being set from settings, so loading does not auto-save. */
    private var restoringViews = true

    private var saveJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_discord_presence)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            view.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        bindViews()
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        loadFromSettings()
        refreshSignInCard()
        refreshPreview()

        buttonSignIn.setOnClickListener { launchSignIn() }
        buttonSignOut.setOnClickListener {
            DiscordPresence.onSignedOut()
            NativeConfig.saveGlobalConfig()
            refreshSignInCard()
            refreshPreview()
        }

        // The switch saves itself the same way: a short debounce, then the change applies and
        // the presence follows. The job dies with the lifecycle, so no save outlives the page.
        switchEnable.setOnCheckedChangeListener { _, _ -> scheduleSave() }
    }

    private fun scheduleSave() {
        if (restoringViews) {
            return
        }
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(300)
            save()
            // Reconnecting reads the settings back, so the switch takes effect now instead of at
            // the next game launch; with no game running it is a no-op that just reports state.
            DiscordPresence.reconnectWithCurrentSettings()
        }
    }

    override fun onStart() {
        super.onStart()
        DiscordPresence.statusListener = statusListener
        textStatus.text = DiscordPresence.status().ifBlank {
            getString(R.string.discord_status_idle)
        }
        refreshSignInCard()
        refreshPreview()

        // The browser comes back through DiscordOAuthCallbackActivity, which emits its redirect
        // here; completing it does a network call, so that part leaves the main thread.
        lifecycleScope.launch {
            DiscordAuthCoordinator.redirects.collect { redirect ->
                val session = authorizationSession ?: return@collect
                if (redirect.getQueryParameter("state") != session.state) {
                    return@collect
                }
                val result = withContext(Dispatchers.IO) {
                    DiscordOAuth.completeAuthorization(session, redirect)
                }
                result.onSuccess { authSession ->
                    authorizationSession = null
                    DiscordOAuth.saveSession(authSession)
                    // Signing in IS enabling presence: no second switch to find. onSignedIn
                    // saves the config and connects.
                    switchEnable.isChecked = true
                    DiscordPresence.onSignedIn()
                    NativeConfig.saveGlobalConfig()
                    refreshSignInCard()
                    refreshPreview()
                    textStatus.text = authSession.account?.displayName?.takeIf { it.isNotBlank() }
                        ?.let { getString(R.string.discord_signed_in_as, it) }
                        ?: getString(R.string.discord_authorization_success)
                }.onFailure { error ->
                    authorizationSession = null
                    textStatus.text = error.message
                        ?: getString(R.string.discord_authorization_failed)
                }
            }
        }
    }

    private fun launchSignIn() {
        val session = DiscordOAuth.createAuthorizationSession()
        authorizationSession = session
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, session.authorizationUri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
            )
        }.onFailure {
            authorizationSession = null
            textStatus.text = it.message ?: getString(R.string.discord_authorization_failed)
        }
    }

    /** Shows exactly one of: the sign-in button, or who is signed in with a way out. */
    private fun refreshSignInCard() {
        val account = DiscordOAuth.account()
        val signedIn = DiscordOAuth.isSignedIn()
        buttonSignIn.visibility = if (signedIn) View.GONE else View.VISIBLE
        cardSignedIn.visibility = if (signedIn) View.VISIBLE else View.GONE
        if (signedIn) {
            textSignedIn.text = account?.displayName?.takeIf { it.isNotBlank() }
                ?.let { getString(R.string.discord_signed_in_as, it) }
                ?: getString(R.string.discord_signed_in)
        }
    }

    /**
     * Mirrors what Discord is showing, or would show: running game's title and cover art beside
     * the signed-in account's name. Missing pieces degrade to placeholders rather than hiding —
     * a blank card reads as breakage, a placeholder reads as "waiting for a game".
     */
    private fun refreshPreview() {
        val preview = DiscordPresence.livePreview()
        textPreviewHeadline.text = preview.title
            ?: getString(R.string.discord_preview_no_game)
        textPreviewState.text = preview.accountName
            ?: getString(R.string.discord_preview_signed_out)

        val artwork = preview.titleId?.takeIf { it.length >= 14 }
            ?.let { "https://tinfoil.media/ti/${it.lowercase()}/" }
        if (artwork != null) {
            imagePreviewLarge.load(artwork) { crossfade(true) }
        } else {
            imagePreviewLarge.setImageDrawable(null)
        }
    }

    override fun onStop() {
        // The listener holds this activity; leaving it registered would leak the whole view tree
        // for as long as presence outlives the page.
        if (DiscordPresence.statusListener === statusListener) {
            DiscordPresence.statusListener = null
        }
        super.onStop()
    }

    private fun bindViews() {
        switchEnable = findViewById(R.id.switch_enable)
        buttonSignIn = findViewById(R.id.button_sign_in)
        cardSignedIn = findViewById(R.id.card_signed_in)
        buttonSignOut = findViewById(R.id.button_sign_out)
        textSignedIn = findViewById(R.id.text_signed_in)
        imagePreviewLarge = findViewById(R.id.image_preview_large)
        textPreviewHeadline = findViewById(R.id.text_preview_headline)
        textPreviewState = findViewById(R.id.text_preview_state)
        textStatus = findViewById(R.id.text_status)
    }

    private fun loadFromSettings() {
        restoringViews = true
        switchEnable.isChecked = BooleanSetting.DISCORD_RICH_PRESENCE.getBoolean()
        restoringViews = false
    }

    private fun save() {
        BooleanSetting.DISCORD_RICH_PRESENCE.setBoolean(switchEnable.isChecked)
        // Nothing survives a restart without this; the settings registry only owns the values.
        NativeConfig.saveGlobalConfig()
    }
}
