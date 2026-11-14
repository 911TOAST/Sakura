// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <common/settings_common.h>
#ifdef __ANDROID__
#include <android/api-level.h>
#endif
#include "common/common_types.h"
#include "common/settings_setting.h"
#include "common/settings_enums.h"

namespace AndroidSettings {

    struct GameDir {
        std::string path;
        bool deep_scan = false;
    };

    struct OverlayControlData {
        std::string id;
        bool enabled;
        std::pair<double, double> landscape_position;
        std::pair<double, double> portrait_position;
        std::pair<double, double> foldable_position;
        float individual_scale;
    };

    struct Values {
        Settings::Linkage linkage;

        // Path settings
        std::vector<GameDir> game_dirs;

        // Android
        Settings::Setting<bool> picture_in_picture{linkage, false, "picture_in_picture",
                                                   Settings::Category::Android};
        Settings::Setting<s32> screen_layout{linkage,
                                             5,
                                             "screen_layout",
                                             Settings::Category::Android,
                                             Settings::Specialization::Default,
                                             true,
                                             true};
        Settings::Setting<s32> vertical_alignment{linkage,
                                                  0,
                                                  "vertical_alignment",
                                                  Settings::Category::Android,
                                                  Settings::Specialization::Default,
                                                  true,
                                                  true};

        Settings::SwitchableSetting<std::string, false> driver_path{linkage, "", "driver_path",
                                                                    Settings::Category::GpuDriver};

        // Material You is the Material 3 look the app already ships (values-v31 dynamic roles), but
        // it used to be off by default even on Android 16. Pre-S devices keep the static palette
        // because the dynamic system colors do not exist there; the user can still pick either.
        Settings::Setting<s32> theme{linkage,
#if defined(__ANDROID__)
                                     android_get_device_api_level() >= 31 ? 1 : 0,
#else
                                     0,
#endif
                                     "theme", Settings::Category::Android};
        Settings::Setting<s32> theme_mode{linkage, -1, "theme_mode", Settings::Category::Android};
        Settings::Setting<s32> static_theme_color{linkage, 0, "static_theme_color", Settings::Category::Android};
        Settings::Setting<bool> black_backgrounds{linkage, false, "black_backgrounds",
                                                  Settings::Category::Android};
        Settings::Setting<s32> app_language{linkage, 0, "app_language", Settings::Category::Android};
        Settings::Setting<bool> enable_update_checks{linkage, true, "enable_update_checks",
                                                  Settings::Category::Android};
        Settings::Setting<bool> show_update_button{linkage, true, "show_update_button",
                                                  Settings::Category::Android};

        // Interface motion. Style 0 = tween, 1 = kinetic (physics), 2 = instant.
        Settings::Setting<s32> ui_motion_style{linkage, 0, "ui_motion_style",
                                               Settings::Category::Android};
        Settings::Setting<s32> ui_motion_scale{linkage, 100, "ui_motion_scale",
                                               Settings::Category::Android};
        Settings::Setting<s32> ui_press_scale{linkage, 97, "ui_press_scale",
                                              Settings::Category::Android};
        Settings::Setting<s32> ui_motion_bounce{linkage, 22, "ui_motion_bounce",
                                                Settings::Category::Android};
        Settings::Setting<bool> ui_press_feedback{linkage, true, "ui_press_feedback",
                                                  Settings::Category::Android};
        Settings::Setting<bool> ui_haptic_feedback{linkage, true, "ui_haptic_feedback",
                                                   Settings::Category::Android};
        Settings::Setting<bool> ui_entrance_animation{linkage, true, "ui_entrance_animation",
                                                      Settings::Category::Android};
        Settings::Setting<bool> ui_transition_animation{linkage, true, "ui_transition_animation",
                                                        Settings::Category::Android};

        Settings::Setting<bool> enable_folder_button{linkage, true, "enable_folder_button",
                                                  Settings::Category::Android};
        Settings::Setting<bool> enable_qlaunch_button{linkage, false, "enable_qlaunch_button",
                                                  Settings::Category::Android};
        Settings::Setting<bool> invert_confirm_back_controller_buttons{
            linkage, false, "invert_confirm_back_controller_buttons", Settings::Category::Android};

        // Input/performance overlay settings
        std::vector<OverlayControlData> overlay_control_data;
        Settings::Setting<s32> overlay_scale{linkage, 50, "control_scale",
                                             Settings::Category::Overlay};
        Settings::Setting<s32> overlay_opacity{linkage, 100, "control_opacity",
                                               Settings::Category::Overlay};

