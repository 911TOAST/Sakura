// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.YuzuApplication
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.WeakHashMap

/**
 * Runtime app-font manager.
 *
 * The font choice is an app-local preference (not an emulator config value): it depends on a
 * folder on the device, so it is stored in SharedPreferences just like the wallpaper and layout
 * presets. Two fonts ship with the app; any .ttf/.otf/.ttc file in the chosen folder becomes an
 * extra selectable option without a rebuild.
 */
object FontManager {
    const val DEFAULT_FONT_ID = "google_sans"
    const val SAKURA_FONT_ID = "sakura_display"

    private const val PREFS = "sakura_fonts"
    private const val KEY_SELECTED = "selected_font"
    private const val KEY_FOLDER = "font_folder_uri"
    private const val CACHE_DIR = "custom_fonts"
    private const val MAX_FONTS = 48

    data class FontOption(val id: String, val label: String, val uri: String? = null)

    private val prefs: SharedPreferences
        get() = YuzuApplication.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Synthetic bold keeps weight changes working for single-file fonts; tracking which base
    // typeface produced a TextView's current face makes the global-layout pass idempotent.
    private val appliedViews = WeakHashMap<TextView, Pair<Typeface, Int>>()
    private val typefaceCache = HashMap<String, Typeface>()
    private val activityListeners = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()

    fun availableFonts(context: Context): List<FontOption> {
        val fonts = mutableListOf(
            FontOption(DEFAULT_FONT_ID, context.getString(R.string.font_default)),
            FontOption(SAKURA_FONT_ID, context.getString(R.string.font_sakura))
        )
        fonts.addAll(discoverCustomFonts(context).take(MAX_FONTS))
        return fonts
    }

    fun selectedId(context: Context): String =
        prefs.getString(KEY_SELECTED, DEFAULT_FONT_ID) ?: DEFAULT_FONT_ID

    fun selectedLabel(context: Context): String =
        availableFonts(context).firstOrNull { it.id == selectedId(context) }?.label
            ?: context.getString(R.string.font_default)

    fun setSelectedId(context: Context, id: String) {
        prefs.edit { putString(KEY_SELECTED, id) }
        typefaceCache.clear()
        appliedViews.clear()
        reapplyAll()
    }

    fun folderUri(context: Context): Uri? =
        prefs.getString(KEY_FOLDER, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun setFolderUri(context: Context, uri: Uri) {
        prefs.edit { putString(KEY_FOLDER, uri.toString()) }
        typefaceCache.clear()
        appliedViews.clear()
        reapplyAll()
    }

    /** A bundled font is applied by leaving the XML resources alone; only the Compose side
     * needs an explicit family. Returning null means "use the app's original type scale". */
    fun currentTypeface(context: Context): Typeface? {
        val id = selectedId(context)
        if (id == DEFAULT_FONT_ID) return null
        typefaceCache[id]?.let { return it }
        val typeface = if (id == SAKURA_FONT_ID) {
            runCatching { ResourcesCompat.getFont(context, R.font.sakura_display) }.getOrNull()
        } else {
            loadCustomTypeface(context, id)
        }
        if (typeface != null) typefaceCache[id] = typeface
        return typeface
    }

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object :
            Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                attach(activity)
                registerDialogHooks(activity)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = reapply(activity)
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                activityListeners.remove(activity)?.let { listener ->
                    runCatching {
                        activity.window?.decorView?.viewTreeObserver
                            ?.removeOnGlobalLayoutListener(listener)
                    }
                }
            }
        })
    }

    private fun attach(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        if (activityListeners.containsKey(activity)) return
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            applyToViewTree(decor, currentTypeface(activity))
        }
        activityListeners[activity] = listener
        runCatching { decor.viewTreeObserver.addOnGlobalLayoutListener(listener) }
        applyToViewTree(decor, currentTypeface(activity))
    }

    private fun reapply(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        applyToViewTree(decor, currentTypeface(activity))
    }

    private fun reapplyAll() {
        activityListeners.keys.toList().forEach { reapply(it) }
    }

    private fun applyToViewTree(root: View, typeface: Typeface?) {
        if (typeface == null) return
        applyToView(root, typeface)
    }

    private fun applyToView(view: View, typeface: Typeface) {
        if (view is TextView && view.typeface != Typeface.MONOSPACE) {
            val style = view.typeface?.style ?: Typeface.NORMAL
            val previous = appliedViews[view]
            if (previous?.first !== typeface || previous.second != style) {
                runCatching { Typeface.create(typeface, style) }.getOrNull()?.let { desired ->
                    view.typeface = desired
                    appliedViews[view] = typeface to style
                }
            }
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) applyToView(view.getChildAt(i), typeface)
        }
    }

    private fun registerDialogHooks(activity: Activity) {
        val fragmentActivity = activity as? FragmentActivity ?: return
        fragmentActivity.supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
                    if (fragment !is DialogFragment) return
                    val decor = fragment.dialog?.window?.decorView ?: return
                    decor.post { applyToViewTree(decor, currentTypeface(fragmentActivity)) }
                }
            },
            true
        )
    }

    private fun discoverCustomFonts(context: Context): List<FontOption> {
        val candidates: List<Pair<String, String>> = folderUri(context)?.let { treeUri ->
            runCatching {
                DocumentFile.fromTreeUri(context, treeUri)?.listFiles()
                    ?.filter { it.isFile && it.name?.isFontFileName() == true }
                    ?.mapNotNull { file -> file.name?.let { it to file.uri.toString() } }
            }.getOrNull()
        } ?: runCatching {
            (context.getExternalFilesDir("fonts")?.listFiles() ?: emptyArray())
                .filter { it.isFile && it.name.isFontFileName() }
                .map { it.name to Uri.fromFile(it).toString() }
        }.getOrNull() ?: emptyList()

        val usedNames = HashSet<String>()
        return candidates.sortedBy { it.first.lowercase(Locale.ROOT) }.map { (name, uri) ->
            var label = name.substringBeforeLast('.', name)
            var suffix = 2
            while (!usedNames.add(label)) {
                label = "${name.substringBeforeLast('.', name)} ($suffix)"
                suffix++
            }
            FontOption(id = uri, label = label, uri = uri)
        }
    }

    private fun String.isFontFileName(): Boolean =
        endsWith(".ttf", ignoreCase = true) ||
            endsWith(".otf", ignoreCase = true) ||
            endsWith(".ttc", ignoreCase = true)

    private fun loadCustomTypeface(context: Context, uriString: String): Typeface? = runCatching {
        val uri = Uri.parse(uriString)
        val file = if (uri.scheme == "file") {
            File(uri.path ?: return@runCatching null)
        } else {
            copyToCache(context, uri) ?: return@runCatching null
        }
        Typeface.createFromFile(file)
    }.getOrNull()

    private fun copyToCache(context: Context, uri: Uri): File? {
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val name = "font_" + (uri.toString().hashCode().toLong() and 0x7fffffff) + ".ttf"
        val target = File(dir, name)
        val size = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull()
        if (target.exists() && size != null && target.length() == size) return target
        val input = context.contentResolver.openInputStream(uri) ?: return null
        input.use { stream ->
            FileOutputStream(target).use { output -> stream.copyTo(output) }
        }
        return target
    }
}
