#include "asr_vk.h"

PFN_vkGetDeviceProcAddr asr_gdpa{};
PFN_vkEnumerateDeviceExtensionProperties asr_edep{};
PFN_vkGetPhysicalDeviceFeatures2 asr_gpf2{};
PFN_vkGetPhysicalDeviceProperties2 asr_gpp2{};
PFN_vkGetPhysicalDeviceMemoryProperties asr_gpmp{};
PFN_vkCmdSetViewport asr_csv{};
PFN_vkCmdSetScissor asr_css{};

extern "C" void asr_vk_set(PFN_vkGetDeviceProcAddr a, PFN_vkEnumerateDeviceExtensionProperties b,
                           PFN_vkGetPhysicalDeviceFeatures2 c,
                           PFN_vkGetPhysicalDeviceProperties2 d,
                           PFN_vkGetPhysicalDeviceMemoryProperties e, PFN_vkCmdSetViewport f,
                           PFN_vkCmdSetScissor g) {
    asr_gdpa = a;
    asr_edep = b;
    asr_gpf2 = c;
    asr_gpp2 = d;
    asr_gpmp = e;
    asr_csv = f;
    asr_css = g;
}
