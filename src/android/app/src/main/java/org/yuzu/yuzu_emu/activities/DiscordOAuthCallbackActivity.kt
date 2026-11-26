// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import org.yuzu.yuzu_emu.network.DiscordAuthCoordinator
import org.yuzu.yuzu_emu.network.DiscordOAuth

/**
 * Return stop for "Sign in with Discord".
 *
 * The browser redirects to `sakura:/authorize/callback` after the user taps Authorise; this
 * transparent activity hands the URI to [DiscordOAuth] through the coordinator and vanishes
 * before the user can notice it existed. Every value of the redirect is validated by the
 * OAuth flow itself; unknown URIs are dropped on the floor here.
 */
class DiscordOAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
        finish()
    }

    private fun handle(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        if (DiscordOAuth.isRedirectUri(uri)) {
            DiscordAuthCoordinator.emit(uri)
        }
    }
}
