#pragma once

#include <vulkan/vulkan.h>

extern "C" void asr_vk_set(PFN_vkGetDeviceProcAddr a, PFN_vkEnumerateDeviceExtensionProperties b,
                           PFN_vkGetPhysicalDeviceFeatures2 c,
                           PFN_vkGetPhysicalDeviceProperties2 d,
                           PFN_vkGetPhysicalDeviceMemoryProperties e, PFN_vkCmdSetViewport f,
                           PFN_vkCmdSetScissor g);
