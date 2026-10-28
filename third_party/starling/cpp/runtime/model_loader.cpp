// model_loader.cpp — GGUF parse + weight realize.
//
// Parses the GGUF header (gguf_init_from_file, no_alloc) and maps the file
// privately: every tensor points into the mapping, so the weight region is
// one contiguous host range (zero-copy on CPU) whose pages load on first
// touch. The fast engines repack straight from it and drop the pages they
// consumed; nothing holds a second copy. Platforms without mmap read the
// file into the ggml context instead.
// Reads KV metadata + maps tensor name -> ggml_tensor*. realize_weights() gives
// each weight a backend buffer (zero-copy borrow on CPU, mirrored upload on
// GPU) so clone_weight() can hand them to graphs as already-allocated leaves.

#include "model_loader.hpp"
#include "backend.hpp"
#include "cpu_repack.hpp"
#include "graph.hpp"

#include "ggml.h"
#include "gguf.h"
#include "ggml-backend.h"

#if defined(__unix__) || defined(__APPLE__)
#define STARLING_LOADER_MMAP 1
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#else
#define STARLING_LOADER_MMAP 0
#endif

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <unordered_set>
#include <stdexcept>
#include <string>
#include <vector>

namespace starling::ggml {

namespace {
std::unordered_set<ModelLoader*> loaders;
std::unordered_set<ModelLoader*>& live_loaders() { return loaders; }

using FileOwner = std::unique_ptr<FILE, decltype(&std::fclose)>;

#if STARLING_LOADER_MMAP
class DescriptorOwner {
public:
    explicit DescriptorOwner(int fd) noexcept : fd_(fd) {}
    ~DescriptorOwner() { if (fd_ >= 0) (void)close(fd_); }
    DescriptorOwner(const DescriptorOwner&) = delete;
    DescriptorOwner& operator=(const DescriptorOwner&) = delete;
    int get() const noexcept { return fd_; }
    int release() noexcept { int fd = fd_; fd_ = -1; return fd; }
private:
    int fd_;
};

// Validate BEFORE fdopen/fread, so a path FIFO with no writer cannot block.
bool descriptor_size(int fd, size_t& size, std::string& error) {
    struct stat st = {};
    if (fstat(fd, &st) != 0) { error = "cannot stat GGUF descriptor"; return false; }
    if (!S_ISREG(st.st_mode) || st.st_size <= 0) {
        error = "GGUF requires a regular nonempty file"; return false;
    }
    if (static_cast<uintmax_t>(st.st_size) > std::numeric_limits<size_t>::max()) {
        error = "GGUF file size is not representable"; return false;
    }
    const int flags = fcntl(fd, F_GETFL);
    if (flags < 0 || (flags & O_ACCMODE) == O_WRONLY) {
        error = "GGUF descriptor is not readable"; return false;
    }
    if (lseek(fd, 0, SEEK_CUR) < 0) {
        error = "GGUF descriptor is not seekable"; return false;
    }
    size = static_cast<size_t>(st.st_size);
    return true;
}
#else
bool stream_size(FILE* file, size_t& size, std::string& error) {
#if defined(_WIN32)
    if (_fseeki64(file, 0, SEEK_END) != 0) { error = "GGUF stream is not seekable"; return false; }
    const auto bytes = _ftelli64(file);
#else
    if (std::fseek(file, 0, SEEK_END) != 0) { error = "GGUF stream is not seekable"; return false; }
    const auto bytes = std::ftell(file);
#endif
    if (bytes <= 0 || static_cast<uintmax_t>(bytes) > std::numeric_limits<size_t>::max()) {
        error = "GGUF stream size is empty or not representable"; return false;
    }
    size = static_cast<size_t>(bytes);
    return true;
}
#endif

}

ModelLoader::ModelLoader() {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    live_loaders().insert(this);
}

void ModelLoader::release_runtime_resources() {
    try {
        if (retained_runtime_resources_) throw std::runtime_error("loader resources are quarantined");
        if (realized_backend_) realized_backend_->require_healthy();
        while (!caches_.empty()) {
            caches_.pop_back();
            // Cache destructors are noexcept and can retain their own native
            // resources. Never then free the weights those resources reference.
            if (realized_backend_) realized_backend_->require_healthy();
        }
        if (weight_buffer_) cpu_repack::detach(weight_buffer_);
        if (weight_buffer_) ggml_backend_buffer_free(weight_buffer_);
        weight_buffer_ = nullptr;
        if (device_ctx_) {
            tensors_.swap(host_tensors_);
            host_tensors_.clear();
        }
        for (auto& item : tensors_) item.second->buffer = nullptr;
        if (device_ctx_) ggml_free(device_ctx_);
        device_ctx_ = nullptr;
        realized_ = false;
        realized_backend_ = nullptr;
    } catch (...) {
        retained_runtime_resources_ = true;
        if (realized_backend_) realized_backend_->mark_unhealthy();
        throw;
    }
}

void ModelLoader::release_all_runtime_resources() {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    for (auto* loader : live_loaders()) loader->release_runtime_resources();
}

ModelLoader::~ModelLoader() {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    try { release_runtime_resources(); }
    catch (...) {
        // A destructor cannot report drain. Retain every remaining cache, raw
        // weight/context and mapped byte instead of invalidating device readers.
        for (auto& slot : caches_) (void)slot.second.release();
        live_loaders().erase(this);
        std::fprintf(stderr, "[loader] unconfirmed release; retaining model resources and mapping\n");
        return;
    }
    live_loaders().erase(this);
    if (data_) cpu_repack::forget(data_, data_size_);
    if (ctx_) ggml_free(ctx_);
    if (compat_ctx_) ggml_free(compat_ctx_);
    if (gguf_ctx_) gguf_free(gguf_ctx_);
#if STARLING_LOADER_MMAP
    if (map_addr_) munmap(map_addr_, map_len_);
#endif
}

void ModelLoader::drop_mapping() {
#if STARLING_LOADER_MMAP
    if (map_addr_) munmap(map_addr_, map_len_);
#endif
    map_addr_ = nullptr;
    map_len_ = 0;
    data_ = nullptr;
    data_size_ = 0;
    if (gguf_ctx_) gguf_free(gguf_ctx_);
    gguf_ctx_ = nullptr;
    if (ctx_) ggml_free(ctx_);
    ctx_ = nullptr;
}

ModelLoader::MapResult ModelLoader::map_weights(FILE* file, size_t file_size) {
#if STARLING_LOADER_MMAP
    gguf_init_params params = { /*.no_alloc =*/ true, /*.ctx =*/ &ctx_ };
    gguf_ctx_ = gguf_init_from_file_ptr(file, params);
    if (!gguf_ctx_) {
        drop_mapping(); error_ = "failed to parse GGUF metadata";
        return MapResult::Failed;
    }
    // CPU buffers need 32-byte-aligned data. Rewind/read this SAME stream if
    // the file's alignment or the current address space prevents mapping.
    if (gguf_get_alignment(gguf_ctx_) % 32 != 0) {
        drop_mapping(); return MapResult::ReadInstead;
    }
    const int fd = fileno(file);
    if (fd < 0) {
        drop_mapping(); error_ = "GGUF stream descriptor is unavailable";
        return MapResult::Failed;
    }
    const size_t data_off = gguf_get_data_offset(gguf_ctx_);
    if (data_off > file_size) {
        drop_mapping(); error_ = "GGUF data offset is past the file end";
        return MapResult::Failed;
    }
    // The exact descriptor already used by the parser, never a reopened path.
    // MAP_PRIVATE remains writable for CPU repack without mutating the file.
    void* p = mmap(nullptr, file_size, PROT_READ | PROT_WRITE, MAP_PRIVATE, fd, 0);
    if (p == MAP_FAILED) {
        drop_mapping(); return MapResult::ReadInstead;
    }
    map_addr_ = p; map_len_ = file_size;
    data_ = static_cast<uint8_t*>(p) + data_off;
    data_size_ = file_size - data_off;
    const int64_t n_tensors = gguf_get_n_tensors(gguf_ctx_);
    for (int64_t i = 0; i < n_tensors; ++i) {
        const char* name = gguf_get_tensor_name(gguf_ctx_, i);
        ggml_tensor* t = name ? ggml_get_tensor(ctx_, name) : nullptr;
        if (!t) continue;
        const size_t off = gguf_get_tensor_offset(gguf_ctx_, i);
        if (off > data_size_ || ggml_nbytes(t) > data_size_ - off) {
            error_ = "GGUF tensor extends past the file end";
            drop_mapping(); return MapResult::Failed;
        }
        t->data = data_ + off;
    }
    return MapResult::Mapped;
#else
    (void)file; (void)file_size;
    return MapResult::ReadInstead;
#endif
}

bool ModelLoader::load(const char* path) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if (ctx_ || gguf_ctx_) { error_ = "model loader is already loaded"; return false; }
    if (!path || !*path) { error_ = "empty GGUF path"; return false; }
    error_.clear();
    size_t bytes = 0;
#if STARLING_LOADER_MMAP
    // O_NONBLOCK avoids waiting on a FIFO before fstat rejects it. It has no
    // effect on ordinary regular-file reads. This is the ONLY path open.
    DescriptorOwner fd(open(path, O_RDONLY | O_CLOEXEC | O_NONBLOCK));
    if (fd.get() < 0) { error_ = "cannot open GGUF file"; return false; }
    if (!descriptor_size(fd.get(), bytes, error_)) return false;
    FILE* file = fdopen(fd.get(), "rb");
    if (!file) { error_ = "cannot create GGUF file stream"; return false; }
    (void)fd.release(); // FILE now owns the exact already-validated descriptor
#else
    // Preserve ggml's UTF-8 path support on Windows rather than raw fopen.
    FileOwner stream(ggml_fopen(path, "rb"), &std::fclose);
    if (!stream) { error_ = "cannot open GGUF file"; return false; }
    if (!stream_size(stream.get(), bytes, error_)) return false;
    FILE* file = stream.release();
#endif
    return load_file(file, bytes, path);
}

