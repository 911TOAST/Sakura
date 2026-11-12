// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2019 yuzu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <span>

#include "video_core/texture_cache/texture_cache_base.h"

#include "shader_recompiler/shader_info.h"
#include "video_core/renderer_vulkan/vk_compute_pass.h"
#include "video_core/renderer_vulkan/vk_render_pass_cache.h"
#include "video_core/renderer_vulkan/vk_staging_buffer_pool.h"
#include "video_core/texture_cache/image_view_base.h"
#include "video_core/vulkan_common/vulkan_memory_allocator.h"
#include "video_core/vulkan_common/vulkan_wrapper.h"

namespace Settings {
struct ResolutionScalingInfo;
}

namespace Vulkan {

using Common::SlotVector;
using VideoCommon::ImageId;
using VideoCommon::NUM_RT;
using VideoCommon::Region2D;
using VideoCore::Surface::PixelFormat;

class BlitImageHelper;
class DescriptorPool;
class Device;
class Image;
class ImageView;
class Framebuffer;
class RenderPassCache;
class StagingBufferPool;
class Scheduler;

/// True when AFBC-native layout tracking is active: the experimental toggle is on and the device
/// is a Mali GPU (whose driver applies lossless AFBC only to images kept out of GENERAL). When
/// false every image rests in GENERAL exactly as before, so the legacy path is unchanged.
[[nodiscard]] bool UseAfbcLayouts(const Device& device);

class TextureCacheRuntime {
public:
    explicit TextureCacheRuntime(const Device& device_, Scheduler& scheduler_,
                                 MemoryAllocator& memory_allocator_,
                                 StagingBufferPool& staging_buffer_pool_,
                                 BlitImageHelper& blit_image_helper_,
                                 RenderPassCache& render_pass_cache_,
                                 DescriptorPool& descriptor_pool,
                                 ComputePassDescriptorQueue& compute_pass_descriptor_queue);

    void Finish();

    StagingBufferRef UploadStagingBuffer(size_t size, bool deferred = false);

    StagingBufferRef DownloadStagingBuffer(size_t size, bool deferred = false);

    void FreeDeferredStagingBuffer(StagingBufferRef& ref);

    void TickFrame();

    u64 GetDeviceLocalMemory() const;

    u64 GetDeviceMemoryUsage() const;

    bool CanReportMemoryUsage() const;

    std::optional<size_t> GetSamplerHeapBudget() const;

    void BlitImage(Framebuffer* dst_framebuffer, ImageView& dst, ImageView& src,
                   const Region2D& dst_region, const Region2D& src_region,
                   Tegra::Engines::Fermi2D::Filter filter,
                   Tegra::Engines::Fermi2D::Operation operation);

    void CopyImage(Image& dst, Image& src, std::span<const VideoCommon::ImageCopy> copies);

    void CopyImageMSAA(Image& dst, Image& src, std::span<const VideoCommon::ImageCopy> copies);

    bool ShouldReinterpret(Image& dst, Image& src);

    void ReinterpretImage(Image& dst, Image& src, std::span<const VideoCommon::ImageCopy> copies);

    void ConvertImage(Framebuffer* dst, ImageView& dst_view, ImageView& src_view);

    bool CanAccelerateImageUpload(Image&) const noexcept {
        return false;
    }

    bool CanUploadMSAA() const noexcept {
        return msaa_copy_pass.operator bool();
    }

    void AccelerateImageUpload(Image&, const StagingBufferRef&,
                               std::span<const VideoCommon::SwizzleParameters>,
                               u32 z_start, u32 z_count);

    void InsertUploadMemoryBarrier() {}

    void TransitionImageLayout(Image& image);

    /// AFBC layout hooks invoked by the texture cache before an image is rendered to or sampled.
    /// No-ops unless UseAfbcLayouts() is active; the legacy GENERAL path is then untouched.
    void PrepareRenderTargetLayout(Image& image);
    void PrepareSampledImageLayout(Image& image);

    bool HasBrokenTextureViewFormats() const noexcept {
        // No known Vulkan driver has broken image views
        return false;
    }

    bool HasNativeBgr() const noexcept {
        // All known Vulkan drivers can natively handle BGR textures
        return true;
    }

    [[nodiscard]] VkBuffer GetTemporaryBuffer(size_t needed_size);

    std::span<const VkFormat> ViewFormats(PixelFormat format) {
        return view_formats[static_cast<std::size_t>(format)];
    }

