// backend.cpp — Backend + ReplayGraph implementation.
//
// Mirrors the proven parakeet.cpp architecture (src/backend.cpp): registry-
// driven device selection, persistent gallocr reused across calls (never
// backend_sched on the fast path), zero-copy weights via clone_weight, private
// gallocr per ReplayGraph, stable cgraph uid (skips ggml-cuda's per-replay
// O(n_nodes) memcmp), async set_input + async readback + single sync.
//
// The stable uid reaches into ggml's internal cgraph->uid field (declared in
// the public ggml.h on this pinned ggml version, no internal header needed).

#include "backend.hpp"
#include "automatic_device_policy.hpp"
#include "call_abort.hpp"
#include "cpu_repack.hpp"

#include "graph.hpp"
#include "graph_snapshot.hpp"
#include "imatrix.hpp"
#include "lru_cache.hpp"
#include "model_loader.hpp"
#include "trace.hpp"

#include "ggml.h"
#include "ggml-alloc.h"
#include "ggml-backend.h"
#include "ggml-backend-impl.h"

// ggml-impl.h is ggml's internal header (under third_party/ggml/src/); it's the
// only place the cgraph `uid` field is declared. The public ggml.h leaves
// ggml_cgraph opaque. The uid lets ggml-cuda skip its O(n_nodes) per-replay
// memcmp (patch 0002) — load-bearing for ReplayGraph's steady-state perf.
#include "ggml-impl.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <filesystem>
#include <fstream>
#include <limits>
#include <map>
#include <memory>
#include <new>
#include <mutex>
#include <stdexcept>
#include <system_error>
#include <thread>
#include <vector>

#ifdef _WIN32
#include <process.h>
#ifdef STARLING_CUBLAS_DLL
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>  // LoadLibraryA: the cuBLAS probe in Backend::Backend
#endif
#else
#include <unistd.h>
#endif

namespace {
// F1 instrumentation gate. When on, ReplayGraph::compute prints a per-replay
// split of host wall between graph_compute (the async graph launch — returns to
// host almost immediately on the fast CUDA path) and readback (async D2H + the
// SINGLE trailing sync — host blocks here until the GPU finishes, so this is
// the steady-state proxy for per-replay GPU time). Also prints n_nodes + the
// stable cgraph uid so callers can tell encoder vs decode replays apart and
// confirm capture reached steady state (uid non-zero = patch-0008 fast-path).
bool replay_timing_on() {
    static const bool on = std::getenv("STARLING_REPLAY_TIMING") != nullptr;
    return on;
}
} // namespace

// ggml backend headers (registered devices).
#include "ggml-cuda.h"
#include "ggml-cpu.h"
#ifdef GGML_USE_METAL
#include "ggml-metal.h"
#endif
#ifdef GGML_USE_VULKAN
#include "ggml-vulkan.h"
#endif
#ifdef GGML_USE_HIP
// HIP registers through the CUDA-compatible path; ggml-cuda.h covers it.
#endif

namespace starling::ggml {
namespace {

// --------------------------------------------------------------------------- //
// Thread-local pointer to the Backend driving the current compute (set by
// Backend::compute / ReplayGraph ctor, read by add_graph_input /
// capture_graph_output so model build lambdas don't need a Backend ref).
// --------------------------------------------------------------------------- //
thread_local Backend* t_active_backend = nullptr;
// Inputs/captures registered during the current build; drained by the driver.
struct PendingInput {
    ggml_tensor* t;
    const void* host; // original identity, never the async transfer owner
    size_t nbytes;
    std::shared_ptr<std::vector<uint8_t>> bytes;
};
struct PendingCapture{ ggml_tensor* t; std::vector<float>* dst; };
thread_local std::vector<PendingInput>*   t_pending_inputs = nullptr;
thread_local std::vector<PendingCapture>* t_pending_captures = nullptr;
// Side-effect expansion roots registered during the current build (Wave D
// decode-state write-back cpys): nodes that must execute but are not read back.
thread_local std::vector<ggml_tensor*>*    t_pending_roots = nullptr;
// [starling pos-cache] tensors marked persistent during the current build.
thread_local std::vector<ggml_tensor*>*   t_pending_persistent = nullptr;

// Restore registration state on normal return, exceptions and nested builds.
struct BuildScope {
    Backend* previous_backend = t_active_backend;
    std::vector<PendingInput>* previous_inputs = t_pending_inputs;
    std::vector<PendingCapture>* previous_captures = t_pending_captures;
    std::vector<ggml_tensor*>* previous_roots = t_pending_roots;
    std::vector<ggml_tensor*>* previous_persistent = t_pending_persistent;
    BuildScope(Backend& backend, std::vector<PendingInput>& inputs,
               std::vector<PendingCapture>& captures, std::vector<ggml_tensor*>& roots,
               std::vector<ggml_tensor*>* persistent_marks = nullptr) {
        t_active_backend = &backend;
        t_pending_inputs = &inputs;
        t_pending_captures = &captures;
        t_pending_roots = &roots;
        t_pending_persistent = persistent_marks;
    }
    ~BuildScope() {
        t_active_backend = previous_backend;
        t_pending_inputs = previous_inputs;
        t_pending_captures = previous_captures;
        t_pending_roots = previous_roots;
        t_pending_persistent = previous_persistent;
    }
};

constexpr size_t kGraphSize = 32768;  // max nodes in one ggml_cgraph (bumped for
                                          // the moss K-step multistep decode graph)

// Device-class fallback for the per-shape replay-graph LRU caches (see
// runtime/lru_cache.hpp). kDefaultReplayCacheSize assumes discrete-GPU VRAM:
// on integrated GPUs — and any card whose reported memory is small — the
// weights and every cached per-shape encoder graph share one window, and a
// 4 GB-class model plus 16 cached graphs exhausts an 8 GB GTT mid-stream
// (observed as radv "not enough memory for command submission" -> device
// lost). Written once per Backend construction from the chosen device's
// properties; reads are lock-free.
std::atomic<size_t> g_device_replay_cache_default{kDefaultReplayCacheSize};
constexpr size_t kSharedWindowTotalBytes = size_t(12) << 30;  // 12 GiB

// The ggml_backend_sched_eval_callback trampoline feeding the ImatrixCollector
// (see imatrix.hpp). NOTE: returning true does NOT force the node onto the
// CPU backend — the sched consults the callback only for batching/
// observation; placement is decided without it. Host-side activation reads
// are sound because the collect tool pins STARLING_GGML_DEVICE=cpu.
bool imatrix_eval_cb(ggml_tensor* t, bool ask, void* /*user_data*/) {
    return ImatrixCollector::instance().observe(t, ask);
}

} // namespace

static void maybe_export_graph(ggml_cgraph* graph, ggml_tensor* output,
                               const char* device,
                               const std::vector<PendingCapture>& pending_captures,
                               const std::vector<ggml_tensor*>& side_effect_roots) {
    const char* directory = std::getenv("STARLING_GRAPH_EXPORT_DIR");
    if (!directory) return;
    if (!*directory) throw std::runtime_error("STARLING_GRAPH_EXPORT_DIR is empty");
    // One-shot decoding can rebuild a graph for every token. Keep this
    // diagnostic export bounded even when left enabled for a long session.
    static constexpr unsigned long long kMaxSnapshots = 256;
    static std::atomic<unsigned long long> sequence{0};
    const auto number = sequence.fetch_add(1, std::memory_order_relaxed);
    if (number >= kMaxSnapshots) {
        if (number == kMaxSnapshots)
            std::fprintf(stderr, "STARLING_GRAPH_EXPORT_DIR: reached %llu snapshots; export stopped\n",
                         kMaxSnapshots);
        return;
    }
    std::vector<ggml_tensor*> captures;
    captures.reserve(pending_captures.size());
    for (const auto& capture : pending_captures) captures.push_back(capture.t);
    const auto stamp = std::chrono::system_clock::now().time_since_epoch().count();
#ifdef _WIN32
    const auto pid = _getpid();
#else
    const auto pid = getpid();
#endif
    const std::filesystem::path path =
        std::filesystem::path(directory) /
        ("graph-" + std::to_string(stamp) + "-" +
         std::to_string(pid) + "-" + std::to_string(number) + ".json");
    std::filesystem::create_directories(path.parent_path());
    // Serialize first so a serializer failure cannot leave a .tmp behind.
    const std::string json =
        graph_snapshot_json(graph, output, device, captures, side_effect_roots);
    const std::filesystem::path incomplete = path.string() + ".tmp";
    std::ofstream file(incomplete, std::ios::binary | std::ios::trunc);
    if (!file) throw std::runtime_error("cannot open graph snapshot: " + incomplete.string());
    file << json;
    file.close();
    if (!file) {
        std::error_code ignored;
        std::filesystem::remove(incomplete, ignored);
        throw std::runtime_error("cannot write graph snapshot: " + incomplete.string());
    }
    try {
        std::filesystem::rename(incomplete, path);
    } catch (...) {
        std::error_code ignored;
        std::filesystem::remove(incomplete, ignored);
        throw;
    }
}

// --------------------------------------------------------------------------- //
// Backend::Impl
// --------------------------------------------------------------------------- //
struct Backend::Impl {
    ggml_backend_t backend = nullptr;       // primary (GPU or CPU)
    ggml_backend_t cpu_backend = nullptr;   // CPU fallback for GPU offload
    ggml_gallocr_t galloc = nullptr;        // persistent, reused across one-shot computes
    ggml_backend_sched_t sched = nullptr;   // lazy; GPU op offload + imatrix collection
    bool use_sched = false;                 // true iff a GPU backend is active
    // Metadata arena for one-shot build contexts (~13 MiB at kGraphSize).
    // A fresh ggml_init per graph maps and unmaps it every decode step and
    // faults the touched pages back in; the arena is reused instead. A
    // nested compute (a build lambda that runs another graph) gets its own.
    std::unique_ptr<uint8_t[]> ctx_arena;
    bool ctx_arena_busy = false;
    // Persistent worker pool for the CPU primary backend (see
    // attach_threadpool). Without one, ggml starts and joins a fresh pool of
    // threads for every graph when built without OpenMP.
    ggml_threadpool_t threadpool = nullptr;
    int threadpool_n = 0;

