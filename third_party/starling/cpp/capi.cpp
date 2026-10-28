// capi.cpp — shared C API shell for libstarling_ggml.
//
// Implements the C API declared in cpp/include/starling_ggml.h. Dispatches
// load/free/transcribe through the central model table
// (lib/model_registry.hpp); adding a model is one registry row, not another
// if-chain here. Every entry is exception-fenced: a C++ exception never
// crosses the boundary; it's caught and stored in last_error.

#include "starling_ggml.h"

#include "lib/model_registry.hpp"
#include "lib/granite_job_internal.hpp"
#include "runtime/backend.hpp"
#include "runtime/graph.hpp"  // global_backend, shutdown_backend, shutting_down
#include "runtime/imatrix.hpp"  // ImatrixCollector (imatrix_flush_pub)

#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <atomic>
#include <mutex>
#include <string>
#include <memory>
#include <optional>
#include <type_traits>
#include <stdexcept>
#include <unordered_set>
#include <limits>
#if defined(__unix__) || defined(__APPLE__)
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

static_assert(sizeof(starling_ggml_load_report) == 16, "load report wire layout must remain 16 bytes");

// The internal model table (cpp/lib/model_registry.hpp), aliased for brevity.
namespace lib = starling::ggml::lib;

// The opaque context: a tagged variant over the per-model handles. Only one
// model kind is active per context (selected at load). Defined at global scope
// so it IS the header's incomplete `struct starling_ggml_ctx` made complete.
struct starling_ggml_ctx {
    // Backend must outlive all model weights and replay-cache references.
    std::unique_ptr<starling::ggml::Backend> owned_backend;
    std::string backend_name;
    int backend_is_gpu = -1;
    bool isolated = false;
    bool quarantined = false;
    bool initialization_unconfirmed = false;
    starling_ggml_model kind = (starling_ggml_model)0;
    void* model = nullptr;   // the per-model handle (ParakeetCtx*, ...)
    std::string last_error;
};

