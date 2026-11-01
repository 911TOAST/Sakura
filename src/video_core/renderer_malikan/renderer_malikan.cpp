// SPDX-FileCopyrightText: Copyright 2026 suyu-mtk Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include "common/settings.h"
#include "core/frontend/graphics_context.h"
#include "video_core/renderer_malikan/renderer_malikan.h"
#include "video_core/vulkan_common/vulkan_device.h"

namespace Malikan {

RendererMalikan::RendererMalikan(Core::Frontend::EmuWindow& emu_window,
                                 Tegra::MaxwellDeviceMemoryManager& device_memory,
                                 Tegra::GPU& gpu,
                                 std::unique_ptr<Core::Frontend::GraphicsContext> context)
    : Vulkan::RendererVulkan(emu_window, device_memory, gpu, std::move(context)) {
    // The Mali profile is applied once, as defaults, from Settings::ApplyPerformanceProfile()
    // before the renderer is constructed. Re-applying it here overrode every user toggle and
    // every per-game override on each game start, and it contradicted the profile on the safety
    // settings: it re-enabled inner-invalidation skipping, shader-loop-check removal and the
    // racy present thread, all of which the profile deliberately leaves off.

    if (GetDevice().IsMali()) {
        LOG_INFO(Render_Vulkan,
                 "[Malikan] Turbo profile engaged: {} driver {} (native ASTC: {}, ASTC decode "
                 "mode: {}, AFRC: {}, mailbox present, async GPU/present, EDS2 + dynamic "
                 "vertex input).",
                 GetDevice().GetModelName(), GetDevice().GetDriverVersion(),
                 GetDevice().IsOptimalAstcSupported(),
                 GetDevice().IsAstcDecodeModeSupported(), GetDevice().HasImageCompressionControl());
    } else {
        LOG_INFO(Render_Vulkan,
                 "[Malikan] Renderer created on {} (profile applied from settings).",
                 GetDevice().GetModelName());
    }
}

RendererMalikan::~RendererMalikan() = default;

} // namespace Malikan