        Settings::Setting<bool> joystick_rel_center{linkage, true, "joystick_rel_center",
                                                    Settings::Category::Overlay};
        Settings::Setting<bool> dpad_slide{linkage, true, "dpad_slide",
                                           Settings::Category::Overlay};
        Settings::Setting<bool> haptic_feedback{linkage, true, "haptic_feedback",
                                                Settings::Category::Overlay};
        Settings::Setting<bool> show_performance_overlay{linkage, true, "show_performance_overlay",
                                                         Settings::Category::Overlay,
                                                         Settings::Specialization::Paired, true,
                                                         true};
        Settings::Setting<bool> enable_input_overlay_auto_hide{linkage, false,
                                                              "enable_input_overlay_auto_hide",
                                                              Settings::Category::Overlay,
                                                              Settings::Specialization::Default, true,
                                                              true,};

        Settings::Setting<u32> input_overlay_auto_hide{linkage, 5, "input_overlay_auto_hide",
                                                    Settings::Category::Overlay,
                                                    Settings::Specialization::Default, true, true, &enable_input_overlay_auto_hide};
        Settings::Setting<bool> hide_overlay_on_controller_input{linkage, true,
                                                              "hide_overlay_on_controller_input",
                                                              Settings::Category::Overlay,
                                                              Settings::Specialization::Default, true,
                                                              true};
        Settings::Setting<bool> perf_overlay_background{linkage, false, "perf_overlay_background",
                                                        Settings::Category::Overlay,
                                                        Settings::Specialization::Default, true,
                                                        true,
                                                        &show_performance_overlay};
        Settings::Setting<s32> perf_overlay_position{linkage, 0, "perf_overlay_position",
                                                     Settings::Category::Overlay,
                                                     Settings::Specialization::Default, true, true,
                                                     &show_performance_overlay};

        Settings::Setting<bool> show_fps{linkage, true, "show_fps",
                                         Settings::Category::Overlay,
                                         Settings::Specialization::Default, true, true,
                                         &show_performance_overlay};
        Settings::Setting<bool> show_frame_time{linkage, false, "show_frame_time",
                                                Settings::Category::Overlay,
                                                Settings::Specialization::Default, true, true,
                                                &show_performance_overlay};
        Settings::Setting<bool> show_app_ram_usage{linkage, false, "show_app_ram_usage",
                                                   Settings::Category::Overlay,
                                                   Settings::Specialization::Default, true, true,
                                                   &show_performance_overlay};
        Settings::Setting<bool> show_system_ram_usage{linkage, false, "show_system_ram_usage",
                                                      Settings::Category::Overlay,
                                                      Settings::Specialization::Default, true, true,
                                                      &show_performance_overlay};
        Settings::Setting<bool> show_bat_temperature{linkage, false, "show_bat_temperature",
                                                     Settings::Category::Overlay,
                                                     Settings::Specialization::Default, true, true,
                                                     &show_performance_overlay};
        Settings::Setting<Settings::TemperatureUnits> bat_temperature_unit{linkage,
                                                                           Settings::TemperatureUnits::Celsius,
                                                                           "bat_temperature_unit",
                                                                           Settings::Category::Overlay,
                                                                           Settings::Specialization::Default,
                                                                           true, true,
                                                                           &show_bat_temperature};
        Settings::Setting<bool> show_power_info{linkage, false, "show_power_info",
                                                Settings::Category::Overlay,
                                                Settings::Specialization::Default, true, true,
                                                &show_performance_overlay};
        Settings::Setting<bool> show_shaders_building{linkage, true, "show_shaders_building",
                                                      Settings::Category::Overlay,
                                                      Settings::Specialization::Default, true, true,
                                                      &show_performance_overlay};
        Settings::Setting<bool> show_skip_stats{linkage, false, "show_skip_stats",
                                                Settings::Category::Overlay,
                                                Settings::Specialization::Default, true, true,
                                                &show_performance_overlay};


        // These threads run the driver's shader compiler via vkCreateGraphicsPipelines, which is
        // the slow path on Mali. Each worker holds its own compile working set, so eight of them
        // spike memory on a phone that has none to spare. GetTotalPipelineWorkers() also caps the
        // pool at hardware_concurrency - 1. Raise it in Graphics -> Advanced for faster warm-up.
        Settings::Setting<s32> pipeline_worker_count{linkage, 4, "pipeline_worker_count",
                                 Settings::Category::Android,
                                 Settings::Specialization::Default,
                                 true,
                                 true};