bool ModelLoader::load_fd(int fd) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if (ctx_ || gguf_ctx_) { error_ = "model loader is already loaded"; return false; }
    error_.clear();
#if STARLING_LOADER_MMAP
    if (fd < 0) { error_ = "invalid GGUF descriptor"; return false; }
    // Own a different descriptor number, NOT a different seek cursor. Caller
    // ownership is preserved even on parser/map/read/close failure.
    DescriptorOwner owned(fcntl(fd, F_DUPFD_CLOEXEC, 0));
    if (owned.get() < 0) { error_ = "cannot duplicate GGUF descriptor"; return false; }
    size_t bytes = 0;
    if (!descriptor_size(owned.get(), bytes, error_)) return false;
    FILE* file = fdopen(owned.get(), "rb");
    if (!file) { error_ = "cannot create GGUF descriptor stream"; return false; }
    (void)owned.release(); // FILE owns only our duplicate, never the caller fd
    return load_file(file, bytes, "");
#else
    (void)fd; error_ = "GGUF descriptor loading is unsupported on this platform";
    return false;
#endif
}

bool ModelLoader::load_file(FILE* raw, size_t file_size, const char* path_label) {
    FileOwner file(raw, &std::fclose);
    const auto t0 = std::chrono::steady_clock::now();
    if (std::fseek(file.get(), 0, SEEK_SET) != 0) {
        error_ = "cannot rewind GGUF stream"; return false;
    }
    const MapResult mapped = map_weights(file.get(), file_size);
    if (mapped == MapResult::Failed) return false;
    if (mapped == MapResult::ReadInstead) {
        // Alignment/mmap refusal must not cause a second pathname open.
        if (std::fseek(file.get(), 0, SEEK_SET) != 0) {
            error_ = "cannot rewind GGUF read fallback"; return false;
        }
        gguf_init_params params = { /*.no_alloc =*/ false, /*.ctx =*/ &ctx_ };
        gguf_ctx_ = gguf_init_from_file_ptr(file.get(), params);
        if (!gguf_ctx_) {
            drop_mapping(); error_ = "failed to read GGUF from the same stream";
            return false;
        }
        data_ = static_cast<uint8_t*>(ggml_get_mem_buffer(ctx_));
        data_size_ = ggml_get_mem_size(ctx_);
    }
    // Do not publish successful metadata/path if even the owned stream's close
    // is unconfirmed. release() prevents a second close after an ambiguous one.
    if (std::fclose(file.release()) != 0) {
        drop_mapping(); error_ = "GGUF owned stream close was not confirmed";
        return false;
    }
    if (const char* e = std::getenv("STARLING_FAST_TIMING"); e && e[0] == '1')
        std::fprintf(stderr, "[load] gguf %s %.1f ms (%.1f MiB)\n", map_addr_ ? "map" : "read",
                     std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count(),
                     data_size_ / 1048576.0);
    finalize_metadata();
    path_ = path_label;
    return true;
}