namespace {

// ---- parakeet debug-passthrough entry points (capi_parakeet.cpp) ----
// Only the mel/encode/decode/decode_ids introspection entries used by the
// _pub wrappers below; every model's load/free/decode trio (parakeet's
// included) is declared once, next to the registry table in
// lib/model_registry.cpp.
extern "C" {
void * starling_ggml_parakeet_load_retained(const char*, const char**, void**);
void * starling_ggml_parakeet_load_fd_retained(int, const char**, void**);
float * starling_ggml_parakeet_mel(void * handle, const float * pcm, int64_t n,
                                   int * out_T, const char ** err_out);
float * starling_ggml_parakeet_encode(void * handle, const float * pcm, int64_t n,
                                      int * out_T, const char ** err_out);
char * starling_ggml_parakeet_decode(void * handle, const float * pcm, int64_t n,
                                     const char ** err_out);
int64_t * starling_ggml_parakeet_decode_ids(void * handle, const float * pcm, int64_t n,
                                            int64_t * out_n, const char ** err_out);
}

thread_local char g_last_error[2048] = {};
// Under runtime_mutex; also retains failed-to-drain contexts (not reusable).
std::unordered_set<starling_ggml_ctx*> g_owned_contexts;

void require_no_quarantined_context() {
    for (auto* ctx : g_owned_contexts)
        if (ctx->quarantined || ctx->initialization_unconfirmed ||
            (ctx->owned_backend && !ctx->owned_backend->healthy()))
            throw std::runtime_error("unresolved quarantined context blocks inference/load");
}

void release_context_model(starling_ggml_ctx* ctx) {
    starling::ggml::ContextBackendScope scope(ctx->owned_backend.get());
    if (!starling::ggml::shutting_down()) starling::ggml::synchronize_current_backend();
    if (ctx->model) {
        if (const lib::ModelDescriptor* d = lib::find_model(ctx->kind))
            d->free_fn(ctx->model);
        ctx->model = nullptr;
    }
    if (ctx->owned_backend) ctx->owned_backend->require_healthy();
}

void release_isolated_context(starling_ggml_ctx* ctx) {
    if (!ctx->isolated || ctx->initialization_unconfirmed)
        throw std::runtime_error("isolated backend initialization/release is unconfirmed");
    {
        starling::ggml::ContextBackendScope scope(ctx->owned_backend.get());
        release_context_model(ctx);
        if (ctx->owned_backend) ctx->owned_backend->release_checked();
    }
    g_owned_contexts.erase(ctx);
    delete ctx;
}

// Called ONLY by shutdown_backend under runtime_mutex, before it touches any
// loader or terminal flag. It must never call shutdown_backend/CAPI shutdown.
void drain_owned_context_backends() {
    // Establish every private device's drain before releasing any model cache.
    // A throw retains registry/ownership and leaves the runtime non-terminal.
    for (auto* ctx : g_owned_contexts) {
        if (ctx->initialization_unconfirmed)
            throw std::runtime_error("unconfirmed isolated initialization remains quarantined");
        if (ctx->owned_backend) ctx->owned_backend->synchronize();
    }
    for (auto* ctx : g_owned_contexts) release_context_model(ctx);
    for (auto* ctx : g_owned_contexts)
        if (ctx->owned_backend) ctx->owned_backend->release_checked();
    for (auto* ctx : g_owned_contexts) ctx->owned_backend.reset();
    g_owned_contexts.clear();
}

void set_global_error(const std::string& msg) {
    std::snprintf(g_last_error, sizeof(g_last_error), "%s", msg.c_str());
}

template<class F>
auto api_call(starling_ggml_ctx* ctx, F&& fn, bool allow_quarantine = false) -> decltype(fn()) {
    using Result = decltype(fn());
    try {
        std::lock_guard<std::recursive_mutex> lock(starling::ggml::runtime_mutex());
        try {
            // A null context leaves an enclosing load/free scope intact.
            std::optional<starling::ggml::ContextBackendScope> backend_scope;
            if (ctx) {
                backend_scope.emplace(ctx->owned_backend.get());
                if (!allow_quarantine) {
                    require_no_quarantined_context();
                    if (ctx->quarantined) throw std::runtime_error("context is quarantined, not usable");
                    if (ctx->owned_backend) ctx->owned_backend->require_healthy();
                }
            }
            return fn();
        } catch (const std::exception& e) {
            std::snprintf(g_last_error, sizeof(g_last_error), "%s", e.what());
        } catch (...) {
            std::snprintf(g_last_error, sizeof(g_last_error), "%s", "unknown native exception");
        }
        if (ctx) {
            // Keep the context protected through error reporting, including OOM.
            try { ctx->last_error = g_last_error; } catch (...) {}
        }
    } catch (...) {
        std::snprintf(g_last_error, sizeof(g_last_error), "%s", "native runtime lock failed");
    }
    if constexpr (!std::is_void_v<Result>) return Result{};
}

void require_ggml_context_query(starling_ggml_ctx* ctx) {
    if (!ctx || !ctx->model) throw std::invalid_argument("no loaded context");
#if defined(STARLING_HAVE_FAST)
    // Legacy load can choose a non-ggml fast engine at runtime. Do not borrow
    // another model's process-global ggml identity/drain as evidence for it.
    if (!ctx->owned_backend)
        throw std::runtime_error("isolated backend queries do not support legacy fast-engine builds");
#endif
}

void require_running() {
    if (starling::ggml::shutting_down())
        throw std::runtime_error("Starling backend has been shut down");
}

const char * backend_name_for_build() {
#if defined(GGML_USE_CUDA)
    return "cuda";
#elif defined(GGML_USE_METAL)
    return "metal";
#elif defined(GGML_USE_VULKAN)
    return "vulkan";
#elif defined(GGML_USE_HIP)
    return "hip";
#else
    return "cpu";
#endif
}

enum class CheckedSourceKind { Path, Descriptor };
struct CheckedSource {
    CheckedSourceKind kind;
    const char* path;
    int fd;
};

// Nonallocating preflight only. ModelLoader owns/validates its duplicate again;
// caller must keep the original fd open/unreplaced throughout the whole call.
// This does not claim content immutability or an independent seek cursor.
bool valid_checked_fd(int fd) noexcept {
#if defined(__unix__) || defined(__APPLE__)
    if (fd < 0) return false;
    const int flags = fcntl(fd, F_GETFL);
    if (flags < 0 || (flags & O_ACCMODE) == O_WRONLY) return false;
    struct stat st = {};
    return fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_size > 0 &&
        static_cast<uintmax_t>(st.st_size) <= std::numeric_limits<size_t>::max() &&
        lseek(fd, 0, SEEK_CUR) >= 0;
#else
    (void)fd; return false;
#endif
}

starling_ggml_load_result load_checked_source(starling_ggml_model model,
    CheckedSource source, starling_ggml_device_policy policy, starling_ggml_ctx** out,
    starling_ggml_load_report* report = nullptr) {
    // Generic exception fencing defaults to enum0 UNCONFIRMED, never READY.
    return api_call(nullptr, [&]() -> starling_ggml_load_result {
        if (!out || *out || starling::ggml::shutting_down() ||
            model != STARLING_GGML_PARAKEET_TDT ||
            (policy != STARLING_GGML_DEVICE_AUTOMATIC && policy != STARLING_GGML_DEVICE_CPU)) {
            set_global_error("checked load rejected before a device attempt");
            return STARLING_GGML_LOAD_REJECTED_NO_ATTEMPT;
        }
        if ((source.kind == CheckedSourceKind::Path && (!source.path || !*source.path)) ||
            (source.kind == CheckedSourceKind::Descriptor && !valid_checked_fd(source.fd))) {
            set_global_error("checked load rejected before a device attempt");
            return STARLING_GGML_LOAD_REJECTED_NO_ATTEMPT;
        }
#if defined(STARLING_HAVE_FAST)
        set_global_error("isolated backend API does not support fast-engine builds");
        return STARLING_GGML_LOAD_REJECTED_NO_ATTEMPT;
#else
        for (auto* previous : g_owned_contexts) {
            if (previous->quarantined || previous->initialization_unconfirmed ||
                (previous->owned_backend && !previous->owned_backend->healthy())) {
                set_global_error("unreleased quarantined context blocks a new load");
                return STARLING_GGML_LOAD_REJECTED_NO_ATTEMPT;
            }
        }
        auto holder = std::make_unique<starling_ggml_ctx>();
        holder->kind = model;
        holder->isolated = true;
        holder->quarantined = true; // only a complete healthy load becomes usable
        starling::ggml::set_context_backend_shutdown_hook(drain_owned_context_backends);
        g_owned_contexts.insert(holder.get());
        starling_ggml_ctx* ctx = holder.release();
        *out = ctx; // publish retained identity before any device/string allocation
        char primary_error[2048] = {};
        try {
            ctx->initialization_unconfirmed = true;
            ctx->owned_backend = std::make_unique<starling::ggml::Backend>(8,
                policy == STARLING_GGML_DEVICE_CPU ? starling::ggml::Backend::DevicePolicy::CPU
                                                 : starling::ggml::Backend::DevicePolicy::Automatic);
            ctx->initialization_unconfirmed = false;
            if (report) {
                // Actual initialized backend, not requested/candidate/compiled
                // capability. Publish before any later allocating metadata so
                // the typed fact survives both released and quarantined failure.
                const starling_ggml_load_report actual = {
                    STARLING_GGML_LOAD_REPORT_VERSION,
                    STARLING_GGML_LOAD_REPORT_BACKEND_KNOWN,
                    ctx->owned_backend->is_gpu() ? STARLING_GGML_BACKEND_ACCELERATOR : STARLING_GGML_BACKEND_CPU,
                    0u,
                };
                std::memcpy(report, &actual, sizeof actual);
            }
            starling::ggml::register_backend_teardown();
            ctx->backend_name = ctx->owned_backend->device_name();
            ctx->backend_is_gpu = ctx->owned_backend->is_gpu() ? 1 : 0;
            starling::ggml::ContextBackendScope scope(ctx->owned_backend.get());
            const char* err = nullptr;
            void* retained_model = nullptr;
            ctx->model = source.kind == CheckedSourceKind::Descriptor
                ? starling_ggml_parakeet_load_fd_retained(source.fd, &err, &retained_model)
                : starling_ggml_parakeet_load_retained(source.path, &err, &retained_model);
            if (!ctx->model) {
                ctx->model = retained_model; // partial model is cleanup-only
                std::snprintf(primary_error, sizeof(primary_error), "%s", err ? err : "isolated model load failed");
            } else {
                ctx->owned_backend->synchronize();
                ctx->owned_backend->require_healthy();
                ctx->quarantined = false;
                return STARLING_GGML_LOAD_READY;
            }
        } catch (const std::exception& error) {
            std::snprintf(primary_error, sizeof(primary_error), "%s", error.what());
        } catch (...) {
            std::snprintf(primary_error, sizeof(primary_error), "%s", "isolated load native failure");
        }
        // A failed constructor can retain native resources without a complete
        // Backend object. Never turn that uncertainty into a retryable failure.
        if (ctx->initialization_unconfirmed) {
            try { starling::ggml::register_backend_teardown(); } catch (...) {}
        }
        std::snprintf(g_last_error, sizeof(g_last_error), "%s", primary_error);
        try { ctx->last_error = primary_error; } catch (...) {}
        try {
            release_isolated_context(ctx);
            *out = nullptr;
            return STARLING_GGML_LOAD_FAILED_RELEASED;
        } catch (...) {
            // Exact owner is still published/registered; neither inference nor
            // another context may overtake its unresolved resource lifetime.
            return STARLING_GGML_LOAD_FAILED_QUARANTINED;
        }
#endif
    });
}


} // namespace

