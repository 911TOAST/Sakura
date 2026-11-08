// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.animation.Animator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.findNavController
import org.yuzu.yuzu_emu.HomeNavigationDirections
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.FragmentAboutBinding
import org.yuzu.yuzu_emu.features.settings.ui.SettingsSubscreen
import org.yuzu.yuzu_emu.model.HomeViewModel
import org.yuzu.yuzu_emu.utils.BrandAnimation
import org.yuzu.yuzu_emu.utils.ViewUtils.updateMargins
import org.yuzu.yuzu_emu.NativeLibrary

class AboutFragment : Fragment() {
    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by activityViewModels()

    // Looping decorators, cancelled when the view goes away.
    private val brandAnimators = mutableListOf<Animator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAboutBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        homeViewModel.setStatusBarShadeVisibility(visible = false)
        binding.toolbarAbout.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        binding.imageLogo.setOnLongClickListener {
            Toast.makeText(
                requireContext(),
                R.string.gaia_is_not_real,
                Toast.LENGTH_SHORT
            ).show()
            true
        }

        binding.buttonContributors.setOnClickListener {
            openLink(
                getString(R.string.contributors_link)
            )
        }
        binding.buttonLicenses.setOnClickListener {
            val action = HomeNavigationDirections.actionGlobalSettingsSubscreenActivity(
                SettingsSubscreen.LICENSES,
                null
            )
            binding.root.findNavController().navigate(action)
        }

        val buildName = getString(R.string.app_name_suffixed)
        val buildVersion = NativeLibrary.getBuildVersion()
        val fullVersionText = "$buildName ($buildVersion)"

        binding.textVersionName.text = fullVersionText
        binding.buttonVersionName.setOnClickListener {
            val clipBoard =
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(getString(R.string.build), fullVersionText)
            clipBoard.setPrimaryClip(clip)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(
                    requireContext(),
                    R.string.copied_to_clipboard,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.buttonReddit.setOnClickListener { openLink(getString(R.string.reddit_link)) }
        // Small, quiet pointer to a one-developer game. It reads as a thank-you note, not a store
        // banner: outlined card, small type, no popup.
        binding.neotronLink.setOnClickListener { openLink(getString(R.string.neotron_link)) }

        setInsets()
        playBrandIntro()
    }

    /**
     * Brand entrance and ambient motion for the About screen.
     *
     * The entrance is a short staggered rise. Once it settles, the logo, wordmark and credit keep
     * moving on their own: shimmer in the text paint, width-only breathing, a slow float and rock
     * on the mark, and a nudging chevron. Nothing here touches layout or bitmaps, so it stays cheap
     * even on low-end devices.
     */
    private fun playBrandIntro() {
        if (!animationsEnabled()) {
            return
        }

        val density = resources.displayMetrics.density

        // Text paint sweep. The glyph height never changes.
        BrandAnimation.startShimmer(binding.aboutWordmark, 2800L)
        BrandAnimation.startShimmer(binding.neotronTitle, 3400L)

        // Width-only breathing and the credit icon's drift can start right away; they do not fight
        // the entrance transform on their own views.
        brandAnimators += BrandAnimation.breathe(binding.aboutWordmark)
        brandAnimators += BrandAnimation.float(binding.neotronIcon, 3f * density, 1900L)
        brandAnimators += BrandAnimation.nudgeX(binding.neotronChevron, 4f * density, 1100L)

        val rise = 18f * density
        val targets = listOf(
            binding.imageLogo,
            binding.aboutWordmark,
            binding.buttonContributors,
            binding.buttonLicenses,
            binding.buttonVersionName,
            binding.buttonReddit,
            binding.neotronLink
        )
        targets.forEachIndexed { index, target ->
            target.animate().cancel()
            target.alpha = 0f
            target.translationY = rise
            target.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(60L + index * 55L)
                .setDuration(620L)
                .setInterpolator(OvershootInterpolator(1.1f))
                .start()
        }

        // The hero logo already owns translationY for its entrance, so its ambient drift starts
        // once the rise is done.
        binding.root.postDelayed({
            if (_binding != null) {
                brandAnimators += BrandAnimation.float(binding.imageLogo, 6f * density, 2600L)
                brandAnimators += BrandAnimation.sway(binding.imageLogo, 2.5f, 4200L)
            }
        }, 900L)
    }

    /** System animator scale can be zero, in which case the screen should be completely still. */
    private fun animationsEnabled(): Boolean {
        val scale = Settings.Global.getFloat(
            requireContext().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        return scale > 0f
    }

    override fun onDestroyView() {
        brandAnimators.forEach { it.cancel() }
        brandAnimators.clear()
        _binding = null
        super.onDestroyView()
    }

    private fun openLink(link: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
        startActivity(intent)
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.root
        ) { _: View, windowInsets: WindowInsetsCompat ->
            val barInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())

            val leftInsets = barInsets.left + cutoutInsets.left
            val rightInsets = barInsets.right + cutoutInsets.right

            binding.toolbarAbout.updateMargins(left = leftInsets, right = rightInsets)
            binding.scrollAbout.updateMargins(left = leftInsets, right = rightInsets)

            binding.contentAbout.updatePadding(bottom = barInsets.bottom)

            windowInsets
        }
}