void ModelLoader::finalize_metadata() {
    // Map every tensor name -> tensor*.
    const int64_t n_tensors = gguf_get_n_tensors(gguf_ctx_);
    for (int64_t i = 0; i < n_tensors; ++i) {
        const char* name = gguf_get_tensor_name(gguf_ctx_, i);
        ggml_tensor* t = name ? ggml_get_tensor(ctx_, name) : nullptr;
        if (name && t) tensors_[name] = t;
    }
    // Read KV metadata.
    const int64_t n_kv = gguf_get_n_kv(gguf_ctx_);
    for (int64_t i = 0; i < n_kv; ++i) {
        const char* key = gguf_get_key(gguf_ctx_, i);
        const enum gguf_type t = gguf_get_kv_type(gguf_ctx_, i);
        if (!key) continue;
        GgufValue v;
        switch (t) {
            case GGUF_TYPE_STRING:
                v.kind = GgufValue::Kind::k_str;
                v.s = gguf_get_val_str(gguf_ctx_, i);
                break;
            case GGUF_TYPE_UINT8: case GGUF_TYPE_INT8:
            case GGUF_TYPE_UINT16: case GGUF_TYPE_INT16:
            case GGUF_TYPE_UINT32: case GGUF_TYPE_INT32:
            case GGUF_TYPE_UINT64: case GGUF_TYPE_INT64:
                v.kind = GgufValue::Kind::k_int;
                // Use the type-specific accessor (gguf_get_val_i64 aborts on
                // non-INT64 types). Map each width to its accessor.
                switch (t) {
                    case GGUF_TYPE_UINT8:  v.i = gguf_get_val_u8(gguf_ctx_, i); break;
                    case GGUF_TYPE_INT8:   v.i = gguf_get_val_i8(gguf_ctx_, i); break;
                    case GGUF_TYPE_UINT16: v.i = gguf_get_val_u16(gguf_ctx_, i); break;
                    case GGUF_TYPE_INT16:  v.i = gguf_get_val_i16(gguf_ctx_, i); break;
                    case GGUF_TYPE_UINT32: v.i = gguf_get_val_u32(gguf_ctx_, i); break;
                    case GGUF_TYPE_INT32:  v.i = gguf_get_val_i32(gguf_ctx_, i); break;
                    case GGUF_TYPE_UINT64: v.i = (int64_t)gguf_get_val_u64(gguf_ctx_, i); break;
                    case GGUF_TYPE_INT64:  v.i = gguf_get_val_i64(gguf_ctx_, i); break;
                    default: continue;
                }
                break;
            case GGUF_TYPE_FLOAT32: case GGUF_TYPE_FLOAT64:
                v.kind = GgufValue::Kind::k_float;
                v.f = (t == GGUF_TYPE_FLOAT32) ? (double)gguf_get_val_f32(gguf_ctx_, i)
                                                : gguf_get_val_f64(gguf_ctx_, i);
                break;
            case GGUF_TYPE_ARRAY: {
                const enum gguf_type at = gguf_get_arr_type(gguf_ctx_, i);
                const size_t n = gguf_get_arr_n(gguf_ctx_, i);
                if (at == GGUF_TYPE_STRING) {
                    v.kind = GgufValue::Kind::k_arr_str;
                    v.arr_s.reserve(n);
                    for (size_t j = 0; j < n; ++j)
                        v.arr_s.emplace_back(gguf_get_arr_str(gguf_ctx_, i, j));
                } else {
                    // Integer array: gguf_get_arr_data returns the raw buffer;
                    // interpret according to `at`.
                    v.kind = GgufValue::Kind::k_arr_int;
                    const void* raw = gguf_get_arr_data(gguf_ctx_, i);
                    v.arr_i.resize(n);
                    for (size_t j = 0; j < n; ++j) {
                        switch (at) {
                            case GGUF_TYPE_INT8:  v.arr_i[j] = ((const int8_t*)raw)[j]; break;
                            case GGUF_TYPE_UINT8: v.arr_i[j] = ((const uint8_t*)raw)[j]; break;
                            case GGUF_TYPE_INT16: v.arr_i[j] = ((const int16_t*)raw)[j]; break;
                            case GGUF_TYPE_UINT16:v.arr_i[j] = ((const uint16_t*)raw)[j]; break;
                            case GGUF_TYPE_INT32: v.arr_i[j] = ((const int32_t*)raw)[j]; break;
                            case GGUF_TYPE_UINT32:v.arr_i[j] = ((const uint32_t*)raw)[j]; break;
                            case GGUF_TYPE_INT64: v.arr_i[j] = ((const int64_t*)raw)[j]; break;
                            case GGUF_TYPE_UINT64:v.arr_i[j] = ((const uint64_t*)raw)[j]; break;
                            default: v.arr_i[j] = 0; break;
                        }
                    }
                }
                break;
            }
            default: continue;
        }
        kv_[key] = std::move(v);
    }
}

