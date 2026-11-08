// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.activities

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.features.settings.model.IntSetting
import org.yuzu.yuzu_emu.features.settings.model.StringSetting
import org.yuzu.yuzu_emu.network.DiscordPresence
import org.yuzu.yuzu_emu.network.PresenceCustomization
import org.yuzu.yuzu_emu.utils.NativeConfig

/**
 * Full-page editor for Discord Rich Presence.
 *
 * The page owns every Discord setting, so the settings list only has to carry one entry that
 * opens it. Fields are read from settings on create and written back on save; nothing is applied
 * live while typing, because half-typed credentials would otherwise reach the gateway on every
 * keystroke.
 */
class DiscordPresenceActivity : AppCompatActivity() {

    private lateinit var switchEnable: MaterialSwitch
    private lateinit var groupMode: RadioGroup
    private lateinit var modeSocial: View
    private lateinit var modeAccount: View
    private lateinit var modeBot: View
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_discord_presence)

        bindViews()
        (findViewById<MaterialToolbar>(R.id.toolbar)).setNavigationOnClickListener { finish() }

        loadFromSettings()
        wireModeSelection()
        wirePreview()

        findViewById<MaterialButton>(R.id.button_save).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.button_apply).setOnClickListener {
            save()
            // Reconnecting reads the settings back, so the transport change takes effect now
            // instead of at the next game launch.
            DiscordPresence.reconnectWithCurrentSettings()
        }
        findViewById<MaterialButton>(R.id.button_disconnect).setOnClickListener {
            DiscordPresence.disconnect()
        }
    }

    override fun onStart() {
        super.onStart()
        DiscordPresence.statusListener = statusListener
        textStatus.text = DiscordPresence.status().ifBlank {
            getString(R.string.discord_status_idle)
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
        modeSocial = findViewById(R.id.mode_social)
        modeAccount = findViewById(R.id.mode_account)
        modeBot = findViewById(R.id.mode_bot)
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
        switchEnable.isChecked = BooleanSetting.DISCORD_RICH_PRESENCE.getBoolean()

        when (DiscordPresence.Mode.fromStored(IntSetting.DISCORD_PRESENCE_MODE.getInt())) {
            DiscordPresence.Mode.SOCIAL_SDK -> modeSocial.id
            DiscordPresence.Mode.ACCOUNT_TOKEN -> modeAccount.id
            DiscordPresence.Mode.BOT_TOKEN -> modeBot.id
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
    }

    private fun selectedMode(): DiscordPresence.Mode = when (groupMode.checkedRadioButtonId) {
        R.id.mode_account -> DiscordPresence.Mode.ACCOUNT_TOKEN
        R.id.mode_bot -> DiscordPresence.Mode.BOT_TOKEN
        else -> DiscordPresence.Mode.SOCIAL_SDK
    }

    private fun wireModeSelection() {
        groupMode.setOnCheckedChangeListener { _, _ -> applyModeVisibility(selectedMode()) }
    }

    /**
     * Shows only the fields the selected transport actually uses.
     *
     * Leaving the Application ID visible in token mode invited the paste that then did nothing,
     * which is exactly the confusion this page exists to remove.
     */
    private fun applyModeVisibility(mode: DiscordPresence.Mode) {
        val isSocial = mode == DiscordPresence.Mode.SOCIAL_SDK
        val isAccount = mode == DiscordPresence.Mode.ACCOUNT_TOKEN

        layoutApplicationId.visibility = if (isSocial) View.VISIBLE else View.GONE
        layoutToken.visibility = if (isSocial) View.GONE else View.VISIBLE

        textModeSummary.setText(
            when (mode) {
                DiscordPresence.Mode.SOCIAL_SDK -> R.string.discord_mode_social_sdk_summary
                DiscordPresence.Mode.ACCOUNT_TOKEN -> R.string.discord_mode_account_summary
                DiscordPresence.Mode.BOT_TOKEN -> R.string.discord_mode_bot_summary
            }
        )

        // The warning belongs to account-token mode only: bot mode is lawful and Social SDK mode
        // involves no credential at all.
        textWarning.visibility = if (isAccount) View.VISIBLE else View.GONE
        textSocialSdkMissing.visibility =
            if (isSocial && !DiscordPresence.isSocialSdkAvailable()) View.VISIBLE else View.GONE
    }

    private fun wirePreview() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updatePreview()
        }
        listOf(inputName, inputDetails, inputState).forEach { it.addTextChangedListener(watcher) }
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
        textStatus.text = getString(R.string.discord_saved)
    }
}
