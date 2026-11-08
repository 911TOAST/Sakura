// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.settings.ui

import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.databinding.ItemOnlineBackgroundBinding
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.utils.background.OnlineBackground
import org.yuzu.yuzu_emu.utils.background.WallpaperHttp
import org.yuzu.yuzu_emu.utils.background.WallpaperImages

/** Thumbnail grid for the online background browser. */
class OnlineBackgroundAdapter(
    private val owner: LifecycleOwner,
    private val onClick: (OnlineBackground) -> Unit
) : ListAdapter<OnlineBackground, OnlineBackgroundAdapter.ViewHolder>(DIFF) {

    init { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

    /** Id of the item currently downloading, so only that tile shows a spinner. */
    var busyId: String? = null

    fun submit(list: List<OnlineBackground>, onCommitted: (() -> Unit)? = null) =
        submitList(list.toList(), onCommitted?.let { callback -> Runnable { callback() } })

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOnlineBackgroundBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: ViewHolder) { holder.clear() }
    override fun onViewDetachedFromWindow(holder: ViewHolder) { holder.cancel() }
    override fun onViewAttachedToWindow(holder: ViewHolder) { holder.loadThumbnail() }

    inner class ViewHolder(
        private val binding: ItemOnlineBackgroundBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var job: Job? = null
        private var item: OnlineBackground? = null
        private var generation = 0L

        fun bind(value: OnlineBackground) {
            clear()
            item = value
            binding.backgroundTitle.text = value.title.ifBlank { binding.root.context.getString(R.string.wallpaper_untitled) }
            binding.backgroundMetadata.text = listOfNotNull(
                if (value.width > 0 && value.height > 0) "${value.width} × ${value.height}" else null,
                value.author.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            binding.backgroundMetadata.visibility = if (binding.backgroundMetadata.text.isEmpty()) View.GONE else View.VISIBLE
            binding.root.isEnabled = busyId == null
            binding.root.setOnClickListener { if (busyId == null) onClick(value) }
            loadThumbnail()
        }

        fun loadThumbnail() {
            val value = item ?: return
            if (job?.isActive == true || binding.backgroundThumb.drawable != null) return
            val request = ++generation
            binding.backgroundProgress.visibility = View.VISIBLE
            job = owner.lifecycleScope.launch {
                try {
                    val bitmap = thumbnails.get(value.thumb) ?: withContext(Dispatchers.IO) {
                        val bytes = WallpaperHttp.get(value.thumb, image = true)
                        WallpaperImages.decode(bytes, maxEdge = 512).also { thumbnails.put(value.thumb, it) }
                    }
                    if (request == generation) binding.backgroundThumb.setImageBitmap(bitmap)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (request == generation) {
                        binding.backgroundMetadata.text = binding.root.context.getString(R.string.wallpaper_thumbnail_failed)
                        binding.backgroundMetadata.visibility = View.VISIBLE
                    }
                } finally {
                    if (request == generation) binding.backgroundProgress.visibility = View.GONE
                }
            }
        }

        fun cancel() {
            generation++
            job?.cancel()
            job = null
            binding.backgroundProgress.visibility = View.GONE
        }

        fun clear() {
            cancel()
            item = null
            binding.backgroundThumb.setImageDrawable(null)
            binding.root.setOnClickListener(null)
        }
    }

    companion object {
        private val thumbnails = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
        private val DIFF = object : DiffUtil.ItemCallback<OnlineBackground>() {
            override fun areItemsTheSame(old: OnlineBackground, new: OnlineBackground) = old.id == new.id
            override fun areContentsTheSame(old: OnlineBackground, new: OnlineBackground) = old == new
        }
    }
}
