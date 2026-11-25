// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import org.yuzu.yuzu_emu.utils.SakuraDialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.HomeNavigationDirections
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.adapters.DriverAdapter
import org.yuzu.yuzu_emu.databinding.FragmentDriverManagerBinding
import org.yuzu.yuzu_emu.features.settings.model.StringSetting
import org.yuzu.yuzu_emu.features.settings.ui.SettingsSubscreen
import org.yuzu.yuzu_emu.model.DriverViewModel
import org.yuzu.yuzu_emu.model.HomeViewModel
import org.yuzu.yuzu_emu.utils.FileUtil
import org.yuzu.yuzu_emu.utils.GpuDriverHelper
import org.yuzu.yuzu_emu.utils.NativeConfig
import org.yuzu.yuzu_emu.utils.UiLayout
import org.yuzu.yuzu_emu.utils.ViewUtils.updateMargins
import org.yuzu.yuzu_emu.utils.collect
import java.io.File
import java.io.IOException

class DriverManagerFragment : Fragment() {
    private var _binding: FragmentDriverManagerBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by activityViewModels()
    private val driverViewModel: DriverViewModel by activityViewModels()

    private val args by navArgs<DriverManagerFragmentArgs>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriverManagerBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        homeViewModel.setStatusBarShadeVisibility(visible = false)