    void BarrierFeedbackLoop();

    bool IsFormatDitherable(VideoCore::Surface::PixelFormat format);
    bool IsFormatScalable(VideoCore::Surface::PixelFormat format);

    VkFormat GetSupportedFormat(VkFormat requested_format, VkFormatFeatureFlags required_features) const;

    const Device& device;
    Scheduler& scheduler;
    MemoryAllocator& memory_allocator;
    StagingBufferPool& staging_buffer_pool;
    BlitImageHelper& blit_image_helper;
    RenderPassCache& render_pass_cache;
    std::optional<ASTCDecoderPass> astc_decoder_pass;

    std::optional<BlockLinearUnswizzle3DPass> bl3d_unswizzle_pass;
    std::optional<MSAACopyPass> msaa_copy_pass;
    const Settings::ResolutionScalingInfo& resolution;
    std::array<std::vector<VkFormat>, VideoCore::Surface::MaxPixelFormat> view_formats;

    static constexpr size_t indexing_slots = 8 * sizeof(size_t);
    std::array<vk::Buffer, indexing_slots> buffers{};
};

class Framebuffer {
public:
    explicit Framebuffer(TextureCacheRuntime& runtime, std::span<ImageView*, NUM_RT> color_buffers,
                         ImageView* depth_buffer, const VideoCommon::RenderTargets& key);

    explicit Framebuffer(TextureCacheRuntime& runtime, ImageView* color_buffer,
                         ImageView* depth_buffer, VkExtent2D extent, bool is_rescaled);

    ~Framebuffer();

    Framebuffer(const Framebuffer&) = delete;
    Framebuffer& operator=(const Framebuffer&) = delete;

    Framebuffer(Framebuffer&&) = default;
    Framebuffer& operator=(Framebuffer&&) = default;

    void CreateFramebuffer(TextureCacheRuntime& runtime,
                           std::span<ImageView*, NUM_RT> color_buffers, ImageView* depth_buffer,
                           bool is_rescaled = false);

    [[nodiscard]] VkFramebuffer Handle() const noexcept {
        return *framebuffer;
    }

    [[nodiscard]] VkRenderPass RenderPass(bool general_layout = false) const noexcept {
        return general_layout ? general_renderpass : renderpass;
    }

    [[nodiscard]] VkExtent2D RenderArea() const noexcept {
        return render_area;
    }

    [[nodiscard]] VkSampleCountFlagBits Samples() const noexcept {
        return samples;
    }

    [[nodiscard]] u32 NumColorBuffers() const noexcept {
        return num_color_buffers;
    }

    [[nodiscard]] u32 NumImages() const noexcept {
        return num_images;
    }

    [[nodiscard]] const std::array<VkImage, 9>& Images() const noexcept {
        return images;
    }

    [[nodiscard]] const std::array<VkImageSubresourceRange, 9>& ImageRanges() const noexcept {
        return image_ranges;
    }

    [[nodiscard]] bool HasAspectColorBit(size_t index) const noexcept {
        return (image_ranges.at(rt_map[index]).aspectMask & VK_IMAGE_ASPECT_COLOR_BIT) != 0;
    }

    [[nodiscard]] bool HasAspectDepthBit() const noexcept {
        return has_depth;
    }

    [[nodiscard]] bool HasAspectStencilBit() const noexcept {
        return has_stencil;
    }

    [[nodiscard]] bool IsRescaled() const noexcept {
        return is_rescaled;
    }

    /// Variant of RenderPass() whose attachments are defined at pass begin rather than loaded.
    /// VK_NULL_HANDLE when the framebuffer has no such variant.
    [[nodiscard]] VkRenderPass ClearRenderPass(bool general_layout = false) const noexcept {
        return general_layout ? general_clear_renderpass : clear_renderpass;
    }

    /// Feedback-loop variant: colour attachments stay in ATTACHMENT_FEEDBACK_LOOP_OPTIMAL_EXT
    /// during the subpass. Aliased to the GENERAL passes when the extension is unavailable.
    [[nodiscard]] VkRenderPass FeedbackRenderPass() const noexcept {
        return feedback_renderpass;
    }

    [[nodiscard]] VkRenderPass FeedbackClearRenderPass() const noexcept {
        return feedback_clear_renderpass;
    }