namespace starling::ggml::lib {

GraniteChunkJob* create_granite_job(starling_ggml_ctx* ctx,
                                    const float* pcm, int64_t n) {
    // Job creation only snapshots immutable model policy and borrows PCM. It
    // must not wait on runtime_mutex before taking a serving queue ticket:
    // otherwise a short WS call arriving during a long chunk could miss the
    // next FIFO turn. The server owns the loaded context for the whole call;
    // as with every C API call, concurrent free(ctx) is outside that contract.
    if (!ctx || ctx->kind != STARLING_GGML_GRANITE || !ctx->model ||
        starling::ggml::shutting_down()) {
        return api_call(ctx, [&]() -> GraniteChunkJob* {
            // A shutdown in progress is reported by require_running().
            require_running();
            set_global_error("Granite job requires a loaded Granite model");
            if (ctx) ctx->last_error = g_last_error;
            return nullptr;
        });
    }
    const char* err = nullptr;
    auto* job = granite_job_create_impl(ctx->model, pcm, n, &err);
    if (!job) {
        return api_call(ctx, [&]() -> GraniteChunkJob* {
            ctx->last_error = err ? err : "Granite job creation failed";
            set_global_error(ctx->last_error);
            return nullptr;
        });
    }
    return job;
}

int step_granite_job(starling_ggml_ctx* ctx, GraniteChunkJob* job,
                     std::string* final_text) {
    auto status = api_call(ctx, [&]() -> std::optional<int> {
        require_running();
        if (!ctx || ctx->kind != STARLING_GGML_GRANITE || !ctx->model || !job) {
            set_global_error("invalid Granite job step");
            if (ctx) ctx->last_error = g_last_error;
            return -1;
        }
        const char* err = nullptr;
        const int result = granite_job_step_impl(job, final_text, &err);
        if (result < 0) {
            ctx->last_error = err ? err : "Granite chunk failed";
            set_global_error(ctx->last_error);
        }
        return result;
    });
    return status.value_or(-1);
}

bool granite_job_last_chunk(const GraniteChunkJob* job) {
    return granite_job_last_chunk_impl(job);
}

void free_granite_job(GraniteChunkJob* job) { granite_job_free_impl(job); }

} // namespace starling::ggml::lib