        driverViewModel.onOpenDriverManager(args.game)
        if (NativeConfig.isPerGameConfigLoaded()) {
            binding.toolbarDrivers.inflateMenu(R.menu.menu_driver_manager)
            driverViewModel.showClearButton(!StringSetting.DRIVER_PATH.global)
            binding.toolbarDrivers.setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.menu_driver_use_global -> {
                        driverViewModel.onUseGlobalDriver()
                        (binding.listDrivers.adapter as DriverAdapter)
                            .replaceList(driverViewModel.driverList.value)
                        driverViewModel.showClearButton(false)
                        true
                    }

                    else -> false
                }
            }

            driverViewModel.showClearButton.collect(viewLifecycleOwner) {
                binding.toolbarDrivers.menu.findItem(R.id.menu_driver_use_global).isVisible = it
            }
        }

        driverViewModel.shouldShowDriverShaderDialog.collect(viewLifecycleOwner) { shouldShow ->
            if (shouldShow) {
                showDriverShaderWipeDialog()
            }
        }

        // Keep the header honest: it always names the driver the next launch will load.
        driverViewModel.selectedDriverTitle.collect(viewLifecycleOwner) { title ->
            binding.textActiveDriver.text = title.ifEmpty {
                getString(R.string.select_gpu_driver_default)
            }
        }
        driverViewModel.selectedDriverVersion.collect(viewLifecycleOwner) { version ->
            binding.textActiveVersion.isVisible = version.isNotBlank()
            binding.textActiveVersion.text = version
        }

        if (!driverViewModel.isInteractionAllowed.value) {
            DriversLoadingDialogFragment().show(
                childFragmentManager,
                DriversLoadingDialogFragment.TAG
            )
        }

        binding.toolbarDrivers.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        binding.buttonInstall.setOnClickListener {
            getDriver.launch(arrayOf("application/zip"))
        }

        binding.buttonFetch.setOnClickListener {
            val action = HomeNavigationDirections.actionGlobalSettingsSubscreenActivity(
                SettingsSubscreen.DRIVER_FETCHER,
                null
            )
            binding.root.findNavController().navigate(action)
        }

        binding.listDrivers.apply {
            // One full-width row per driver: the title, badge and delete action need the room.
            layoutManager = LinearLayoutManager(requireContext())
            adapter = DriverAdapter(driverViewModel)
        }

        // The header follows the same shape language as the rows below it.
        binding.cardActiveDriver.radius =
            UiLayout.current(requireContext()).cornerRadiusPx(requireContext())
        // The list always holds the system driver; show guidance only while it is alone.
        (binding.listDrivers.adapter as DriverAdapter).registerAdapterDataObserver(
            object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() = updateHeader()

                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) =
                    updateHeader()

                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) =
                    updateHeader()
            }
        )
        updateHeader()

        setInsets()
    }

    /**
     * Keeps the installed counter and the empty guidance in sync with the adapter, including
     * after the fetcher screen installs a driver behind this list.
     */
    private fun updateHeader() {
        val adapter = binding.listDrivers.adapter as? DriverAdapter ?: return
        // Position 0 is always the system driver; only count the installed custom ones.
        val customDriverCount = (adapter.itemCount - 1).coerceAtLeast(0)
        val hasCustomDrivers = customDriverCount > 0

        binding.textDriverCount.text = customDriverCount.toString()
        binding.sectionDrivers.isVisible = hasCustomDrivers
        binding.listDrivers.isVisible = hasCustomDrivers
        binding.emptyDrivers.isVisible = !hasCustomDrivers
    }

    override fun onResume() {
        super.onResume()
        refreshDriverList()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        driverViewModel.onCloseDriverManager(args.game)
    }

    private fun refreshDriverList() {
        driverViewModel.reloadDriverData()
        (binding.listDrivers.adapter as? DriverAdapter)
            ?.replaceList(driverViewModel.driverList.value)
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.root
        ) { _: View, windowInsets: WindowInsetsCompat ->
            val barInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())

            val leftInsets = barInsets.left + cutoutInsets.left
            val rightInsets = barInsets.right + cutoutInsets.right
            val density = resources.displayMetrics.density

            binding.toolbarDrivers.updateMargins(left = leftInsets, right = rightInsets)
            binding.contentDrivers.updatePadding(left = leftInsets, right = rightInsets)

            // The action bar owns the navigation-bar inset; the list just needs breathing room
            // above it so the last driver is never hidden behind the buttons.
            binding.driverActions.updatePadding(
                bottom = barInsets.bottom + (10 * density).toInt()
            )
            binding.listDrivers.updatePadding(
                bottom = (8 * density).toInt()
            )

            windowInsets
        }

    private val getDriver =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { result ->
            if (result == null) {
                return@registerForActivityResult
            }

            ProgressDialogFragment.newInstance(
                requireActivity(),
                R.string.installing_driver,
                false
            ) { _, _ ->
                val driverPath =
                    "${GpuDriverHelper.driverStoragePath}${FileUtil.getFilename(result)}"
                val driverFile = File(driverPath)

                // Ignore file exceptions when a user selects an invalid zip
                try {
                    if (!GpuDriverHelper.copyDriverToInternalStorage(result)) {
                        throw IOException("Driver failed validation!")
                    }
                } catch (_: IOException) {
                    if (driverFile.exists()) {
                        driverFile.delete()
                    }
                    return@newInstance getString(R.string.select_gpu_driver_error)
                }

                val driverData = GpuDriverHelper.getMetadataFromZip(driverFile)
                val driverInList =
                    driverViewModel.driverData.firstOrNull {
                        it.first == driverPath || it.second == driverData
                    }
                if (driverInList != null) {
                    return@newInstance getString(R.string.driver_already_installed)
                } else {
                    driverViewModel.onDriverAdded(Pair(driverPath, driverData))
                    withContext(Dispatchers.Main) {
                        if (_binding != null) {
                            refreshDriverList()
                            val adapter = binding.listDrivers.adapter as DriverAdapter
                            val selectedPosition = adapter.currentList
                                .indexOfFirst { it.selected }
                                .let { if (it == -1) 0 else it }
                            driverViewModel.showClearButton(!StringSetting.DRIVER_PATH.global)
                            binding.listDrivers
                                .smoothScrollToPosition(selectedPosition)
                        }
                    }
                }
                return@newInstance Any()
            }.show(childFragmentManager, ProgressDialogFragment.TAG)
        }

    private fun showDriverShaderWipeDialog() {
        SakuraDialogs.builder(requireContext())
            .setTitle(R.string.driver_shader_wipe_dialog_title)
            .setMessage(R.string.driver_shader_wipe_dialog_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                driverViewModel.onDriverShaderDialogDismissed(dontShowAgain = false)
            }
            .setNegativeButton(R.string.dont_show_again) { _, _ ->
                driverViewModel.onDriverShaderDialogDismissed(dontShowAgain = true)
            }
            .setCancelable(false)
            .show()
    }
}