// ---- typed KV accessors -----------------------------------------------------
bool ModelLoader::kv_str(const std::string& key, std::string& out) const {
    auto it = kv_.find(key);
    if (it == kv_.end() || it->second.kind != GgufValue::Kind::k_str) return false;
    out = it->second.s; return true;
}
bool ModelLoader::kv_int(const std::string& key, int64_t& out) const {
    auto it = kv_.find(key);
    if (it == kv_.end() || it->second.kind != GgufValue::Kind::k_int) return false;
    out = it->second.i; return true;
}
bool ModelLoader::kv_float(const std::string& key, double& out) const {
    auto it = kv_.find(key);
    if (it == kv_.end() || it->second.kind != GgufValue::Kind::k_float) return false;
    out = it->second.f; return true;
}
bool ModelLoader::kv_arr_str(const std::string& key, std::vector<std::string>& out) const {
    auto it = kv_.find(key);
    if (it == kv_.end() || it->second.kind != GgufValue::Kind::k_arr_str) return false;
    out = it->second.arr_s; return true;
}
bool ModelLoader::kv_arr_int(const std::string& key, std::vector<int64_t>& out) const {
    auto it = kv_.find(key);
    if (it == kv_.end() || it->second.kind != GgufValue::Kind::k_arr_int) return false;
    out = it->second.arr_i; return true;
}

