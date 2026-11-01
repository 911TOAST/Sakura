// SPDX-FileCopyrightText: Copyright 2026 suyu-mtk Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include "video_core/renderer_vulkan/renderer_vulkan.h"

namespace Malikan {

// Malikan is an experimental Mali profile using the existing Vulkan renderer.
// Driver-specific behavior remains gated by detected hardware capabilities.
class RendererMalikan final : public Vulkan::RendererVulkan {
public:
    explicit RendererMalikan(Core::Frontend::EmuWindow& emu_window,
                             Tegra::MaxwellDeviceMemoryManager& device_memory,
                             Tegra::GPU& gpu,
                             std::unique_ptr<Core::Frontend::GraphicsContext> context);
    ~RendererMalikan() override;
};

} // namespace Malikan
