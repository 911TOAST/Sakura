// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2021 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <algorithm>
#include <array>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "common/common_types.h"
#include "common/settings_common.h"
#include "common/settings_enums.h"
#include "common/settings_input.h"
#include "common/settings_setting.h"

namespace Settings {

const char* TranslateCategory(Settings::Category category);

struct ResolutionScalingInfo {
    u32 up_scale{1};
    u32 down_shift{0};
    f32 up_factor{1.0f};
    f32 down_factor{1.0f};
    bool active{};
    bool downscale{};

    s32 ScaleUp(s32 value) const {
        if (value == 0) {
            return 0;
        }
        return (std::max)((value * static_cast<s32>(up_scale)) >> static_cast<s32>(down_shift), 1);
    }

    u32 ScaleUp(u32 value) const {
        if (value == 0U) {
            return 0U;
        }
        return (std::max)((value * up_scale) >> down_shift, 1U);
    }
};

#ifndef CANNOT_EXPLICITLY_INSTANTIATE
// Instantiate the classes elsewhere (settings.cpp) to reduce compiler/linker work
// TODO(crueter): Move new enums here
#define SETTING(TYPE, RANGED) extern template class Setting<TYPE, RANGED>
#define SWITCHABLE(TYPE, RANGED) extern template class SwitchableSetting<TYPE, RANGED>

SETTING(AudioEngine, false);
SETTING(bool, false);
SETTING(int, false);
SETTING(s32, false);
SETTING(std::string, false);
SETTING(std::string, false);
SETTING(u16, false);
SWITCHABLE(AnisotropyMode, true);
SWITCHABLE(AntiAliasing, false);
SWITCHABLE(AspectRatio, true);
SWITCHABLE(AstcDecodeMode, true);
SWITCHABLE(AstcRecompression, true);
SWITCHABLE(AudioMode, true);
SWITCHABLE(CpuBackend, true);
SWITCHABLE(CpuAccuracy, true);
SWITCHABLE(FullscreenMode, true);
SWITCHABLE(GpuAccuracy, true);
SWITCHABLE(Language, true);
SWITCHABLE(MemoryLayout, true);
SWITCHABLE(NvdecEmulation, false);
SWITCHABLE(Region, true);
SWITCHABLE(RendererBackend, true);
SWITCHABLE(ScalingFilter, false);
SWITCHABLE(SpirvOptimizeMode, true);
SWITCHABLE(TimeZone, true);
SETTING(VSyncMode, true);
SWITCHABLE(bool, false);
SWITCHABLE(int, false);
SWITCHABLE(int, true);
SWITCHABLE(s64, false);
SWITCHABLE(u16, true);
SWITCHABLE(u32, false);
SWITCHABLE(u8, false);
SWITCHABLE(u8, true);

// Used in UISettings
// TODO see if we can move this to uisettings.h
SWITCHABLE(ConfirmStop, true);

#undef SETTING
#undef SWITCHABLE
#endif

/**
 * The InputSetting class allows for getting a reference to either the global or custom members.
 * This is required as we cannot easily modify the values of user-defined types within containers
 * using the SetValue() member function found in the Setting class. The primary purpose of this
 * class is to store an array of 10 PlayerInput structs for both the global and custom setting and
 * allows for easily accessing and modifying both settings.
 */
template <typename Type>
class InputSetting final {
public:
    InputSetting() = default;
    explicit InputSetting(Type val) : Setting<Type>(val) {}
    ~InputSetting() = default;
    void SetGlobal(bool to_global) {
        use_global = to_global;
    }
    [[nodiscard]] bool UsingGlobal() const {
        return use_global;
    }
    [[nodiscard]] Type& GetValue(bool need_global = false) {
        if (use_global || need_global) {
            return global;
        }
        return custom;
    }

private:
    bool use_global{true}; ///< The setting's global state
    Type global{};         ///< The setting
    Type custom{};         ///< The custom setting value
};

struct TouchFromButtonMap {
    std::string name;
    std::vector<std::string> buttons;
};

struct Values {
    Linkage linkage{};

    // Applet
    SwitchableSetting<AppletMode> cabinet_applet_mode{linkage, AppletMode::LLE, "cabinet_applet_mode",
                                            Category::LibraryApplet};
    SwitchableSetting<AppletMode> controller_applet_mode{linkage, AppletMode::HLE, "controller_applet_mode",
                                               Category::LibraryApplet};
    Setting<AppletMode> data_erase_applet_mode{linkage, AppletMode::HLE, "data_erase_applet_mode",
                                               Category::LibraryApplet};
    SwitchableSetting<AppletMode> error_applet_mode{linkage, AppletMode::LLE, "error_applet_mode",
                                          Category::LibraryApplet};
    Setting<AppletMode> net_connect_applet_mode{linkage, AppletMode::LLE, "net_connect_applet_mode",
                                                Category::LibraryApplet};
    SwitchableSetting<AppletMode> player_select_applet_mode{
                                                  linkage, AppletMode::LLE, "player_select_applet_mode", Category::LibraryApplet};
    SwitchableSetting<AppletMode> swkbd_applet_mode{linkage, AppletMode::HLE, "swkbd_applet_mode",
                                          Category::LibraryApplet};
    SwitchableSetting<AppletMode> mii_edit_applet_mode{linkage, AppletMode::LLE, "mii_edit_applet_mode",
                                             Category::LibraryApplet};
    SwitchableSetting<AppletMode> web_applet_mode{linkage, AppletMode::HLE, "web_applet_mode",
                                        Category::LibraryApplet};
    Setting<AppletMode> shop_applet_mode{linkage, AppletMode::HLE, "shop_applet_mode",
                                         Category::LibraryApplet};
    SwitchableSetting<AppletMode> photo_viewer_applet_mode{
                                                 linkage, AppletMode::LLE, "photo_viewer_applet_mode", Category::LibraryApplet};
    SwitchableSetting<AppletMode> offline_web_applet_mode{linkage, AppletMode::LLE, "offline_web_applet_mode",
                                                Category::LibraryApplet};
    Setting<AppletMode> login_share_applet_mode{linkage, AppletMode::HLE, "login_share_applet_mode",
                                                Category::LibraryApplet};
    Setting<AppletMode> wifi_web_auth_applet_mode{
                                                  linkage, AppletMode::HLE, "wifi_web_auth_applet_mode", Category::LibraryApplet};
    Setting<AppletMode> my_page_applet_mode{linkage, AppletMode::LLE, "my_page_applet_mode",
                                            Category::LibraryApplet};
    SwitchableSetting<bool> enable_overlay{linkage, false, "enable_overlay", Category::LibraryApplet};