    /// Variant of the pass selected by (general_layout, feedback_layout, clear) whose attachment
    /// stores are elided per \p mask (bit k = attachment k in description order: colours then
    /// depth). Used when the next pass re-clears those attachments so their writeback is dead.
    /// loadOp/storeOp do not affect render pass compatibility, so this shares the framebuffer and
    /// pipelines of the storing variant.
    [[nodiscard]] VkRenderPass RenderPassStoreElide(u16 mask, bool general_layout,
                                                    bool feedback_layout, bool clear) const;

private:
    vk::Framebuffer framebuffer;
    VkRenderPass renderpass{};
    VkRenderPass clear_renderpass{};
    /// Variants of the two passes above whose attachment references use GENERAL. Draws that sample
    /// the render target they write to must use these.
    VkRenderPass general_renderpass{};
    VkRenderPass general_clear_renderpass{};
    /// Feedback-loop variants of the optimal passes; aliased to GENERAL without the extension.
    VkRenderPass feedback_renderpass{};
    VkRenderPass feedback_clear_renderpass{};
    VkExtent2D render_area{};
    VkSampleCountFlagBits samples = VK_SAMPLE_COUNT_1_BIT;
    u32 num_color_buffers = 0;
    u32 num_images = 0;
    std::array<VkImage, 9> images{};
    std::array<VkImageSubresourceRange, 9> image_ranges{};
    std::array<size_t, NUM_RT> rt_map{};
    bool has_depth{};
    bool has_stencil{};
    bool is_rescaled{};
    /// Base render pass key (optimal layout as configured, no clears) kept so a store-elision
    /// variant of any layout/clear combination can be rebuilt on demand.
    RenderPassKey base_key{};
    RenderPassCache* pass_cache{};
    bool feedback_supported{};
};

class Image : public VideoCommon::ImageBase {
public:
    explicit Image(TextureCacheRuntime&, const VideoCommon::ImageInfo& info, GPUVAddr gpu_addr,
                   VAddr cpu_addr);
    explicit Image(TextureCacheRuntime&, const VideoCommon::ImageInfo& info, GPUVAddr gpu_addr,
                   VAddr cpu_addr, vk::Image existing_image);
    explicit Image(const VideoCommon::NullImageParams&);

    ~Image();

    Image(const Image&) = delete;
    Image& operator=(const Image&) = delete;

    Image(Image&&) = default;
    Image& operator=(Image&&) = default;

    void UploadMemory(VkBuffer buffer, VkDeviceSize offset,
                      std::span<const VideoCommon::BufferImageCopy> copies);

    void UploadMemory(const StagingBufferRef& map,
                      std::span<const VideoCommon::BufferImageCopy> copies);

    void DownloadMemory(VkBuffer buffer, size_t offset,
                        std::span<const VideoCommon::BufferImageCopy> copies);

    void DownloadMemory(std::span<VkBuffer> buffers, std::span<size_t> offsets,
                        std::span<const VideoCommon::BufferImageCopy> copies);

    void DownloadMemory(const StagingBufferRef& map,
                        std::span<const VideoCommon::BufferImageCopy> copies);

    void AllocateComputeUnswizzleImage();

    /// AFBC-native layout tracking. Records a barrier from the tracked layout to \p new_layout and
    /// updates the tracker; a no-op when already in that layout or when AFBC tracking is disabled
    /// (the legacy GENERAL paths then remain solely responsible for transitions). \p old_layout may
    /// be UNDEFINED to discard contents.
    void TransitionLayout(VkImageLayout new_layout, VkAccessFlags src_access, VkAccessFlags dst_access,
                          VkPipelineStageFlags src_stage, VkPipelineStageFlags dst_stage);

    /// The AFBC-friendly layout this image rests in when not being rendered: SHADER_READ_ONLY_OPTIMAL
    /// for colour, DEPTH_STENCIL_ATTACHMENT_OPTIMAL for depth/stencil. GENERAL when tracking is off.
    [[nodiscard]] VkImageLayout AfbcRestLayout() const noexcept;

    [[nodiscard]] VkImageLayout CurrentLayout() const noexcept {
        return current_layout;
    }

    /// Adopts a layout established by an external barrier (e.g. a render pass's automatic
    /// transitions or a transfer helper) without recording a new barrier.
    void SetCurrentLayout(VkImageLayout layout) noexcept {
        current_layout = layout;
    }

