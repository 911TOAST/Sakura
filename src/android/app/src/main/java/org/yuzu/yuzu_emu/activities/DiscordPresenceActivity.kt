// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.activities

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.RadioGroup
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
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.features.settings.model.IntSetting
import org.yuzu.yuzu_emu.features.settings.model.StringSetting
import org.yuzu.yuzu_emu.network.DiscordAuthCoordinator
import org.yuzu.yuzu_emu.network.DiscordOAuth
import org.yuzu.yuzu_emu.network.DiscordPresence
import org.yuzu.yuzu_emu.network.PresenceCustomization
import org.yuzu.yuzu_emu.utils.NativeConfig

/**
 * Discord Rich Presence, built around "sign in once, never touch it again".
 *
 * The top of the page is the whole setup: the switch and the sign-in card. Every edit anywhere on
 * the page is saved automatically a moment after typing stops and reconnects, so there is no save
 * button to forget. Customisation (text, images, buttons) is collapsed under "Customise";
 * transports and credentials — only relevant when sign-in is not an option — are collapsed under
 * "Advanced", so the default view never asks a question the user does not have to answer.
 */
class DiscordPresenceActivity : AppCompatActivity() {

    private lateinit var switchEnable: MaterialSwitch
    private lateinit var groupMode: RadioGroup
    private lateinit var buttonSignIn: MaterialButton
    private lateinit var cardSignedIn: MaterialCardView
    private lateinit var buttonSignOut: MaterialButton
    private lateinit var textSignedIn: TextView
    private lateinit var imagePreviewLarge: ShapeableImageView
    private lateinit var imagePreviewSmall: ShapeableImageView
    private lateinit var headerCustomise: TextView
    private lateinit var containerCustomise: View
    private lateinit var headerAdvanced: TextView
    private lateinit var containerAdvanced: View
    private lateinit var textModeSummary: TextView
    private lateinit var textSocialSdkMissing: TextView
    private lateinit var textWarning: TextView
    private lateinit var layoutApplicationId: TextInputLayout
    private lateinit var inputApplicationId: TextInputEditText
    private lateinit var layoutToken: TextInputLayout
    private lateinit var inputToken: TextInputEditText
    private lateinit var inputName: TextInputEditText
    private lateinit var inputDetails: TextInputEditText
    private lateinit var inputState: TextInputEditText
    private lateinit var inputLargeImage: TextInputEditText
    private lateinit var inputSmallImage: TextInputEditText
    private lateinit var inputButton1Label: TextInputEditText
    private lateinit var inputButton1Url: TextInputEditText
    private lateinit var inputButton2Label: TextInputEditText
    private lateinit var inputButton2Url: TextInputEditText
    private lateinit var switchTimestamps: MaterialSwitch
    private lateinit var textPreviewHeadline: TextView
    private lateinit var textPreviewDetails: TextView
    private lateinit var textPreviewState: TextView
    private lateinit var textStatus: TextView

    private val statusListener: (String) -> Unit = { message ->
        runOnUiThread { textStatus.text = message }
    }

    /** The authorisation currently in flight in the browser, or null between attempts. */
    private var authorizationSession: DiscordOAuth.Session? = null

    /** True while the views are being filled from settings, so loading does not auto-save. */
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
        (findViewById<MaterialToolbar>(R.id.toolbar)).setNavigationOnClickListener { finish() }

        loadFromSettings()
        wireModeSelection()
        wirePreview()
        wireSectionToggles()
        wireAutoSave()
        refreshSignInCard()