extern "C" {

// --------------------------------------------------------------------------- //
// ABI / build introspection
// --------------------------------------------------------------------------- //
int starling_ggml_abi_version(void) { return STARLING_GGML_ABI_VERSION; }
const char * starling_ggml_backend_name(void) {
    return api_call(nullptr, [&]() -> const char * {
        // Report the runtime-selected device (STARLING_GGML_DEVICE / auto-pick)
        // once a model load has created the global Backend; before that, the
        // compile-time backend family. The device name is latched into a static
        // so the returned pointer outlives the Backend (the header promises a
        // static string, and a caller may hold it across shutdown).
        // try_global_backend_device_name() returns the name BY VALUE (copied
        // under the Backend lock), so a concurrent shutdown cannot free the
        // string mid-copy.
        if (auto dev = starling::ggml::try_global_backend_device_name()) {
            static const std::string runtime_name = *dev;
            return runtime_name.c_str();
        }
        return backend_name_for_build();
    });
}

// --------------------------------------------------------------------------- //
// Lifecycle
// --------------------------------------------------------------------------- //
starling_ggml_ctx * starling_ggml_load(starling_ggml_model model,
                                       const char * gguf_path) {
    return api_call(nullptr, [&]() -> starling_ggml_ctx * {
        require_running();
        require_no_quarantined_context();
        const lib::ModelDescriptor* d = lib::find_model(model);
        if (!d) {
            set_global_error("starling_ggml_load: unsupported model kind");
            return nullptr;
        }
        auto ctx = std::make_unique<starling_ggml_ctx>();
        const char* err = nullptr;
        void* handle = d->load_fn(gguf_path, &err);
        if (!handle) {
            set_global_error(err ? err : "starling_ggml_load: failed (no message)");
            return nullptr;
        }
        ctx->kind = model;
        ctx->model = handle;
        // No allocation after acquiring the model: legacy introspection remains
        // available via the process API; new query reads actual backend on demand.
        return ctx.release();
    });
}

starling_ggml_load_result starling_ggml_load_checked(starling_ggml_model model,
    const char* gguf_path, starling_ggml_device_policy policy, starling_ggml_ctx** out) {
    return load_checked_source(model, {CheckedSourceKind::Path, gguf_path, -1}, policy, out);
}

starling_ggml_load_result starling_ggml_load_fd_checked(starling_ggml_model model,
    int fd, starling_ggml_device_policy policy, starling_ggml_ctx** out) {
    return load_checked_source(model, {CheckedSourceKind::Descriptor, nullptr, fd}, policy, out);
}

starling_ggml_load_result starling_ggml_load_fd_checked_report(starling_ggml_model model,
    int fd, starling_ggml_device_policy policy, starling_ggml_ctx** out,
    starling_ggml_load_report* report, size_t report_size) {
    // Do not read even report.version when the caller supplies a short buffer.
    // These bounded C operations allocate nothing and occur before entering the
    // shared lifecycle/owner registry; malformed metadata cannot start a device.
    uint32_t requested_version = 0;
    if (report && report_size == sizeof(starling_ggml_load_report))
        std::memcpy(&requested_version, report, sizeof requested_version);
    if (!report || report_size != sizeof(starling_ggml_load_report) ||
        requested_version != STARLING_GGML_LOAD_REPORT_VERSION) {
        if (report) {
            const size_t clear_size = report_size < sizeof(starling_ggml_load_report)
                ? report_size : sizeof(starling_ggml_load_report);
            std::memset(report, 0, clear_size);
        }
        std::snprintf(g_last_error, sizeof(g_last_error), "%s", "checked load report requires exact size/version1");
        return STARLING_GGML_LOAD_REJECTED_NO_ATTEMPT;
    }
    const starling_ggml_load_report empty = {
        STARLING_GGML_LOAD_REPORT_VERSION, 0u, STARLING_GGML_BACKEND_UNKNOWN, 0u,
    };
    std::memcpy(report, &empty, sizeof empty);
    return load_checked_source(model, {CheckedSourceKind::Descriptor, nullptr, fd}, policy, out, report);
}

starling_ggml_ctx * starling_ggml_load_with_device(starling_ggml_model model,
    const char * gguf_path, starling_ggml_device_policy policy) {
    starling_ggml_ctx* ctx = nullptr;
    return starling_ggml_load_checked(model, gguf_path, policy, &ctx) == STARLING_GGML_LOAD_READY
        ? ctx : nullptr; // old API deliberately cannot promise cleanup from NULL
}

int starling_ggml_release_checked(starling_ggml_ctx** owner) {
    return api_call(nullptr, [&]() -> int {
        if (!owner) throw std::invalid_argument("context owner slot required");
        if (!*owner) return 1;
        auto* ctx = *owner;
        if (!ctx->isolated) throw std::invalid_argument("checked release requires isolated context");
        ctx->quarantined = true; // never revive after a failed retirement
        release_isolated_context(ctx);
        *owner = nullptr;
        return 1;
    });
}

const char* starling_ggml_context_backend_name(starling_ggml_ctx* ctx) {
    return api_call(ctx, [&]() -> const char* {
        require_running();
        require_ggml_context_query(ctx);
        if (ctx->backend_name.empty()) {
            if (!starling::ggml::try_global_backend_device_name())
                throw std::runtime_error("context has no ggml backend (possibly a fast-engine context)");
            ctx->backend_name = starling::ggml::global_backend().device_name();
            ctx->backend_is_gpu = starling::ggml::global_backend().is_gpu() ? 1 : 0;
        }
        return ctx->backend_name.c_str();
    });
}
int starling_ggml_context_is_gpu(starling_ggml_ctx* ctx) {
    auto result = api_call(ctx, [&]() -> std::optional<int> {
        require_running();
        require_ggml_context_query(ctx);
        if (ctx->owned_backend) return ctx->backend_is_gpu;
        if (!starling::ggml::try_global_backend_device_name())
            throw std::runtime_error("context has no ggml backend");
        return starling::ggml::global_backend().is_gpu() ? 1 : 0;
    });
    return result.value_or(-1);
}
int starling_ggml_context_synchronize(starling_ggml_ctx* ctx) {
    return api_call(ctx, [&]() -> int {
        require_running();
        if (!ctx || ctx->initialization_unconfirmed) throw std::invalid_argument("no confirmed backend");
        if (!ctx->isolated) require_ggml_context_query(ctx);
        if (ctx->isolated && !ctx->owned_backend) throw std::invalid_argument("isolated backend released");
        starling::ggml::synchronize_current_backend();
        return 1; // does not clear quarantine or certify healthy inference
    }, true);
}

void starling_ggml_free(starling_ggml_ctx * ctx) {
    return api_call(nullptr, [&]() -> void {
        if (!ctx) return;
        if (ctx->isolated) {
            ctx->quarantined = true;
            release_isolated_context(ctx);
            return;
        }
        release_context_model(ctx); // scope ends before owned backend deletion
        g_owned_contexts.erase(ctx);
        delete ctx;
    });
}

void starling_ggml_shutdown(void) {
    return api_call(nullptr, [&]() -> void {
        // Direct C++, legacy atexit and C API routes share the same hook/order.
        starling::ggml::shutdown_backend();
    });
}

// ---- cooperative stop (#325: let a SIGTERM'd process tear down its GPU) ----
namespace {
// Set from signal handlers: only async-signal-safe while lock-free.
static_assert(std::atomic<bool>::is_always_lock_free, "cooperative stop flag must be lock-free");
std::atomic<bool> g_coop_stop{false};
}

void starling_ggml_request_stop(void) { g_coop_stop.store(true, std::memory_order_relaxed); }
int  starling_ggml_stop_requested(void) { return g_coop_stop.load(std::memory_order_relaxed) ? 1 : 0; }
void starling_ggml_clear_stop(void) { g_coop_stop.store(false, std::memory_order_relaxed); }

const char * starling_ggml_last_error(starling_ggml_ctx * ctx) {
    return api_call(ctx, [&]() -> const char * {
        if (ctx) return ctx->last_error.c_str();
        return g_last_error;
    }, true);
}

// --------------------------------------------------------------------------- //
// Inference
// --------------------------------------------------------------------------- //
char * starling_ggml_transcribe_pcm(starling_ggml_ctx * ctx,
                                    const float * samples, int64_t n,
                                    int sample_rate) {
    return api_call(ctx, [&]() -> char * {
        require_running();
        if (!ctx || !ctx->model) {
            set_global_error("starling_ggml_transcribe_pcm: null context");
            return nullptr;
        }
        const lib::ModelDescriptor* d = lib::find_model(ctx->kind);
        if (!d) {
            ctx->last_error = "starling_ggml_transcribe_pcm: unsupported model kind";
            set_global_error(ctx->last_error);
            return nullptr;
        }
        // Every engine consumes 16 kHz mono; resample upstream if needed. We only
        // reject (not resample) on a mismatch, and tolerate sample_rate=0 so a
        // 16k caller that doesn't know the rate still works.
        if (sample_rate != 0 && sample_rate != 16000) {
            char msg[128];
            std::snprintf(msg, sizeof(msg), d->rate_error_fmt, sample_rate);
            if (d->rate_error_in_ctx) ctx->last_error = msg;
            set_global_error(msg);
            return nullptr;
        }
        const char* err = nullptr;
        char* r = d->decode_fn(ctx->model, samples, n, &err);
        if (!r) {
            ctx->last_error = err ? err : d->decode_fallback;
            set_global_error(ctx->last_error);
        }
        return r;
    });
}

char * starling_ggml_transcribe_pcm_words(starling_ggml_ctx * ctx,
                                          const float * samples, int64_t n,
                                          int sample_rate,
                                          starling_ggml_word ** words,
                                          int64_t * n_words) {
    if (words) *words = nullptr;
    if (n_words) *n_words = -1;
    return api_call(ctx, [&]() -> char * {
        require_running();
        if (!ctx || !ctx->model) {
            set_global_error("starling_ggml_transcribe_pcm_words: null context");
            return nullptr;
        }
        const lib::ModelDescriptor* d = lib::find_model(ctx->kind);
        if (!d) {
            ctx->last_error = "starling_ggml_transcribe_pcm_words: unsupported model kind";
            set_global_error(ctx->last_error);
            return nullptr;
        }
        if (!d->decode_words_fn || !words || !n_words) {
            // No word timestamps from this engine (or none asked for): the
            // plain transcript, *n_words = -1.
            return starling_ggml_transcribe_pcm(ctx, samples, n, sample_rate);
        }
        // The same 16 kHz guard as starling_ggml_transcribe_pcm.
        if (sample_rate != 0 && sample_rate != 16000) {
            char msg[128];
            std::snprintf(msg, sizeof(msg), d->rate_error_fmt, sample_rate);
            if (d->rate_error_in_ctx) ctx->last_error = msg;
            set_global_error(msg);
            return nullptr;
        }
        const char* err = nullptr;
        char* r = d->decode_words_fn(ctx->model, samples, n, words, n_words, &err);
        if (!r) {
            ctx->last_error = err ? err : d->decode_fallback;
            set_global_error(ctx->last_error);
        }
        return r;
    });
}

void starling_ggml_free_string(char * s) {
    if (s) std::free(s);
}

void starling_ggml_free_words(starling_ggml_word * words) {
    if (words) std::free(words);
}

char * starling_ggml_normalize_text(starling_ggml_ctx * ctx,
                                    const char * transcript,
                                    const char * styling,
                                    const char * structure,
                                    const char * context) {
    return api_call(ctx, [&]() -> char * {
        require_running();
        if (!ctx || !ctx->model) {
            set_global_error("starling_ggml_normalize_text: null context");
            return nullptr;
        }
        const lib::ModelDescriptor* d = lib::find_model(ctx->kind);
        if (!d) {
            ctx->last_error = "starling_ggml_normalize_text: unsupported model kind";
            set_global_error(ctx->last_error);
            return nullptr;
        }
        if (!d->normalize_fn) {
            ctx->last_error = std::string("starling_ggml_normalize_text: model '")
                              + d->slug + "' has no text path";
            set_global_error(ctx->last_error);
            return nullptr;
        }
        const char* err = nullptr;
        char* r = d->normalize_fn(ctx->model, transcript, styling, structure, context, &err);
        if (!r) {
            ctx->last_error = err ? err : "S1 normalize failed";
            set_global_error(ctx->last_error);
        }
        return r;
    });
}

// --------------------------------------------------------------------------- //
// Internal mel-test passthrough (not in the public header; used by the Phase 1a
// validation script). Exposed so the Python binding can call it via ctypes.
// --------------------------------------------------------------------------- //
float * starling_ggml_parakeet_mel_pub(starling_ggml_ctx * ctx,
                                       const float * pcm, int64_t n,
                                       int * out_T) {
    return api_call(ctx, [&]() -> float * {
        require_running();
        if (!ctx || ctx->kind != STARLING_GGML_PARAKEET_TDT) {
            set_global_error("starling_ggml_parakeet_mel_pub: not a parakeet context");
            return nullptr;
        }
        const char* err = nullptr;
        float* r = starling_ggml_parakeet_mel(ctx->model, pcm, n, out_T, &err);
        if (!r) set_global_error(err ? err : "mel failed");
        return r;
    });
}

// Internal encoder-test passthrough (Phase 1b validation). Runs mel + encoder
// + joint.enc projection and returns the [640, T'] feat-major f32 buffer. The
// caller frees the buffer with starling_ggml_free.
float * starling_ggml_parakeet_encode_pub(starling_ggml_ctx * ctx,
                                          const float * pcm, int64_t n,
                                          int * out_T) {
    return api_call(ctx, [&]() -> float * {
        require_running();
        if (!ctx || ctx->kind != STARLING_GGML_PARAKEET_TDT) {
            set_global_error("starling_ggml_parakeet_encode_pub: not a parakeet context");
            return nullptr;
        }
        const char* err = nullptr;
        float* r = starling_ggml_parakeet_encode(ctx->model, pcm, n, out_T, &err);
        if (!r) set_global_error(err ? err : "encode failed");
        return r;
    });
}

// Internal decode passthrough (Phase 1c validation + the transcribe path). Runs
// the FULL pipeline (mel + encoder + decode + detokenize) and returns a
// malloc'd UTF-8 text string the caller frees with starling_ggml_free_string.
char * starling_ggml_parakeet_decode_pub(starling_ggml_ctx * ctx,
                                         const float * pcm, int64_t n) {
    return api_call(ctx, [&]() -> char * {
        require_running();
        if (!ctx || ctx->kind != STARLING_GGML_PARAKEET_TDT) {
            set_global_error("starling_ggml_parakeet_decode_pub: not a parakeet context");
            return nullptr;
        }
        const char* err = nullptr;
        char* r = starling_ggml_parakeet_decode(ctx->model, pcm, n, &err);
        if (!r) set_global_error(err ? err : "decode failed");
        return r;
    });
}

// Internal decode-ids passthrough (Phase 1c validation). Runs the FULL pipeline
// and returns the emitted id stream (INCLUDING blanks, matching golden
// parakeet_tdt_*_ids.pt) as a malloc'd int64 array the caller frees with
// starling_ggml_free. Writes the count to *out_n.
int64_t * starling_ggml_parakeet_decode_ids_pub(starling_ggml_ctx * ctx,
                                                const float * pcm, int64_t n,
                                                int64_t * out_n) {
    return api_call(ctx, [&]() -> int64_t * {
        require_running();
        if (!ctx || ctx->kind != STARLING_GGML_PARAKEET_TDT) {
            set_global_error("starling_ggml_parakeet_decode_ids_pub: not a parakeet context");
            return nullptr;
        }
        const char* err = nullptr;
        int64_t* r = starling_ggml_parakeet_decode_ids(ctx->model, pcm, n, out_n, &err);
        if (!r) set_global_error(err ? err : "decode_ids failed");
        return r;
    });
}

// Flush the activation-importance collector (STARLING_IMATRIX mode) to disk
// NOW instead of at process exit. The ctypes drivers call this before freeing
// the model so a teardown crash can never eat the collection.
void starling_ggml_imatrix_flush_pub(void) {
    return api_call(nullptr, [&]() -> void {
        starling::ggml::ImatrixCollector::instance().flush();
    });
}

} // extern "C"