// ---- tensors ----------------------------------------------------------------
ggml_tensor* ModelLoader::tensor(const char* name) const {
    auto it = tensors_.find(name);
    return it == tensors_.end() ? nullptr : it->second;
}
std::vector<std::string> ModelLoader::tensor_names() const {
    std::vector<std::string> names;
    names.reserve(tensors_.size());
    for (const auto& kv : tensors_) names.push_back(kv.first);
    return names;
}

// ---- community-dialect compat ---------------------------------------------
void ModelLoader::add_tensor_alias(const char* alias, const char* existing) {
    auto it = tensors_.find(existing);
    if (it != tensors_.end()) tensors_[alias] = it->second;
}

void ModelLoader::add_owned_tensor(const char* name, const std::vector<float>& data,
                                   int64_t ne0, int64_t ne1) {
    if (!compat_ctx_) {
        // Small scratch context for compat-fabricated tensors (mel
        // filterbank/window, zero bias rows). Freed with the loader.
        struct ggml_init_params params = {
            /*.mem_size   =*/ ggml_tensor_overhead() * 8 + (1 << 20),
            /*.mem_buffer =*/ nullptr,
            /*.no_alloc   =*/ false,
        };
        compat_ctx_ = ggml_init(params);
    }
    ggml_tensor* t = (ne1 > 1) ? ggml_new_tensor_2d(compat_ctx_, GGML_TYPE_F32, ne0, ne1)
                               : ggml_new_tensor_1d(compat_ctx_, GGML_TYPE_F32, ne0);
    if (!t || (size_t)ggml_nelements(t) != data.size()) return;
    std::memcpy(t->data, data.data(), data.size() * sizeof(float));
    ggml_set_name(t, name);
    tensors_[name] = t;
}

void ModelLoader::add_kv_int(const std::string& key, int64_t v) {
    GgufValue val; val.kind = GgufValue::Kind::k_int; val.i = v;
    kv_[key] = std::move(val);
}
void ModelLoader::add_kv_float(const std::string& key, double v) {
    GgufValue val; val.kind = GgufValue::Kind::k_float; val.f = v;
    kv_[key] = std::move(val);
}
void ModelLoader::add_kv_str(const std::string& key, const std::string& v) {
    GgufValue val; val.kind = GgufValue::Kind::k_str; val.s = v;
    kv_[key] = std::move(val);
}
void ModelLoader::add_kv_arr_int(const std::string& key, const std::vector<int64_t>& v) {
    GgufValue val; val.kind = GgufValue::Kind::k_arr_int; val.arr_i = v;
    kv_[key] = std::move(val);
}
void ModelLoader::add_kv_arr_str(const std::string& key, const std::vector<std::string>& v) {
    GgufValue val; val.kind = GgufValue::Kind::k_arr_str; val.arr_s = v;
    kv_[key] = std::move(val);
}

