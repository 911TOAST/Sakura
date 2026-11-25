// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import org.yuzu.yuzu_emu.R
import kotlin.random.Random

/**
 * Bundled UI click with a randomized pitch on every play.
 *
 * A click sample played at a constant rate becomes a metronome within half a dozen taps, so each
 * play re-pitches the sample like keyboard and gamepad inputs do. Variance stays inside a tight
 * band (+/- 12 percent) so the sample keeps its character; the same randomness also covers
 * accidental same-note streaks.
 *
 * The pool is created lazily the first time a click is requested and lives for the process, so
 * there is nothing to release from screens that come and go. Streams are capped low: sounds play
 * in quick succession only while a list is scrolled and old ones are meant to be trimmed.
 */
object ClickSound {
    private const val PITCH_VARIANCE = 0.12f

    private var soundPool: SoundPool? = null
    private var loadedId: Int = 0

    /**
     * Plays the click at a fresh pitch if UI sound feedback is enabled and the sample has loaded.
     * Bails out silently otherwise - sound feedback must never delay or error the press itself.
     */
    fun play(context: Context) {
        if (!SuyuMotion.soundFeedbackEnabled()) {
            return
        }

        val pool = soundPool ?: SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Media stream, not the default sonification stream: UI clicks ride the
                    // media volume, which games keep high, while notification/system volume is
                    // commonly set low - that was why the click was inaudible for some players.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .build()
            .also {
                soundPool = it
                loadedId = it.load(context.applicationContext, R.raw.ui_click, 1)
            }

        if (loadedId == 0) {
            return
        }

        val rate = 1f + Random.nextFloat() * 2f * PITCH_VARIANCE - PITCH_VARIANCE
        pool.play(loadedId, 1f, 1f, 1, 0, rate.coerceIn(0.5f, 2f))
    }
}