    // Only called after a proven drain, except constructor rollback (which
    // has not submitted any graph work). Each pointer is cleared only AFTER
    // its native free returned. A throw leaves uncertain ownership retained.
    void release_checked() {
        if (sched) { ggml_backend_sched_free(sched); sched = nullptr; }
        if (galloc) { ggml_gallocr_free(galloc); galloc = nullptr; }
        if (use_sched && cpu_backend) { ggml_backend_free(cpu_backend); cpu_backend = nullptr; }
        if (backend) {
            const bool shared_cpu = cpu_backend == backend;
            ggml_backend_free(backend); backend = nullptr;
            if (shared_cpu) cpu_backend = nullptr;
        }
        if (threadpool) { ggml_threadpool_free(threadpool); threadpool = nullptr; }
        ctx_arena.reset();
    }
    ~Impl() {
        if (shutting_down()) { (void)ctx_arena.release(); return; }
        try { release_checked(); }
        catch (...) {
            // No-throw constructor rollback; preserve metadata if native free
            // has an ambiguous outcome. Raw native handles are not RAII-freed.
            (void)ctx_arena.release();
            std::fprintf(stderr, "[backend] constructor resource release failed; retained uncertain resources\n");
        }
    }

    // (Re)create the CPU worker pool for n threads and attach it. The pool's
    // threads park after `poll` rounds of spinning without work (ggml's
    // hybrid poll; 0 = park right away); STARLING_GGML_POLL overrides ggml's
    // default of 50. STARLING_GGML_CPUMASK (hex, e.g. 0xfc) confines the
    // worker threads to those CPUs, e.g. a phone's performance cores. With
    // OpenMP builds the pool only carries these settings; libomp owns the
    // threads.
    void attach_threadpool(int n) {
        if (!backend || !ggml_backend_is_cpu(backend)) return;
        ggml_threadpool_params tpp = ggml_threadpool_params_default(n);
        if (const char* v = std::getenv("STARLING_GGML_POLL"); v && *v) {
            char* end = nullptr;
            const long poll = std::strtol(v, &end, 10);
            if (*end == '\0' && poll >= 0 && poll <= 100) tpp.poll = (uint32_t)poll;
            else std::fprintf(stderr, "[backend] ignoring STARLING_GGML_POLL='%s' (want 0..100)\n", v);
        }
        if (const char* v = std::getenv("STARLING_GGML_CPUMASK"); v && *v) {
            char* end = nullptr;
            const unsigned long long mask = std::strtoull(v, &end, 16);
            if (*end == '\0' && mask != 0) {
                for (int i = 0; i < 64 && i < GGML_MAX_N_THREADS; ++i)
                    tpp.cpumask[i] = (mask >> i) & 1u;
                tpp.strict_cpu = false;  // every thread may use the whole mask
            } else {
                std::fprintf(stderr, "[backend] ignoring STARLING_GGML_CPUMASK='%s' (want a nonzero hex mask)\n", v);
            }
        }
        ggml_threadpool_t fresh = ggml_threadpool_new(&tpp);
        if (!fresh) return;  // keep ggml's per-graph pool
        ggml_backend_cpu_set_threadpool(backend, fresh);
        if (threadpool) ggml_threadpool_free(threadpool);
        threadpool = fresh;
        threadpool_n = n;
    }

    // Create the sched on first need: {GPU, CPU} when a GPU is active, the
    // lone CPU backend otherwise (a duplicated entry would confuse sched's
    // backend list). When imatrix collection is enabled the eval callback is
    // attached so every MUL_MAT with a named weight src[0] is observed.
    ggml_backend_sched_t ensure_sched() {
        if (sched) return sched;
        ggml_backend_t backends[2];
        ggml_backend_buffer_type_t bufs[2];
        int n = 0;
        backends[n] = backend;
        bufs[n] = ggml_backend_get_default_buffer_type(backend);
        ++n;
        if (use_sched) {
            backends[n] = cpu_backend;
            bufs[n] = ggml_backend_get_default_buffer_type(cpu_backend);
            ++n;
        }
        // NOTE: the 4th arg is the graph size in NODES (see ggml-backend.h),
        // not bytes — the historical code here passed tensor_overhead()*
        // kGraphSize, which only worked because this branch never executed
        // (no unsupported ops on the CUDA builds).
        sched = ggml_backend_sched_new(backends, bufs, n,
                                       (int)kGraphSize,
                                       /*parallel=*/false, /*op_offload=*/true);
        // In the vendored ggml the eval callback gates observation only, not
        // backend placement; host-side activation reads are sound because
        // collection runs pin STARLING_GGML_DEVICE=cpu.
        if (sched && ImatrixCollector::enabled()) {
            ggml_backend_sched_set_eval_callback(sched, imatrix_eval_cb, nullptr);
        }
        return sched;
    }
};

// The generic GGML GPU label can include software/virtual Vulkan devices.
// Selection reads cached registry provenance only and NEVER initializes a device.
static ggml_backend_dev_t eligible_automatic_device() noexcept {
    try {
        const size_t count = ggml_backend_dev_count();
        for (size_t i = 0; i < count; ++i) {
            try {
                auto* device = ggml_backend_dev_get(i);
                if (!device) continue;
                const auto type = ggml_backend_dev_type(device);
                if (type != GGML_BACKEND_DEVICE_TYPE_GPU && type != GGML_BACKEND_DEVICE_TYPE_IGPU) continue;
                auto* registry = ggml_backend_dev_backend_reg(device);
                if (!registry) continue;
                const char* family = ggml_backend_reg_name(registry);
                if (!family || std::strcmp(family, GGML_VK_NAME) != 0) continue;
                auto query = reinterpret_cast<ggml_backend_vk_device_info_v1_t>(
                    ggml_backend_reg_get_proc_address(registry, GGML_VK_DEVICE_INFO_V1_PROC));
                if (!query) continue;
                ggml_backend_vk_device_info_v1 facts = {};
                if (!query(device, GGML_VK_DEVICE_INFO_VERSION, &facts, sizeof(facts))) continue;
                if (automatic_device_eligibility(facts) == AutomaticDeviceEligibility::EligibleReportedVulkanGpu)
                    return device;
            } catch (...) {
                // A failed/unavailable probe is not eligibility. Inspect the
                // remaining registered candidates without initializing this one.
            }
        }
    } catch (...) { /* enumeration failure leaves direct CPU fallback */ }
    return nullptr;
}

Backend::Backend(int n_threads) : Backend(n_threads, DevicePolicy::LegacyEnvironment) {}

Backend::Backend(int n_threads, DevicePolicy policy)
    : impl_(new Impl()), n_threads_(n_threads < 1 ? 1 : n_threads) {
    if (policy != DevicePolicy::LegacyEnvironment && policy != DevicePolicy::Automatic &&
        policy != DevicePolicy::CPU) throw std::invalid_argument("invalid backend device policy");
    replay_cache_default_ = kDefaultReplayCacheSize;
    // Device selection (registry-driven; mirrors parakeet.cpp src/backend.cpp:91-166).
    // STARLING_GGML_DEVICE selects a named device ("cpu", "CUDA0", "Vulkan0",
    // "Metal", ...); unset auto-picks the first GPU/IGPU, else CPU.
    ggml_backend_dev_t chosen = nullptr;
    if (const char* dev_env = policy == DevicePolicy::LegacyEnvironment ? std::getenv("STARLING_GGML_DEVICE") : nullptr) {
        std::string want = dev_env;
        std::transform(want.begin(), want.end(), want.begin(),
                       [](unsigned char c){ return std::tolower(c); });
        if (want == "cpu") {
            chosen = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        } else {
            for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
                ggml_backend_dev_t d = ggml_backend_dev_get(i);
                std::string name = ggml_backend_dev_name(d);
                std::transform(name.begin(), name.end(), name.begin(),
                               [](unsigned char c){ return std::tolower(c); });
                if (name == want) { chosen = d; break; }
            }
        }
    }
    if (policy == DevicePolicy::Automatic) {
        chosen = eligible_automatic_device();
    } else if (!chosen && policy == DevicePolicy::LegacyEnvironment) {
        // Preserve old callers' environment/registry behavior separately.
        chosen = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_GPU);
        if (!chosen) chosen = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_IGPU);
        if (!chosen) chosen = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    }
