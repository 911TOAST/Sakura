// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2021 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <ankerl/unordered_dense.h>

#include <boost/container/static_vector.hpp>

#include "video_core/renderer_vulkan/maxwell_to_vk.h"
#include "video_core/renderer_vulkan/vk_render_pass_cache.h"
#include "video_core/surface.h"
#include "video_core/vulkan_common/vulkan_device.h"
#include "video_core/vulkan_common/vulkan_wrapper.h"

namespace Vulkan {
namespace {
using VideoCore::Surface::PixelFormat;
using VideoCore::Surface::SurfaceType;

        constexpr SurfaceType GetSurfaceType(PixelFormat format) {
            switch (format) {
                // Depth formats
                case PixelFormat::D16_UNORM:
                case PixelFormat::D32_FLOAT:
                case PixelFormat::X8_D24_UNORM:
                    return SurfaceType::Depth;

                    // Stencil formats
                case PixelFormat::S8_UINT:
                    return SurfaceType::Stencil;

                    // Depth+Stencil formats
                case PixelFormat::D24_UNORM_S8_UINT:
                case PixelFormat::S8_UINT_D24_UNORM:
                case PixelFormat::D32_FLOAT_S8_UINT:
                    return SurfaceType::DepthStencil;

                    // Everything else is a color texture
                default:
                    return SurfaceType::ColorTexture;
            }
        }

        VkAttachmentDescription AttachmentDescription(const Device& device, PixelFormat format,
                                                      VkSampleCountFlagBits samples,
                                                      bool clear_color, bool clear_depth,
                                                      bool clear_stencil) {
            using MaxwellToVK::SurfaceFormat;

            const SurfaceType surface_type = GetSurfaceType(format);
            const bool has_stencil = surface_type == SurfaceType::DepthStencil ||
                                     surface_type == SurfaceType::Stencil;
            const bool is_depth = surface_type == SurfaceType::Depth ||
                                  surface_type == SurfaceType::DepthStencil;
            const bool clear{is_depth ? clear_depth : clear_color};

            return {
                .flags = {},
                .format = SurfaceFormat(device, FormatType::Optimal, true, format).format,
                .samples = samples,
                .loadOp = clear ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD,
                .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
                .stencilLoadOp = !has_stencil ? VK_ATTACHMENT_LOAD_OP_DONT_CARE
                                              : (clear_stencil ? VK_ATTACHMENT_LOAD_OP_CLEAR
                                                               : VK_ATTACHMENT_LOAD_OP_LOAD),
                .stencilStoreOp = has_stencil ? VK_ATTACHMENT_STORE_OP_STORE
                                                  : VK_ATTACHMENT_STORE_OP_DONT_CARE,
                .initialLayout = VK_IMAGE_LAYOUT_GENERAL,
                .finalLayout = VK_IMAGE_LAYOUT_GENERAL,
            };
        }
    } // Anonymous namespace

RenderPassCache::RenderPassCache(const Device& device_) : device{&device_} {}

VkRenderPass RenderPassCache::Get(const RenderPassKey& key) {
    std::scoped_lock lock{mutex};
    const auto [pair, is_new] = cache.try_emplace(key);
    if (!is_new) {
        return *pair->second;
    }
    boost::container::static_vector<VkAttachmentDescription, 9> descriptions;
    std::array<VkAttachmentReference, 8> references{};
    // The attachments rest in GENERAL; only the layout used during the subpass changes. Vulkan
    // considers render passes that differ only in attachment-reference layout compatible, so the
    // pipelines and framebuffers built for the GENERAL variant accept this one too.
    const VkImageLayout color_layout = key.optimal_layout
                                           ? VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                                           : VK_IMAGE_LAYOUT_GENERAL;
    const VkImageLayout depth_layout = key.optimal_layout
                                           ? VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
                                           : VK_IMAGE_LAYOUT_GENERAL;
    u32 num_attachments{};
    u32 num_colors{};
    for (size_t index = 0; index < key.color_formats.size(); ++index) {
        const PixelFormat format{key.color_formats[index]};
        const bool is_valid{format != PixelFormat::Invalid};
        references[index] = VkAttachmentReference{
            .attachment = is_valid ? num_colors : VK_ATTACHMENT_UNUSED,
            .layout = color_layout,
        };
        if (is_valid) {
            descriptions.push_back(AttachmentDescription(*device, format, key.samples,
                                                         key.color_clear, key.depth_clear,
                                                         key.stencil_clear));
            num_attachments = static_cast<u32>(index + 1);
            ++num_colors;
        }
    }
    const bool has_depth{key.depth_format != PixelFormat::Invalid};
    VkAttachmentReference depth_reference{};
    if (key.depth_format != PixelFormat::Invalid) {
        depth_reference = VkAttachmentReference{
            .attachment = num_colors,
            .layout = depth_layout,
        };
        descriptions.push_back(AttachmentDescription(*device, key.depth_format, key.samples,
                                                     key.color_clear, key.depth_clear,
                                                     key.stencil_clear));
    }
    const VkSubpassDescription subpass{
        .flags = 0,
        .pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
        .inputAttachmentCount = 0,
        .pInputAttachments = nullptr,
        .colorAttachmentCount = num_attachments,
        .pColorAttachments = references.data(),
        .pResolveAttachments = nullptr,
        .pDepthStencilAttachment = has_depth ? &depth_reference : nullptr,
        .preserveAttachmentCount = 0,
        .pPreserveAttachments = nullptr,
    };
    // Both layout variants must carry an identical dependency list: dependencies are part of
    // render pass compatibility, and a pipeline built for one variant is used with the other.
    // The self-dependency makes attachment writes visible to same-subpass fragment shader reads,
    // which is what the GENERAL variant needs for feedback loops. The two external dependencies
    // synchronize the automatic attachment layout transitions the optimal variant performs when
    // the images enter and leave GENERAL.
    boost::container::static_vector<VkSubpassDependency, 3> dependencies;
    dependencies.push_back(VkSubpassDependency{
        .srcSubpass = 0,  // Current subpass
        .dstSubpass = 0,  // Same subpass (self-dependency)
        .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                        VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT |
                        VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
        .dstStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
        .srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                         VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_SHADER_READ_BIT,
        .dependencyFlags = VK_DEPENDENCY_BY_REGION_BIT,
    });
    dependencies.push_back(VkSubpassDependency{
        .srcSubpass = VK_SUBPASS_EXTERNAL,
        .dstSubpass = 0,
        .srcStageMask = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        .dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                        VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT |
                        VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
        .srcAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_READ_BIT |
                         VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                         VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT |
                         VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
        .dependencyFlags = VK_DEPENDENCY_BY_REGION_BIT,
    });
    dependencies.push_back(VkSubpassDependency{
        .srcSubpass = 0,
        .dstSubpass = VK_SUBPASS_EXTERNAL,
        .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                        VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
        .dstStageMask = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        .srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                         VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
        .dependencyFlags = VK_DEPENDENCY_BY_REGION_BIT,
    });
    pair->second = device->GetLogical().CreateRenderPass({
        .sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .attachmentCount = static_cast<u32>(descriptions.size()),
        .pAttachments = descriptions.empty() ? nullptr : descriptions.data(),
        .subpassCount = 1,
        .pSubpasses = &subpass,
        .dependencyCount = static_cast<u32>(dependencies.size()),
        .pDependencies = dependencies.data(),
    });
    return *pair->second;
}

} // namespace Vulkan
