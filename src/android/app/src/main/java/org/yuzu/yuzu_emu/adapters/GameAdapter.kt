// SPDX-FileCopyrightText: Copyright 2025 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.adapters

import android.content.DialogInterface
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.preference.PreferenceManager
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.HomeNavigationDirections
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.databinding.CardGameListBinding
import org.yuzu.yuzu_emu.databinding.CardGameGridBinding
import org.yuzu.yuzu_emu.databinding.CardGameCarouselBinding
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.model.GamesViewModel
import org.yuzu.yuzu_emu.utils.GameIconUtils
import org.yuzu.yuzu_emu.utils.FavStore
import org.yuzu.yuzu_emu.utils.SuyuMotion
import org.yuzu.yuzu_emu.utils.ViewUtils.marquee
import org.yuzu.yuzu_emu.viewholder.AbstractViewHolder
import androidx.core.net.toUri
import androidx.core.content.edit
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.databinding.CardGameGridCompactBinding
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.features.settings.model.Settings

class GameAdapter(private val activity: AppCompatActivity) :
    AbstractDiffAdapter<Game, GameAdapter.GameViewHolder>(exact = false) {

    companion object {
        const val VIEW_TYPE_GRID = 0
        const val VIEW_TYPE_GRID_COMPACT = 1
        const val VIEW_TYPE_LIST = 2
        const val VIEW_TYPE_CAROUSEL = 3
    }

    private var viewType = 0
    private var entranceRemaining = 0

    fun setViewType(type: Int) {
        viewType = type
        notifyDataSetChanged()
    }

    public var cardSize: Int = 0
        private set

    fun setCardSize(size: Int) {
        if (cardSize != size && size > 0) {
            cardSize = size
            notifyDataSetChanged()
        }
    }

    override fun getItemViewType(position: Int): Int = viewType

    override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        when (getItemViewType(position)) {
            VIEW_TYPE_LIST -> {
                val listBinding = holder.binding as CardGameListBinding
                listBinding.cardGameList.scaleX = 1f
                listBinding.cardGameList.scaleY = 1f
                listBinding.cardGameList.alpha = 1f
                // Reset layout params to XML defaults
                listBinding.root.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                listBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }

            VIEW_TYPE_GRID -> {
                val gridBinding = holder.binding as CardGameGridBinding
                gridBinding.cardGameGrid.scaleX = 1f
                gridBinding.cardGameGrid.scaleY = 1f
                gridBinding.cardGameGrid.alpha = 1f
                // Reset layout params to XML defaults
                gridBinding.root.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                gridBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }

            VIEW_TYPE_GRID_COMPACT -> {
                val gridCompactBinding = holder.binding as CardGameGridCompactBinding
                gridCompactBinding.cardGameGridCompact.scaleX = 1f
                gridCompactBinding.cardGameGridCompact.scaleY = 1f
                gridCompactBinding.cardGameGridCompact.alpha = 1f
                // Reset layout params to XML defaults (same as normal grid)
                gridCompactBinding.root.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                gridCompactBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }

            VIEW_TYPE_CAROUSEL -> {
                val carouselBinding = holder.binding as CardGameCarouselBinding
                // soothens transient flickering
                carouselBinding.cardGameCarousel.scaleY = 0f
                carouselBinding.cardGameCarousel.alpha = 0f
            }
        }
        // Raw M3 entrance: staggered fade+slide when the library first lays out.
        if (entranceRemaining > 0) {
            entranceRemaining--
            val itemView = holder.itemView
            itemView.alpha = 0f
            itemView.translationY =
                itemView.resources.getDimensionPixelSize(R.dimen.spacing_large).toFloat()
            itemView.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(position.coerceAtMost(16) * 24L)
                .setDuration(SuyuMotion.durationMedium2(itemView.context))
                .setInterpolator(SuyuMotion.emphasizedDecelerate(itemView.context))
                .start()
        }
    }

    /**
     * Queue staggered M3 entrances for the next binds (used on first library load
     * and whenever the view mode changes).
     */
    fun playEntranceAnimation(tileCount: Int = 24) {
        if (!SuyuMotion.entrancesEnabled()) {
            entranceRemaining = 0
            return
        }
        // Cap to roughly two screens so later scroll binds never re-animate.
        entranceRemaining = tileCount.coerceAtMost(24)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
        val binding = when (viewType) {
            VIEW_TYPE_LIST -> CardGameListBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

            VIEW_TYPE_GRID -> CardGameGridBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

            VIEW_TYPE_GRID_COMPACT -> CardGameGridCompactBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

            VIEW_TYPE_CAROUSEL -> CardGameCarouselBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

            else -> throw IllegalArgumentException("Invalid view type")
        }
        return GameViewHolder(binding, viewType)
    }

    inner class GameViewHolder(
        internal val binding: ViewBinding,
        private val viewType: Int
    ) : AbstractViewHolder<Game>(binding) {

        override fun bind(model: Game) {
            when (viewType) {
                VIEW_TYPE_LIST -> bindListView(model)
                VIEW_TYPE_GRID -> bindGridView(model)
                VIEW_TYPE_CAROUSEL -> bindCarouselView(model)
                VIEW_TYPE_GRID_COMPACT -> bindGridCompactView(model)
            }
        }

        private fun bindListView(model: Game) {
            val listBinding = binding as CardGameListBinding

            listBinding.imageGameScreen.scaleType = ImageView.ScaleType.CENTER_CROP
            GameIconUtils.loadGameIcon(model, listBinding.imageGameScreen)

            listBinding.textGameTitle.text = model.title.replace("[\\t\\n\\r]+".toRegex(), " ")
            listBinding.textGameDeveloper.text = model.developer

            // Library appearance: titles are optional, the artwork can carry the shelf on its own.
            val titlesVisible = !BooleanSetting.HIDE_GAME_TITLES.getBoolean()
            listBinding.textGameTitle.visibility = if (titlesVisible) View.VISIBLE else View.GONE
            listBinding.textGameDeveloper.visibility = if (titlesVisible) View.VISIBLE else View.GONE

            listBinding.textGameTitle.marquee()
            listBinding.cardGameList.setOnClickListener { onClick(model) }
            listBinding.cardGameList.setOnLongClickListener { onLongClick(model) }
            SuyuMotion.installPressFeedback(listBinding.cardGameList)

            // Reset layout params to XML defaults
            listBinding.root.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            listBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }

        private fun bindGridView(model: Game) {
            val gridBinding = binding as CardGameGridBinding

            gridBinding.imageGameScreen.scaleType = ImageView.ScaleType.CENTER_CROP
            GameIconUtils.loadGameIcon(model, gridBinding.imageGameScreen)

            gridBinding.textGameTitle.text = model.title.replace("[\\t\\n\\r]+".toRegex(), " ")
            gridBinding.textGameTitle.visibility =
                if (BooleanSetting.HIDE_GAME_TITLES.getBoolean()) View.GONE else View.VISIBLE

            gridBinding.cardGameGrid.setOnClickListener { onClick(model) }
            gridBinding.cardGameGrid.setOnLongClickListener { onLongClick(model) }
            SuyuMotion.installPressFeedback(gridBinding.cardGameGrid)

            // Reset layout params to XML defaults
            gridBinding.root.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            gridBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }

        private fun bindGridCompactView(model: Game) {
            val gridCompactBinding = binding as CardGameGridCompactBinding

            gridCompactBinding.imageGameScreenCompact.scaleType = ImageView.ScaleType.CENTER_CROP
            GameIconUtils.loadGameIcon(model, gridCompactBinding.imageGameScreenCompact)

            gridCompactBinding.textGameTitleCompact.text = model.title.replace("[\\t\\n\\r]+".toRegex(), " ")
            gridCompactBinding.textGameTitleCompact.visibility =
                if (BooleanSetting.HIDE_GAME_TITLES.getBoolean()) View.GONE else View.VISIBLE

            gridCompactBinding.cardGameGridCompact.setOnClickListener { onClick(model) }
            gridCompactBinding.cardGameGridCompact.setOnLongClickListener { onLongClick(model) }
            SuyuMotion.installPressFeedback(gridCompactBinding.cardGameGridCompact)

            // Reset layout params to XML defaults (same as normal grid)
            gridCompactBinding.root.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            gridCompactBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }

        private fun bindCarouselView(model: Game) {
            val carouselBinding = binding as CardGameCarouselBinding

            carouselBinding.imageGameScreen.scaleType = ImageView.ScaleType.CENTER_CROP
            GameIconUtils.loadGameIcon(model, carouselBinding.imageGameScreen)

            carouselBinding.textGameTitle.text = model.title.replace("[\\t\\n\\r]+".toRegex(), " ")
            carouselBinding.textGameTitle.visibility =
                if (BooleanSetting.HIDE_GAME_TITLES.getBoolean()) View.GONE else View.VISIBLE
            carouselBinding.cardGameCarousel.setOnClickListener { onClick(model) }
            carouselBinding.cardGameCarousel.setOnLongClickListener { onLongClick(model) }
            SuyuMotion.installPressFeedback(carouselBinding.cardGameCarousel)

            carouselBinding.imageGameScreen.contentDescription =
                binding.root.context.getString(R.string.game_image_desc, model.title)

            // Ensure zero-heighted-full-width cards for carousel
            carouselBinding.root.layoutParams.width = cardSize
        }

        fun onClick(game: Game) {
            val gameExists = DocumentFile.fromSingleUri(
                YuzuApplication.appContext,
                game.path.toUri()
            )?.exists() == true

            if (!gameExists) {
                Toast.makeText(
                    YuzuApplication.appContext,
                    R.string.loader_error_file_not_found,
                    Toast.LENGTH_LONG
                ).show()

                ViewModelProvider(activity)[GamesViewModel::class.java].reloadGames(true)
                return
            }

            val launch: () -> Unit = {
                // Physical confirmation on the way into the game: the launch transition starts on
                // the next frame, so the tap needs its own feedback to feel answered.
                SuyuMotion.performHaptic(binding.root)

                val preferences =
                    PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)
                preferences.edit {
                    putLong(
                        game.keyLastPlayedTime,
                        System.currentTimeMillis()
                    )
                }

                activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val shortcut =
                            ShortcutInfoCompat.Builder(YuzuApplication.appContext, game.path)
                                .setShortLabel(game.title)
                                .setIcon(GameIconUtils.getShortcutIcon(activity, game))
                                .setIntent(game.launchIntent)
                                .build()
                        ShortcutManagerCompat.pushDynamicShortcut(
                            YuzuApplication.appContext,
                            shortcut
                        )
                    }
                }

                val action = HomeNavigationDirections.actionGlobalEmulationActivity(game, true)
                binding.root.findNavController().navigate(action)
            }

            // With fake system software enabled there is nothing to warn about: the applets the
            // title is missing are served by the HLE frontend instead of a guest launch.
            if (NativeLibrary.gameRequiresFirmware(game.programId) &&
                !NativeLibrary.isFirmwareAvailable() &&
                !BooleanSetting.FAKE_SYSTEM_APPLETS.getBoolean()
            ) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.loader_requires_firmware)
                    .setMessage(
                        Html.fromHtml(
                            activity.getString(R.string.loader_requires_firmware_description),
                            Html.FROM_HTML_MODE_LEGACY
                        )
                    )
                    .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                        launch()
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> }
                    .show()
            } else {
                launch()
            }
        }

        fun onLongClick(game: Game): Boolean {
            val popup = PopupMenu(activity, binding.root)
            val fav = FavStore.has(game.path)
            popup.menu.add(0, 1, 0, R.string.game_properties)
            popup.menu.add(0, 2, 1, if (fav) R.string.unpin_favorite else R.string.pin_favorite)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        openProperties(game)
                        true
                    }
                    2 -> {
                        val added = FavStore.toggle(game.path)
                        Toast.makeText(
                            YuzuApplication.appContext,
                            if (added) R.string.favorite_added else R.string.favorite_removed,
                            Toast.LENGTH_SHORT
                        ).show()
                        ViewModelProvider(activity)[GamesViewModel::class.java].bumpFavorites()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
            return true
        }

        private fun openProperties(game: Game): Boolean {
            val action = HomeNavigationDirections.actionGlobalPerGamePropertiesFragment(game)

            // Long press opens the per-game properties screen with plain navigation.
            // The old code passed ActivityOptionsCompat.makeSceneTransitionAnimation() here;
            // that transition is executed by the framework *after* navigate() returns, so a
            // bad shared view (recycled, clipped, duplicate transitionName, activity tearing
            // down) crashed the whole app outside any try/catch. The destination fragment's
            // own MaterialSharedAxis transition still animates the screen.
            runCatching {
                binding.root.findNavController().navigate(action)
            }.onFailure {
                runCatching { activity.findNavController(R.id.fragment_container).navigate(action) }
            }
            return true
        }
    }
}