#if defined(_WIN32) && defined(STARLING_CUBLAS_DLL)
    // Windows CUDA builds delay-load cuBLAS (release-starling-serve.yml) so the
    // exe starts without it; the CUDA runtime itself is static. A missing DLL
    // would otherwise kill the process with no message on the first matmul,
    // after a load that reported success. Fail the load here instead. The
    // probe uses the same search order as the delay-load helper, and the
    // module stays loaded for it. Both cuBLAS DLLs are probed explicitly:
    // cublas64 happens to import cublasLt64 today, so loading the former
    // transitively loads the latter, but a ggml that calls cublasLt directly
    // must not die silently at first use either.
    if (chosen &&
        std::strcmp(ggml_backend_reg_name(ggml_backend_dev_backend_reg(chosen)), "CUDA") == 0) {
        // cublas64 imports cublasLt64, so probe cublasLt first: when only
        // cublasLt is missing, loading cublas64 would fail with a misleading
        // error naming a DLL that is present.
        const char* const cublas_dlls[] = {
#ifdef STARLING_CUBLASLT_DLL
            STARLING_CUBLASLT_DLL,
#endif
            STARLING_CUBLAS_DLL,
        };
        for (const char* dll : cublas_dlls) {
            if (!LoadLibraryA(dll)) {
                // Include GetLastError(): the code separates causes that need
                // different user fixes (126 = the DLL or one of its
                // dependencies was not found, 193 = bitness mismatch, ...).
                const DWORD load_error = GetLastError();
                throw std::runtime_error(
                    std::string("CUDA device ") + ggml_backend_dev_name(chosen) +
                    " needs " + dll + " (cuBLAS), which could not be loaded (Windows error " +
                    std::to_string(load_error) +
                    "); add the CUDA runtime's cuBLAS DLL directory to PATH (see RUNTIME.md) "
                    "or set STARLING_GGML_DEVICE=cpu");
            }
        }
    }
#endif
    if (chosen) {
        device_name_ = ggml_backend_dev_name(chosen);
        dev_ = chosen;
        impl_->backend = ggml_backend_dev_init(chosen, nullptr);
        // Record the replay-cache default for this device class before any
        // model can construct its per-shape caches.
        struct ggml_backend_dev_props props = {};
        ggml_backend_dev_get_props(chosen, &props);
        const bool shared_window =
            props.type == GGML_BACKEND_DEVICE_TYPE_IGPU ||
            (props.memory_total > 0 && props.memory_total < kSharedWindowTotalBytes);
        replay_cache_default_ = shared_window ? kSharedMemoryReplayCacheSize : kDefaultReplayCacheSize;
        if (policy == DevicePolicy::LegacyEnvironment)
            g_device_replay_cache_default.store(replay_cache_default_, std::memory_order_relaxed);
    }
    if (!impl_->backend) {
        // CPU fallback (always available).
        impl_->backend = ggml_backend_cpu_init();
        device_name_ = "cpu";
        dev_ = nullptr; // failed device initialization is not the actual device
        replay_cache_default_ = kDefaultReplayCacheSize;
    }
    if (!impl_->backend) throw std::runtime_error("CPU backend initialization failed");
    // Classify the actual initialized backend, not a device-name guess.
    impl_->use_sched = !ggml_backend_is_cpu(impl_->backend);
    if (impl_->use_sched) {
        impl_->cpu_backend = ggml_backend_cpu_init();
        if (!impl_->cpu_backend) throw std::runtime_error("CPU offload backend initialization failed");
    } else {
        // CPU-only: the primary backend is already CPU.
        impl_->cpu_backend = impl_->backend;
    }
    if (ggml_backend_is_cpu(impl_->backend)) {
        ggml_backend_cpu_set_n_threads(impl_->backend, n_threads_);
        impl_->attach_threadpool(n_threads_);
    }
    // Persistent gallocr (shared by one-shot computes; ReplayGraph has its own).
    impl_->galloc = ggml_gallocr_new(ggml_backend_get_default_buffer_type(impl_->backend));
    if (!impl_->galloc) throw std::runtime_error("Backend graph allocator initialization failed");
}

Backend::~Backend() {
    if (!impl_) return; // checked release already completed; no hidden sync
    try { release_checked(); }
    catch (...) {
        std::fprintf(stderr, "[backend] unconfirmed drain/release; retaining backend resources\n");
        (void)impl_.release();
    }
}

bool Backend::healthy() const noexcept {
    return impl_ && health_ && health_->healthy.load(std::memory_order_acquire) && !shutting_down();
}
void Backend::mark_unhealthy() noexcept {
    if (health_) health_->healthy.store(false, std::memory_order_release);
}
void Backend::require_healthy() const {
    if (!healthy()) throw std::runtime_error("Backend is released, terminal or permanently unhealthy");
}
void Backend::synchronize() {
    require_healthy();
    try {
        if (impl_->backend) ggml_backend_synchronize(impl_->backend);
        if (impl_->cpu_backend && impl_->cpu_backend != impl_->backend)
            ggml_backend_synchronize(impl_->cpu_backend);
    } catch (...) {
        health_->healthy.store(false, std::memory_order_release);
        throw;
    }
}
void Backend::release_checked() {
    if (!impl_) return; // idempotent already-proven release
    require_healthy();
    try {
        synchronize();
        impl_->release_checked();
        impl_.reset(); // emptied Impl destructor does not synchronize
    } catch (...) {
        health_->healthy.store(false, std::memory_order_release);
        throw;
    }
}

void Backend::set_n_threads(int n_threads) {
    require_healthy();
    n_threads_ = n_threads < 1 ? 1 : n_threads;
    if (impl_ && ggml_backend_is_cpu(impl_->backend)) {
        ggml_backend_cpu_set_n_threads(impl_->backend, n_threads_);
        // A pool never runs more threads than it was created with.
        if (impl_->threadpool_n != n_threads_)
            impl_->attach_threadpool(n_threads_);
    }
}

bool Backend::is_gpu() const { return impl_ && impl_->use_sched; }
ggml_backend_t Backend::handle() const { require_healthy(); return impl_->backend; }

long long Backend::device_memory_free() const {
    // Trace-only query (STARLING_TRACE cache records). -1 = the device cannot
    // report it; the trace renders that as "unavailable" rather than
    // fabricating a number. The CPU device reports zeros — treated the same.
    if (!healthy() || !dev_) return -1;
    struct ggml_backend_dev_props props = {};
    ggml_backend_dev_get_props(dev_, &props);
    return props.memory_free > 0 ? (long long)props.memory_free : -1;
}