    // HLE performance preset. When enabled, every library applet is served by the HLE frontend
    // instead of booting a guest applet process, and Maxwell macro programs always take their HLE
    // implementation. Both are host-side shortcuts: the game's own code still runs, only the
    // system applets and the known macro programs stop running as guest ARM code.
    SwitchableSetting<bool> hle_performance_preset{linkage, false, "hle_performance_preset",
                                                   Category::Core};

    // Firmware-less operation. When a library applet's program is not installed (no firmware, or a
    // title that only ships some of them), the HLE frontend applet is used instead of a guest
    // launch that cannot succeed, and applets without a frontend get a stub that immediately
    // reports Closed with empty output so the game continues. Default on: missing system software
    // should not stop a game. Turn it off to get the retail behaviour of failing the launch.
    SwitchableSetting<bool> fake_system_applets{linkage, true, "fake_system_applets",
                                                Category::Core};

    // Audio
    SwitchableSetting<AudioEngine> sink_id{linkage, AudioEngine::Auto, "output_engine",
                                           Category::Audio, Specialization::RuntimeList};
    SwitchableSetting<std::string> audio_output_device_id{
                                                          linkage, "auto", "output_device", Category::Audio, Specialization::RuntimeList};
    SwitchableSetting<std::string> audio_input_device_id{
                                                         linkage, "auto", "input_device", Category::Audio, Specialization::RuntimeList};
    SwitchableSetting<AudioMode, true> sound_index{
                                                   linkage,       AudioMode::Stereo,
                                                   "sound_index", Category::SystemAudio, Specialization::Default, true,
                                                   true};
    SwitchableSetting<u8, true> volume{linkage,
                                       100,
                                       0,
                                       200,
                                       "volume",
                                       Category::Audio,
                                       Specialization::Scalar | Specialization::Percentage,
                                       true,
                                       true};
    Setting<bool, false> audio_muted{
                                     linkage, false, "audio_muted", Category::Audio, Specialization::Default, true, true};
    Setting<bool, false> dump_audio_commands{
                                             linkage, false, "dump_audio_commands", Category::Audio, Specialization::Default, false};

    // Core
    SwitchableSetting<bool> use_multi_core{linkage, true, "use_multi_core", Category::Core};
    SwitchableSetting<MemoryLayout, true> memory_layout_mode{linkage,
                                                             MemoryLayout::Memory_4Gb,
                                                             "memory_layout_mode",
                                                             Category::Core,
                                                             Specialization::Default,
                                                             true};
    SwitchableSetting<bool> use_speed_limit{
                                            linkage, true, "use_speed_limit", Category::Core, Specialization::Paired, true, true};

    SwitchableSetting<u16, true> speed_limit{linkage,
                                             100,
                                             0,
                                             9999,
                                             "speed_limit",
                                             Category::Core,
                                             Specialization::Countable | Specialization::Percentage,
                                             true,
                                             true,
                                             &use_speed_limit};

    SwitchableSetting<u16, true> slow_speed_limit{linkage,
                                             50,
                                             0,
                                             9999,
                                             "slow_speed_limit",
                                             Category::Core,
                                             Specialization::Countable | Specialization::Percentage,
                                             true,
                                             true};

    SwitchableSetting<u16, true> turbo_speed_limit{linkage,
                                             200,
                                             0,
                                             9999,
                                             "turbo_speed_limit",
                                             Category::Core,
                                             Specialization::Countable | Specialization::Percentage,
                                             true,
                                             true};

    // The currently used speed mode.
    Setting<SpeedMode> current_speed_mode{linkage, SpeedMode::Standard, "current_speed_mode", Category::Core, Specialization::Default, false, true};

    SwitchableSetting<bool> sync_core_speed{linkage, false, "sync_core_speed", Category::Core,
                                            Specialization::Default};

    // Cpu
    SwitchableSetting<CpuBackend, true> cpu_backend{linkage,
#ifdef HAS_NCE
                                                    CpuBackend::Nce,
#else
                                                    CpuBackend::Dynarmic,
#endif
                                                    CpuBackend::Dynarmic,
#ifdef HAS_NCE
                                                    CpuBackend::Nce,
#else
                                                    CpuBackend::Dynarmic,
#endif
                                                    "cpu_backend",
                                                    Category::Cpu};
    SwitchableSetting<CpuAccuracy, true> cpu_accuracy{linkage, CpuAccuracy::Auto,
                                                      "cpu_accuracy", Category::Cpu};
    SwitchableSetting<CpuClock> fast_cpu_time{linkage,
                                              CpuClock::Off,
                                              "fast_cpu_time",
                                              Category::Cpu,
                                              Specialization::Default,
                                              true,
                                              true};

    SwitchableSetting<bool> use_custom_cpu_ticks{linkage,
                                                 false,
                                                 "use_custom_cpu_ticks",
                                                 Category::Cpu,
                                                 Specialization::Paired,
                                                 true,
                                                 true};

    SwitchableSetting<u32, true> cpu_ticks{linkage,
                                           16000,
                                           77,
                                           65535,
                                           "cpu_ticks",
                                           Category::Cpu,
                                           Specialization::Countable,
                                           true,
                                           true,
                                           &use_custom_cpu_ticks};

    Setting<bool> cpuopt_page_tables{linkage, true, "cpuopt_page_tables", Category::CpuDebug};
    Setting<bool> cpuopt_block_linking{linkage, true, "cpuopt_block_linking", Category::CpuDebug};
    Setting<bool> cpuopt_return_stack_buffer{linkage, true, "cpuopt_return_stack_buffer",
                                             Category::CpuDebug};
    Setting<bool> cpuopt_fast_dispatcher{linkage, true, "cpuopt_fast_dispatcher",
                                         Category::CpuDebug};
    Setting<bool> cpuopt_context_elimination{linkage, true, "cpuopt_context_elimination",
                                             Category::CpuDebug};
    Setting<bool> cpuopt_const_prop{linkage, true, "cpuopt_const_prop", Category::CpuDebug};
    Setting<bool> cpuopt_misc_ir{linkage, true, "cpuopt_misc_ir", Category::CpuDebug};
    Setting<bool> cpuopt_reduce_misalign_checks{linkage, true, "cpuopt_reduce_misalign_checks",
                                                Category::CpuDebug};
    SwitchableSetting<bool> cpuopt_fastmem{linkage, true, "cpuopt_fastmem", Category::CpuDebug};
    SwitchableSetting<bool> cpuopt_fastmem_exclusives{linkage, true, "cpuopt_fastmem_exclusives",
                                                      Category::CpuDebug};
    Setting<bool> cpuopt_recompile_exclusives{linkage, true, "cpuopt_recompile_exclusives",
                                              Category::CpuDebug};
    Setting<bool> cpuopt_ignore_memory_aborts{linkage, true, "cpuopt_ignore_memory_aborts",
                                              Category::CpuDebug};

