// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.network

import org.json.JSONArray
import org.json.JSONObject
import org.yuzu.yuzu_emu.features.settings.model.StringSetting

/**
 * The user-editable part of a presence, stored as one JSON document.
 *
 * A dozen separate settings keys would each need a Kotlin enum entry, a native declaration and a
 * row in the settings list, for values that are only ever read as a set and only ever written by
 * the Discord page. One document keeps that whole surface out of the settings registry.
 *
 * Every field is optional: empty means "fall back to the game title" for [name], or "omit this
 * line" for the rest.
 */
data class PresenceCustomization(
    /** Overrides the activity's headline. Empty means the game title. */
    val name: String = "",
    val details: String = "",
    val state: String = "",
    /** Asset key uploaded to the Discord application, or an external https image URL. */
    val largeImage: String = "",
    val smallImage: String = "",
    val button1Label: String = "",
    val button1Url: String = "",
    val button2Label: String = "",
    val button2Url: String = "",
    val showTimestamps: Boolean = true
) {
    fun toJson(): String = JSONObject()
        .put(KEY_NAME, name)
        .put(KEY_DETAILS, details)
        .put(KEY_STATE, state)
        .put(KEY_LARGE_IMAGE, largeImage)
        .put(KEY_SMALL_IMAGE, smallImage)
        .put(KEY_BUTTON1_LABEL, button1Label)
        .put(KEY_BUTTON1_URL, button1Url)
        .put(KEY_BUTTON2_LABEL, button2Label)
        .put(KEY_BUTTON2_URL, button2Url)
        .put(KEY_TIMESTAMPS, showTimestamps)
        .toString()

    /** True when nothing is customised, so the page can show "using defaults". */
    fun isDefault(): Boolean = this == DEFAULT

    companion object {
        private const val KEY_NAME = "name"
        private const val KEY_DETAILS = "details"
        private const val KEY_STATE = "state"
        private const val KEY_LARGE_IMAGE = "large_image"
        private const val KEY_SMALL_IMAGE = "small_image"
        private const val KEY_BUTTON1_LABEL = "button1_label"
        private const val KEY_BUTTON1_URL = "button1_url"
        private const val KEY_BUTTON2_LABEL = "button2_label"
        private const val KEY_BUTTON2_URL = "button2_url"
        private const val KEY_TIMESTAMPS = "timestamps"

        val DEFAULT = PresenceCustomization()

        /**
         * Reads the stored document.
         *
         * Malformed JSON yields the defaults rather than throwing: this is called from the
         * emulation path on game launch, and a hand-edited config must not be able to stop a
         * game from starting.
         */
        fun load(): PresenceCustomization {
            val raw = StringSetting.DISCORD_ACTIVITY_JSON.getString()
            if (raw.isBlank()) {
                return DEFAULT
            }
            return runCatching {
                val json = JSONObject(raw)
                PresenceCustomization(
                    name = json.optString(KEY_NAME, ""),
                    details = json.optString(KEY_DETAILS, ""),
                    state = json.optString(KEY_STATE, ""),
                    largeImage = json.optString(KEY_LARGE_IMAGE, ""),
                    smallImage = json.optString(KEY_SMALL_IMAGE, ""),
                    button1Label = json.optString(KEY_BUTTON1_LABEL, ""),
                    button1Url = json.optString(KEY_BUTTON1_URL, ""),
                    button2Label = json.optString(KEY_BUTTON2_LABEL, ""),
                    button2Url = json.optString(KEY_BUTTON2_URL, ""),
                    showTimestamps = json.optBoolean(KEY_TIMESTAMPS, true)
                )
            }.getOrDefault(DEFAULT)
        }

        fun save(customization: PresenceCustomization) {
            StringSetting.DISCORD_ACTIVITY_JSON.setString(customization.toJson())
        }

        /**
         * Builds the Discord `activities[0]` object.
         *
         * Discord caps most strings at 128 characters and a button label at 32, and silently
         * rejects the whole update when a field overruns instead of truncating it, so the limits
         * are applied here rather than trusted from the UI.
         */
        fun buildActivity(
            customization: PresenceCustomization,
            gameTitle: String?,
            startedAt: Long
        ): JSONObject {
            val headline = customization.name.ifBlank { gameTitle.orEmpty() }
            val activity = JSONObject()
                .put("name", headline.take(128))
                .put("type", 0)

            if (customization.details.isNotBlank()) {
                activity.put("details", customization.details.take(128))
            }
            if (customization.state.isNotBlank()) {
                activity.put("state", customization.state.take(128))
            }
            if (customization.showTimestamps) {
                activity.put("timestamps", JSONObject().put("start", startedAt))
            }

            val assets = JSONObject()
            if (customization.largeImage.isNotBlank()) {
                assets.put("large_image", customization.largeImage.take(256))
            }
            if (customization.smallImage.isNotBlank()) {
                assets.put("small_image", customization.smallImage.take(256))
            }
            if (assets.length() > 0) {
                activity.put("assets", assets)
            }

            val buttons = JSONArray()
            addButton(buttons, customization.button1Label, customization.button1Url)
            addButton(buttons, customization.button2Label, customization.button2Url)
            if (buttons.length() > 0) {
                activity.put("buttons", buttons)
            }

            return activity
        }

        private fun addButton(target: JSONArray, label: String, url: String) {
            // Discord drops the activity if a button has a label without a URL, so both are
            // required; the URL must also be absolute http(s).
            if (label.isBlank() || url.isBlank()) {
                return
            }
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                return
            }
            target.put(
                JSONObject()
                    .put("label", label.take(32))
                    .put("url", url)
            )
        }
    }
}