void Backend::register_input(ggml_tensor* t, const void* host, size_t nbytes) {
    require_healthy();
    if (!t_pending_inputs) return;
    if ((!host && nbytes) || !t || nbytes > ggml_nbytes(t))
        throw std::invalid_argument("Invalid graph input host/size");
    auto owned = std::make_shared<std::vector<uint8_t>>(nbytes);
    if (nbytes) std::memcpy(owned->data(), host, nbytes);
    t_pending_inputs->push_back({t, host, nbytes, std::move(owned)});
}
void Backend::register_capture(ggml_tensor* t, std::vector<float>* dst) {
    if (t_pending_captures) t_pending_captures->push_back({t, dst});
}

// ---------------------------------------------------------------------------
// Dtype-correct readback conversion. run_graph / ReplayGraph hand callers a
// std::vector<float> for the graph output AND every capture, so a non-F32
// output tensor must be converted elementwise. The old output path read
// nbytes and copied a BF16 tensor's 2n bytes raw into the low half of the
// 4n-byte vector — every second float garbage bits (the "CPU-only
// garbage output" bug; no over-read); the old CAPTURE paths sized the read
// cn*sizeof(float), a true over-read for any 2-byte-type capture. I32/F32
// are bit-compatible (the K-step token
// rings rely on the i32-bits-in-float trick); F16/BF16 convert; anything
// else fails loudly rather than silently miscasting.
// ---------------------------------------------------------------------------
static void raw_bytes_to_f32(ggml_type type, const void* raw, size_t nbytes,
                             std::vector<float>& out) {
    switch (type) {
    case GGML_TYPE_F32:
    case GGML_TYPE_I32: {
        out.resize(nbytes / 4);
        std::memcpy(out.data(), raw, nbytes);
        return;
    }
    case GGML_TYPE_BF16: {
        size_t n = nbytes / 2;
        out.resize(n);
        const ggml_bf16_t* b = (const ggml_bf16_t*) raw;
        for (size_t i = 0; i < n; ++i) out[i] = ggml_bf16_to_fp32(b[i]);
        return;
    }
    case GGML_TYPE_F16: {
        size_t n = nbytes / 2;
        out.resize(n);
        const ggml_fp16_t* h = (const ggml_fp16_t*) raw;
        for (size_t i = 0; i < n; ++i) out[i] = ggml_fp16_to_fp32(h[i]);
        return;
    }
    default:
        GGML_ABORT("run_graph/ReplayGraph readback of unsupported output type %s",
                   ggml_type_name(type));
    }
}


namespace {

// A no_alloc=true metadata context for one one-shot graph, built in the
// backend's reusable arena when it is free.
//    ggml_graph_overhead_custom(kGraphSize, ...) reserves node/leaf slot
//    capacity matching the cgraph allocated below (ggml_new_graph_custom);
//    the default ggml_new_graph() caps at GGML_DEFAULT_GRAPH_SIZE (2048)
//    nodes, which the parakeet conformer encoder (24 layers, ~2400 ops)
//    overflows.
class BuildContext {
public:
    BuildContext(std::unique_ptr<uint8_t[]>& arena, bool& busy) {
        const size_t size = ggml_tensor_overhead() * kGraphSize
                          + ggml_graph_overhead_custom(kGraphSize, false);
        void* mem = nullptr;
        if (!busy) {
            if (!arena) arena.reset(new (std::nothrow) uint8_t[size]);
            if (arena) {
                mem = arena.get();
                busy_ = &busy;
                busy = true;
            }
        }
        struct ggml_init_params params = {
            /*.mem_size   =*/ size,
            /*.mem_buffer =*/ mem,
            /*.no_alloc   =*/ true,
        };
        ctx_ = ggml_init(params);
    }
    ~BuildContext() {
        if (ctx_) ggml_free(ctx_);
        if (busy_) *busy_ = false;
    }
    BuildContext(const BuildContext&) = delete;
    BuildContext& operator=(const BuildContext&) = delete;
    ggml_context* get() const { return ctx_; }
    // On uncertain drain neither metadata nor the arena-busy claim may be
    // recycled. Backend's unhealthy Impl retains the borrowed arena too.
    void retain() noexcept { ctx_ = nullptr; busy_ = nullptr; }
private:
    ggml_context* ctx_ = nullptr;
    bool* busy_ = nullptr;
};

} // namespace

Backend::StepAlloc::~StepAlloc() {
    if (!galloc_ || shutting_down()) return;
    try {
        if (!owner_ || !health_ || !health_->healthy.load(std::memory_order_acquire)) return;
        owner_->synchronize();
        ggml_gallocr_free(galloc_);
    } catch (...) {
        if (health_) health_->healthy.store(false, std::memory_order_release);
        std::fprintf(stderr, "[backend] retaining uncertain step allocator\n");
    }
}

bool Backend::reserve_steps(StepAlloc& sa, int64_t key,
                            const std::function<ggml_tensor*(ggml_context*)>& build) {
    std::unique_lock<std::recursive_mutex> runtime_lock(runtime_mutex());
    require_healthy();
    if (sa.owner_ && sa.owner_ != this) throw std::invalid_argument("Step allocator belongs to another backend");
    sa.owner_ = this; sa.health_ = health_;
    BuildContext bctx(impl_->ctx_arena, impl_->ctx_arena_busy);
    ggml_context* ctx = bctx.get();
    if (!ctx) return false;
    auto pin = std::make_unique<std::vector<PendingInput>>();
    std::vector<PendingCapture> pcap;
    std::vector<ggml_tensor*> roots;
    try {
        ggml_tensor* out_t;
        {
            BuildScope scope(*this, *pin, pcap, roots);
            out_t = build(ctx);
        }
        if (!out_t) { synchronize(); return false; }
        ggml_set_output(out_t);
        ggml_cgraph* gf = ggml_new_graph_custom(ctx, kGraphSize, false);
        ggml_build_forward_expand(gf, out_t);
        for (const auto& c : pcap) ggml_build_forward_expand(gf, c.t);
        for (ggml_tensor* r : roots) ggml_build_forward_expand(gf, r);
        if (!sa.galloc_) sa.galloc_ = ggml_gallocr_new(ggml_backend_get_default_buffer_type(impl_->backend));
        const bool ok = sa.galloc_ && ggml_gallocr_reserve(sa.galloc_, gf);
        sa.reserved = ok ? key : -1;
        synchronize();
        return ok;
    } catch (...) {
        auto original = std::current_exception();
        try { synchronize(); }
        catch (...) { bctx.retain(); (void)pin.release(); throw; }
        std::rethrow_exception(original);
    }
}

bool Backend::compute(const std::function<ggml_tensor*(ggml_context*)>& build,
                      std::vector<float>& out) {
    return compute_impl(build, out, nullptr);
}

bool Backend::compute_step(StepAlloc& sa,
                           const std::function<ggml_tensor*(ggml_context*)>& build,
                           std::vector<float>& out) {
    return compute_impl(build, out, &sa);
}

