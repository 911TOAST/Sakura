#include <algorithm>
#include <cmath>
#include <initializer_list>

#include "video_core/renderer_vulkan/present/asr.h"

#include <backends/vk/ffxm_vk.h>
#include <ffxm_fsr2.h>

#include "asr_vk_set.h"
#include "common/div_ceil.h"
#include "common/logging.h"
#include "common/settings.h"
#include "video_core/asr_state.h"
#include "video_core/host_shaders/vulkan_asr_down_comp_spv.h"
#include "video_core/host_shaders/vulkan_asr_flow_comp_spv.h"
#include "video_core/host_shaders/vulkan_asr_ingest_comp_spv.h"
#include "video_core/host_shaders/vulkan_asr_resolve_comp_spv.h"
#include "video_core/renderer_vulkan/present/util.h"
#include "video_core/renderer_vulkan/vk_scheduler.h"
#include "video_core/renderer_vulkan/vk_shader_util.h"
#include "video_core/vulkan_common/vulkan_device.h"

namespace Vulkan {

namespace {

struct PcA {
    std::array<f32, 4> cr;
    std::array<f32, 4> dr;
    std::array<f32, 2> jt;
    std::array<s32, 2> sz;
    u32 inv;
    u32 rst;
};
static_assert(sizeof(PcA) == 56);

struct PcB {
    std::array<s32, 2> sz;
};
static_assert(sizeof(PcB) == 8);

struct PcC {
    std::array<s32, 2> sz;
    s32 r;
    s32 crs;
    s32 sub;
};
static_assert(sizeof(PcC) == 20);

struct PcD {
    std::array<s32, 2> sz;
    std::array<s32, 2> fz;
    f32 cth;
    u32 rst;
};
static_assert(sizeof(PcD) == 24);

constexpr VkFormat f_c = VK_FORMAT_R8G8B8A8_UNORM;
constexpr VkFormat f_d = VK_FORMAT_R32_SFLOAT;
constexpr VkFormat f_mv = VK_FORMAT_R16G16_SFLOAT;
constexpr VkFormat f_r = VK_FORMAT_R8_UNORM;
constexpr VkFormat f_l = VK_FORMAT_R16_SFLOAT;
constexpr VkFormat f_f = VK_FORMAT_R16G16B16A16_SFLOAT;
constexpr f32 cut_th = 0.14f;

constexpr VkPipelineStageFlags st_cs = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
constexpr VkPipelineStageFlags st_fs = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
constexpr VkPipelineStageFlags st_rt = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;

std::array<s32, 2> Sz(VkExtent2D e) {
    return {static_cast<s32>(e.width), static_cast<s32>(e.height)};
}

VkExtent2D Half(VkExtent2D e) {
    return {Common::DivCeil(e.width, 2u), Common::DivCeil(e.height, 2u)};
}

bool Fmt(const Device& d, VkFormat f) {
    return d.IsFormatSupported(f,
                               VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT |
                                   VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
                                   VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT,
                               FormatType::Optimal);
}

vk::DescriptorSetLayout Lay(const Device& d, std::initializer_list<VkDescriptorType> ts) {
    std::array<VkDescriptorSetLayoutBinding, 8> bs{};
    u32 n = 0;
    for (const VkDescriptorType t : ts) {
        bs[n] = VkDescriptorSetLayoutBinding{
            .binding = n,
            .descriptorType = t,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
            .pImmutableSamplers = nullptr,
        };
        ++n;
    }
    return d.GetLogical().CreateDescriptorSetLayout(VkDescriptorSetLayoutCreateInfo{
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .bindingCount = n,
        .pBindings = bs.data(),
    });
}

vk::PipelineLayout PLay(const Device& d, std::initializer_list<VkDescriptorSetLayout> ls,
                        u32 pcs) {
    const VkPushConstantRange rg{
        .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        .offset = 0,
        .size = pcs,
    };
    return d.GetLogical().CreatePipelineLayout(VkPipelineLayoutCreateInfo{
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .setLayoutCount = static_cast<u32>(ls.size()),
        .pSetLayouts = ls.begin(),
        .pushConstantRangeCount = 1,
        .pPushConstantRanges = &rg,
    });
}

vk::Pipeline Pipe(const Device& d, const vk::ShaderModule& m, const vk::PipelineLayout& l) {
    return d.GetLogical().CreateComputePipeline(VkComputePipelineCreateInfo{
        .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .stage{
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .stage = VK_SHADER_STAGE_COMPUTE_BIT,
            .module = *m,
            .pName = "main",
            .pSpecializationInfo = nullptr,
        },
        .layout = *l,
        .basePipelineHandle = VK_NULL_HANDLE,
        .basePipelineIndex = 0,
    });
}

void Mb(const vk::CommandBuffer& cb, VkPipelineStageFlags s, VkPipelineStageFlags t,
        VkAccessFlags sa, VkAccessFlags ta) {
    cb.PipelineBarrier(s, t, 0,
                       VkMemoryBarrier{
                           .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
                           .pNext = nullptr,
                           .srcAccessMask = sa,
                           .dstAccessMask = ta,
                       });
}

void Cs(const vk::CommandBuffer& cb) {
    Mb(cb, st_cs, st_cs, VK_ACCESS_SHADER_WRITE_BIT,
       VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
}

VkImageMemoryBarrier Ib(VkImage img) {
    return VkImageMemoryBarrier{
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .pNext = nullptr,
        .srcAccessMask = 0,
        .dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT |
                         VK_ACCESS_COLOR_ATTACHMENT_READ_BIT |
                         VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
        .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        .newLayout = VK_IMAGE_LAYOUT_GENERAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = img,
        .subresourceRange{
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .baseMipLevel = 0,
            .levelCount = 1,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
    };
}

arm::FfxmResource Rx(VkImage img, arm::FfxmSurfaceFormat f, VkExtent2D e,
                     arm::FfxmResourceUsage u) {
    arm::FfxmResourceDescription ds{};
    ds.type = arm::FFXM_RESOURCE_TYPE_TEXTURE2D;
    ds.format = f;
    ds.width = e.width;
    ds.height = e.height;
    ds.depth = 1;
    ds.mipCount = 1;
    ds.flags = arm::FFXM_RESOURCE_FLAGS_NONE;
    ds.usage = u;
    wchar_t nm[1] = {0};
    return arm::ffxmGetResourceVK(reinterpret_cast<void*>(img), ds, nm,
                                  arm::FFXM_RESOURCE_STATE_UNORDERED_ACCESS);
}

class Wr {
public:
    explicit Wr(size_t n) {
        im.reserve(n);
        bf.reserve(n);
        w.reserve(n);
    }

    void Img(VkDescriptorSet s, u32 b, VkImageView v, VkSampler sm) {
        im.push_back(VkDescriptorImageInfo{
            .sampler = sm,
            .imageView = v,
            .imageLayout = VK_IMAGE_LAYOUT_GENERAL,
        });
        w.push_back(VkWriteDescriptorSet{
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .pNext = nullptr,
            .dstSet = s,
            .dstBinding = b,
            .dstArrayElement = 0,
            .descriptorCount = 1,
            .descriptorType = sm != VK_NULL_HANDLE ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                                                   : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
            .pImageInfo = &im.back(),
            .pBufferInfo = nullptr,
            .pTexelBufferView = nullptr,
        });
    }

    void Buf(VkDescriptorSet s, u32 b, VkBuffer x) {
        bf.push_back(VkDescriptorBufferInfo{
            .buffer = x,
            .offset = 0,
            .range = VK_WHOLE_SIZE,
        });
        w.push_back(VkWriteDescriptorSet{
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .pNext = nullptr,
            .dstSet = s,
            .dstBinding = b,
            .dstArrayElement = 0,
            .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
            .pImageInfo = nullptr,
            .pBufferInfo = &bf.back(),
            .pTexelBufferView = nullptr,
        });
    }

    void Go(const Device& d) const {
        d.GetLogical().UpdateDescriptorSets(w, {});
    }

private:
    std::vector<VkDescriptorImageInfo> im;
    std::vector<VkDescriptorBufferInfo> bf;
    std::vector<VkWriteDescriptorSet> w;
};

}

struct ASR::Cx {
    arm::VkDeviceContext vdc{};
    std::unique_ptr<u8[]> scr;
    std::unique_ptr<arm::FfxmFsr2Context> ctx;
    VkExtent2D ns{};
    vk::Image out;
    vk::ImageView ov;
    bool live{};
    bool fresh{true};

    ~Cx() {
        if (live) {
            arm::ffxmFsr2ContextDestroy(ctx.get());
        }
    }
};

struct ASR::Rs {
    VkExtent2D sz{};
    std::array<VkExtent2D, 3> lz{};
    std::array<vk::Image, 2> img_c;
    std::array<vk::ImageView, 2> v_c;
    vk::Image img_d;
    vk::ImageView v_d;
    vk::Image img_mv;
    vk::ImageView v_mv;
    vk::Image img_r;
    vk::ImageView v_r;
    std::array<std::array<vk::Image, 3>, 2> img_l;
    std::array<std::array<vk::ImageView, 3>, 2> v_l;
    std::array<vk::Image, 3> img_f;
    std::array<vk::ImageView, 3> v_f;
    vk::Buffer cut;
    vk::DescriptorPool pool;
    vk::DescriptorSets s_a0;
    vk::DescriptorSets s_a1;
    vk::DescriptorSets s_b;
    vk::DescriptorSets s_c;
    vk::DescriptorSets s_d;
    bool fresh{true};
};

void Tq::Init(const Device& d, size_t n, u32 k) {
    const VkPhysicalDeviceProperties pr = d.GetPhysical().GetProperties();
    if (!pr.limits.timestampComputeAndGraphics || pr.limits.timestampPeriod <= 0.0f || k > 4) {
        return;
    }
    const auto qf = d.GetPhysical().GetQueueFamilyProperties();
    const u32 gf = d.GetGraphicsFamily();
    if (gf >= qf.size() || qf[gf].timestampValidBits == 0) {
        return;
    }
    const u32 vb = qf[gf].timestampValidBits;
    wt = reinterpret_cast<PFN_vkCmdWriteTimestamp>(
        d.GetDispatchLoader().vkGetDeviceProcAddr(*d.GetLogical(), "vkCmdWriteTimestamp"));
    if (!wt) {
        return;
    }
    msk = vb >= 64 ? ~u64{0} : ((u64{1} << vb) - 1);
    per = static_cast<f64>(pr.limits.timestampPeriod);
    k_n = k;
    live.assign(n, 0);
    qp = d.GetLogical().CreateQueryPool(VkQueryPoolCreateInfo{
        .sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .queryType = VK_QUERY_TYPE_TIMESTAMP,
        .queryCount = static_cast<u32>(n) * k,
        .pipelineStatistics = 0,
    });
}

void Tq::Begin(vk::CommandBuffer cb, size_t i) const {
    cb.ResetQueryPool(*qp, static_cast<u32>(i) * k_n, k_n);
}

void Tq::Mark(vk::CommandBuffer cb, size_t i, u32 j) const {
    wt(*cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, *qp, static_cast<u32>(i) * k_n + j);
}

bool Tq::Read(const Device& d, size_t i, std::array<u64, 4>& t) const {
    if (live[i] == 0) {
        return false;
    }
    return d.GetLogical().GetQueryResults(*qp, static_cast<u32>(i) * k_n, k_n,
                                          sizeof(u64) * k_n, t.data(), sizeof(u64),
                                          VK_QUERY_RESULT_64_BIT) == VK_SUCCESS;
}

u32 Tq::Us(u64 a, u64 b) const {
    return static_cast<u32>(static_cast<f64>((b - a) & msk) * per / 1000.0);
}

ASR::ASR(const Device& d, MemoryAllocator& a, Scheduler& s, size_t n, VkExtent2D out, u32 q)
    : vk_d{d}, alc{a}, sch{s}, n_img{n}, out_sz{out}, q_g{q} {
    const auto& dl = d.GetDispatchLoader();
    asr_vk_set(dl.vkGetDeviceProcAddr, dl.vkEnumerateDeviceExtensionProperties,
               dl.vkGetPhysicalDeviceFeatures2, dl.vkGetPhysicalDeviceProperties2,
               dl.vkGetPhysicalDeviceMemoryProperties, dl.vkCmdSetViewport, dl.vkCmdSetScissor);
    VkPhysicalDeviceFeatures2 f2{
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
        .pNext = nullptr,
        .features = {},
    };
    d.GetPhysical().GetFeatures2(f2);
    cap = d.IsFloat16Supported() && d.IsShaderInt16Supported() &&
          f2.features.shaderStorageImageExtendedFormats == VK_TRUE &&
          d.IsSubgroupFeatureSupported(VK_SUBGROUP_FEATURE_QUAD_BIT) &&
          (d.GetSubgroupSupportedStages() & VK_SHADER_STAGE_COMPUTE_BIT) != 0 && Fmt(d, f_c) &&
          Fmt(d, f_d) && Fmt(d, f_mv) && Fmt(d, f_r) && Fmt(d, f_l) && Fmt(d, f_f);
    if (!cap) {
        LOG_WARNING(Render_Vulkan, "Arm ASR unavailable on this device, using FSR1");
        return;
    }
    s_lin = CreateBilinearSampler(d);
    s_pt = CreateNearestNeighborSampler(d);
    constexpr VkDescriptorType ci = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    constexpr VkDescriptorType si = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    constexpr VkDescriptorType sb = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    l_a0 = Lay(d, {si, si, si, ci, sb});
    l_a1 = Lay(d, {ci, ci});
    l_b = Lay(d, {ci, si});
    l_c = Lay(d, {ci, ci, ci, ci, si});
    l_d = Lay(d, {ci, ci, ci, ci, si, si, sb});
    pl_a = PLay(d, {*l_a0, *l_a1}, sizeof(PcA));
    pl_b = PLay(d, {*l_b}, sizeof(PcB));
    pl_c = PLay(d, {*l_c}, sizeof(PcC));
    pl_d = PLay(d, {*l_d}, sizeof(PcD));
    m_a = BuildShader(d, VULKAN_ASR_INGEST_COMP_SPV);
    m_b = BuildShader(d, VULKAN_ASR_DOWN_COMP_SPV);
    m_c = BuildShader(d, VULKAN_ASR_FLOW_COMP_SPV);
    m_d = BuildShader(d, VULKAN_ASR_RESOLVE_COMP_SPV);
    p_a = Pipe(d, m_a, pl_a);
    p_b = Pipe(d, m_b, pl_b);
    p_c = Pipe(d, m_c, pl_c);
    p_d = Pipe(d, m_d, pl_d);
    tq.Init(d, n, 3);
}

ASR::~ASR() {
    if (bld.joinable()) {
        bld.join();
    }
    auto& st = VideoCore::asr_st;
    if (st.jon.exchange(0) != 0) {
        st.jg.fetch_add(1);
    }
    st.mode.store(0);
}

bool ASR::Build(Cx& c, const Device& d, u32 q) {
    c.vdc.vkDevice = *d.GetLogical();
    c.vdc.vkPhysicalDevice = d.GetPhysical();
    c.vdc.vkDeviceProcAddr = d.GetDispatchLoader().vkGetDeviceProcAddr;
    const size_t scr_sz = arm::ffxmGetScratchMemorySizeVK(d.GetPhysical(), 1);
    c.scr = std::make_unique<u8[]>(scr_sz);
    arm::FfxmFsr2ContextDescription cd{};
    if (arm::ffxmGetInterfaceVK(&cd.backendInterface, arm::ffxmGetDeviceVK(&c.vdc), c.scr.get(),
                                scr_sz, 1) != arm::FFXM_OK) {
        return false;
    }
    cd.qualityMode = static_cast<arm::FfxmFsr2ShaderQualityMode>(q);
    cd.flags = arm::FFXM_FSR2_ENABLE_DEPTH_INVERTED | arm::FFXM_FSR2_ENABLE_DYNAMIC_RESOLUTION;
    cd.maxRenderSize = {c.ns.width, c.ns.height};
    cd.displaySize = {c.ns.width, c.ns.height};
    c.ctx = std::make_unique<arm::FfxmFsr2Context>();
    if (arm::ffxmFsr2ContextCreate(c.ctx.get(), &cd) != arm::FFXM_OK) {
        return false;
    }
    c.live = true;
    return true;
}

void ASR::Reap() {
    if (res_old && sch.IsFree(res_t)) {
        res_old.reset();
    }
    if (cx_old && sch.IsFree(cx_t)) {
        cx_old.reset();
    }
}

void ASR::Kick(VkExtent2D ns) {
    cx_new = std::make_unique<Cx>();
    cx_new->ns = ns;
    bst.store(1);
    Cx* const c = cx_new.get();
    const Device* const dp = &vk_d;
    const u32 qm = q_g;
    std::atomic<u32>* const bs = &bst;
    bld = std::jthread([c, dp, qm, bs] { bs->store(Build(*c, *dp, qm) ? 2u : 3u); });
}

void ASR::Adopt() {
    const u32 b = bst.load();
    if (b < 2u) {
        return;
    }
    if (bld.joinable()) {
        bld.join();
    }
    bst.store(0);
    if (b == 3u) {
        dead = true;
        cx_new.reset();
        LOG_WARNING(Render_Vulkan, "Arm ASR context creation failed, using FSR1");
        return;
    }
    cx_new->out = CreateWrappedImage(alc, cx_new->ns, f_c);
    cx_new->ov = CreateWrappedImageView(vk_d, cx_new->out, f_c);
    if (cx) {
        if (cx_old) {
            sch.Wait(cx_t);
            cx_old.reset();
        }
        cx_old = std::move(cx);
        cx_t = sch.CurrentTick();
    }
    cx = std::move(cx_new);
    rst = true;
}

void ASR::MakeRs(VkExtent2D sz) {
    if (res && res->sz.width == sz.width && res->sz.height == sz.height) {
        return;
    }
    if (res) {
        if (res_old) {
            sch.Wait(res_t);
            res_old.reset();
        }
        res_old = std::move(res);
        res_t = sch.CurrentTick();
    }
    auto nr = std::make_unique<Rs>();
    Rs& r = *nr;
    r.sz = sz;
    r.lz[0] = Half(sz);
    r.lz[1] = Half(r.lz[0]);
    r.lz[2] = Half(r.lz[1]);
    for (size_t k = 0; k < 2; ++k) {
        r.img_c[k] = CreateWrappedImage(alc, sz, f_c);
        r.v_c[k] = CreateWrappedImageView(vk_d, r.img_c[k], f_c);
        for (size_t lv = 0; lv < 3; ++lv) {
            r.img_l[k][lv] = CreateWrappedImage(alc, r.lz[lv], f_l);
            r.v_l[k][lv] = CreateWrappedImageView(vk_d, r.img_l[k][lv], f_l);
        }
    }
    r.img_d = CreateWrappedImage(alc, sz, f_d);
    r.v_d = CreateWrappedImageView(vk_d, r.img_d, f_d);
    r.img_mv = CreateWrappedImage(alc, sz, f_mv);
    r.v_mv = CreateWrappedImageView(vk_d, r.img_mv, f_mv);
    r.img_r = CreateWrappedImage(alc, sz, f_r);
    r.v_r = CreateWrappedImageView(vk_d, r.img_r, f_r);
    for (size_t lv = 0; lv < 3; ++lv) {
        r.img_f[lv] = CreateWrappedImage(alc, r.lz[lv], f_f);
        r.v_f[lv] = CreateWrappedImageView(vk_d, r.img_f[lv], f_f);
    }
    r.cut = alc.CreateBuffer(
        VkBufferCreateInfo{
            .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .size = 16,
            .usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
            .queueFamilyIndexCount = 0,
            .pQueueFamilyIndices = nullptr,
        },
        MemoryUsage::DeviceLocal);
    r.pool = CreateWrappedDescriptorPool(vk_d, 48 + 2 * n_img, 16 + n_img,
                                         {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                                          VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                          VK_DESCRIPTOR_TYPE_STORAGE_BUFFER});
    const std::array<VkDescriptorSetLayout, 2> la0{*l_a0, *l_a0};
    const std::vector<VkDescriptorSetLayout> la1(n_img, *l_a1);
    const std::array<VkDescriptorSetLayout, 4> lb{*l_b, *l_b, *l_b, *l_b};
    const std::array<VkDescriptorSetLayout, 6> lc{*l_c, *l_c, *l_c, *l_c, *l_c, *l_c};
    const std::array<VkDescriptorSetLayout, 2> ld{*l_d, *l_d};
    r.s_a0 = CreateWrappedDescriptorSets(r.pool, la0);
    r.s_a1 = CreateWrappedDescriptorSets(r.pool, la1);
    r.s_b = CreateWrappedDescriptorSets(r.pool, lb);
    r.s_c = CreateWrappedDescriptorSets(r.pool, lc);
    r.s_d = CreateWrappedDescriptorSets(r.pool, ld);
    Wr w{128};
    for (size_t p = 0; p < 2; ++p) {
        const size_t o = p ^ 1u;
        w.Img(r.s_a0[p], 0, *r.v_c[p], VK_NULL_HANDLE);
        w.Img(r.s_a0[p], 1, *r.v_d, VK_NULL_HANDLE);
        w.Img(r.s_a0[p], 2, *r.v_l[p][0], VK_NULL_HANDLE);
        w.Img(r.s_a0[p], 3, *r.v_l[o][0], *s_pt);
        w.Buf(r.s_a0[p], 4, *r.cut);
        for (size_t k = 0; k < 2; ++k) {
            w.Img(r.s_b[p * 2 + k], 0, *r.v_l[p][k], *s_lin);
            w.Img(r.s_b[p * 2 + k], 1, *r.v_l[p][k + 1], VK_NULL_HANDLE);
        }
        for (size_t k = 0; k < 3; ++k) {
            const size_t lv = 2 - k;
            const VkDescriptorSet cs = r.s_c[p * 3 + k];
            w.Img(cs, 0, *r.v_l[p][lv], *s_lin);
            w.Img(cs, 1, *r.v_l[o][lv], *s_lin);
            w.Img(cs, 2, *r.v_f[std::min<size_t>(lv + 1, 2)], *s_lin);
            w.Img(cs, 3, *r.v_d, *s_pt);
            w.Img(cs, 4, *r.v_f[lv], VK_NULL_HANDLE);
        }
        w.Img(r.s_d[p], 0, *r.v_f[0], *s_pt);
        w.Img(r.s_d[p], 1, *r.v_d, *s_pt);
        w.Img(r.s_d[p], 2, *r.v_c[p], *s_pt);
        w.Img(r.s_d[p], 3, *r.v_c[o], *s_pt);
        w.Img(r.s_d[p], 4, *r.v_mv, VK_NULL_HANDLE);
        w.Img(r.s_d[p], 5, *r.v_r, VK_NULL_HANDLE);
        w.Buf(r.s_d[p], 6, *r.cut);
    }
    w.Go(vk_d);
    res = std::move(nr);
    rst = true;
}

void ASR::Jit(bool on, VkExtent2D rsz, VkExtent2D nsz, VkExtent2D re, bool fh, bool fv) {
    auto& st = VideoCore::asr_st;
    if (!on) {
        jx = 0.0f;
        jy = 0.0f;
        if (st.jon.exchange(0) != 0) {
            st.jg.fetch_add(1);
        }
        return;
    }
    const s32 ph = std::max(
        arm::ffxmFsr2GetJitterPhaseCount(static_cast<s32>(rsz.width), static_cast<s32>(nsz.width)),
        1);
    jit_i = (jit_i + 1) % static_cast<u32>(ph);
    f32 x = 0.0f;
    f32 y = 0.0f;
    arm::ffxmFsr2GetJitterOffset(&x, &y, static_cast<s32>(jit_i), ph);
    jx = x;
    jy = y;
    st.jx.store(fh ? -x : x);
    st.jy.store(fv ? -y : y);
    st.rw.store(re.width);
    st.rh.store(re.height);
    st.jon.store(1);
    st.jg.fetch_add(1);
}

VkImageView ASR::Fb(size_t i, VkImage src, VkImageView sv, VkExtent2D re,
                    const Common::Rectangle<f32>& crop) {
    rst = true;
    Jit(false, {}, {}, {}, false, false);
    VideoCore::asr_st.mode.store(3);
    if (!fsr) {
        fsr.emplace(vk_d, alc, n_img, out_sz);
    }
    return fsr->Draw(vk_d, sch, i, src, sv, re, crop);
}

VkImageView ASR::Draw(const Device& d, size_t i, VkImage src, VkImageView sv, const AsrIn& in,
                      VkExtent2D re, const Common::Rectangle<f32>& crop) {
    auto& st = VideoCore::asr_st;
    Reap();
    if (tq.On()) {
        std::array<u64, 4> t{};
        if (tq.Read(d, i, t)) {
            st.mv_us.store(tq.Us(t[0], t[1]), std::memory_order_relaxed);
            st.asr_us.store(tq.Us(t[1], t[2]), std::memory_order_relaxed);
        }
    }
    const bool ok = cap && !dead && !bad.load(std::memory_order_relaxed) &&
                    in.dv != VK_NULL_HANDLE && in.rs.width >= 64 && in.rs.height >= 64 &&
                    in.rs.width < in.ns.width && in.rs.height <= in.ns.height;
    if (!ok) {
        return Fb(i, src, sv, re, crop);
    }
    Adopt();
    if (!cx || cx->ns.width != in.ns.width || cx->ns.height != in.ns.height) {
        if (bst.load() == 0 && !dead) {
            Kick(in.ns);
        }
        return Fb(i, src, sv, re, crop);
    }
    MakeRs(in.rs);
    Rs& rr = *res;

    const size_t pp = par;
    const VkExtent2D rsz = in.rs;
    const VkExtent2D nsz = in.ns;
    const std::array<VkExtent2D, 3> lz = rr.lz;
    const bool rfresh = rr.fresh;
    const bool cfresh = cx->fresh;
    rr.fresh = false;
    cx->fresh = false;
    const bool r0 = rst;
    const bool reuse = Settings::values.asr_mv_reuse.GetValue() && !r0 && (fr & 1u) != 0;

    const std::array<VkDescriptorImageInfo, 2> ii{{
        {.sampler = *s_lin, .imageView = sv, .imageLayout = VK_IMAGE_LAYOUT_GENERAL},
        {.sampler = *s_pt, .imageView = in.dv, .imageLayout = VK_IMAGE_LAYOUT_GENERAL},
    }};
    const std::array<VkWriteDescriptorSet, 2> ww{{
        {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .pNext = nullptr,
            .dstSet = rr.s_a1[i],
            .dstBinding = 0,
            .dstArrayElement = 0,
            .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .pImageInfo = &ii[0],
            .pBufferInfo = nullptr,
            .pTexelBufferView = nullptr,
        },
        {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .pNext = nullptr,
            .dstSet = rr.s_a1[i],
            .dstBinding = 1,
            .dstArrayElement = 0,
            .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .pImageInfo = &ii[1],
            .pBufferInfo = nullptr,
            .pTexelBufferView = nullptr,
        },
    }};
    d.GetLogical().UpdateDescriptorSets(ww, {});

    PcA pa{};
    pa.cr = {in.cr.left, in.cr.top, in.cr.right - in.cr.left, in.cr.bottom - in.cr.top};
    pa.dr = {in.dr.left, in.dr.top, in.dr.right - in.dr.left, in.dr.bottom - in.dr.top};
    pa.jt = {jx, jy};
    pa.sz = Sz(rsz);
    pa.inv = in.inv ? 1u : 0u;
    pa.rst = r0 ? 1u : 0u;
    const PcB pb1{Sz(lz[1])};
    const PcB pb2{Sz(lz[2])};
    const PcC pc3{Sz(lz[2]), 4, 0, 0};
    const PcC pc2{Sz(lz[1]), 2, 1, 0};
    const PcC pc1{Sz(lz[0]), 1, 1, 1};
    const PcD pd{Sz(rsz), Sz(lz[0]), cut_th, r0 ? 1u : 0u};

    const VkDescriptorSet sa0 = rr.s_a0[pp];
    const VkDescriptorSet sa1 = rr.s_a1[i];
    const VkDescriptorSet sb1 = rr.s_b[pp * 2];
    const VkDescriptorSet sb2 = rr.s_b[pp * 2 + 1];
    const VkDescriptorSet sc3 = rr.s_c[pp * 3];
    const VkDescriptorSet sc2 = rr.s_c[pp * 3 + 1];
    const VkDescriptorSet sc1 = rr.s_c[pp * 3 + 2];
    const VkDescriptorSet sd1 = rr.s_d[pp];
    const VkBuffer cutb = *rr.cut;
    const VkImage oi = *cx->out;
    const std::array<VkImage, 14> imgs{
        *rr.img_c[0],     *rr.img_c[1],     *rr.img_d,        *rr.img_mv,       *rr.img_r,
        *rr.img_l[0][0], *rr.img_l[0][1], *rr.img_l[0][2], *rr.img_l[1][0], *rr.img_l[1][1],
        *rr.img_l[1][2], *rr.img_f[0],     *rr.img_f[1],     *rr.img_f[2],
    };

    arm::FfxmFsr2DispatchDescription dd{};
    dd.color = Rx(*rr.img_c[pp], arm::FFXM_SURFACE_FORMAT_R8G8B8A8_UNORM, rsz,
                  arm::FFXM_RESOURCE_USAGE_READ_ONLY);
    dd.depth =
        Rx(*rr.img_d, arm::FFXM_SURFACE_FORMAT_R32_FLOAT, rsz, arm::FFXM_RESOURCE_USAGE_READ_ONLY);
    dd.motionVectors = Rx(*rr.img_mv, arm::FFXM_SURFACE_FORMAT_R16G16_FLOAT, rsz,
                          arm::FFXM_RESOURCE_USAGE_READ_ONLY);
    dd.reactive =
        Rx(*rr.img_r, arm::FFXM_SURFACE_FORMAT_R8_UNORM, rsz, arm::FFXM_RESOURCE_USAGE_READ_ONLY);
    dd.output = Rx(oi, arm::FFXM_SURFACE_FORMAT_R8G8B8A8_UNORM, nsz,
                   arm::FFXM_RESOURCE_USAGE_RENDERTARGET);
    dd.jitterOffset = {jx, jy};
    dd.motionVectorScale = {static_cast<f32>(rsz.width), static_cast<f32>(rsz.height)};
    dd.renderSize = {rsz.width, rsz.height};
    const f32 sh =
        std::clamp(static_cast<f32>(Settings::values.fsr_sharpening_slider.GetValue()) / 100.0f,
                   0.0f, 1.0f);
    dd.enableSharpening = sh > 0.0f;
    dd.sharpness = sh;
    const auto now = std::chrono::steady_clock::now();
    const f32 ms = t_last.time_since_epoch().count() == 0
                       ? 16.6f
                       : std::chrono::duration<f32, std::milli>(now - t_last).count();
    t_last = now;
    dd.frameTimeDelta = std::clamp(ms, 1.0f, 100.0f);
    dd.preExposure = 1.0f;
    dd.reset = r0;
    dd.cameraNear = 0.1f;
    dd.cameraFar = 1000.0f;
    dd.cameraFovAngleVertical = 1.0471976f;
    dd.viewSpaceToMetersFactor = 1.0f;
    arm::FfxmFsr2Context* const cp = cx->ctx.get();

    const bool tm = tq.On();
    if (tm) {
        tq.Arm(i);
    }
    sch.RequestOutsideRenderPassOperationContext(TileFlowBreak::PresentFilter);
    sch.Record([this, i, tm, rfresh, cfresh, reuse, imgs, oi, sa0, sa1, sb1, sb2, sc3, sc2, sc1,
                sd1, pa, pb1, pb2, pc3, pc2, pc1, pd, cp, dd, cutb, rsz,
                lz](vk::CommandBuffer cb) {
        if (rfresh) {
            std::array<VkImageMemoryBarrier, 14> ib{};
            for (size_t k = 0; k < imgs.size(); ++k) {
                ib[k] = Ib(imgs[k]);
            }
            cb.PipelineBarrier(VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                               st_cs | st_fs | VK_PIPELINE_STAGE_TRANSFER_BIT, 0, {}, {}, ib);
        }
        if (cfresh) {
            cb.PipelineBarrier(VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, st_cs | st_fs | st_rt, 0,
                               Ib(oi));
        }
        if (tm) {
            tq.Begin(cb, i);
            tq.Mark(cb, i, 0);
        }
        Mb(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, st_cs | VK_PIPELINE_STAGE_TRANSFER_BIT,
           VK_ACCESS_MEMORY_WRITE_BIT,
           VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);
        cb.FillBuffer(cutb, 0, VK_WHOLE_SIZE, 0);
        Mb(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, st_cs, VK_ACCESS_TRANSFER_WRITE_BIT,
           VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);

        cb.BindPipeline(VK_PIPELINE_BIND_POINT_COMPUTE, *p_a);
        cb.BindDescriptorSets(VK_PIPELINE_BIND_POINT_COMPUTE, *pl_a, 0,
                              std::array<VkDescriptorSet, 2>{sa0, sa1}, {});
        cb.PushConstants(*pl_a, VK_SHADER_STAGE_COMPUTE_BIT, pa);
        cb.Dispatch(Common::DivCeil(lz[0].width, 8u), Common::DivCeil(lz[0].height, 8u), 1);
        Cs(cb);

        cb.BindPipeline(VK_PIPELINE_BIND_POINT_COMPUTE, *p_b);
        cb.BindDescriptorSets(VK_PIPELINE_BIND_POINT_COMPUTE, *pl_b, 0, sb1, {});
        cb.PushConstants(*pl_b, VK_SHADER_STAGE_COMPUTE_BIT, pb1);
        cb.Dispatch(Common::DivCeil(lz[1].width, 8u), Common::DivCeil(lz[1].height, 8u), 1);
        Cs(cb);
        cb.BindDescriptorSets(VK_PIPELINE_BIND_POINT_COMPUTE, *pl_b, 0, sb2, {});
        cb.PushConstants(*pl_b, VK_SHADER_STAGE_COMPUTE_BIT, pb2);
        cb.Dispatch(Common::DivCeil(lz[2].width, 8u), Common::DivCeil(lz[2].height, 8u), 1);
        Cs(cb);

        if (!reuse) {
            cb.BindPipeline(VK_PIPELINE_BIND_POINT_COMPUTE, *p_c);
            cb.BindDescriptorSets(VK_PIPELINE_BIND_POINT_COMPUTE, *pl_c, 0, sc3, {});
            cb.PushConstants(*pl_c, VK_SHADER_STAGE_COMPUTE_BIT, pc3);
            cb.Dispatch(Common::DivCeil(lz[2].width, 8u), Common::DivCeil(lz[2].height, 8u), 1);
            Cs(cb);
            cb.BindDescriptorSets(VK_PIPELINE_BIND_POINT_COMPUTE, *pl_c, 0, sc2, {});
            cb.PushConstants(*pl_c, VK_SHADER_STAGE_COMPUTE_BIT, pc2);
            cb.Dispatch(Common::DivCeil(lz[1].width, 8u), Common::DivCeil(lz[1].height, 8u), 1);
            Cs(cb);
            cb.BindDescriptorSets(VK_PIPELINE_BIND_POINT_COMPUTE, *pl_c, 0, sc1, {});
            cb.PushConstants(*pl_c, VK_SHADER_STAGE_COMPUTE_BIT, pc1);
            cb.Dispatch(Common::DivCeil(lz[0].width, 8u), Common::DivCeil(lz[0].height, 8u), 1);
            Cs(cb);
        }

        cb.BindPipeline(VK_PIPELINE_BIND_POINT_COMPUTE, *p_d);
        cb.BindDescriptorSets(VK_PIPELINE_BIND_POINT_COMPUTE, *pl_d, 0, sd1, {});
        cb.PushConstants(*pl_d, VK_SHADER_STAGE_COMPUTE_BIT, pd);
        cb.Dispatch(Common::DivCeil(rsz.width, 8u), Common::DivCeil(rsz.height, 8u), 1);
        Mb(cb, st_cs, st_cs | st_fs, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
        if (tm) {
            tq.Mark(cb, i, 1);
        }

        Mb(cb, st_fs | st_cs | st_rt, st_fs | st_cs | st_rt,
           VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT |
               VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
           VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
               VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        auto dl = dd;
        dl.commandList = arm::ffxmGetCommandListVK(*cb);
        if (arm::ffxmFsr2ContextDispatch(cp, &dl) != arm::FFXM_OK) {
            bad.store(true, std::memory_order_relaxed);
        }
        Mb(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, st_fs | st_cs, VK_ACCESS_MEMORY_WRITE_BIT,
           VK_ACCESS_SHADER_READ_BIT);
        if (tm) {
            tq.Mark(cb, i, 2);
        }
    });

    rst = false;
    par ^= 1u;
    ++fr;
    st.mode.store(2);
    Jit(Settings::values.asr_jitter.GetValue(), rsz, nsz, re, in.cr.right < in.cr.left,
        in.cr.bottom < in.cr.top);
    return *cx->ov;
}

}
