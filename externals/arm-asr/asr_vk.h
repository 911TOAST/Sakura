#pragma once

#include <vulkan/vulkan.h>

#include "asr_vk_set.h"

extern PFN_vkGetDeviceProcAddr asr_gdpa;
extern PFN_vkEnumerateDeviceExtensionProperties asr_edep;
extern PFN_vkGetPhysicalDeviceFeatures2 asr_gpf2;
extern PFN_vkGetPhysicalDeviceProperties2 asr_gpp2;
extern PFN_vkGetPhysicalDeviceMemoryProperties asr_gpmp;
extern PFN_vkCmdSetViewport asr_csv;
extern PFN_vkCmdSetScissor asr_css;

#define vkGetDeviceProcAddr asr_gdpa
#define vkEnumerateDeviceExtensionProperties asr_edep
#define vkGetPhysicalDeviceFeatures2 asr_gpf2
#define vkGetPhysicalDeviceProperties2 asr_gpp2
#define vkGetPhysicalDeviceMemoryProperties asr_gpmp
#define vkCmdSetViewport asr_csv
#define vkCmdSetScissor asr_css