    SwitchableSetting<bool> cpuopt_unsafe_host_mmu{linkage,
#if !defined(__APPLE__) && !defined(__linux__) && !defined(__ANDROID__) && !defined(_WIN32)
                                        false,
#else
                                        true,
#endif
                                        "cpuopt_unsafe_host_mmu",
                                        Category::CpuUnsafe};
    SwitchableSetting<bool> cpuopt_unsafe_unfuse_fma{linkage, true, "cpuopt_unsafe_unfuse_fma",
                                                     Category::CpuUnsafe};
    SwitchableSetting<bool> cpuopt_unsafe_reduce_fp_error{
                                                          linkage, true, "cpuopt_unsafe_reduce_fp_error", Category::CpuUnsafe};
    SwitchableSetting<bool> cpuopt_unsafe_ignore_standard_fpcr{
                                                               linkage, true, "cpuopt_unsafe_ignore_standard_fpcr", Category::CpuUnsafe};
    SwitchableSetting<bool> cpuopt_unsafe_inaccurate_nan{
                                                         linkage, true, "cpuopt_unsafe_inaccurate_nan", Category::CpuUnsafe};
    SwitchableSetting<bool> cpuopt_unsafe_fastmem_check{
                                                        linkage, true, "cpuopt_unsafe_fastmem_check", Category::CpuUnsafe};
    SwitchableSetting<bool> cpuopt_unsafe_ignore_global_monitor{
                                                                linkage, true, "cpuopt_unsafe_ignore_global_monitor", Category::CpuUnsafe};

    // Renderer
    SwitchableSetting<RendererBackend, true> renderer_backend{linkage,
#if defined(__ANDROID__) && defined(SAKURA_OPTIMIZED)
        RendererBackend::Malikan,
#elif defined(__sun__) || defined(__managarm__)
        RendererBackend::OpenGL_GLSL,
#else
        RendererBackend::Vulkan,
#endif
        "backend", Category::Renderer};
    SwitchableSetting<u32> vulkan_device{linkage, 0, "vulkan_device", Category::Renderer, Specialization::RuntimeList};

    // Graphics Settings
    ResolutionScalingInfo resolution_info{};
    SwitchableSetting<ResolutionSetup> resolution_setup{linkage, ResolutionSetup::Res1X,
                                                        "resolution_setup", Category::Renderer};

    SwitchableSetting<VSyncMode, true> vsync_mode{linkage,
                                                  VSyncMode::Fifo,
                                                  "use_vsync",
                                                  Category::Renderer,
                                                  Specialization::RuntimeList,
                                                  true,
                                                  true};

    SwitchableSetting<ScalingFilter> scaling_filter{linkage,
                                                    ScalingFilter::Bilinear,
                                                    "scaling_filter",
                                                    Category::Renderer,
                                                    Specialization::Default,
                                                    true,
                                                    true};
    SwitchableSetting<int, true> fsr_sharpening_slider{linkage,
#ifdef __ANDROID__
                                                       0,
#else
                                                       25,
#endif
                                                       0,
                                                       200,
                                                       "fsr_sharpening_slider",
                                                       Category::Renderer,
                                                       Specialization::Scalar |
                                                           Specialization::Percentage,
                                                       true,
                                                       true};
    SwitchableSetting<AspectRatio, true> aspect_ratio{linkage,
                                                      AspectRatio::R16_9,
                                                      "aspect_ratio",
                                                      Category::Renderer,
                                                      Specialization::Default,
                                                      true,
                                                      true};

    SwitchableSetting<AntiAliasing> anti_aliasing{linkage,
                                                  AntiAliasing::None,
                                                  "anti_aliasing",
                                                  Category::Renderer,
                                                  Specialization::Default,
                                                  true,
                                                  true};

