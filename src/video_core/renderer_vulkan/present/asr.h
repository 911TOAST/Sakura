#pragma once

#include <array>
#include <atomic>
#include <chrono>
#include <memory>
#include <optional>
#include <thread>
#include <vector>

#include "common/common_types.h"
#include "common/math_util.h"
#include "video_core/renderer_vulkan/present/fsr.h"
#include "video_core/vulkan_common/vulkan_memory_allocator.h"
#include "video_core/vulkan_common/vulkan_wrapper.h"

namespace Vulkan {

class Device;
class Scheduler;

struct AsrIn {
    VkImageView dv{};
    VkExtent2D rs{};
    VkExtent2D ns{};
    Common::Rectangle<f32> cr{};
    Common::Rectangle<f32> dr{};
    bool inv{};
};

class Tq {
public:
    void Init(const Device& d, size_t n, u32 k);
    bool On() const {
        return static_cast<bool>(qp);
    }
    void Arm(size_t i) {
        live[i] = 1;
    }
    void Begin(vk::CommandBuffer cb, size_t i) const;
    void Mark(vk::CommandBuffer cb, size_t i, u32 j) const;
    bool Read(const Device& d, size_t i, std::array<u64, 4>& t) const;
    u32 Us(u64 a, u64 b) const;

private:
    vk::QueryPool qp;
    PFN_vkCmdWriteTimestamp wt{};
    f64 per{};
    u64 msk{};
    u32 k_n{};
    std::vector<u8> live;
};

class ASR {
public:
    explicit ASR(const Device& d, MemoryAllocator& a, Scheduler& s, size_t n, VkExtent2D out,
                 u32 q);
    ~ASR();

    ASR(const ASR&) = delete;
    ASR& operator=(const ASR&) = delete;

    VkImageView Draw(const Device& d, size_t i, VkImage src, VkImageView sv, const AsrIn& in,
                     VkExtent2D re, const Common::Rectangle<f32>& crop);

private:
    struct Cx;
    struct Rs;

    static bool Build(Cx& c, const Device& d, u32 q);
    void Reap();
    void Kick(VkExtent2D ns);
    void Adopt();
    void MakeRs(VkExtent2D sz);
    void Jit(bool on, VkExtent2D rsz, VkExtent2D nsz, VkExtent2D re, bool fh, bool fv);
    VkImageView Fb(size_t i, VkImage src, VkImageView sv, VkExtent2D re,
                   const Common::Rectangle<f32>& crop);

    const Device& vk_d;
    MemoryAllocator& alc;
    Scheduler& sch;
    const size_t n_img;
    const VkExtent2D out_sz;
    const u32 q_g;
    bool cap{};

    vk::Sampler s_lin;
    vk::Sampler s_pt;
    vk::DescriptorSetLayout l_a0;
    vk::DescriptorSetLayout l_a1;
    vk::DescriptorSetLayout l_b;
    vk::DescriptorSetLayout l_c;
    vk::DescriptorSetLayout l_d;
    vk::PipelineLayout pl_a;
    vk::PipelineLayout pl_b;
    vk::PipelineLayout pl_c;
    vk::PipelineLayout pl_d;
    vk::ShaderModule m_a;
    vk::ShaderModule m_b;
    vk::ShaderModule m_c;
    vk::ShaderModule m_d;
    vk::Pipeline p_a;
    vk::Pipeline p_b;
    vk::Pipeline p_c;
    vk::Pipeline p_d;

    std::unique_ptr<Rs> res;
    std::unique_ptr<Rs> res_old;
    u64 res_t{};
    std::unique_ptr<Cx> cx;
    std::unique_ptr<Cx> cx_new;
    std::unique_ptr<Cx> cx_old;
    u64 cx_t{};
    std::jthread bld;
    std::atomic<u32> bst{};
    std::atomic<bool> bad{};
    bool dead{};

    std::optional<FSR> fsr;
    Tq tq;
    u32 par{};
    bool rst{true};
    u32 jit_i{};
    f32 jx{};
    f32 jy{};
    u32 fr{};
    std::chrono::steady_clock::time_point t_last{};
};

}
