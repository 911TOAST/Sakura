// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2021 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <ankerl/unordered_dense.h>

#include <boost/container/static_vector.hpp>

#include "common/settings.h"
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
                                                      bool clear_stencil, bool elide_store,
                                                      bool afbc) {
            using MaxwellToVK::SurfaceFormat;

            const SurfaceType surface_type = GetSurfaceType(format);
            const bool has_stencil = surface_type == SurfaceType::DepthStencil ||
                                     surface_type == SurfaceType::Stencil;
            const bool is_depth = surface_type == SurfaceType::Depth ||
                                  surface_type == SurfaceType::DepthStencil;
            const bool clear{is_depth ? clear_depth : clear_color};
            // AFBC keeps the attachment in its optimal layout at rest (begin and end) so Mali never
            // decompresses the tile back to GENERAL between passes; otherwise it rests in GENERAL.
            const VkImageLayout rest_layout =
                afbc ? (is_depth ? VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
                                 : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                     : VK_IMAGE_LAYOUT_GENERAL;

            return {
                .flags = {},
                .format = SurfaceFormat(device, FormatType::Optimal, true, format).format,
                .samples = samples,
                .loadOp = clear ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD,
                .storeOp = elide_store ? VK_ATTACHMENT_STORE_OP_DONT_CARE
                                       : VK_ATTACHMENT_STORE_OP_STORE,
                .stencilLoadOp = !has_stencil ? VK_ATTACHMENT_LOAD_OP_DONT_CARE
                                              : (clear_stencil ? VK_ATTACHMENT_LOAD_OP_CLEAR
                                                               : VK_ATTACHMENT_LOAD_OP_LOAD),
                .stencilStoreOp = has_stencil ? (elide_store ? VK_ATTACHMENT_STORE_OP_DONT_CARE
                                                             : VK_ATTACHMENT_STORE_OP_STORE)
                                                  : VK_ATTACHMENT_STORE_OP_DONT_CARE,
                .initialLayout = rest_layout,
                .finalLayout = rest_layout,
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
    const bool optimal_refs = key.optimal_layout || key.afbc_layout;
    const VkImageLayout color_layout = key.feedback_loop
                                           ? VK_IMAGE_LAYOUT_ATTACHMENT_FEEDBACK_LOOP_OPTIMAL_EXT
                                           : (optimal_refs
                                                  ? VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                                                  : VK_IMAGE_LAYOUT_GENERAL);
    // Depth stays GENERAL in the feedback variant: the sampled surface of a feedback draw may be
    // the depth attachment, and depth-optimal layouts do not allow shader reads.
    const VkImageLayout depth_layout = (optimal_refs && !key.feedback_loop)
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
            const bool elide_store = (key.store_elide_mask >> num_colors) & 1;
            descriptions.push_back(AttachmentDescription(*device, format, key.samples,
                                                         key.color_clear, key.depth_clear,
                                                         key.stencil_clear, elide_store,
                                                         key.afbc_layout));
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
                                                     key.stencil_clear,
                                                     (key.store_elide_mask >> num_colors) & 1,
                                                     key.afbc_layout));
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
    // Experimental stage-mask narrowing (renderer_narrow_pass_barriers, default off). ALL_COMMANDS
    // makes the next pass's vertex/geometry work wait for every fragment stage; Arm's Mali guidance
    // calls that a pipeline bubble. Narrow to the stages that actually produce (entry src) and
    // consume (exit dst) attachments. Access masks stay unchanged so cache coherency is preserved.
    // The setting is constant for the session, so every variant shares the same dependency list and
    // render pass compatibility holds; toggle it before launch, not mid-game.
    const bool narrow_barriers = Settings::values.renderer_narrow_pass_barriers.GetValue();
    const VkPipelineStageFlags entry_src_stage =
        narrow_barriers ? (VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT |
                           VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT |
                           VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                           VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT |
                           VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT)
                        : VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    const VkPipelineStageFlags exit_dst_stage =
        narrow_barriers ? (VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT |
                           VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                           VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT |
                           VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT |
                           VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT)
                        : VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
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
        .srcStageMask = entry_src_stage,
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
        // Early fragment tests are part of the source scope so depth/stencil writes from shaders
        // that run their tests early are ordered too. This dependency is now the only thing that
        // publishes attachment writes after the pass; the explicit image barrier that used to do
        // it was removed from Scheduler::EndRenderPass.
        .srcStageMask = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT |
                        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                        VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
        .dstStageMask = exit_dst_stage,
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