bool Backend::compute_impl(const std::function<ggml_tensor*(ggml_context*)>& build,
                           std::vector<float>& out, StepAlloc* sa) {
    std::unique_lock<std::recursive_mutex> runtime_lock(runtime_mutex());
    require_healthy();
    if (sa) {
        if (sa->owner_ && sa->owner_ != this) throw std::invalid_argument("Step allocator belongs to another backend");
        sa->owner_ = this; sa->health_ = health_;
    }
    BuildContext bctx(impl_->ctx_arena, impl_->ctx_arena_busy);
    ggml_context* ctx = bctx.get();
    if (!ctx) return false;
    auto pin = std::make_unique<std::vector<PendingInput>>();
    std::vector<PendingCapture> pcap;
    std::vector<ggml_tensor*> roots;
    struct Readbacks { std::vector<uint8_t> output; std::vector<std::vector<uint8_t>> captures; };
    auto staging = std::make_unique<Readbacks>();
    try {
        ggml_tensor* out_t;
        {
            BuildScope scope(*this, *pin, pcap, roots);
            out_t = build(ctx);
        }
        if (!out_t) { synchronize(); return false; }
        ggml_set_output(out_t);
        ggml_cgraph* gf = ggml_new_graph_custom(ctx, kGraphSize, false);
        ggml_build_forward_expand(gf, out_t);
        for (const auto& c : pcap) ggml_build_forward_expand(gf, c.t);
        for (ggml_tensor* r : roots) ggml_build_forward_expand(gf, r);
        maybe_export_graph(gf, out_t, device_name(), pcap, roots);
        if (!impl_->use_sched) cpu_repack::prepare_graph(gf);
        bool need_sched = ImatrixCollector::enabled();
        if (impl_->use_sched) {
            for (int i = 0; i < ggml_graph_n_nodes(gf); ++i)
                if (!ggml_backend_supports_op(impl_->backend, ggml_graph_node(gf, i))) {
                    need_sched = true; break;
                }
        }
        // Allocate all host transfer targets before submitting any work. They
        // survive the catch/drain path and are retained on uncertainty.
        staging->output.resize(ggml_nbytes(out_t));
        staging->captures.resize(pcap.size());
        for (size_t i = 0; i < pcap.size(); ++i)
            staging->captures[i].resize(ggml_nbytes(pcap[i].t));
        bool ok = false;
        if (!need_sched) {
            ggml_gallocr_t& galloc = sa ? sa->galloc_ : impl_->galloc;
            if (!galloc) galloc = ggml_gallocr_new(ggml_backend_get_default_buffer_type(impl_->backend));
            if (galloc && ggml_gallocr_alloc_graph(galloc, gf)) {
                for (const auto& in : *pin)
                    if (in.t->data && in.nbytes) ggml_backend_tensor_set(in.t, in.bytes->data(), 0, in.nbytes);
                ok = ggml_backend_graph_compute_async(impl_->backend, gf) == GGML_STATUS_SUCCESS;
                synchronize();
            }
        } else {
            ggml_backend_sched_t sched = impl_->ensure_sched();
            if (sched) {
                bool allocated;
                try {
                    ggml_backend_sched_reset(sched);
                    allocated = ggml_backend_sched_alloc_graph(sched, gf);
                } catch (...) { health_->healthy.store(false, std::memory_order_release); throw; }
                if (allocated) {
                    for (const auto& in : *pin)
                        if (in.t->data && in.nbytes) ggml_backend_tensor_set(in.t, in.bytes->data(), 0, in.nbytes);
                    try { ok = ggml_backend_sched_graph_compute_async(sched, gf) == GGML_STATUS_SUCCESS; }
                    catch (...) { health_->healthy.store(false, std::memory_order_release); throw; }
                    synchronize();
                }
            }
        }
        if (ok) {
            // Buffer-dispatched synchronous reads support scheduler CPU buffers
            // too; do not use primary-backend async reads against those buffers.
            ggml_backend_tensor_get(out_t, staging->output.data(), 0, staging->output.size());
            for (size_t i = 0; i < pcap.size(); ++i)
                ggml_backend_tensor_get(pcap[i].t, staging->captures[i].data(), 0, staging->captures[i].size());
        }
        synchronize(); // before arena, galloc plan or any host owner can recycle
        if (ok) {
            std::vector<float> result;
            std::vector<std::vector<float>> captures(pcap.size());
            raw_bytes_to_f32(out_t->type, staging->output.data(), staging->output.size(), result);
            for (size_t i = 0; i < pcap.size(); ++i)
                raw_bytes_to_f32(pcap[i].t->type, staging->captures[i].data(), staging->captures[i].size(), captures[i]);
            out.swap(result);
            for (size_t i = 0; i < pcap.size(); ++i) pcap[i].dst->swap(captures[i]);
        }
        return ok;
    } catch (...) {
        auto original = std::current_exception();
        try { synchronize(); }
        catch (...) { bctx.retain(); (void)pin.release(); (void)staging.release(); throw; }
        std::rethrow_exception(original);
    }
}

// --------------------------------------------------------------------------- //
// Free helpers routed via thread-locals (model build lambdas call these).
// --------------------------------------------------------------------------- //
void add_graph_input(ggml_tensor* t, const void* host, size_t nbytes) {
    ggml_set_input(t);
    if (t_active_backend) t_active_backend->register_input(t, host, nbytes);
}
ggml_tensor* graph_input_tensor(ggml_context* ctx, int type, int n_dims,
                                const int64_t* ne, const void* host, size_t nbytes) {
    ggml_tensor* t = (n_dims == 1) ? ggml_new_tensor_1d(ctx, (ggml_type)type, ne[0])
                   : (n_dims == 2) ? ggml_new_tensor_2d(ctx, (ggml_type)type, ne[0], ne[1])
                   : (n_dims == 3) ? ggml_new_tensor_3d(ctx, (ggml_type)type, ne[0], ne[1], ne[2])
                   : ggml_new_tensor_4d(ctx, (ggml_type)type, ne[0], ne[1], ne[2], ne[3]);
    add_graph_input(t, host, nbytes);
    return t;
}
void capture_graph_output(ggml_tensor* t, std::vector<float>* dst) {
    ggml_set_output(t);
    if (t_active_backend) t_active_backend->register_capture(t, dst);
}

void mark_graph_input_persistent(ggml_tensor* t) {
    if (t_pending_persistent) t_pending_persistent->push_back(t);
}

void add_graph_root(ggml_tensor* t) {
    // The node is NOT marked output (no readback); the caller just needs it
    // expanded into the cgraph so it executes as a side effect.
    if (t_pending_roots) t_pending_roots->push_back(t);
}

// --------------------------------------------------------------------------- //
// Zero-copy weight referencing.
// --------------------------------------------------------------------------- //
void ensure_weights_realized(const ModelLoader& ml) {
    if (!const_cast<ModelLoader&>(ml).realize_weights(global_backend()))
        throw std::runtime_error(ml.last_error());
}
size_t device_replay_cache_default() {
    if (Backend* scoped = current_context_backend()) return scoped->replay_cache_default();
    return g_device_replay_cache_default.load(std::memory_order_relaxed);
}
ggml_tensor* clone_weight_opt(ggml_context* /*ctx*/, const ModelLoader& ml, const char* name) {
    ggml_tensor* t = ml.tensor(name);
    if (!t) return nullptr;
    // Lazily give it a backend buffer on first use.
    if (!t->buffer) { ensure_weights_realized(ml); t = ml.tensor(name); }
    return t;
}
ggml_tensor* clone_weight(ggml_context* ctx, const ModelLoader& ml, const char* name) {
    ggml_tensor* t = clone_weight_opt(ctx, ml, name);
    return t;  // (loader-level asserts presence; callers assert by use)
}
void weight_to_host_f32(const ModelLoader& ml, const char* name, std::vector<float>& out) {
    ggml_tensor* t = ml.tensor(name);
    if (!t) { out.clear(); return; }
    ensure_weights_realized(ml);
    t = ml.tensor(name);
    size_t n = (size_t)ggml_nelements(t);
    out.resize(n);
    // Backend tensors may be non-f32 (f16); for host-side math the loader keeps
    // the CPU f32 view authoritative when present. Fetch raw bytes, cast if f32.
    if (t->type == GGML_TYPE_F32) {
        ggml_backend_tensor_get(t, out.data(), 0, n * sizeof(float));
    } else {
        // Dequant via a temporary graph (rare path; used for host folds).
        std::vector<char> raw(ggml_row_size(t->type, n));
        ggml_backend_tensor_get(t, raw.data(), 0, ggml_row_size(t->type, n));
        ggml_fp16_to_fp32_row((const ggml_fp16_t*)raw.data(), out.data(), n);
    }
}



// --------------------------------------------------------------------------- //
// Unsupported-graph-node enumeration (issue #184).
// --------------------------------------------------------------------------- //
std::vector<UnsupportedGraphNode> enumerate_unsupported_graph_nodes(
    ggml_cgraph* gf,
    const std::function<bool(const ggml_tensor*)>& supports) {
    std::vector<UnsupportedGraphNode> out;
    if (!gf) return out;
    const int n_nodes = ggml_graph_n_nodes(gf);
    for (int i = 0; i < n_nodes; ++i) {
        const ggml_tensor* n = ggml_graph_node(gf, i);
        if (!n || supports(n)) continue;
        UnsupportedGraphNode e;
        e.node_index = i;
        e.op         = ggml_op_name(n->op);
        e.dst_type   = ggml_type_name(n->type);
        for (int s = 0; s < 3 && n->src[s]; ++s) {
            if (!e.srcs.empty()) e.srcs += ' ';
            e.srcs += "src" + std::to_string(s) + "=" +
                      ggml_type_name(n->src[s]->type) + "(" +
                      ggml_op_name(n->src[s]->op) + "," +
                      (ggml_is_contiguous(n->src[s]) ? "cont" : "STRIDED") + ")";
        }
        out.push_back(std::move(e));
    }
    return out;
}

