#pragma once

#include "ggml.h"
#include "ggml-backend.h"

#ifdef  __cplusplus
extern "C" {
#endif

#define GGML_VK_NAME "Vulkan"
#define GGML_VK_MAX_DEVICES 16

// Optional registry procedure: immutable enumeration facts, not hardware
// attestation, graph support, actual backend selection or efficiency evidence.
// No Vulkan SDK types or global ggml interface-layout change are required.
#define GGML_VK_DEVICE_INFO_VERSION 1u
#define GGML_VK_DEVICE_INFO_V1_PROC "ggml_backend_vk_device_info_v1"
#define GGML_VK_PHYSICAL_PROPERTIES_VALID 1u
#define GGML_VK_DRIVER_PROPERTIES_VALID 2u

struct ggml_backend_vk_device_info_v1 {
    uint32_t version;
    uint32_t flags;
    uint32_t physical_device_type;
    uint32_t vendor_id;
    uint32_t device_id;
    uint32_t api_version;
    uint32_t driver_version;
    uint32_t driver_id;
};

// Obtain with ggml_backend_reg_get_proc_address on the candidate's own registry.
// Exact owned device, version1 and sizeof(v1) are required. A foreign opaque
// device pointer is rejected by identity before it is dereferenced. Missing
// driver properties/probe errors return false, never guessed provenance.
// Caller supplies writable out_size bytes. Every failure clears at most
// min(out_size,sizeof(v1)) bytes, including malformed small/oversize requests;
// no memory is accessed when out==NULL. Successful raw CPU/virtual/other or
// unknown nonzero driver IDs are facts for the caller to reject, not eligibility.
// Query performs no logical-device/model initialization and returns cached data.
typedef bool (*ggml_backend_vk_device_info_v1_t)(
    ggml_backend_dev_t device, uint32_t requested_version,
    struct ggml_backend_vk_device_info_v1 * out, size_t out_size);

// backend API
GGML_BACKEND_API ggml_backend_t ggml_backend_vk_init(size_t dev_num);

GGML_BACKEND_API bool ggml_backend_is_vk(ggml_backend_t backend);
GGML_BACKEND_API int  ggml_backend_vk_get_device_count(void);
GGML_BACKEND_API void ggml_backend_vk_get_device_description(int device, char * description, size_t description_size);
GGML_BACKEND_API void ggml_backend_vk_get_device_memory(int device, size_t * free, size_t * total);

GGML_BACKEND_API ggml_backend_buffer_type_t ggml_backend_vk_buffer_type(size_t dev_num);
// pinned host buffer for use with the CPU backend for faster copies between CPU and GPU
GGML_BACKEND_API ggml_backend_buffer_type_t ggml_backend_vk_host_buffer_type(void);

GGML_BACKEND_API ggml_backend_reg_t ggml_backend_vk_reg(void);

#ifdef  __cplusplus
}
#endif