    /// Transfer guards: the legacy upload/blit/download helpers assume GENERAL, so under AFBC the
    /// image is moved to GENERAL for the transfer and restored to its AFBC rest layout afterwards.
    /// No-ops when AFBC tracking is off. Pair with SCOPE_EXIT for early-return safety.
    void BeginTransfer();
    void EndTransfer();

    [[nodiscard]] VkImage Handle() const noexcept {
        return *(this->*current_image);
    }

    [[nodiscard]] VkImageAspectFlags AspectMask() const noexcept {
        return aspect_mask;
    }

    [[nodiscard]] VkImageUsageFlags UsageFlags() const noexcept {
        return (this->*current_image).UsageFlags();
    }

    /// Returns true when the image is already initialized and mark it as initialized
    [[nodiscard]] bool ExchangeInitialization() noexcept {
        return std::exchange(initialized, true);
    }

    VkImageView StorageImageView(s32 level) noexcept;

    bool IsRescaled() const noexcept;

    bool ScaleUp(bool ignore = false);

    bool ScaleDown(bool ignore = false);

    /// Releases the cached rescaling copy and falls back to the unscaled image. The caller must
    /// guarantee the device is idle: the next ScaleUp() allocates a fresh copy using whatever
    /// resolution is selected at that point, which is what a mid-session resolution change needs.
    void DropScaledCopy();

    u64 allocation_tick;

    friend class BlockLinearUnswizzle3DPass;

private:
    bool BlitScaleHelper(bool scale_up);

    bool NeedsScaleHelper() const;

    Scheduler* scheduler{};
    TextureCacheRuntime* runtime{};

    vk::Image original_image;
    vk::Image scaled_image;

    vk::Buffer compute_unswizzle_buffer;
    VkDeviceSize compute_unswizzle_buffer_size = 0;
    bool has_compute_unswizzle_buffer = false;

    void AllocateComputeUnswizzleBuffer(u32 max_slices);

    // Use a pointer to field because it is relative, so that the object can be
    // moved without breaking the reference.
    vk::Image Image::*current_image{};

    std::vector<vk::ImageView> storage_image_views;
    VkImageAspectFlags aspect_mask = 0;
    bool initialized = false;
    /// Tracked Vulkan layout of the current image handle. UNDEFINED until first transition. Only
    /// consulted when UseAfbcLayouts() is true; the legacy path keeps everything in GENERAL.
    VkImageLayout current_layout = VK_IMAGE_LAYOUT_UNDEFINED;
    /// Per-handle layouts so a rescale swap (original <-> scaled) restores the right layout instead
    /// of applying one handle's layout to the other. current_layout mirrors the active handle.
    VkImageLayout original_layout = VK_IMAGE_LAYOUT_UNDEFINED;
    VkImageLayout scaled_layout = VK_IMAGE_LAYOUT_UNDEFINED;

    /// Records a layout barrier for a specific handle and updates its tracker. Shared by
    /// TransitionLayout (active handle) and the rescale guards (both handles).
    void TransitionHandleLayout(VkImage image, VkImageLayout& layout, VkImageLayout new_layout,
                                VkAccessFlags src_access, VkAccessFlags dst_access,
                                VkPipelineStageFlags src_stage, VkPipelineStageFlags dst_stage);

    std::optional<Framebuffer> scale_framebuffer;
    std::optional<Framebuffer> normal_framebuffer;
    std::unique_ptr<ImageView> scale_view;
    std::unique_ptr<ImageView> normal_view;
};

class ImageView : public VideoCommon::ImageViewBase {
public:
    explicit ImageView(TextureCacheRuntime&, const VideoCommon::ImageViewInfo&, ImageId, Image&);
    explicit ImageView(TextureCacheRuntime&, const VideoCommon::ImageViewInfo&, ImageId, Image&,
                       SlotVector<Image>&);
    explicit ImageView(TextureCacheRuntime&, const VideoCommon::ImageInfo&,
                       const VideoCommon::ImageViewInfo&, GPUVAddr);
    explicit ImageView(TextureCacheRuntime&, const VideoCommon::NullImageViewParams&);

    ~ImageView();

    ImageView(const ImageView&) = delete;
    ImageView& operator=(const ImageView&) = delete;

    ImageView(ImageView&&) = default;
    ImageView& operator=(ImageView&&) = default;

    [[nodiscard]] VkImageView DepthView();

    [[nodiscard]] VkImageView StencilView();

    [[nodiscard]] VkImageView ColorView();

    [[nodiscard]] VkImageView StorageView(Shader::TextureType texture_type,
                                          Shader::ImageFormat image_format);