std::string format_unsupported_graph_node(const UnsupportedGraphNode& node,
                                          int n_nodes) {
    char head[96];
    std::snprintf(head, sizeof head, "node %d/%d: op=%s dst=%s",
                  node.node_index, n_nodes, node.op.c_str(),
                  node.dst_type.c_str());
    std::string line = head;
    if (!node.srcs.empty()) line += " " + node.srcs;
    return line;
}

void check_no_unsupported_graph_nodes(
    ggml_cgraph* gf,
    const std::function<bool(const ggml_tensor*)>& supports,
    const char* device_name) {
    // The build-time guard ReplayGraph::alloc_internal runs on every
    // GPU-routed captured graph (issue #184). Extracted from it so tests can
    // drive the real throw path with a fake predicate — no GPU backend
    // needed. No-op when every node passes `supports`.
    const std::vector<UnsupportedGraphNode> unsupported =
        enumerate_unsupported_graph_nodes(gf, supports);
    if (unsupported.empty()) return;
    const int n_nodes = ggml_graph_n_nodes(gf);
    // STARLING_SCHED_DEBUG echoes the enumeration to stderr at allocation
    // time; it survives callers that log only part of the thrown error below.
    if (const char* debug = std::getenv("STARLING_SCHED_DEBUG");
        debug && debug[0] == '1') {
        for (const auto& u : unsupported)
            std::fprintf(stderr, "[sched-dbg] unsupported %s\n",
                         format_unsupported_graph_node(u, n_nodes).c_str());
    }
    std::string msg =
        "ReplayGraph allocation failed: device '" +
        std::string(device_name) + "' rejected " +
        std::to_string(unsupported.size()) + " of " +
        std::to_string(n_nodes) + " captured-graph nodes:\n";
    // The message crosses the C API into a 2048-byte error buffer
    // (g_last_error in capi.cpp), so cap the embedded per-node lines or the
    // actionable tail below would truncate away first. The
    // STARLING_SCHED_DEBUG echo above always prints every node.
    constexpr size_t kMaxEmbeddedLines = 16;
    for (size_t i = 0; i < unsupported.size() && i < kMaxEmbeddedLines; ++i)
        msg += "  " + format_unsupported_graph_node(unsupported[i], n_nodes) + "\n";
    if (unsupported.size() > kMaxEmbeddedLines)
        msg += "  ... and " +
               std::to_string(unsupported.size() - kMaxEmbeddedLines) +
               " more rejected node(s) - run with STARLING_SCHED_DEBUG=1"
               " to print all of them\n";
    msg +=
        "A captured (replayed) graph cannot fall back to"
        " ggml_backend_sched: its input uploads run on the primary"
        " backend against sched-allocated buffers and abort inside"
        " ggml. This is a starling graph-construction bug - please"
        " report it (see https://github.com/sims1253/starling/issues/184)."
        " Set STARLING_SCHED_DEBUG=1 to also print this enumeration to"
        " stderr at allocation time.";
    throw std::runtime_error(msg);
}

// --------------------------------------------------------------------------- //
// ReplayGraph
// --------------------------------------------------------------------------- //
ReplayGraph::ReplayGraph(Backend& backend,
                         const std::function<ggml_tensor*(ggml_context*)>& build)
    : input_storage_(std::make_unique<InputStorage>()), backend_(backend) {
    std::unique_lock<std::recursive_mutex> runtime_lock(runtime_mutex());
    backend_.require_healthy();
    // graph_build spans construction + allocation of one captured shape (the
    // one-time cost a replay-cache miss pays). Includes the build lambda and
    // alloc_internal(); the gated F1 histogram below is inside the window.
    const bool tr_on = trace::on();
    const auto t_build0 = std::chrono::steady_clock::now();
    struct ggml_init_params params = {
        /*.mem_size   =*/ ggml_tensor_overhead() * kGraphSize
                         + ggml_graph_overhead_custom(kGraphSize, false),
        /*.mem_buffer =*/ nullptr,
        /*.no_alloc   =*/ true,
    };
    ctx_ = ggml_init(params);
    if (!ctx_) throw std::runtime_error("ReplayGraph context allocation failed");
    auto pin = std::make_unique<std::vector<PendingInput>>();
    try {
        std::vector<PendingCapture> pcap;
        std::vector<ggml_tensor*> roots;
        std::vector<ggml_tensor*> persistent_marks;
        {
            BuildScope scope(backend_, *pin, pcap, roots, &persistent_marks);
            out_ = build(ctx_);
        }

        if (out_) {
            ggml_set_output(out_);
            gf_ = ggml_new_graph_custom(ctx_, kGraphSize, false);
            // Stable uid: lets ggml-cuda skip its O(n_nodes) per-replay memcmp
            // (patch 0002: "skip per-node replay validation when uid is stable").
            gf_->uid = ggml_graph_next_uid();
            ggml_build_forward_expand(gf_, out_);
            for (const auto& c : pcap) ggml_build_forward_expand(gf_, c.t);
            // Side-effect roots (Wave D decode-state write-backs into persistent
            // device buffers): expand so they execute each replay without readback.
            for (ggml_tensor* r : roots) ggml_build_forward_expand(gf_, r);
            maybe_export_graph(gf_, out_, backend_.device_name(), pcap, roots);
            // Record inputs + captures in registration order (kept across calls).
            for (const auto& in : *pin) {
                inputs_.push_back(in.t);
                input_hosts_.push_back(in.host);
                input_storage_->initial.push_back(in.bytes);
                input_storage_->uploaded.push_back(false);
                const size_t bytes = ggml_nbytes(in.t);
                if (bytes > (std::numeric_limits<size_t>::max() - input_storage_->pending_budget) / 2)
                    throw std::overflow_error("Replay input staging budget overflow");
                input_storage_->pending_budget += bytes * 2;
            }
            for (ggml_tensor* t : persistent_marks) {
                for (size_t i = 0; i < inputs_.size(); ++i)
                    if (inputs_[i] == t) { persistent_.resize(inputs_.size(), false); persistent_[i] = true; break; }
            }
            for (const auto& c : pcap) {
                captures_.emplace_back(c.t, c.dst);
            }
            if (!alloc_internal()) throw std::runtime_error("ReplayGraph allocation failed");
            if (tr_on) {
                long long ne[4] = {out_->ne[0], out_->ne[1], out_->ne[2], out_->ne[3]};
                trace::graph_build_event(
                    std::chrono::duration<double, std::milli>(
                        std::chrono::steady_clock::now() - t_build0).count(),
                    (unsigned)gf_->uid, gf_->n_nodes, ne, backend_.device_name(),
                    backend_.device_memory_free());
            }
            // F1: one-time op histogram for this graph (gated). Counts nodes by op
            // name and, for MUL_MAT, the source (weight) dtype so we can confirm the
            // f16 tensor-core cuBLAS path vs an f32 fallback. Printed once per ctor.
            if (replay_timing_on()) {
                std::fprintf(stderr, "[enc-graph] uid=%u n_nodes=%d\n",
                             (unsigned)gf_->uid, gf_->n_nodes);
                std::map<ggml_op, int> hist;
                int mm_f32 = 0, mm_f16 = 0, mm_other = 0;
                for (int i = 0; i < gf_->n_nodes; ++i) {
                    ggml_tensor* n = ggml_graph_node(gf_, i);
                    if (!n) continue;
                    hist[n->op]++;
                    if (n->op == GGML_OP_MUL_MAT) {
                        ggml_tensor* src0 = n->src[0];
                        if (src0->type == GGML_TYPE_F32) mm_f32++;
                        else if (src0->type == GGML_TYPE_F16) mm_f16++;
                        else mm_other++;
                    }
                }
                for (const auto& kv : hist) {
                    std::fprintf(stderr, "[enc-graph]   op=%-22s count=%d\n",
                                 ggml_op_name(kv.first), kv.second);
                }
                std::fprintf(stderr,
                    "[enc-graph]   MUL_MAT dtype: f16=%d f32=%d other=%d\n",
                    mm_f16, mm_f32, mm_other);
            }
        }
    } catch (...) {
        auto original = std::current_exception();
        try {
            backend_.synchronize();
            if (galloc_) { ggml_gallocr_free(galloc_); galloc_ = nullptr; }
            if (ctx_) { ggml_free(ctx_); ctx_ = nullptr; }
        } catch (...) {
            // A throwing constructor does not run ReplayGraph's destructor.
            // Failed drain OR native free has an ambiguous outcome; retain all
            // owners/remaining handles and forbid any later backend reuse.
            backend_.health_->healthy.store(false, std::memory_order_release);
            (void)input_storage_.release(); (void)pin.release();
            throw;
        }
        std::rethrow_exception(original);
    }
}