        // Host-visible upload buffer, which is plain system RAM on an integrated Mali part and is
        // committed up front. Read at renderer construction, so it needs a restart to take effect.
        Settings::Setting<s32> mali_stream_buffer_mib{linkage, 64, "mali_stream_buffer_mib",
                                                      Settings::Category::Android,
                                                      Settings::Specialization::Default,
                                                      true,
                                                      false};

        // Ceiling for the video memory the caches may plan against. Mali's DDK reports no memory
        // budget, so without this the estimate falls back to a share of system RAM and the caches
        // keep absolute desktop floors that outrank it. Read at device construction.
        Settings::Setting<s32> vram_budget_mib{linkage, 1536, "vram_budget_mib",
                                              Settings::Category::Android,
                                              Settings::Specialization::Default,
                                              true,
                                              false};

        // How many draws RasterizerVulkan::FlushWork records before it submits the command chunk.
        // Fewer submissions mean less driver and queue overhead, but the worker sees the work later
        // and one batch costs more memory. Read every flush, so it applies immediately and can be
        // tuned per game. Only used in Malikan mode.
        Settings::Setting<s32> draw_batch_size{linkage, 8192, "draw_batch_size",
                                               Settings::Category::Android,
                                               Settings::Specialization::Default,
                                               true,
                                               true};


        Settings::Setting<bool> show_input_overlay{linkage, true, "show_input_overlay",
                                                   Settings::Category::Overlay};
        Settings::Setting<bool> overlay_snap_to_grid{linkage, false, "overlay_snap_to_grid",
                                                     Settings::Category::Overlay};
        Settings::Setting<s32> overlay_grid_size{linkage, 32, "overlay_grid_size",
                                                 Settings::Category::Overlay};
        Settings::Setting<bool> touchscreen{linkage, true, "touchscreen",
                                            Settings::Category::Overlay};
        Settings::Setting<s32> lock_drawer{linkage, false, "lock_drawer",
                                           Settings::Category::Overlay};

        /// DEVICE/SOC OVERLAY

        Settings::Setting<bool> show_soc_overlay{linkage, true, "show_soc_overlay",
                                                 Settings::Category::Overlay,
                                                 Settings::Specialization::Paired, true, true};
        Settings::Setting<bool> show_build_id{linkage, true, "show_build_id",
                                                  Settings::Category::Overlay,
                                                  Settings::Specialization::Default, true, true,
                                                  &show_performance_overlay};
        Settings::Setting<bool> show_driver_version{linkage, true, "show_driver_version",
                                                  Settings::Category::Overlay,
                                                  Settings::Specialization::Default, true, true,
                                                  &show_performance_overlay};
        Settings::Setting<bool> show_device_model{linkage, true, "show_device_model",
                                                  Settings::Category::Overlay,
                                                  Settings::Specialization::Default, true, true,
                                                  &show_performance_overlay};

        Settings::Setting<bool> show_gpu_model{linkage, true, "show_gpu_model",
                                               Settings::Category::Overlay,
                                               Settings::Specialization::Default, true, true,
                                               &show_performance_overlay};

        Settings::Setting<bool> show_soc_model{linkage, true, "show_soc_model",
                                               Settings::Category::Overlay,
                                               Settings::Specialization::Default, true, true,
                                               &show_soc_overlay};

        Settings::Setting<bool> show_fw_version{linkage, true, "show_firmware_version",
                                                Settings::Category::Overlay,
                                                Settings::Specialization::Default, true, true,
                                                &show_performance_overlay};

        Settings::Setting<bool> soc_overlay_background{linkage, false, "soc_overlay_background",
                                                       Settings::Category::Overlay,
                                                       Settings::Specialization::Default, true,
                                                       true,
                                                       &show_soc_overlay};
        Settings::Setting<s32> soc_overlay_position{linkage, 2, "soc_overlay_position",
                                                    Settings::Category::Overlay,
                                                    Settings::Specialization::Default, true, true,
                                                    &show_soc_overlay};

        // MISC
        Settings::Setting<bool> dont_show_driver_shader_warning{linkage, false,
                                                                "dont_show_driver_shader_warning",
                                                                Settings::Category::Android, Settings::Specialization::Default, true, true};
        Settings::Setting<bool> enable_quick_settings{linkage, true,
                                                        "enable_quick_settings",
                                                        Settings::Category::Android, Settings::Specialization::Default, true,
                                                      false};
    };

    extern Values values;

} // namespace AndroidSettings