    [[nodiscard]] bool IsRescaled() const noexcept;

    [[nodiscard]] VkImageView Handle(Shader::TextureType texture_type) const noexcept {
        return *image_views[static_cast<size_t>(texture_type)];
    }

    [[nodiscard]] VkImage ImageHandle() const noexcept {
        return image_handle;
    }

    /// The backing Image (for AFBC transfer guards). Null when this view has no slot image.
    [[nodiscard]] Image* GetImage() const noexcept;

    [[nodiscard]] VkImageView RenderTarget() const noexcept {
        return render_target;
    }

    [[nodiscard]] VkSampleCountFlagBits Samples() const noexcept {
        return samples;
    }

    [[nodiscard]] bool SupportsDepthComparison() const noexcept {
        return supports_depth_comparison;
    }

    [[nodiscard]] GPUVAddr GpuAddr() const noexcept {
        return gpu_addr;
    }

    [[nodiscard]] u32 BufferSize() const noexcept {
        return buffer_size;
    }

private:
    struct StorageViews {
        std::array<vk::ImageView, Shader::NUM_TEXTURE_TYPES> signeds;
        std::array<vk::ImageView, Shader::NUM_TEXTURE_TYPES> unsigneds;
    };

    [[nodiscard]] vk::ImageView MakeView(VkFormat vk_format, VkImageAspectFlags aspect_mask,
                                         std::optional<Shader::TextureType> texture_type = std::nullopt);

    const Device* device = nullptr;
    SlotVector<Image>* slot_images = nullptr;

    std::array<vk::ImageView, Shader::NUM_TEXTURE_TYPES> image_views;
    std::optional<StorageViews> storage_views;
    vk::ImageView typeless_storage_view;
    vk::ImageView depth_view;
    vk::ImageView stencil_view;
    vk::ImageView color_view;
    vk::Image null_image;
    VkImage image_handle = VK_NULL_HANDLE;
    VkImageView render_target = VK_NULL_HANDLE;
    VkSampleCountFlagBits samples = VK_SAMPLE_COUNT_1_BIT;
    u32 buffer_size = 0;

    bool uses_widened_astc_format = false;
    bool supports_depth_comparison = false;
};

class ImageAlloc : public VideoCommon::ImageAllocBase {};

class Sampler {
public:
    explicit Sampler(TextureCacheRuntime&, const Tegra::Texture::TSCEntry&);

    [[nodiscard]] VkSampler Handle() const noexcept {
        return *sampler;
    }

    [[nodiscard]] VkSampler HandleWithDefaultAnisotropy() const noexcept {
        return *sampler_default_anisotropy;
    }

    [[nodiscard]] bool HasAddedAnisotropy() const noexcept {
        return static_cast<bool>(sampler_default_anisotropy);
    }

    [[nodiscard]] VkSampler HandleWithNearestFilter() const noexcept {
        return *sampler_nearest;
    }

    [[nodiscard]] bool HasLinearFiltering() const noexcept {
        return static_cast<bool>(sampler_nearest);
    }

    [[nodiscard]] VkSampler HandleWithoutDepthComparison() const noexcept {
        return *sampler_noncompare;
    }

    [[nodiscard]] bool HasDepthComparison() const noexcept {
        return static_cast<bool>(sampler_noncompare);
    }

private:
    vk::Sampler sampler;
    vk::Sampler sampler_default_anisotropy;
    vk::Sampler sampler_nearest;
    vk::Sampler sampler_noncompare;
};

struct TextureCacheParams {
    static constexpr bool ENABLE_VALIDATION = true;
    static constexpr bool FRAMEBUFFER_BLITS = false;
    static constexpr bool HAS_EMULATED_COPIES = false;
    static constexpr bool HAS_DEVICE_MEMORY_INFO = true;
    static constexpr bool IMPLEMENTS_ASYNC_DOWNLOADS = true;

    using Runtime = Vulkan::TextureCacheRuntime;
    using Image = Vulkan::Image;
    using ImageAlloc = Vulkan::ImageAlloc;
    using ImageView = Vulkan::ImageView;
    using Sampler = Vulkan::Sampler;
    using Framebuffer = Vulkan::Framebuffer;
    using AsyncBuffer = Vulkan::StagingBufferRef;
    using BufferType = VkBuffer;
};

using TextureCache = VideoCommon::TextureCache<TextureCacheParams>;

} // namespace Vulkan