bool ReplayGraph::alloc_internal() {
    backend_.require_healthy();
    // Decide sched vs gallocr (same logic as Backend::compute) — with one
    // deliberate exception: an op the accelerator REJECTS must not route a
    // captured graph onto sched. One-shot graphs re-upload their inputs
    // through the sched-aware ggml_backend_tensor_set, but ReplayGraph
    // replays upload via ggml_backend_tensor_set_async with the PRIMARY
    // backend, which aborts on sched-allocated buffers ("unsupported buffer
    // type", issue #184). So for captured graphs the unsupported-op route is
    // a hard allocation error, not a fallback. The guard runs for EVERY
    // GPU-routed captured graph, the imatrix sched route included: the
    // STARLING_IMATRIX env gate does not pin the device to CPU, and a
    // rejected node behind that route would hit the same first-upload
    // assert. (A fully-supported graph still takes the imatrix sched route
    // below — that configuration is unchanged.)
    need_sched_ = ImatrixCollector::enabled();
    if (!backend_.is_gpu()) {
        // Captured CPU graphs replay without rebuilding, so this is the one
        // point where their weights can be opted into the repacked kernels.
        cpu_repack::prepare_graph(gf_);
    } else {
        ggml_backend_t backend = backend_.handle();
        check_no_unsupported_graph_nodes(
            gf_, [backend](const ggml_tensor* n) {
                return ggml_backend_supports_op(backend, n);
            }, backend_.device_name());
    }
    if (!need_sched_) {
        // PRIVATE gallocr per ReplayGraph: a shared one would invalidate other
        // graphs' device pointers when a larger graph reallocs its buffer.
        if (!galloc_) galloc_ = ggml_gallocr_new(ggml_backend_get_default_buffer_type(backend_.handle()));
        return ggml_gallocr_alloc_graph(galloc_, gf_);
    }
    // sched path (GPU op offload, or imatrix collection on any device).
    ggml_backend_sched_t sched = backend_.impl_->ensure_sched();
    if (!sched) return false;
    try {
        ggml_backend_sched_reset(sched);
        return ggml_backend_sched_alloc_graph(sched, gf_);
    } catch (...) {
        backend_.health_->healthy.store(false, std::memory_order_release);
        throw;
    }
}

ReplayGraph::~ReplayGraph() {
    // Field destructors normally release InputStorage. On an unconfirmed drain
    // that would invalidate queued host pointers, so retain the WHOLE owner.
    if (shutting_down()) { (void)input_storage_.release(); return; }
    try {
        backend_.synchronize();
        if (galloc_) { ggml_gallocr_free(galloc_); galloc_ = nullptr; }
        if (ctx_) { ggml_free(ctx_); ctx_ = nullptr; }
    } catch (...) {
        backend_.health_->healthy.store(false, std::memory_order_release);
        (void)input_storage_.release();
        std::fprintf(stderr, "[backend] unconfirmed replay drain/release; retaining replay and input resources\n");
    }
}

void ReplayGraph::clear_drained_inputs() {
    input_storage_->pending.clear();
    input_storage_->pending_bytes = 0;
}

void ReplayGraph::queue_owned_input(size_t i, std::shared_ptr<std::vector<uint8_t>> bytes) {
    backend_.require_healthy();
    const size_t size = bytes->size();
    // Repeated uploads without a compute cannot grow an unbounded FIFO. This
    // adds no sync to the normal once-per-input/once-per-replay submission path.
    if (input_storage_->pending_bytes > input_storage_->pending_budget - size) {
        backend_.synchronize();
        clear_drained_inputs();
    }
    input_storage_->pending.push_back(bytes); // ownership BEFORE possible enqueue
    input_storage_->pending_bytes += size;
    try {
        ggml_backend_t native = backend_.handle();
        if (native->iface.set_tensor_async) {
            ggml_backend_tensor_set_async(native, inputs_[i], bytes->data(), 0, size);
        } else {
            backend_.synchronize(); // ggml helper's otherwise-hidden sync
            ggml_backend_tensor_set(inputs_[i], bytes->data(), 0, size);
        }
        input_storage_->uploaded[i] = true;
        input_storage_->initial[i].reset(); // pending owns the snapshot until drain
    } catch (...) {
        auto original = std::current_exception();
        backend_.synchronize(); // failed drain is sticky; pending owners stay alive
        clear_drained_inputs();
        std::rethrow_exception(original);
    }
}

void ReplayGraph::set_input(size_t i, const void* host, size_t nbytes) {
    std::unique_lock<std::recursive_mutex> runtime_lock(runtime_mutex());
    backend_.require_healthy();
    if (i >= inputs_.size() || inputs_[i]->data == nullptr || nbytes == 0) return;
    if (!host || nbytes > ggml_nbytes(inputs_[i]))
        throw std::invalid_argument("Invalid replay input host/size");
    // Preserve partial-update semantics: initialize the untouched suffix from
    // the constructor snapshot before the first partial upload.
    if (!input_storage_->uploaded[i] && nbytes < ggml_nbytes(inputs_[i]) &&
        input_storage_->initial[i] && !input_storage_->initial[i]->empty())
        queue_owned_input(i, input_storage_->initial[i]);
    auto owned = std::make_shared<std::vector<uint8_t>>(nbytes);
    std::memcpy(owned->data(), host, nbytes);
    queue_owned_input(i, std::move(owned));
}

size_t ReplayGraph::input_nbytes(size_t i) const {
    if (i >= inputs_.size()) return 0;
    return (size_t)ggml_nbytes(inputs_[i]);
}
const void* ReplayGraph::input_host(size_t i) const {
    return (i < input_hosts_.size()) ? input_hosts_[i] : nullptr;
}

size_t ReplayGraph::device_alloc_bytes() const {
    // Private gallocr buffer, buffer slot 0 (single buffer type). This is the
    // exact device allocation ReplayGraph::~ReplayGraph frees via
    // ggml_gallocr_free, so cache budgets accounting it measure what eviction
    // actually reclaims.
    return galloc_ ? ggml_gallocr_get_buffer_size(galloc_, 0) : 0;
}