    SwitchableSetting<bool> use_asynchronous_gpu_emulation{linkage,
        true,
        "use_asynchronous_gpu_emulation", Category::Renderer};
    // Prefer host Vulkan execution paths and CPU/GPU overlap where the device supports them.
    SwitchableSetting<bool> native_gpu_mode{linkage,
#ifdef __ANDROID__
                                            true,
#else
                                            false,
#endif
                                            "native_gpu_mode",
                                            Category::Renderer};
    // Experimental: skip the NVIDIA Maxwell shader workaround pass. This does not bypass
    // Maxwell shader translation; Switch shaders are still translated to SPIR-V.
    SwitchableSetting<bool> no_well{linkage, false, "no_well", Category::RendererDebug};
    // Draw-path experiments are read when the draw or the render pass is built, never cached at
    // startup, so they can be flipped mid-session to bisect a regression without relaunching.
    //
    // Skips re-deriving the fixed-function pipeline state on draws where the guest wrote no
    // register that can reach the pipeline key, so a steady-state draw only re-emits the draw
    // command itself. Requires the full extended dynamic state set; a no-op without it.
    SwitchableSetting<bool> renderer_maxwell_bypass{linkage, false, "renderer_maxwell_bypass",
                                                    Category::RendererDebug,
                                                    Specialization::Default, true, true};
    // Expresses a full-surface guest clear as the render pass's load operation instead of loading
    // the attachments and clearing them inside the pass. Removes a full attachment read on tilers.
    SwitchableSetting<bool> renderer_clear_loadop{linkage, true, "renderer_clear_loadop",
                                                  Category::RendererDebug,
                                                  Specialization::Default, true, true};
    // Skip the DRAM writeback of a render target whose contents the very next pass discards by
    // clearing it again. On a tiler the store writes the whole tile to memory only for the next
    // pass's clear to throw it away. The store op is chosen when the pass ends and the next pass is
    // already known, so a target is only marked DONT_CARE when the next pass provably re-clears it;
    // anything that is read, presented or loaded again keeps its store. Turn it off if a scene
    // shows stale or missing contents on a render target.
    SwitchableSetting<bool> renderer_store_elision{linkage, true, "renderer_store_elision",
                                                   Category::RendererDebug,
                                                   Specialization::Default, true, true};
    // Recompress guest BCn colour textures (BC1/BC2/BC3/BC7) to native ASTC 4x4 at upload instead
    // of inflating them to uncompressed RGBA8. Mali samples ASTC natively, so this keeps texture
    // memory and fetch bandwidth close to the guest format (4x smaller than RGBA8) like a native
    // game. Only takes effect on devices that sample ASTC; encoding happens once per texture at
    // load time. Experimental: turn it off if textures look wrong or load times regress.
    SwitchableSetting<bool> renderer_bcn_to_astc{linkage, false, "renderer_bcn_to_astc",
                                                 Category::RendererDebug,
                                                 Specialization::Default, true, true};
    // Track per-image Vulkan layouts instead of resting every image in GENERAL, so Mali can apply
    // lossless AFBC to framebuffer and texture traffic. GENERAL disables AFBC and transaction
    // elimination; native games keep AFBC-friendly layouts. Experimental: a missed layout
    // transition can corrupt a frame, so turn it off if anything renders wrong. Mali only.
    SwitchableSetting<bool> renderer_afbc_layouts{linkage, false, "renderer_afbc_layouts",
                                                  Category::RendererDebug,
                                                  Specialization::Default, true, true};
    // Draw with COLOR_ATTACHMENT_OPTIMAL / DEPTH_STENCIL_ATTACHMENT_OPTIMAL attachment references
    // while leaving the attachments' initial/final layout at GENERAL. The textures still rest in
    // GENERAL, so the texture cache needs no changes. Draws that sample the render target they
    // write to (feedback loops) must use GENERAL and are given the GENERAL render pass variant.
    // Turn this off to make every draw use GENERAL again if a driver misbehaves.
    SwitchableSetting<bool> renderer_optimal_attachments{linkage, true, "renderer_optimal_attachments",
                                                         Category::RendererDebug,
                                                         Specialization::Default, true, true};
    // Feedback draws (sampling the render target they write) keep an optimal attachment layout
    // through VK_EXT_attachment_feedback_loop_layout instead of dropping to GENERAL, so Mali
    // framebuffer compression and transaction elimination stay active for bloom/post-process
    // passes, and the feedback barrier no longer breaks the render pass. Requires the extension;
    // without it the GENERAL variant is used exactly as before.
    SwitchableSetting<bool> renderer_feedback_loop_layout{linkage, true, "renderer_feedback_loop_layout",
                                                          Category::RendererDebug,
                                                          Specialization::Default, true, true};
    // Experimental: narrow the render pass external dependencies' pipeline stage masks from
    // ALL_COMMANDS to the stages that actually produce/consume attachments, so the next pass's
    // vertex/geometry work is not forced to wait for all fragment work (Arm Mali pipeline-bubble
    // guidance). Access masks are unchanged, so cache coherency is preserved. Correctness-sensitive:
    // a missed consumer can show stale pixels, so it defaults off and is meant for on-device A/B.
    SwitchableSetting<bool> renderer_narrow_pass_barriers{linkage, false, "renderer_narrow_pass_barriers",
                                                          Category::RendererDebug,
                                                          Specialization::Default, true, true};
    // Second half of the steady-state draw: when the same pipeline is configured again and no
    // register write, guest-memory invalidation or render pass start happened in between, the
    // pipeline, the descriptor sets and the dynamic state the previous Configure emitted are still
    // the ones bound in the command stream, so the per-draw re-resolution of every binding, the
    // re-emission of every vkCmdSet*, and the re-arming of the transform feedback counter are
    // skipped entirely. The pipeline key derivation is unaffected; renderer_maxwell_bypass covers
    // that half. Also drops the two cache mutexes from the steady-state draw, because nothing is
    // resolved and therefore nothing needs guarding against the CPU thread.
    SwitchableSetting<bool> renderer_draw_state_cache{linkage, false, "renderer_draw_state_cache",
                                                      Category::RendererDebug,
                                                      Specialization::Default, true, true};
    // Marks every FP32 arithmetic result in a fragment shader RelaxedPrecision, which tells the
    // driver it may evaluate that chain at reduced precision. Mali and Adreno run 16-bit float math
    // at twice the rate of FP32 and with lower register pressure, so a driver that honours the
    // decoration can cut fragment ALU time and power without changing what the shader computes.
    // Only fragment arithmetic is decorated (vertex math, comparisons and integer ops are not), and
    // the decoration is a permission rather than a demand, so a driver that keeps FP32 simply
    // ignores it. Experimental: a driver that picks 16-bit for colour math can also pick it for
    // something precision-sensitive, so it is off by default and meant for on-device A/B. Applies to
    // shaders compiled after the setting changes; the driver pipeline cache keeps old ones.
    SwitchableSetting<bool> renderer_relaxed_precision{linkage, false, "renderer_relaxed_precision",
                                                       Category::RendererDebug,
                                                       Specialization::Default, true, true};
    // Presents the frame that is already on screen instead of re-compositing it when the layer
    // layout is unchanged and the guest recorded no draw since the last presented frame. The
    // composition pass is then not recorded at all, so a static menu, pause screen or dialogue box
    // costs no GPU bandwidth: nothing is drawn, copied or presented for that frame. The pixels are
    // identical by construction, because the guest produced nothing new to show. Developer switch,
    // off by default: an update that reaches guest memory without a draw (a decoder writing the
    // displayed surface in place, for example) is not seen by the predicate and would hold the
    // previous frame until the next real one.
    SwitchableSetting<bool> renderer_static_frame_skip{linkage, false, "renderer_static_frame_skip",
                                                       Category::RendererDebug,
                                                       Specialization::Default, true, true};
    // *nix platforms may have issues with the borderless windowed fullscreen mode.
    // Default to exclusive fullscreen on these platforms for now.
    SwitchableSetting<FullscreenMode, true> fullscreen_mode{linkage,
#ifdef _WIN32
                                                            FullscreenMode::Borderless,
#else
                                                            FullscreenMode::Exclusive,
#endif
                                                            "fullscreen_mode",
                                                            Category::Renderer,
                                                            Specialization::Default,
                                                            true,
                                                            true};

    SwitchableSetting<u8, false> bg_red{
                                        linkage, 0, "bg_red", Category::Renderer, Specialization::Default, true, true};
    SwitchableSetting<u8, false> bg_green{
                                          linkage, 0, "bg_green", Category::Renderer, Specialization::Default, true, true};
    SwitchableSetting<u8, false> bg_blue{
                                         linkage, 0, "bg_blue", Category::Renderer, Specialization::Default, true, true};

    SwitchableSetting<GpuAccuracy, true> gpu_accuracy{linkage,
#ifdef __ANDROID__
                                                      GpuAccuracy::Low,
#else
                                                      GpuAccuracy::High,
#endif
                                                      "gpu_accuracy",
                                                      Category::RendererAdvanced,
                                                      Specialization::Default,
                                                      true,
                                                      true};

    GpuAccuracy current_gpu_accuracy{GpuAccuracy::High};

    SwitchableSetting<DmaAccuracy, true> dma_accuracy{linkage,
                                                      DmaAccuracy::Default,
                                                      "dma_accuracy",
                                                      Category::RendererAdvanced,
                                                      Specialization::Default,
                                                      true,
                                                      true};

    SwitchableSetting<GpuFenceBehavior, true> gpu_fence_behavior{linkage,
                                                                 GpuFenceBehavior::Default,
                                                                 GpuFenceBehavior::Default,
                                                                 GpuFenceBehavior::Strict,
                                                                 "gpu_fence_behavior",
                                                                 Category::RendererAdvanced,
                                                                 Specialization::Default,
                                                                 true,
                                                                 true};

    SwitchableSetting<VramUsageMode, true> vram_usage_mode{linkage,
                                                           VramUsageMode::Conservative,
                                                           "vram_usage_mode",
                                                           Category::RendererAdvanced};