bool ModelLoader::realize_weights(Backend& backend) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if (realized_) return true;
    backend.require_healthy();
    realized_backend_ = &backend;
    if (!ctx_) { error_ = "realize_weights: no loaded context"; return false; }
    // On CPU: borrow the loader's memory-backed context zero-copy.
    // On GPU: mirror each weight into a device-side context + upload.
    if (!backend.is_gpu()) {
        // Borrow the loader's weight memory zero-copy (the file mapping, or
        // the ggml context's allocation when the file was read).
        ggml_backend_buffer_t buf = ggml_backend_cpu_buffer_from_ptr(data_, data_size_);
        if (!buf) { error_ = "realize_weights: CPU buffer alloc failed"; return false; }
        weight_buffer_ = buf;
        // Point each tensor's ->buffer at the borrowed buffer.
        for (const auto& kv : tensors_) {
            ggml_tensor* t = kv.second;
            t->buffer = buf;
            // data pointer already set by the GGUF allocation.
        }
        // Opt the weights into ggml's repacked CPU kernels as graphs use
        // them (no-op unless enabled; see cpu_repack.hpp).
        try {
            cpu_repack::attach(buf);
        } catch (const std::exception& e) {
            error_ = std::string("realize_weights: ") + e.what();
            try { ggml_backend_buffer_free(buf); }
            catch (...) { backend.mark_unhealthy(); throw; }
            weight_buffer_ = nullptr;
            for (const auto& kv : tensors_) kv.second->buffer = nullptr;
            return false;
        }
    } else {
        // GPU: allocate a no_alloc device context, mirror tensors, upload.
        struct ggml_init_params params = {
            /*.mem_size   =*/ ggml_tensor_overhead() * (tensors_.size() + 16),
            /*.mem_buffer =*/ nullptr,
            /*.no_alloc   =*/ true,
        };
        struct UploadOwners {
            Backend& backend;
            ggml_context* context = nullptr;
            ggml_backend_buffer_t buffer = nullptr;
            ~UploadOwners() noexcept {
                if (!context && !buffer) return;
                try {
                    backend.synchronize();
                    if (buffer) { ggml_backend_buffer_free(buffer); buffer = nullptr; }
                    if (context) { ggml_free(context); context = nullptr; }
                } catch (...) {
                    // Native free can itself be uncertain. Never let it throw
                    // through a unique_ptr deleter or free the other owner.
                    // The outer retained model keeps its original mapped bytes.
                    backend.mark_unhealthy();
                }
                // Remaining raw handles deliberately stay allocated/quarantined.
            }
        } owners{backend};
        owners.context = ggml_init(params);
        if (!owners.context) { error_ = "realize_weights: context alloc failed"; return false; }
        std::unordered_map<std::string, ggml_tensor*> device_tensors;
        std::unordered_map<ggml_tensor*, ggml_tensor*> mirrored;
        for (const auto& kv : tensors_) {
            ggml_tensor* src = kv.second;
            auto& dst = mirrored[src];
            if (!dst) { dst = ggml_dup_tensor(owners.context, src); ggml_set_name(dst, src->name); }
            device_tensors[kv.first] = dst;
        }
        owners.buffer = ggml_backend_alloc_ctx_tensors(owners.context, backend.handle());
        if (!owners.buffer) { error_ = "realize_weights: device alloc failed"; return false; }
        for (const auto& item : mirrored) {
            if (item.first->data)
                ggml_backend_tensor_set(item.second, item.first->data, 0, ggml_nbytes(item.first));
        }
        backend.synchronize();
        tensors_.swap(device_tensors);
        host_tensors_.swap(device_tensors);
        device_ctx_ = owners.context; owners.context = nullptr;
        weight_buffer_ = owners.buffer; owners.buffer = nullptr;

    }
    realized_ = true;
    return true;
}

} // namespace starling::ggml