void ReplayGraph::readback_async_then_sync(Backend::Impl* impl,
                                           ggml_tensor* out_t,
                                           std::vector<float>& out) {
    // Async D2H for the output, then async D2H for each capture, then ONE sync.
    // Collapses N syncs (each ~150-200us on WSL2) into one. The async variant
    // runs on the backend stream so copies queue behind the graph launch.
    // Non-F32 tensors read back into staging buffers and convert to f32 after
    // the sync (see raw_bytes_to_f32): the callers' vectors are float.
    // All transfer targets are owned staging, allocated BEFORE any enqueue.
    // A failed synchronization retains those bytes rather than letting stack
    // unwinding invalidate memory still referenced by the device.
    struct Readbacks { std::vector<char> output; std::vector<std::vector<char>> captures; };
    auto staging = std::make_unique<Readbacks>();
    staging->output.resize(ggml_nbytes(out_t));
    staging->captures.resize(captures_.size());
    for (size_t ci = 0; ci < captures_.size(); ++ci)
        if (captures_[ci].second) staging->captures[ci].resize(ggml_nbytes(captures_[ci].first));
    try {
        const auto get = [&](const ggml_tensor* tensor, void* dst, size_t bytes) {
            if (impl->backend->iface.get_tensor_async) {
                ggml_backend_tensor_get_async(impl->backend, tensor, dst, 0, bytes);
            } else {
                backend_.synchronize(); // ggml helper's otherwise-hidden sync
                ggml_backend_tensor_get(tensor, dst, 0, bytes);
            }
        };
        get(out_t, staging->output.data(), staging->output.size());
        for (size_t ci = 0; ci < captures_.size(); ++ci)
            if (captures_[ci].second)
                get(captures_[ci].first, staging->captures[ci].data(), staging->captures[ci].size());
        backend_.synchronize();
    } catch (...) {
        try { backend_.synchronize(); }
        catch (...) { (void)staging.release(); }
        throw;
    }
    std::vector<float> result;
    std::vector<std::vector<float>> converted(captures_.size());
    raw_bytes_to_f32(out_t->type, staging->output.data(), staging->output.size(), result);
    for (size_t ci = 0; ci < captures_.size(); ++ci)
        if (captures_[ci].second)
            raw_bytes_to_f32(captures_[ci].first->type, staging->captures[ci].data(),
                             staging->captures[ci].size(), converted[ci]);
    out.swap(result);
    for (size_t ci = 0; ci < captures_.size(); ++ci)
        if (captures_[ci].second) captures_[ci].second->swap(converted[ci]);
}

namespace {
// Cancellable replay (issue #357): an abortable graph (set_abortable; the
// encoder) runs in kAbortSlices views with a sync between them when the
// caller installed a call_abort scope, so a cancelled call stops after the
// current slice instead of the whole graph. Small graphs are not worth the
// syncs. Without a scope the single-submit fast path is unchanged.
constexpr int kAbortSliceMinNodes = 256;
constexpr int kAbortSlices = 4;
} // namespace

bool ReplayGraph::compute(std::vector<float>& out) {
    // Match the CPU build/loader lock through execution and readback so a
    // different graph cannot repack a weight while this one reads it.
    std::unique_lock<std::recursive_mutex> runtime_lock(runtime_mutex());
    backend_.require_healthy();
    if (!gf_ || !out_) return false;
    Backend::Impl* impl = backend_.impl_.get();
    // Fast path: graph_compute_async (skip the sync-wrapping graph_compute so the
    // readbacks can pipeline behind the graph on the same stream), then async
    // readback + single sync. The trace timestamps ride the same clocks as the
    // STARLING_REPLAY_TIMING split; with both gates off the only added work is
    // the gate load.
    const bool t_on = replay_timing_on();
    const bool tr_on = trace::on();
    const bool any_gate = t_on || tr_on;
    const int64_t t_gc0 = any_gate ? ggml_time_us() : 0;
    try {
        // Registered bytes are owned even when the build lambda's local host
        // went out of scope. Explicit set_input still wins for mutable pools.
        for (size_t i = 0; i < inputs_.size(); ++i)
            if (!input_storage_->uploaded[i] && inputs_[i]->data &&
                input_storage_->initial[i] && !input_storage_->initial[i]->empty())
                queue_owned_input(i, input_storage_->initial[i]);
        bool ok;
        if (!need_sched_ && abortable_ && t_call_abort && gf_->n_nodes >= kAbortSliceMinNodes) {
            ok = true;
            for (int k = 0; ok && k < kAbortSlices; ++k) {
                const int i0 = static_cast<int>((int64_t)gf_->n_nodes * k / kAbortSlices);
                const int i1 = static_cast<int>((int64_t)gf_->n_nodes * (k + 1) / kAbortSlices);
                ggml_cgraph view = ggml_graph_view(gf_, i0, i1);
                ok = (ggml_backend_graph_compute_async(impl->backend, &view) == GGML_STATUS_SUCCESS);
                if (ok && k + 1 < kAbortSlices) {
                    backend_.synchronize();
                    clear_drained_inputs();
                    if (call_abort_requested()) return false;  // nothing read back
                }
            }
        } else if (!need_sched_) {
            ok = (ggml_backend_graph_compute_async(impl->backend, gf_) == GGML_STATUS_SUCCESS);
        } else {
            try { ok = (ggml_backend_sched_graph_compute_async(impl->sched, gf_) == GGML_STATUS_SUCCESS); }
            catch (...) { backend_.health_->healthy.store(false, std::memory_order_release); throw; }
        }
        if (!ok) { backend_.synchronize(); clear_drained_inputs(); return false; }
        if (std::getenv("STARLING_NODE_HIST") && gf_) {
            // One-shot per-replay node-type histogram (debug aid; off by default).
            static std::mutex hist_mu;
            std::lock_guard<std::mutex> lk(hist_mu);
            std::map<std::string, int> hist;
            for (int i = 0; i < gf_->n_nodes; ++i) {
                ggml_tensor* n = gf_->nodes[i];
                if (!n) continue;
                hist[ggml_op_name(n->op)]++;
                if (n->op == GGML_OP_CPY && std::getenv("STARLING_CPY_DEBUG"))
                    std::fprintf(stderr, "[cpy] %s <- %s (op %s)\n", n->name, n->src[0] ? n->src[0]->name : "?", n->src[0] ? ggml_op_name(n->src[0]->op) : "?");
            }
            std::string line;
            for (auto& [k, v] : hist) line += k + "=" + std::to_string(v) + " ";
            std::fprintf(stderr, "[node-hist] uid=%u n=%d %s\n",
                         (unsigned)gf_->uid, gf_->n_nodes, line.c_str());
        }
        if (std::getenv("STARLING_NODE_SHAPES") && gf_) {
            static std::mutex shp_mu;
            std::lock_guard<std::mutex> lk(shp_mu);
            for (int i = 0; i < gf_->n_nodes; ++i) {
                ggml_tensor* n = gf_->nodes[i];
                if (!n || n->op != GGML_OP_MUL_MAT) continue;
                ggml_tensor* a = n->src[0];
                ggml_tensor* b = n->src[1];
                std::fprintf(stderr, "[mm-shape] m=%lld n=%lld k=%lld wtype=%s\n",
                    (long long)a->ne[1], (long long)b->ne[1], (long long)a->ne[0],
                    ggml_type_name(a->type));
            }
        }
        const int64_t t_gc1 = any_gate ? ggml_time_us() : 0;
        if (tr_on) {
            long long ne[4] = {out_->ne[0], out_->ne[1], out_->ne[2], out_->ne[3]};
            trace::graph_event("graph_replay",
                               (double)(t_gc1 - t_gc0) / 1000.0,
                               gf_ ? (unsigned)gf_->uid : 0u, gf_ ? gf_->n_nodes : -1,
                               ne, backend_.device_name());
        }
        readback_async_then_sync(impl, out_, out);
        clear_drained_inputs();
        const int64_t t_rb1 = any_gate ? ggml_time_us() : 0;
        if (tr_on) {
            long long ne[4] = {out_->ne[0], out_->ne[1], out_->ne[2], out_->ne[3]};
            // Host blocked on the single trailing sync — includes waiting for
            // prior GPU work, so it is NOT transfer time alone (see trace.hpp).
            // Output/captures are already committed. Optional diagnostics can
            // allocate (including under OOM); losing a trace must never turn a
            // successfully completed inference into a failed/duplicated call.
            try {
                trace::graph_event("readback_sync",
                                   (double)(t_rb1 - t_gc1) / 1000.0,
                                   gf_ ? (unsigned)gf_->uid : 0u, gf_ ? gf_->n_nodes : -1,
                                   ne, backend_.device_name());
            } catch (...) { /* best-effort trace only; inference succeeded */ }
        }
        if (t_on) {
            std::fprintf(stderr,
                "[enc-timing]   graph_compute=%lldus readback=%lldus n_nodes=%d uid=%u\n",
                (long long)(t_gc1 - t_gc0), (long long)(t_rb1 - t_gc1),
                gf_ ? gf_->n_nodes : -1, gf_ ? (unsigned)gf_->uid : 0u);
        }
        return true;
    } catch (...) {
        // Queue submissions and caller output resize can fail. Drain while
        // graph/model resources are still owned; never turn failure into text.
        backend_.synchronize();
        clear_drained_inputs();
        throw;
    }
}

bool ReplayGraph::compute_with_captures(std::vector<float>& out) {
    // Same as compute(); the readback path already drains captures.
    return compute(out);
}

} // namespace starling::ggml