    SwitchableSetting<NvdecEmulation> nvdec_emulation{linkage, NvdecEmulation::Gpu,
                                                      "nvdec_emulation", Category::RendererAdvanced};

    SwitchableSetting<AnisotropyMode, true> max_anisotropy{linkage,
#ifdef __ANDROID__
                                                           AnisotropyMode::Default,
#else
                                                           AnisotropyMode::Automatic,
#endif
                                                           "max_anisotropy",
                                                           Category::RendererAdvanced};
    SwitchableSetting<AstcDecodeMode, true> accelerate_astc{linkage,
                                                            AstcDecodeMode::Gpu,
                                                            "accelerate_astc",
                                                            Category::RendererAdvanced};

    SwitchableSetting<FramePacingMode, true> frame_pacing_mode{linkage,
                                                               FramePacingMode::Target_Auto,
                                                               FramePacingMode::Target_Auto,
                                                               FramePacingMode::Target_120,
                                                               "frame_pacing_mode",
                                                               Category::RendererAdvanced,
                                                               Specialization::Default,
                                                               true,
                                                               true};

    SwitchableSetting<AstcRecompression, true> astc_recompression{linkage,
                                                                  AstcRecompression::Uncompressed,
                                                                  "astc_recompression",
                                                                  Category::RendererAdvanced};

    // Requests AFBC/AFRC on safe colour render targets via VK_EXT_image_compression_control.
    // Experimental: an image that opts in gets an opaque layout, so it may never be copied or
    // reinterpreted; the gate in the texture cache enforces that. The optimized Android build
    // enables this by default, while device capability checks keep unsupported drivers on the
    // normal image path.
    SwitchableSetting<bool> mali_afrc{linkage,
#if defined(__ANDROID__)
                                      true,
#else
                                      false,
#endif
                                      "mali_afrc", Category::RendererAdvanced};

    // Vulkan instance layers requested by name, separated by ':' or ','. Android only discovers
    // layers bundled in the application's native library directory or provided by the GPU driver.
    // Layers the loader does not report are dropped by RemoveUnavailableLayers.
    Setting<std::string> vulkan_instance_layers{linkage,
                                                std::string(),
                                                "vulkan_instance_layers",
                                                Category::RendererAdvanced};


    SwitchableSetting<bool> sync_memory_operations{linkage,
                                                   false,
                                                   "sync_memory_operations",
                                                   Category::RendererAdvanced,
                                                   Specialization::Default,
                                                   true,
                                                   true};

    SwitchableSetting<bool> renderer_force_max_clock{linkage,
#ifdef __ANDROID__
                                                     true,
#else
                                                     false,
#endif
                                                     "force_max_clock",
                                                     Category::RendererAdvanced};

    // big.LITTLE: a hot thread that the scheduler parks on a little core loses most of its
    // throughput, and the GPU thread is the one that records every draw. Pinning it to the set of
    // highest-capacity cores (read from schedutil's cpu_capacity) removes that lottery. Silently
    // ignored when the kernel refuses the affinity mask.
    SwitchableSetting<bool> renderer_core_pinning{linkage,
#if defined(__ANDROID__)
                                                  true,
#else
                                                  false,
#endif
                                                  "renderer_core_pinning",
                                                  Category::RendererAdvanced};

    SwitchableSetting<bool> use_disk_shader_cache{linkage, true, "use_disk_shader_cache",
                                                  Category::RendererAdvanced};

    SwitchableSetting<bool> use_vulkan_driver_pipeline_cache{
        linkage, true, "use_vulkan_driver_pipeline_cache", Category::RendererAdvanced,
        Specialization::Default};

    SwitchableSetting<bool> enable_compute_pipelines{linkage, false, "enable_compute_pipelines",
                                                     Category::RendererAdvanced};

    SwitchableSetting<bool> use_video_framerate{linkage, false, "use_video_framerate",
                                                Category::RendererAdvanced};

    SwitchableSetting<bool> use_reactive_flushing{linkage,
#ifdef __ANDROID__
                                                  false,
#else
                                                  true,
#endif
                                                  "use_reactive_flushing",
                                                  Category::RendererAdvanced};

    SwitchableSetting<bool> barrier_feedback_loops{linkage, true, "barrier_feedback_loops",
                                                   Category::RendererAdvanced};

    SwitchableSetting<bool> enable_buffer_history{linkage,
                                                  false,
                                                  "enable_buffer_history",
                                                  Category::RendererAdvanced,
                                                  Specialization::Default,
                                                  true,
                                                  true};

#ifdef __ANDROID__
    SwitchableSetting<bool> use_optimized_vertex_buffers{linkage,
#if defined(SAKURA_OPTIMIZED)
                                                 true,
#else
                                                 false,
#endif
                                                 "use_optimized_vertex_buffers",
                                                 Category::RendererAdvanced,
                                                 Specialization::Default,
                                                 true,
                                                 true};
#endif

    // Renderer Hacks //
    SwitchableSetting<bool> use_fast_gpu_time{linkage,
                                              true,
                                              "use_fast_gpu_time",
                                              Category::RendererHacks,
                                              Specialization::Default,
                                              true,
                                              true};

    SwitchableSetting<GpuOverclock> fast_gpu_time{linkage,
                                                  GpuOverclock::Medium,
                                                  "fast_gpu_time",
                                                  Category::RendererHacks,
                                                  Specialization::Default,
                                                        true,
                                                        true};

    SwitchableSetting<bool> skip_cpu_inner_invalidation{linkage,
                                                        false,
                                                        "skip_cpu_inner_invalidation",
                                                        Category::RendererHacks,
                                                        Specialization::Default,
                                                        true,
                                                        true};
    SwitchableSetting<bool> async_presentation{linkage,
#ifdef __ANDROID__
                                               false,
#else
                                               false,
#endif
                                               "async_presentation", Category::RendererHacks};

    // Only used by the Malikan direct-present path: the frame is handed to the WSI as soon as its
    // submission is issued instead of draining the whole render worker inline, so the CPU starts
    // recording the next frame immediately. The WSI call itself always stays on the thread that
    // acquired the image (Android surface handles are not thread-safe). Harmless on every other
    // path.
    SwitchableSetting<bool> async_direct_present{linkage,
                                                 true,
                                                 "async_direct_present",
                                                 Category::RendererHacks};

    SwitchableSetting<bool> fix_bloom_effects{linkage, false, "fix_bloom_effects",
                                                     Category::RendererHacks};

    SwitchableSetting<bool> emulate_bgr565{linkage, false, "emulate_bgr565",
                                            Category::RendererHacks};

    SwitchableSetting<bool> rescale_hack{linkage,
#ifdef __ANDROID__
        true,
#else
        false,
#endif
        "rescale_hack", Category::RendererHacks};
    SwitchableSetting<bool> enable_gpu_buffer_readback{linkage,
                                                       false,
                                                       "enable_gpu_buffer_readback",
                                                       Category::RendererAdvanced,
                                                       Specialization::Default,
                                                       true,
                                                       true};

