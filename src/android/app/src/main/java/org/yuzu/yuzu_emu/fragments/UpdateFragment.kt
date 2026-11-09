// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.FragmentUpdateBinding
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.model.UpdateViewModel
import org.yuzu.yuzu_emu.ui.main.MainActivity
import org.yuzu.yuzu_emu.utils.NativeConfig
import org.yuzu.yuzu_emu.utils.collect

class UpdateFragment : Fragment() {
    private var _binding: FragmentUpdateBinding? = null
    private val binding get() = _binding!!

    private val updateViewModel: UpdateViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUpdateBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbarUpdate.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        binding.updateDisable.isChecked = !BooleanSetting.SHOW_UPDATE_BUTTON.getBoolean()
        binding.updateDisable.setOnCheckedChangeListener { _, checked ->
            BooleanSetting.SHOW_UPDATE_BUTTON.setBoolean(!checked)
            NativeConfig.saveGlobalConfig()
        }

        updateViewModel.availableUpdate.collect(viewLifecycleOwner) { update ->
            bindUpdate(update)
        }
    }

    private fun bindUpdate(update: NativeLibrary.UpdateResult?) {
        if (update == null) {
            return
        }

        val version = update.tag.ifBlank { update.title }
        binding.updateVersion.text = getString(R.string.update_page_version, version)
        binding.updateTitle.text = update.title.ifBlank { getString(R.string.app_name) }
        binding.updateBody.text = update.body.ifBlank {
            getString(R.string.update_page_no_notes)
        }

        binding.updateDownload.setOnClickListener {
            if (update.assets.isEmpty()) {
                openLink(update.url)
            } else {
                (activity as? MainActivity)?.downloadAndInstallUpdate(update)
            }
        }
        binding.updateRelease.setOnClickListener { openLink(update.url) }
        binding.updateStar.setOnClickListener {
            openLink(getString(R.string.repository_link))
        }
    }

    private fun openLink(link: String) {
        if (link.isNotBlank()) {
            (activity as? MainActivity)?.openLink(link)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
