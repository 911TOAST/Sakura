// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.utils.Log
import org.yuzu.yuzu_emu.utils.background.BackgroundMetadata
import org.yuzu.yuzu_emu.utils.background.OnlineBackgroundCatalog
import org.yuzu.yuzu_emu.utils.background.SecureBackgroundStore
import org.yuzu.yuzu_emu.utils.background.WallpaperContributions
import org.yuzu.yuzu_emu.utils.background.WallpaperHttp
import org.yuzu.yuzu_emu.utils.background.WallpaperSettings
import org.yuzu.yuzu_emu.utils.background.WallpaperSettingsSnapshot

/**
 * The wallpaper contribution API: any app can hand Sakura a picture for the library background.
 *
 * Two ways in, both exported:
 * - `org.yuzu.yuzu_emu.action.CONTRIBUTE_WALLPAPER` with the image in `intent.data` or
 *   `EXTRA_STREAM`, plus the optional extras documented in `THEME_BACKGROUNDS.md`;
 * - plain `ACTION_SEND` with an image MIME type (the standard Android share sheet), which every
 *   gallery app can drive with no code of its own.
 *
 * Trust model: this activity is exported, so everything from the caller is untrusted input.
 * Bytes are size-capped and validated through the same decoder as every other wallpaper,
 * optional text extras are length-capped, a claimed source URL must be https, and — crucially —
 * a contribution never silently replaces the current background: default behaviour only adds it
 * to the hub's "From other apps" section, and applying it live takes the explicit `EXTRA_APPLY`.
 *
 * The stream URI is readable only because the caller granted it for this handoff; nothing is
 * persisted beyond the encrypted image itself.
 */
class WallpaperContributionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent ?: run { finish(); return }
        if (intent.action != ACTION_CONTRIBUTE && intent.action != Intent.ACTION_SEND) {
            finish()
            return
        }

        val stream: Uri? = intent.getParcelableExtraCompat(Intent.EXTRA_STREAM) ?: intent.data
        if (stream == null) {
            fail(R.string.wallpaper_contribution_failed)
            return
        }

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val author = intent.getStringExtra(EXTRA_AUTHOR).orEmpty()
        val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
            .takeIf { runCatching { it.toUri().isHttps }.getOrDefault(false) }
            ?: runCatching { intent.data?.toString() }
                .getOrNull()
                ?.takeIf { it.toUri().isHttps }
                .orEmpty()
        val applyLive = intent.getBooleanExtra(EXTRA_APPLY, false)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO + NonCancellable) {
                runCatching {
                    val bytes = contentResolver.openInputStream(stream)?.use {
                        WallpaperHttp.readBounded(it, OnlineBackgroundCatalog.MAX_IMAGE_BYTES)
                    } ?: throw IllegalStateException("Stream could not be opened")
                    val contribution = WallpaperContributions.add(
                        applicationContext,
                        bytes,
                        BackgroundMetadata(
                            id = "shared",
                            title = title.ifBlank { getString(R.string.wallpaper_contributions_fallback_title) },
                            author = author.ifBlank { callerName() },
                            source = source
                        )
                    )
                    if (applyLive) {
                        // commitOnline writes the selection's own encrypted blob and flips the
                        // config pointer; pruning then keeps exactly that blob. The contribution
                        // entry itself is removed afterwards — the pixels now live in the
                        // selection store, identical bytes either way.
                        var committedUri = ""
                        SecureBackgroundStore.commitOnline(
                            bytes,
                            BackgroundMetadata(
                                id = contribution.id,
                                title = contribution.title,
                                author = contribution.author,
                                source = contribution.source
                            )
                        ) { selectionUri ->
                            committedUri = selectionUri
                            WallpaperSettings.save(WallpaperSettingsSnapshot(selectionUri))
                        }
                        SecureBackgroundStore.pruneUnselected(committedUri)
                        WallpaperContributions.remove(applicationContext, contribution.id)
                    }
                    contribution.id
                }
            }
            result.onSuccess { id ->
                setResult(
                    RESULT_OK,
                    Intent().putExtra(EXTRA_ID, id)
                        .putExtra(EXTRA_APPLIED, applyLive)
                )
                Toast.makeText(
                    this@WallpaperContributionActivity,
                    if (applyLive) R.string.wallpaper_contribution_applied
                    else R.string.wallpaper_contribution_added,
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Log.warning("[Wallpaper] Contribution rejected: ${error.message}")
                Toast.makeText(
                    this@WallpaperContributionActivity,
                    R.string.wallpaper_contribution_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
            finish()
        }
    }

    /** The package that sent the share, when it can be known; blank means "unknown sender". */
    private fun callerName(): String {
        val referrer = referrer ?: return ""
        return runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(referrer.host ?: return "", 0)
            ).toString()
        }.getOrDefault("")
    }

    private fun fail(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun Intent.getParcelableExtraCompat(name: String): Uri? =
        runCatching {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }.getOrNull()

    private fun String.toUri(): Uri = Uri.parse(this)

    companion object {
        const val ACTION_CONTRIBUTE = "org.yuzu.yuzu_emu.action.CONTRIBUTE_WALLPAPER"

        /** Optional String: display title, up to 100 characters. */
        const val EXTRA_TITLE = "org.yuzu.yuzu_emu.extra.TITLE"

        /** Optional String: attribution line, up to 100 characters. */
        const val EXTRA_AUTHOR = "org.yuzu.yuzu_emu.extra.AUTHOR"

        /** Optional String: https page the image came from, shown as the wallpaper's source. */
        const val EXTRA_SOURCE = "org.yuzu.yuzu_emu.extra.SOURCE"

        /** Optional Boolean: also make the contribution the current library background. */
        const val EXTRA_APPLY = "org.yuzu.yuzu_emu.extra.APPLY"

        /** Result extra: the contribution id (also the id in the result intent on success). */
        const val EXTRA_ID = "org.yuzu.yuzu_emu.extra.ID"

        /** Result extra: Boolean, whether the wallpaper was applied immediately. */
        const val EXTRA_APPLIED = "org.yuzu.yuzu_emu.extra.APPLIED"
    }
}