    SwitchableSetting<bool> use_asynchronous_shaders{linkage,
#if defined(__ANDROID__)
                                                     true,
#else
                                                     false,
#endif
                                                     "use_asynchronous_shaders",
                                                     Category::RendererHacks};

    SwitchableSetting<GpuUnswizzleSize> gpu_unswizzle_texture_size{linkage,
                                                  GpuUnswizzleSize::Large,
                                                  "gpu_unswizzle_texture_size",
                                                  Category::RendererHacks,
                                                  Specialization::Default};

    SwitchableSetting<GpuUnswizzle> gpu_unswizzle_stream_size{linkage,
                                                  GpuUnswizzle::Medium,
                                                  "gpu_unswizzle_stream_size",
                                                  Category::RendererHacks,
                                                  Specialization::Default};

    SwitchableSetting<GpuUnswizzleChunk> gpu_unswizzle_chunk_size{linkage,
                                                  GpuUnswizzleChunk::Medium,
                                                  "gpu_unswizzle_chunk_size",
                                                  Category::RendererHacks,
                                                  Specialization::Default};

    SwitchableSetting<bool> gpu_unswizzle_enabled{linkage, false, "gpu_unswizzle_enabled",
                                                  Category::RendererHacks};

    SwitchableSetting<ExtendedDynamicState> dyna_state{linkage,
#if defined(__ANDROID__) && defined(SAKURA_OPTIMIZED)
                                           // Keep more guest raster state out of Mali's pipeline
                                           // keys to reduce graphics-pipeline variants. Device
                                           // setup still disables EDS on old ARM drivers with
                                           // broken dynamic vertex-buffer binding.
                                           ExtendedDynamicState::EDS2,
#elif defined(__ANDROID__) || defined(__APPLE__)
                                           ExtendedDynamicState::Disabled,
#else
                                           ExtendedDynamicState::EDS2,
#endif
                                           "dyna_state",
                                           Category::RendererExtensions};

    SwitchableSetting<u32, true> sample_shading{linkage,
                                                0,
                                                0,
                                                100,
                                                "sample_shading_fraction",
                                                Category::RendererExtensions,
                                                Specialization::Scalar};

    SwitchableSetting<bool> vertex_input_dynamic_state{linkage,
#ifdef __ANDROID__
                                                       false,
#else
                                                       true,
#endif
                                                       "vertex_input_dynamic_state", Category::RendererExtensions};

    Setting<bool> renderer_debug{linkage, false, "debug", Category::RendererDebug};
    Setting<bool> renderer_shader_feedback{linkage, false, "shader_feedback",
                                           Category::RendererDebug};
    Setting<bool> enable_nsight_aftermath{linkage, false, "nsight_aftermath",
                                          Category::RendererDebug};
    Setting<bool> disable_shader_loop_safety_checks{
                                                    linkage, false, "disable_shader_loop_safety_checks", Category::RendererDebug};
    Setting<bool> enable_renderdoc_hotkey{linkage, false, "renderdoc_hotkey",
                                          Category::RendererDebug};
#if defined(__ANDROID__) && defined(ARCHITECTURE_arm64)
    // Debug override for automatic BCn patching detection
    Setting<bool> patch_old_qcom_drivers{linkage, false, "patch_old_qcom_drivers",
                                         Category::RendererDebug};
#endif
    SwitchableSetting<bool> disable_buffer_reorder{linkage, false, "disable_buffer_reorder",
                                         Category::RendererDebug,
                                         Specialization::Default,
                                                         true,
                                                         true};

    // System
    SwitchableSetting<Language, true> language_index{linkage,
                                                     Language::EnglishAmerican,
                                                     "language_index",
                                                     Category::System};
    SwitchableSetting<Region, true> region_index{linkage, Region::Usa, "region_index", Category::System};
    SwitchableSetting<TimeZone, true> time_zone_index{linkage, TimeZone::Auto, "time_zone_index", Category::System};
    Setting<u32> serial_battery{linkage, 0, "serial_battery", Category::Debugging};
    Setting<u32> serial_unit{linkage, 0, "serial_unit", Category::Debugging};
    // Measured in seconds since epoch
    SwitchableSetting<bool> custom_rtc_enabled{linkage, false, "custom_rtc_enabled", Category::System, Specialization::Paired, true, true};
    SwitchableSetting<s64> custom_rtc{
                                      linkage, 0,    "custom_rtc",       Category::System, Specialization::Time,
                                      false,   true, &custom_rtc_enabled};
    SwitchableSetting<s64, true> custom_rtc_offset{linkage,
                                                   0,
                                                   (std::numeric_limits<s64>::min)(),
                                                   (std::numeric_limits<s64>::max)(),
                                                   "custom_rtc_offset",
                                                   Category::System,
                                                   Specialization::Countable,
                                                   true,
                                                   true};
    SwitchableSetting<bool> rng_seed_enabled{
                                             linkage, false, "rng_seed_enabled", Category::System, Specialization::Paired, true, true};
    SwitchableSetting<u32> rng_seed{
                                    linkage, 0,    "rng_seed",       Category::System, Specialization::Hex,
                                    true,    true, &rng_seed_enabled};
    Setting<std::string> device_name{
        linkage, "suyu", "device_name", Category::System, Specialization::Default, true, true};

    Setting<s32> current_user{linkage, 0, "current_user", Category::System};

    SwitchableSetting<ConsoleMode> use_docked_mode{linkage,
#ifdef __ANDROID__
                                                   ConsoleMode::Handheld,
#else
                                                   ConsoleMode::Docked,
#endif
                                                   "use_docked_mode",
                                                   Category::System,
                                                   Specialization::Radio,
                                                   true,
                                                   true};

    // Controls
    InputSetting<std::array<PlayerInput, 10>> players;

    Setting<bool> enable_raw_input{
        linkage, false, "enable_raw_input", Category::Controls, Specialization::Default,
// Only read/write enable_raw_input on Windows platforms
#ifdef _WIN32
        true
#else
        false
#endif
    };
    Setting<bool> controller_navigation{linkage, true, "controller_navigation", Category::Controls};
    Setting<bool> enable_joycon_driver{linkage, true, "enable_joycon_driver", Category::Controls};
    Setting<bool> enable_procon_driver{linkage, false, "enable_procon_driver", Category::Controls};

    SwitchableSetting<bool> vibration_enabled{linkage, true, "vibration_enabled",
                                              Category::Controls};
    SwitchableSetting<bool> enable_accurate_vibrations{linkage, false, "enable_accurate_vibrations",
                                                       Category::Controls};

