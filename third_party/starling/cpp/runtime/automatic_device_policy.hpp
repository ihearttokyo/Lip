// Conservative eligibility, not hardware attestation or measured efficiency.
// Numeric values follow the pinned Khronos Vulkan-Headers v1.4.335 enums.
// This header needs no Vulkan SDK or mandatory Vulkan backend linkage.
#pragma once
#include "ggml-vulkan.h"

namespace starling::ggml {

enum class AutomaticDeviceEligibility {
    EligibleReportedVulkanGpu,
    IncompleteProvenance,
    NonGpuPhysicalType,
    UnsupportedApi,
    SoftwareOrVirtualDriver,
    UnqualifiedDriver,
};

inline AutomaticDeviceEligibility automatic_device_eligibility(
    const ggml_backend_vk_device_info_v1& facts) noexcept {
    if (facts.version != GGML_VK_DEVICE_INFO_VERSION ||
        facts.flags != (GGML_VK_PHYSICAL_PROPERTIES_VALID | GGML_VK_DRIVER_PROPERTIES_VALID) ||
        facts.vendor_id == 0 || facts.driver_id == 0)
        return AutomaticDeviceEligibility::IncompleteProvenance;
    // VkPhysicalDeviceType: integrated=1, discrete=2; reject OTHER=0,
    // virtual=3, CPU=4 and future/unknown values regardless of GGML's label.
    if (facts.physical_device_type != 1 && facts.physical_device_type != 2)
        return AutomaticDeviceEligibility::NonGpuPhysicalType;
    const auto variant = facts.api_version >> 29;
    const auto major = (facts.api_version >> 22) & 0x7fu;
    const auto minor = (facts.api_version >> 12) & 0x3ffu;
    // The inspected ggml Vulkan implementation requires Vulkan >=1.2.
    // Unknown variants/major versions are not implicitly admitted.
    if (variant != 0 || major != 1 || minor < 2)
        return AutomaticDeviceEligibility::UnsupportedApi;
    switch (facts.driver_id) {
        case 10: // GOOGLE_SWIFTSHADER
        case 13: // MESA_LLVMPIPE
        case 22: // MESA_VENUS (virtual)
            return AutomaticDeviceEligibility::SoftwareOrVirtualDriver;
        // Explicit known native-driver provenance, not graph/performance support.
        case 1:  // AMD_PROPRIETARY
        case 2:  // AMD_OPEN_SOURCE
        case 3:  // MESA_RADV
        case 4:  // NVIDIA_PROPRIETARY
        case 5:  // INTEL_PROPRIETARY_WINDOWS
        case 6:  // INTEL_OPEN_SOURCE_MESA
        case 7:  // IMAGINATION_PROPRIETARY
        case 8:  // QUALCOMM_PROPRIETARY
        case 9:  // ARM_PROPRIETARY
        case 12: // BROADCOM_PROPRIETARY
        case 15: // COREAVI_PROPRIETARY
        case 17: // VERISILICON_PROPRIETARY
        case 18: // MESA_TURNIP
        case 19: // MESA_V3DV
        case 20: // MESA_PANVK
        case 21: // SAMSUNG_PROPRIETARY
        case 24: // MESA_NVK
        case 25: // IMAGINATION_OPEN_SOURCE_MESA
        case 26: // MESA_HONEYKRISP
            return AutomaticDeviceEligibility::EligibleReportedVulkanGpu;
        default:
            // Remote/translation/emulation and unrecognized provenance remain
            // unqualified. Do not mislabel all such drivers as software GPUs.
            return AutomaticDeviceEligibility::UnqualifiedDriver;
    }
}

} // namespace starling::ggml