        buttonSignIn.setOnClickListener { launchSignIn() }
        buttonSignOut.setOnClickListener {
            DiscordPresence.onSignedOut()
            NativeConfig.saveGlobalConfig()
            refreshSignInCard()
        }
    }

    /** Collapsed sections keep the default view to two controls; headers are the toggles. */
    private fun wireSectionToggles() {
        val toggle = { expanded: Boolean, container: View ->
            container.visibility = if (expanded) View.VISIBLE else View.GONE
        }
        headerCustomise.setOnClickListener {
            toggle(containerCustomise.visibility != View.VISIBLE, containerCustomise)
        }
        headerAdvanced.setOnClickListener {
            toggle(containerAdvanced.visibility != View.VISIBLE, containerAdvanced)
        }
    }

    /**
     * Every edit saves itself. A short debounce groups a paste or a burst of keystrokes into one
     * write and one reconnect; the job dies with the lifecycle, so no save outlives the page.
     */
    private fun wireAutoSave() {
        switchEnable.setOnCheckedChangeListener { _, _ -> scheduleSave() }
        switchTimestamps.setOnCheckedChangeListener { _, _ -> scheduleSave() }
    }

    private fun scheduleSave() {
        if (restoringViews) {
            return
        }
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(800)
            saveAndReconnect()
        }
    }

    private fun saveAndReconnect() {
        save()
        // Reconnecting reads the settings back, so an edit takes effect now instead of at the
        // next game launch; with no game running it is a no-op that just reports state.
        DiscordPresence.reconnectWithCurrentSettings()
    }

    override fun onStart() {
        super.onStart()
        DiscordPresence.statusListener = statusListener
        textStatus.text = DiscordPresence.status().ifBlank {
            getString(R.string.discord_status_idle)
        }
        refreshSignInCard()

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
                    // Signing in IS choosing the transport and enabling presence: no second
                    // switch to find. onSignedIn sets the mode, saves the config and connects.
                    switchEnable.isChecked = true
                    DiscordPresence.onSignedIn()
                    NativeConfig.saveGlobalConfig()
                    groupMode.check(R.id.mode_oauth)
                    refreshSignInCard()
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
        groupMode = findViewById(R.id.group_mode)
        buttonSignIn = findViewById(R.id.button_sign_in)
        cardSignedIn = findViewById(R.id.card_signed_in)
        buttonSignOut = findViewById(R.id.button_sign_out)
        textSignedIn = findViewById(R.id.text_signed_in)
        imagePreviewLarge = findViewById(R.id.image_preview_large)
        imagePreviewSmall = findViewById(R.id.image_preview_small)
        headerCustomise = findViewById(R.id.header_customise)
        containerCustomise = findViewById(R.id.container_customise)
        headerAdvanced = findViewById(R.id.header_advanced)
        containerAdvanced = findViewById(R.id.container_advanced)
        textModeSummary = findViewById(R.id.text_mode_summary)
        textSocialSdkMissing = findViewById(R.id.text_social_sdk_missing)
        textWarning = findViewById(R.id.text_warning)
        layoutApplicationId = findViewById(R.id.layout_application_id)
        inputApplicationId = findViewById(R.id.input_application_id)
        layoutToken = findViewById(R.id.layout_token)
        inputToken = findViewById(R.id.input_token)
        inputName = findViewById(R.id.input_name)
        inputDetails = findViewById(R.id.input_details)
        inputState = findViewById(R.id.input_state)
        inputLargeImage = findViewById(R.id.input_large_image)
        inputSmallImage = findViewById(R.id.input_small_image)
        inputButton1Label = findViewById(R.id.input_button1_label)
        inputButton1Url = findViewById(R.id.input_button1_url)
        inputButton2Label = findViewById(R.id.input_button2_label)
        inputButton2Url = findViewById(R.id.input_button2_url)
        switchTimestamps = findViewById(R.id.switch_timestamps)
        textPreviewHeadline = findViewById(R.id.text_preview_headline)
        textPreviewDetails = findViewById(R.id.text_preview_details)
        textPreviewState = findViewById(R.id.text_preview_state)
        textStatus = findViewById(R.id.text_status)
    }

    private fun loadFromSettings() {
        restoringViews = true
        switchEnable.isChecked = BooleanSetting.DISCORD_RICH_PRESENCE.getBoolean()

        when (DiscordPresence.Mode.fromStored(IntSetting.DISCORD_PRESENCE_MODE.getInt())) {
            DiscordPresence.Mode.OAUTH -> R.id.mode_oauth
            DiscordPresence.Mode.SOCIAL_SDK -> R.id.mode_social
            DiscordPresence.Mode.ACCOUNT_TOKEN -> R.id.mode_account
            DiscordPresence.Mode.BOT_TOKEN -> R.id.mode_bot
        }.let { groupMode.check(it) }

        inputApplicationId.setText(StringSetting.DISCORD_APPLICATION_ID.getString())
        inputToken.setText(StringSetting.DISCORD_TOKEN.getString())

        val customisation = PresenceCustomization.load()
        inputName.setText(customisation.name)
        inputDetails.setText(customisation.details)
        inputState.setText(customisation.state)
        inputLargeImage.setText(customisation.largeImage)
        inputSmallImage.setText(customisation.smallImage)
        inputButton1Label.setText(customisation.button1Label)
        inputButton1Url.setText(customisation.button1Url)
        inputButton2Label.setText(customisation.button2Label)
        inputButton2Url.setText(customisation.button2Url)
        switchTimestamps.isChecked = customisation.showTimestamps

        applyModeVisibility(selectedMode())
        restoringViews = false
    }

    private fun selectedMode(): DiscordPresence.Mode = when (groupMode.checkedRadioButtonId) {
        R.id.mode_oauth -> DiscordPresence.Mode.OAUTH
        R.id.mode_account -> DiscordPresence.Mode.ACCOUNT_TOKEN
        R.id.mode_bot -> DiscordPresence.Mode.BOT_TOKEN
        else -> DiscordPresence.Mode.SOCIAL_SDK
    }

    private fun wireModeSelection() {
        groupMode.setOnCheckedChangeListener { _, _ ->
            applyModeVisibility(selectedMode())
            scheduleSave()
        }
    }

    /**
     * Shows only the fields the selected transport actually uses.
     *
     * Leaving the Application ID visible in token mode invited the paste that then did nothing,
     * which is exactly the confusion this page exists to remove.
     */
    private fun applyModeVisibility(mode: DiscordPresence.Mode) {
        val isOAuth = mode == DiscordPresence.Mode.OAUTH
        val isSocial = mode == DiscordPresence.Mode.SOCIAL_SDK
        val isAccount = mode == DiscordPresence.Mode.ACCOUNT_TOKEN

        // OAuth has no credential field at all: the browser authorisation IS the credential.
        layoutApplicationId.visibility = if (isSocial) View.VISIBLE else View.GONE
        layoutToken.visibility = if (isSocial || isOAuth) View.GONE else View.VISIBLE

        textModeSummary.setText(
            when (mode) {
                DiscordPresence.Mode.OAUTH -> R.string.discord_mode_oauth_summary
                DiscordPresence.Mode.SOCIAL_SDK -> R.string.discord_mode_social_sdk_summary
                DiscordPresence.Mode.ACCOUNT_TOKEN -> R.string.discord_mode_account_summary
                DiscordPresence.Mode.BOT_TOKEN -> R.string.discord_mode_bot_summary
            }
        )

        // The warning belongs to account-token mode only: bot mode is lawful and the OAuth and
        // Social SDK paths involve no credential at all.
        textWarning.visibility = if (isAccount) View.VISIBLE else View.GONE
        textSocialSdkMissing.visibility =
            if (isSocial && !DiscordPresence.isSocialSdkAvailable()) View.VISIBLE else View.GONE
    }

    private fun wirePreview() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updatePreview()
                scheduleSave()
            }
        }
        listOf(
            inputName,
            inputDetails,
            inputState,
            inputLargeImage,
            inputSmallImage,
            inputApplicationId,
            inputToken,
            inputButton1Label,
            inputButton1Url,
            inputButton2Label,
            inputButton2Url
        ).forEach { it.addTextChangedListener(watcher) }
        updatePreview()
    }

    private fun updatePreview() {
        val headline = inputName.text?.toString().orEmpty()
            .ifBlank { getString(R.string.discord_preview_playing) }
        val details = inputDetails.text?.toString().orEmpty()
        val state = inputState.text?.toString().orEmpty()

        textPreviewHeadline.text = headline.take(128)
        textPreviewDetails.text = details.take(128)
        textPreviewDetails.visibility = if (details.isBlank()) View.GONE else View.VISIBLE
        textPreviewState.text = state.take(128)
        textPreviewState.visibility = if (state.isBlank()) View.GONE else View.VISIBLE
        updateImagePreview(imagePreviewLarge, inputLargeImage.text?.toString().orEmpty())
        updateImagePreview(imagePreviewSmall, inputSmallImage.text?.toString().orEmpty())
    }

    /** Origin of a http(s) URL loads in place; anything else leaves the preview empty. */
    private fun updateImagePreview(view: ShapeableImageView, value: String) {
        if (value.startsWith("https://") || value.startsWith("http://")) {
            view.load(value) { crossfade(true) }
        } else {
            view.setImageDrawable(null)
        }
    }

    private fun save() {
        BooleanSetting.DISCORD_RICH_PRESENCE.setBoolean(switchEnable.isChecked)
        IntSetting.DISCORD_PRESENCE_MODE.setInt(selectedMode().stored)
        StringSetting.DISCORD_APPLICATION_ID.setString(
            inputApplicationId.text?.toString().orEmpty().trim()
        )
        // Stored trimmed so a trailing newline from a paste cannot fail the shape check.
        StringSetting.DISCORD_TOKEN.setString(inputToken.text?.toString().orEmpty().trim())

        PresenceCustomization.save(
            PresenceCustomization(
                name = inputName.text?.toString().orEmpty(),
                details = inputDetails.text?.toString().orEmpty(),
                state = inputState.text?.toString().orEmpty(),
                largeImage = inputLargeImage.text?.toString().orEmpty().trim(),
                smallImage = inputSmallImage.text?.toString().orEmpty().trim(),
                button1Label = inputButton1Label.text?.toString().orEmpty(),
                button1Url = inputButton1Url.text?.toString().orEmpty().trim(),
                button2Label = inputButton2Label.text?.toString().orEmpty(),
                button2Url = inputButton2Url.text?.toString().orEmpty().trim(),
                showTimestamps = switchTimestamps.isChecked
            )
        )

        // Nothing survives a restart without this; the settings registry only owns the values.
        NativeConfig.saveGlobalConfig()
    }
}