    SwitchableSetting<bool> motion_enabled{linkage, true, "motion_enabled", Category::Controls};
    Setting<std::string> udp_input_servers{linkage, "127.0.0.1:26760", "udp_input_servers",
                                           Category::Controls};
    Setting<bool> enable_udp_controller{linkage, false, "enable_udp_controller",
                                        Category::Controls};

    Setting<bool> pause_tas_on_load{linkage, true, "pause_tas_on_load", Category::Controls};
    Setting<bool> tas_enable{linkage, false, "tas_enable", Category::Controls};
    Setting<bool> tas_loop{linkage, false, "tas_loop", Category::Controls};
    Setting<bool> tas_show_recording_dialog{linkage, true, "tas_show_recording_dialog", Category::Controls};

    Setting<bool> mouse_panning{
                                linkage, false, "mouse_panning", Category::Controls, Specialization::Default, false};
    Setting<u8, true> mouse_panning_sensitivity{
                                                linkage, 50, 1, 100, "mouse_panning_sensitivity", Category::Controls};
    Setting<bool> mouse_enabled{linkage, false, "mouse_enabled", Category::Controls};

    Setting<u8, true> mouse_panning_x_sensitivity{
                                                  linkage, 50, 1, 100, "mouse_panning_x_sensitivity", Category::Controls};
    Setting<u8, true> mouse_panning_y_sensitivity{
                                                  linkage, 50, 1, 100, "mouse_panning_y_sensitivity", Category::Controls};
    Setting<u8, true> mouse_panning_deadzone_counterweight{
                                                           linkage, 20, 0, 100, "mouse_panning_deadzone_counterweight", Category::Controls};
    Setting<u8, true> mouse_panning_decay_strength{
                                                   linkage, 18, 0, 100, "mouse_panning_decay_strength", Category::Controls};
    Setting<u8, true> mouse_panning_min_decay{
                                              linkage, 6, 0, 100, "mouse_panning_min_decay", Category::Controls};

    Setting<bool> emulate_analog_keyboard{linkage, false, "emulate_analog_keyboard",
                                          Category::Controls};
    Setting<bool> keyboard_enabled{linkage, false, "keyboard_enabled", Category::Controls};

    Setting<bool> debug_pad_enabled{linkage, false, "debug_pad_enabled", Category::Controls};
    ButtonsRaw debug_pad_buttons;
    AnalogsRaw debug_pad_analogs;

    TouchscreenInput touchscreen;

    Setting<std::string> touch_device{linkage, "min_x:100,min_y:50,max_x:1800,max_y:850",
                                      "touch_device", Category::Controls};
    Setting<u32> touch_from_button_map_index{linkage, 0, "touch_from_button_map",
                                             Category::Controls};
    std::vector<TouchFromButtonMap> touch_from_button_maps;

    Setting<bool> enable_ring_controller{linkage, true, "enable_ring_controller",
                                         Category::Controls};
    RingconRaw ringcon_analogs;

    Setting<bool> enable_ir_sensor{linkage, false, "enable_ir_sensor", Category::Controls};
    Setting<std::string> ir_sensor_device{linkage, "auto", "ir_sensor_device", Category::Controls};

    Setting<bool> random_amiibo_id{linkage, false, "random_amiibo_id", Category::Controls};

    // Data Storage
    Setting<bool> use_virtual_sd{linkage, true, "use_virtual_sd", Category::DataStorage};
    Setting<bool> gamecard_inserted{linkage, false, "gamecard_inserted", Category::DataStorage};
    Setting<bool> gamecard_current_game{linkage, false, "gamecard_current_game",
                                        Category::DataStorage};
    Setting<std::string> gamecard_path{linkage, std::string(), "gamecard_path",
                                       Category::DataStorage};
    Setting<bool> ext_content_from_game_dirs{linkage, true, "ext_content_from_game_dirs",
                                             Category::DataStorage};
    std::vector<std::string> external_content_dirs;

    // Debugging
    bool record_frame_times;
    Setting<bool> use_gdbstub{linkage, false, "use_gdbstub", Category::Debugging};
    Setting<u16> gdbstub_port{linkage, 6543, "gdbstub_port", Category::Debugging};
    SwitchableSetting<std::string> program_args{linkage,
                                                std::string(),
                                                "program_args",
                                                Category::System,
                                                Specialization::Default,
                                                true,    // save_ - persist in config file
                                                false};  // runtime_modifiable_ - startup-only
    Setting<bool> dump_exefs{linkage, false, "dump_exefs", Category::Debugging};
    Setting<bool> dump_nso{linkage, false, "dump_nso", Category::Debugging};
    Setting<bool> dump_guest_shaders{
                               linkage, false, "dump_guest_shaders", Category::DebuggingGraphics, Specialization::Default,
                               false};
    Setting<bool> dump_macros{
                              linkage, false, "dump_macros", Category::DebuggingGraphics, Specialization::Default, false};
    Setting<bool> enable_fs_access_log{linkage, false, "enable_fs_access_log", Category::Debugging};
    Setting<bool> reporting_services{
                                     linkage, false, "reporting_services", Category::Debugging, Specialization::Default, false};
    Setting<bool> quest_flag{linkage, false, "quest_flag", Category::Debugging};
    Setting<bool> use_dev_keys{linkage, false, "use_dev_keys", Category::Debugging};
    Setting<bool> disable_macro_jit{linkage, false, "disable_macro_jit",
                                    Category::DebuggingGraphics};
    Setting<bool> disable_macro_hle{linkage, false, "disable_macro_hle",
                                    Category::DebuggingGraphics};
    Setting<bool> extended_logging{
                                   linkage, false, "extended_logging", Category::Debugging, Specialization::Default, false};
    Setting<bool> use_debug_asserts{linkage, false, "use_debug_asserts", Category::Debugging};
    Setting<bool> use_auto_stub{
                                linkage, false, "use_auto_stub", Category::Debugging};
    Setting<bool> enable_all_controllers{linkage, false, "enable_all_controllers",
                                         Category::Debugging};
    Setting<bool> perform_vulkan_check{linkage, true, "perform_vulkan_check", Category::Debugging};
    Setting<bool> disable_web_applet{linkage, true, "disable_web_applet", Category::Debugging};

    // suyu-exclusive debug/misc settings (not present in Eden upstream)
    Setting<bool> cpu_debug_mode{linkage, false, "cpu_debug_mode", Category::Debugging};
    Setting<bool> dump_shaders{linkage, false, "dump_shaders", Category::DebuggingGraphics};
    Setting<bool> log_async{linkage, true, "log_async", Category::Miscellaneous};
    Setting<bool> enable_gamemode{linkage, false, "enable_gamemode", Category::Miscellaneous};
    Setting<bool> optimize_spirv_output{linkage, false, "optimize_spirv_output", Category::Renderer};

    // GPU Logging
    Setting<GpuLogLevel> gpu_log_level{linkage, GpuLogLevel::Off, "gpu_log_level",
                                       Category::Debugging};
    Setting<bool> gpu_log_vulkan_calls{linkage, true, "gpu_log_vulkan_calls", Category::Debugging};
    Setting<bool> gpu_log_shader_dumps{linkage, false, "gpu_log_shader_dumps", Category::Debugging};
    Setting<bool> gpu_log_memory_tracking{linkage, true, "gpu_log_memory_tracking",
                                           Category::Debugging};
    Setting<bool> gpu_log_driver_debug{linkage, true, "gpu_log_driver_debug", Category::Debugging};
    Setting<s32> gpu_log_ring_buffer_size{linkage, 512, "gpu_log_ring_buffer_size",
                                           Category::Debugging};

    SwitchableSetting<u16, true> debug_knobs{linkage,
                                           0,
                                           0,
                                           65535,
                                           "debug_knobs",
                                           Category::Debugging,
                                           Specialization::Countable,
                                           true,
                                           true};

    // Miscellaneous
    Setting<std::string> log_filter{linkage, "*:Info", "log_filter", Category::Miscellaneous};
    Setting<bool> log_flush_line{linkage, false, "flush_line", Category::Miscellaneous, Specialization::Default, true, true};
    Setting<bool> censor_username{linkage, true, "censor_username", Category::Miscellaneous};
    // Persisted marker for "the Android Mali performance profile has been applied as defaults".
    // It is written once by ApplyPerformanceProfile() so later launches keep the user's choices.
    Setting<bool> first_launch{linkage, true, "first_launch", Category::Miscellaneous};

    // Network
    Setting<std::string> network_interface{linkage, std::string(), "network_interface",
                                           Category::Network};
    SwitchableSetting<bool> airplane_mode{linkage, false, "airplane_mode", Category::Network};

    /// Hostname to answer with when a game asks for one of Nintendo's online
    /// services. Those are unreachable and are otherwise refused; pointing this
    /// at a replacement network - Nextendo and similar, which reimplement
    /// identity and matchmaking and which a modded console reaches by
    /// redirecting its DNS - turns the refusal into a redirect. Empty keeps the
    /// refuse-everything behaviour.
    Setting<std::string> network_replacement_host{linkage, "", "network_replacement_host",
                                                  Category::Network};

    // WebService
    Setting<std::string> web_api_url{linkage, "api.ynet-fun.xyz", "web_api_url",
                                     Category::WebService};
    Setting<std::string> suyu_username{linkage, "suyu", "suyu_username",
                                       Category::WebService};
    Setting<std::string> suyu_token{linkage, "",
                                    "suyu_token", Category::WebService};

    // Library appearance (Android frontend). Persisted like any other setting so they survive
    // restarts without a second preferences store.
    SwitchableSetting<bool> hide_game_titles{linkage, false, "hide_game_titles", Category::Ui};
    Setting<std::string> library_background_uri{linkage, "", "library_background_uri",
                                                Category::Ui};
    SwitchableSetting<int> library_background_blur{linkage, 12, "library_background_blur",
                                                   Category::Ui};
    Setting<int> library_background_fit{linkage, 0, "library_background_fit", Category::Ui};
    Setting<int> library_background_dim{linkage, 30, "library_background_dim", Category::Ui};
    Setting<int> library_background_zoom{linkage, 100, "library_background_zoom", Category::Ui};
    Setting<int> library_background_x{linkage, 50, "library_background_x", Category::Ui};
    Setting<int> library_background_y{linkage, 50, "library_background_y", Category::Ui};
    Setting<bool> wallpaper_recent_games{linkage, false, "wallpaper_recent_games", Category::Ui};
    SwitchableSetting<bool> library_aurora{linkage, true, "library_aurora", Category::Ui};
    SwitchableSetting<bool> library_parallax{linkage, true, "library_parallax", Category::Ui};

    // Discord Rich Presence (Android frontend). Three transports are supported because the
    // platform does not offer one:
    //   0 = Discord Social SDK, the official path. Needs only the public Application ID and the
    //       Discord Android app installed and signed in; there is no credential to store.
    //   1 = account token over the gateway, which is how Kizzy-style apps publish presence on the
    //       player's own account. It is a self-bot and therefore against Discord's Terms.
    //   2 = bot token over the gateway. Lawful, but the presence belongs to the bot account and is
    //       only visible to members of a guild it has been invited to.
    // The token is read at runtime and never logged.
    SwitchableSetting<bool> discord_rich_presence{linkage, false, "discord_rich_presence",
                                                  Category::Ui};
    SwitchableSetting<int> discord_presence_mode{linkage, 0, "discord_presence_mode", Category::Ui};
    Setting<std::string> discord_application_id{linkage, "", "discord_application_id",
                                                Category::Ui};
    Setting<std::string> discord_token{linkage, "", "discord_token", Category::Ui};
    // The activity customiser, as a JSON document. One key rather than a dozen: the whole
    // structure is edited by the dedicated Discord page and is meaningless outside it.
    Setting<std::string> discord_activity_json{linkage, "", "discord_activity_json", Category::Ui};

    // Add-Ons
    std::map<u64, std::vector<std::string>> disabled_addons;

    // Per-game overrides
    bool use_squashed_iterated_blend;
};

extern Values values;

bool getDebugKnobAt(u8 i);

void UpdateGPUAccuracy();
bool IsGPULevelHigh();

bool IsDMALevelDefault();
bool IsDMALevelSafe();

bool IsGPUFenceBehaviorDefault();
bool IsGPUFenceBehaviorBalanced();
bool IsGPUFenceBehaviorAccurate();
bool IsGPUFenceBehaviorStrict();

bool IsFastmemEnabled();
// Applies the forced performance profile. Must run before the renderer is constructed.
void ApplyPerformanceProfile();
void SetNceEnabled(bool is_64bit);
bool IsNceEnabled();

// True when the user enabled the HLE performance preset. Consumers use this to force the HLE path
// even when the per-feature (applet mode / disable_macro_hle) setting says otherwise.
bool IsHlePerformancePresetEnabled();

void SetCurrentProgramID(u64 program_id);
u64 GetCurrentProgramID();

bool IsOpenGL();

bool IsDockedMode();

float Volume();

// speed limit ops
u16 SpeedLimit();
void SetSpeedMode(const SpeedMode &mode);
void ToggleStandardMode();
void ToggleTurboMode();
void ToggleSlowMode();

std::string GetTimeZoneString(TimeZone time_zone);

void LogSettings();

void TranslateResolutionInfo(ResolutionSetup setup, ResolutionScalingInfo& info);
void UpdateRescalingInfo();

// Restore the global state of all applicable settings in the Values struct
void RestoreGlobalState(bool is_powered_on);

bool IsConfiguringGlobal();
void SetConfiguringGlobal(bool is_global);

} // namespace Settings
