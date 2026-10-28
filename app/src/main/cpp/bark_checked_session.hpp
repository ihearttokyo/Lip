#pragma once

#include "sealed_model_snapshot.hpp"
#include "snapshot_hash_contract.hpp"
#include "starling_ggml.h"
#include "runtime/call_abort.hpp"

#include <array>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <utility>
#include <vector>
#if defined(__unix__) || defined(__APPLE__)
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

namespace lip::speech {

struct CheckedSessionOps {
    decltype(&starling_ggml_load_fd_checked_report) load = starling_ggml_load_fd_checked_report;
    decltype(&starling_ggml_release_checked) release = starling_ggml_release_checked;
    decltype(&starling_ggml_transcribe_pcm_words) transcribe = starling_ggml_transcribe_pcm_words;
    decltype(&starling_ggml_context_synchronize) synchronize = starling_ggml_context_synchronize;
    decltype(&starling_ggml_free_string) free_text = starling_ggml_free_string;
    decltype(&starling_ggml_free_words) free_words = starling_ggml_free_words;
    bool valid() const noexcept {
        return load && release && transcribe && synchronize && free_text && free_words;
    }
};

enum class CheckedPhase : std::uint8_t {
    Created, Preparing, Ready, Running, Retiring, FailedReleased, Retained, Closed,
};
enum class PrepareResult : std::uint8_t {
    Ready, Cancelled, InvalidInput, SnapshotUnavailable, HashMismatch, HashUnavailable,
    HashError, NativeFailedReleased, Retained, NotAdmitted, AllocationFailed,
};
enum class CheckedTranscriptResult : std::uint8_t {
    Completed, Cancelled, InvalidInput, NotAdmitted, NativeFailedReleased, Retained,
    MarshalingFailed, AllocationFailed,
};

class CheckedSessionRegistry;

class CheckedBarkSession final {
public:
    struct Request {
        std::atomic<bool> cancel{false};
        std::uint64_t generation = 0;
        std::size_t original_samples = 0;
        int padding_samples = 0;
        std::vector<float> pcm;
        char* text = nullptr;
        starling_ggml_word* words = nullptr;
        std::int64_t word_count = -1;
        bool work_may_live = false;
        // No destructor frees native outputs: unknown retirement must retain the
        // complete request/PCM, not guess that returned pointers are disposable.
    };
    // Both callbacks are synchronous borrowed views. They must not retain PCM,
    // Request references, stack contexts or JNI state after returning. Unknown
    // native work retains the Request itself in the registry, never callbacks.
    using PcmWriter = bool (*)(float* destination, std::size_t samples, void* context) noexcept;
    using TranscriptConsumer = bool (*)(const Request&, void* context) noexcept;

    CheckedBarkSession(const CheckedBarkSession&) = delete;
    CheckedBarkSession& operator=(const CheckedBarkSession&) = delete;
    ~CheckedBarkSession() = default; // registry erases ONLY after confirmed close

    CheckedPhase phase() const noexcept { return phase_of(state_.load(std::memory_order_acquire)); }
    // An old idle cancellation is rearmable. Java revokes its old capture/cache;
    // abort(false) must then establish a NEW generation before native inference.
    bool admission() const noexcept {
        return !global_->poison.load(std::memory_order_acquire) && !closing_.load(std::memory_order_acquire) &&
               phase() == CheckedPhase::Ready;
    }
    bool retained_work() const noexcept {
        if (global_->poison.load(std::memory_order_acquire)) return true;
        const auto p = phase();
        if (closing_.load(std::memory_order_acquire) && p != CheckedPhase::Closed) return true;
        return p == CheckedPhase::Preparing || p == CheckedPhase::Running ||
               p == CheckedPhase::Retiring || p == CheckedPhase::Retained;
    }
    int profile_id() const noexcept { return profile_.id; }

    // Loading-only, monotonic token: a delayed old ticket cannot cancel a cached
    // engine's later requests. No operation mutex or global runtime lock here.
    void cancel_loading() noexcept {
        auto observed = state_.load(std::memory_order_acquire);
        for (;;) {
            const auto p = phase_of(observed);
            if (p != CheckedPhase::Created && p != CheckedPhase::Preparing) return;
            if (state_.compare_exchange_weak(observed, observed | kCancelled,
                    std::memory_order_acq_rel, std::memory_order_acquire)) {
                loading_cancel_.store(true, std::memory_order_release);
                return;
            }
        }
    }

    bool abort_checked(bool abort) noexcept {
        if (!abort) {
            if (global_->poison.load(std::memory_order_acquire) || closing_.load(std::memory_order_acquire)) return false;
            auto observed = state_.load(std::memory_order_acquire);
            if (phase_of(observed) != CheckedPhase::Ready || generation_of(observed) == kMaxGeneration)
                return false;
            if (std::atomic_load_explicit(&active_request_, std::memory_order_acquire)) return false;
            // Advancing the epoch invalidates delayed old abort CAS operations;
            // no old heap cancellation token is ever cleared or resurrected.
            const auto desired = encode(CheckedPhase::Ready, generation_of(observed) + 1, false);
            return state_.compare_exchange_strong(observed, desired,
                std::memory_order_acq_rel, std::memory_order_acquire) &&
                !global_->poison.load(std::memory_order_acquire) && !closing_.load(std::memory_order_acquire);
        }
        auto observed = state_.load(std::memory_order_acquire);
        const auto captured_generation = generation_of(observed);
        auto p = phase_of(observed);
        if (p == CheckedPhase::Created || p == CheckedPhase::Preparing) {
            cancel_loading();
            return true;
        }
        if (p != CheckedPhase::Ready && p != CheckedPhase::Running) return false;
        auto request = std::atomic_load_explicit(&active_request_, std::memory_order_acquire);
        if (request && request->generation == captured_generation)
            request->cancel.store(true, std::memory_order_release);
        for (;;) {
            p = phase_of(observed);
            if (generation_of(observed) != captured_generation ||
                    (p != CheckedPhase::Ready && p != CheckedPhase::Running)) return true;
            if (state_.compare_exchange_weak(observed, observed | kCancelled,
                    std::memory_order_acq_rel, std::memory_order_acquire)) return true;
        }
    }

    PrepareResult prepare_from_path(const std::string& path, SnapshotHashVerifier verify,
                                    void* verifier_context) noexcept {
        std::unique_lock<std::mutex> operation;
        try {
            operation = std::unique_lock<std::mutex>(operation_);
            if (!begin_preparing()) return prepare_not_admitted();
            if (loading_stopped()) return fail_preparation(PrepareResult::Cancelled);
            if (!verify || path.empty() || path.size() > 4096 || path.find('\0') != std::string::npos)
                return fail_preparation(PrepareResult::InvalidInput);
#if defined(__unix__) || defined(__APPLE__)
            int source = -1;
            for (;;) {
                if (loading_stopped()) return fail_preparation(PrepareResult::Cancelled);
                source = ::open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NONBLOCK);
                if (source >= 0 || errno != EINTR) break;
            }
            if (source < 0) return fail_preparation(PrepareResult::InvalidInput);
            Descriptor input(source);
            // Path replacement with a no-writer FIFO must not block before the
            // snapshot's descriptor checks. The open is NONBLOCK and this same
            // descriptor (never a Java/path isFile check) must be exact regular.
            struct stat source_info{};
            for (;;) {
                if (loading_stopped()) return fail_preparation(PrepareResult::Cancelled);
                if (::fstat(input.get(), &source_info) == 0) break;
                if (errno != EINTR) return fail_preparation(PrepareResult::InvalidInput);
            }
            if (!S_ISREG(source_info.st_mode) || source_info.st_size <= 0 ||
                    static_cast<std::uint64_t>(source_info.st_size) != profile_.bytes)
                return fail_preparation(PrepareResult::InvalidInput);
            snapshot_ = SealedModelSnapshot::copy_from_fd(input.get(), profile_.bytes, loading_cancel_);
            if (snapshot_.status() != SnapshotStatus::CopiedSealedHashUnverified) {
                const auto result = snapshot_.status() == SnapshotStatus::Cancelled ? PrepareResult::Cancelled :
                    snapshot_.status() == SnapshotStatus::Unavailable ? PrepareResult::SnapshotUnavailable :
                    snapshot_.status() == SnapshotStatus::InvalidInput ? PrepareResult::InvalidInput : PrepareResult::HashError;
                return fail_preparation(result);
            }
            if (loading_stopped()) return fail_preparation(PrepareResult::Cancelled);
            const auto hash = verify(snapshot_.fd(), snapshot_.size(), profile_.sha.data(), loading_cancel_, verifier_context);
            if (loading_stopped() || hash == HashVerification::Cancelled)
                return fail_preparation(PrepareResult::Cancelled);
            if (hash != HashVerification::Verified) {
                const auto result = hash == HashVerification::Mismatch ? PrepareResult::HashMismatch :
                    hash == HashVerification::Unavailable ? PrepareResult::HashUnavailable : PrepareResult::HashError;
                return fail_preparation(result);
            }
            verified_ = true; // exact chosen profile over IMMUTABLE sealed bytes
            return finish_preparing();
#else
            (void)verifier_context;
            return fail_preparation(PrepareResult::SnapshotUnavailable);
#endif
        } catch (const std::bad_alloc&) {
            if (!operation.owns_lock()) { mark_retained(); return PrepareResult::Retained; }
            return preparation_exception(PrepareResult::AllocationFailed);
        } catch (...) {
            if (!operation.owns_lock()) { mark_retained(); return PrepareResult::Retained; }
            return preparation_exception(PrepareResult::HashError);
        }
    }

    CheckedTranscriptResult transcribe(std::size_t samples, PcmWriter fill, void* fill_context,
                                       TranscriptConsumer consume, void* consume_context) noexcept {
        std::shared_ptr<Request> request;
        std::unique_lock<std::mutex> operation;
        try {
            operation = std::unique_lock<std::mutex>(operation_);
            if (samples == 0 || samples > 480000 || !fill || !consume)
                return CheckedTranscriptResult::InvalidInput;
            if (!admission() || !model_ || !verified_) return transcript_not_admitted();
            request = std::make_shared<Request>();
            request->original_samples = samples;
            request->padding_samples = profile_.padding;
            auto observed = state_.load(std::memory_order_acquire);
            if (phase_of(observed) != CheckedPhase::Ready || (observed & kCancelled) ||
                    generation_of(observed) == kMaxGeneration)
                return CheckedTranscriptResult::NotAdmitted;
            request->generation = generation_of(observed) + 1;
            const auto running = encode(CheckedPhase::Running, request->generation, false);
            if (!state_.compare_exchange_strong(observed, running,
                    std::memory_order_acq_rel, std::memory_order_acquire))
                return CheckedTranscriptResult::NotAdmitted;
            std::atomic_store_explicit(&active_request_, request, std::memory_order_release);
            if (request_stopped(*request)) request->cancel.store(true, std::memory_order_release);
            request->pcm.assign(samples + static_cast<std::size_t>(profile_.padding), 0.f);
            if (request_stopped(*request)) return finish_request_known(request, CheckedTranscriptResult::Cancelled, false);
            if (!fill(request->pcm.data() + profile_.padding, samples, fill_context))
                return finish_request_known(request, CheckedTranscriptResult::MarshalingFailed, false);
            if (request_stopped(*request)) return finish_request_known(request, CheckedTranscriptResult::Cancelled, false);
            for (;;) {
                bool native_failed = false;
                request->work_may_live = true; // published BEFORE entering native
                try {
                    starling::ggml::CallAbortScope scope(request_cancelled, request.get());
                    request->text = ops_.transcribe(model_, request->pcm.data(),
                        static_cast<std::int64_t>(request->pcm.size()), 16000, &request->words, &request->word_count);
                    native_failed = request->text == nullptr;
                    if (!native_failed) native_failed = ops_.synchronize(model_) != 1;
                } catch (...) { native_failed = true; }
                if (!native_failed) {
                    request->work_may_live = false; // actual synchronize==1
                    if (request_stopped(*request))
                        return finish_request_known(request, CheckedTranscriptResult::Cancelled, false);
                    // Word validation/JNI allocation are OUTSIDE retry decisions.
                    const bool consumed = consume(*request, consume_context);
                    if (!consumed)
                        return finish_request_known(request, CheckedTranscriptResult::MarshalingFailed, false);
                    if (request_stopped(*request))
                        return finish_request_known(request, CheckedTranscriptResult::Cancelled, false);
                    return finish_request_known(request, CheckedTranscriptResult::Completed, false);
                }
                const auto failed_kind = actual_backend_kind_;
                if (!retire_model()) return CheckedTranscriptResult::Retained;
                request->work_may_live = false; // release==1 AND actual ownerNULL
                if (!free_outputs(*request)) return CheckedTranscriptResult::Retained;
                if (!may_cpu_retry(failed_kind, request->cancel)) {
                    const auto result = request_stopped(*request) ? CheckedTranscriptResult::Cancelled
                                                                 : CheckedTranscriptResult::NativeFailedReleased;
                    return finish_request_known(request, result, true);
                }
                cpu_retry_used_ = true;
                if (request_stopped(*request))
                    return finish_request_known(request, CheckedTranscriptResult::Cancelled, true);
                const auto loaded = load_attempt(STARLING_GGML_DEVICE_CPU, request->cancel);
                if (loaded == Attempt::Retained) return CheckedTranscriptResult::Retained;
                if (loaded != Attempt::Ready)
                    return finish_request_known(request, request_stopped(*request) ? CheckedTranscriptResult::Cancelled
                        : CheckedTranscriptResult::NativeFailedReleased, true);
                if (request_stopped(*request)) {
                    if (!retire_model()) return CheckedTranscriptResult::Retained;
                    return finish_request_known(request, CheckedTranscriptResult::Cancelled, true);
                }
                // Same heap PCM, immutable verified snapshot, native profile and
                // padding. Discarded partial outputs cannot escape or be merged.
            }
        } catch (const std::bad_alloc&) {
            if (!operation.owns_lock()) { mark_retained(); return CheckedTranscriptResult::Retained; }
            return request_exception(request, CheckedTranscriptResult::AllocationFailed);
        } catch (...) {
            if (!operation.owns_lock()) { mark_retained(); return CheckedTranscriptResult::Retained; }
            return request_exception(request, CheckedTranscriptResult::MarshalingFailed);
        }
    }

    bool close_checked() noexcept {
        closing_.store(true, std::memory_order_release);
        (void)abort_checked(true);
        std::unique_lock<std::mutex> operation;
        try {
            operation = std::unique_lock<std::mutex>(operation_);
            if (phase() == CheckedPhase::Closed) return true;
            if (phase() == CheckedPhase::Retained) return false; // never retry unknown retirement
            set_phase(CheckedPhase::Retiring, true);
            // Independent healthy close remains allowed after another ticket's
            // global poison. No new work or reset is allowed through that fence.
            if (!retire_model()) return false;
            auto request = std::atomic_load_explicit(&active_request_, std::memory_order_acquire);
            if (request) {
                request->work_may_live = false;
                if (!free_outputs(*request)) return false;
                std::atomic_store_explicit(&active_request_, std::shared_ptr<Request>{}, std::memory_order_release);
            }
            clear_snapshot();
            set_phase(CheckedPhase::Closed, true);
            return true;
        } catch (...) {
            mark_retained();
            return false;
        }
    }

#ifdef LIP_CHECKED_BARK_TESTING
    // TEST ONLY: bypasses seals/hash using an owned duplicate of synthetic bytes.
    // Never compiled into the checked JNI bridge, never a crypto/model/GPU proof.
    PrepareResult prepare_verified_fd_for_test(int source) noexcept {
        std::unique_lock<std::mutex> operation;
        try {
            operation = std::unique_lock<std::mutex>(operation_);
            if (!begin_preparing()) return prepare_not_admitted();
#if defined(__unix__) || defined(__APPLE__)
            if (loading_stopped()) return fail_preparation(PrepareResult::Cancelled);
            const int duplicate = ::fcntl(source, F_DUPFD_CLOEXEC, 0);
            if (duplicate < 0) return fail_preparation(PrepareResult::InvalidInput);
            test_fd_ = Descriptor(duplicate);
            struct stat info{};
            if (::fstat(test_fd_.get(), &info) != 0 || !S_ISREG(info.st_mode) || info.st_size <= 0 ||
                    static_cast<std::uint64_t>(info.st_size) != profile_.bytes)
                return fail_preparation(PrepareResult::InvalidInput);
            verified_ = true;
            return finish_preparing();
#else
            (void)source;
            return fail_preparation(PrepareResult::SnapshotUnavailable);
#endif
        } catch (...) {
            if (!operation.owns_lock()) { mark_retained(); return PrepareResult::Retained; }
            return preparation_exception(PrepareResult::AllocationFailed);
        }
    }
#endif

private:
    friend class CheckedSessionRegistry;
    struct GlobalState { std::atomic<bool> poison{false}; };
    struct Profile {
        int id = -1;
        std::uint64_t bytes = 0;
        std::array<char, 65> sha{};
        int padding = 0;
    };
    class Descriptor {
    public:
        explicit Descriptor(int fd = -1) noexcept : fd_(fd) {}
        ~Descriptor() noexcept { close_once(fd_); }
        Descriptor(const Descriptor&) = delete;
        Descriptor& operator=(const Descriptor&) = delete;
        Descriptor(Descriptor&& other) noexcept : fd_(std::exchange(other.fd_, -1)) {}
        Descriptor& operator=(Descriptor&& other) noexcept {
            if (this != &other) { close_once(fd_); fd_ = std::exchange(other.fd_, -1); }
            return *this;
        }
        int get() const noexcept { return fd_; }
    private:
        int fd_;
        static void close_once(int fd) noexcept {
#if defined(__unix__) || defined(__APPLE__)
            if (fd >= 0) { const int saved = errno; (void)::close(fd); errno = saved; }
#else
            (void)fd;
#endif
        }
    };
    enum class Attempt { Ready, Released, Retained };
    static constexpr std::uint64_t kCancelled = 1ULL << 8;
    static constexpr std::uint64_t kMaxGeneration = std::numeric_limits<std::uint64_t>::max() >> 16;
    static CheckedPhase phase_of(std::uint64_t value) noexcept { return static_cast<CheckedPhase>(value & 0xffu); }
    static std::uint64_t generation_of(std::uint64_t value) noexcept { return value >> 16; }
    static std::uint64_t encode(CheckedPhase phase, std::uint64_t generation, bool cancelled) noexcept {
        return (generation << 16) | static_cast<std::uint64_t>(phase) | (cancelled ? kCancelled : 0);
    }
    const Profile profile_;
    const CheckedSessionOps ops_;
    const std::shared_ptr<GlobalState> global_;
    const starling_ggml_device_policy initial_policy_;
    std::mutex operation_;
    std::atomic<std::uint64_t> state_{encode(CheckedPhase::Created, 0, false)};
    std::atomic<bool> loading_cancel_{false};
    std::atomic<bool> closing_{false};
    std::shared_ptr<Request> active_request_;
    SealedModelSnapshot snapshot_;
#ifdef LIP_CHECKED_BARK_TESTING
    Descriptor test_fd_;
#endif
    starling_ggml_ctx* model_ = nullptr;
    starling_ggml_ctx* retained_model_identity_ = nullptr;
    starling_ggml_load_report report_{STARLING_GGML_LOAD_REPORT_VERSION, 0, STARLING_GGML_BACKEND_UNKNOWN, 0};
    std::uint32_t actual_backend_kind_ = STARLING_GGML_BACKEND_UNKNOWN;
    bool verified_ = false;
    bool native_load_unsettled_ = false;
    bool cpu_retry_used_ = false;

    CheckedBarkSession(Profile profile, CheckedSessionOps ops, std::shared_ptr<GlobalState> global,
                       starling_ggml_device_policy initial_policy)
        : profile_(profile), ops_(ops), global_(std::move(global)), initial_policy_(initial_policy) {}
    static bool request_cancelled(void* value) {
        return static_cast<Request*>(value)->cancel.load(std::memory_order_acquire);
    }
    static bool load_cancelled(void* value) {
        return static_cast<const std::atomic<bool>*>(value)->load(std::memory_order_acquire);
    }
    void set_phase(CheckedPhase phase, bool cancelled) noexcept {
        auto observed = state_.load(std::memory_order_acquire);
        for (;;) {
            if (phase_of(observed) == CheckedPhase::Retained && phase != CheckedPhase::Retained) return;
            const auto desired = encode(phase, generation_of(observed), cancelled || (observed & kCancelled));
            if (state_.compare_exchange_weak(observed, desired,
                    std::memory_order_acq_rel, std::memory_order_acquire)) return;
        }
    }
    void mark_retained() noexcept {
        global_->poison.store(true, std::memory_order_release);
        set_phase(CheckedPhase::Retained, true);
        loading_cancel_.store(true, std::memory_order_release);
        const auto request = std::atomic_load_explicit(&active_request_, std::memory_order_acquire);
        if (request) request->cancel.store(true, std::memory_order_release);
    }
    bool loading_stopped() const noexcept {
        return closing_.load(std::memory_order_acquire) || loading_cancel_.load(std::memory_order_acquire) ||
            (state_.load(std::memory_order_acquire) & kCancelled) || global_->poison.load(std::memory_order_acquire);
    }
    bool request_stopped(const Request& request) const noexcept {
        const auto state = state_.load(std::memory_order_acquire);
        return closing_.load(std::memory_order_acquire) || request.cancel.load(std::memory_order_acquire) ||
            global_->poison.load(std::memory_order_acquire) ||
            generation_of(state) != request.generation || phase_of(state) != CheckedPhase::Running || (state & kCancelled);
    }
    bool begin_preparing() noexcept {
        if (global_->poison.load(std::memory_order_acquire) || closing_.load(std::memory_order_acquire)) return false;
        auto observed = state_.load(std::memory_order_acquire);
        if (phase_of(observed) != CheckedPhase::Created) return false;
        return state_.compare_exchange_strong(observed,
            encode(CheckedPhase::Preparing, generation_of(observed), (observed & kCancelled) != 0),
            std::memory_order_acq_rel, std::memory_order_acquire);
    }
    PrepareResult prepare_not_admitted() const noexcept {
        return retained_work() ? PrepareResult::Retained : PrepareResult::NotAdmitted;
    }
    CheckedTranscriptResult transcript_not_admitted() const noexcept {
        return retained_work() ? CheckedTranscriptResult::Retained : CheckedTranscriptResult::NotAdmitted;
    }
    int verified_fd() const noexcept {
#ifdef LIP_CHECKED_BARK_TESTING
        if (test_fd_.get() >= 0) return test_fd_.get();
#endif
        return snapshot_.fd();
    }
    void clear_snapshot() noexcept {
        snapshot_ = SealedModelSnapshot{};
#ifdef LIP_CHECKED_BARK_TESTING
        test_fd_ = Descriptor{};
#endif
        verified_ = false;
    }
    bool valid_report() const noexcept {
        if (report_.version != STARLING_GGML_LOAD_REPORT_VERSION || report_.reserved != 0) return false;
        if (report_.flags == 0) return report_.backend_kind == STARLING_GGML_BACKEND_UNKNOWN;
        return report_.flags == STARLING_GGML_LOAD_REPORT_BACKEND_KNOWN &&
            (report_.backend_kind == STARLING_GGML_BACKEND_CPU || report_.backend_kind == STARLING_GGML_BACKEND_ACCELERATOR);
    }
    Attempt load_attempt(starling_ggml_device_policy policy, const std::atomic<bool>& cancel) noexcept {
        if (!verified_ || verified_fd() < 0 || model_) { mark_retained(); return Attempt::Retained; }
        if (cancel.load(std::memory_order_acquire) || closing_.load(std::memory_order_acquire) ||
                global_->poison.load(std::memory_order_acquire) || (state_.load(std::memory_order_acquire) & kCancelled))
            return Attempt::Released; // no new attempt after observed cancellation/fence
        report_ = {STARLING_GGML_LOAD_REPORT_VERSION, 0, STARLING_GGML_BACKEND_UNKNOWN, 0};
        native_load_unsettled_ = true;
        starling_ggml_load_result result = STARLING_GGML_LOAD_UNCONFIRMED;
        try {
            starling::ggml::CallAbortScope scope(load_cancelled, const_cast<std::atomic<bool>*>(&cancel));
            result = ops_.load(STARLING_GGML_PARAKEET_TDT, verified_fd(), policy, &model_, &report_, sizeof(report_));
        } catch (...) { mark_retained(); return Attempt::Retained; }
        if (!valid_report()) { mark_retained(); return Attempt::Retained; }
        if (result == STARLING_GGML_LOAD_READY && model_ &&
                report_.flags == STARLING_GGML_LOAD_REPORT_BACKEND_KNOWN &&
                (policy != STARLING_GGML_DEVICE_CPU || report_.backend_kind == STARLING_GGML_BACKEND_CPU)) {
            actual_backend_kind_ = report_.backend_kind;
            native_load_unsettled_ = false;
            return Attempt::Ready;
        }
        if ((result == STARLING_GGML_LOAD_FAILED_RELEASED ||
                (result == STARLING_GGML_LOAD_REJECTED_NO_ATTEMPT && report_.flags == 0)) && !model_) {
            actual_backend_kind_ = report_.backend_kind;
            native_load_unsettled_ = false;
            return Attempt::Released;
        }
        // status0, quarantine, contradictory owner/status, unknown ready backend:
        // no release(NULL) shortcut may invent retirement or free the snapshot.
        mark_retained();
        return Attempt::Retained;
    }
    bool retire_model() noexcept {
        const auto before = model_;
        try {
            const int result = ops_.release(&model_);
            if (result == 1 && model_ == nullptr) {
                actual_backend_kind_ = STARLING_GGML_BACKEND_UNKNOWN;
                native_load_unsettled_ = false;
                return true;
            }
        } catch (...) {}
        retained_model_identity_ = before ? before : model_;
        mark_retained();
        return false;
    }
    bool may_cpu_retry(std::uint32_t failed_kind, const std::atomic<bool>& cancel) const noexcept {
        return failed_kind == STARLING_GGML_BACKEND_ACCELERATOR && !cpu_retry_used_ &&
            !closing_.load(std::memory_order_acquire) &&
            !model_ && verified_ && verified_fd() >= 0 && !cancel.load(std::memory_order_acquire) &&
            !global_->poison.load(std::memory_order_acquire) && !(state_.load(std::memory_order_acquire) & kCancelled);
    }
    PrepareResult finish_preparing() noexcept {
        if (loading_stopped()) return fail_preparation(PrepareResult::Cancelled);
        auto result = load_attempt(initial_policy_, loading_cancel_);
        if (result == Attempt::Retained) return PrepareResult::Retained;
        if (result == Attempt::Released && may_cpu_retry(actual_backend_kind_, loading_cancel_)) {
            cpu_retry_used_ = true;
            if (loading_stopped()) return fail_preparation(PrepareResult::Cancelled);
            result = load_attempt(STARLING_GGML_DEVICE_CPU, loading_cancel_);
            if (result == Attempt::Retained) return PrepareResult::Retained;
        }
        if (result != Attempt::Ready) return fail_preparation(loading_stopped() ? PrepareResult::Cancelled
                                                                                             : PrepareResult::NativeFailedReleased);
        if (loading_stopped()) {
            if (!retire_model()) return PrepareResult::Retained;
            return fail_preparation(PrepareResult::Cancelled);
        }
        auto observed = state_.load(std::memory_order_acquire);
        if (phase_of(observed) != CheckedPhase::Preparing || (observed & kCancelled) ||
                global_->poison.load(std::memory_order_acquire) ||
                !state_.compare_exchange_strong(observed, encode(CheckedPhase::Ready, generation_of(observed), false),
                    std::memory_order_acq_rel, std::memory_order_acquire)) {
            if (!retire_model()) return PrepareResult::Retained;
            return fail_preparation(PrepareResult::Cancelled);
        }
        return PrepareResult::Ready;
    }
    PrepareResult fail_preparation(PrepareResult result) noexcept {
        if (native_load_unsettled_ || model_) { mark_retained(); return PrepareResult::Retained; }
        clear_snapshot();
        set_phase(CheckedPhase::FailedReleased, true);
        return result;
    }
    PrepareResult preparation_exception(PrepareResult result) noexcept {
        // Caller retains the operation lock through exception cleanup. No
        // concurrent close/reset can observe a falsely idle intermediate owner.
        if (native_load_unsettled_ || model_) { mark_retained(); return PrepareResult::Retained; }
        clear_snapshot();
        set_phase(CheckedPhase::FailedReleased, true);
        return result;
    }
    bool free_outputs(Request& request) noexcept {
        if (request.work_may_live) { mark_retained(); return false; }
        try {
            if (request.text) { ops_.free_text(request.text); request.text = nullptr; }
            if (request.words) { ops_.free_words(request.words); request.words = nullptr; }
            request.word_count = -1;
            return true;
        } catch (...) { mark_retained(); return false; }
    }
    CheckedTranscriptResult finish_request_known(const std::shared_ptr<Request>& request,
            CheckedTranscriptResult result, bool terminal) noexcept {
        if (request->work_may_live || (terminal && model_)) {
            mark_retained();
            return CheckedTranscriptResult::Retained;
        }
        if (!free_outputs(*request)) return CheckedTranscriptResult::Retained;
        const bool stopped = request_stopped(*request);
        std::atomic_store_explicit(&active_request_, std::shared_ptr<Request>{}, std::memory_order_release);
        if (terminal) {
            clear_snapshot();
            set_phase(CheckedPhase::FailedReleased, true);
        } else {
            set_phase(CheckedPhase::Ready, stopped);
        }
        return result;
    }
    CheckedTranscriptResult request_exception(const std::shared_ptr<Request>& request,
                                              CheckedTranscriptResult result) noexcept {
        if (request) {
            if (request->work_may_live) { mark_retained(); return CheckedTranscriptResult::Retained; }
            return finish_request_known(request, result, false);
        }
        return result;
    }
};

class CheckedSessionRegistry final {
public:
    static CheckedSessionRegistry& instance() {
        // Intentionally process-lifetime heap ownership: Java GC and static
        // destruction cannot drop unknown model/snapshot/request/cancel state.
        static CheckedSessionRegistry* registry = new CheckedSessionRegistry;
        return *registry;
    }
    std::uint64_t create_ticket(int profile_id) noexcept {
        CheckedBarkSession::Profile profile;
        if (!trusted_profile(profile_id, profile)) return 0;
        return create(profile, CheckedSessionOps{}, STARLING_GGML_DEVICE_CPU);
    }
    std::shared_ptr<CheckedBarkSession> session(std::uint64_t id) noexcept {
        if (id == 0 || id > kMaxId) return {};
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            const auto found = sessions_.find(id);
            return found == sessions_.end() ? std::shared_ptr<CheckedBarkSession>{} : found->second;
        } catch (...) { return {}; }
    }
    bool admission(std::uint64_t id) noexcept {
        const auto found = session(id);
        return found && found->admission();
    }
    bool retained_work(std::uint64_t id) noexcept {
        const auto found = session(id);
        if (found) return found->retained_work();
        return !known_closed(id) || global_->poison.load(std::memory_order_acquire);
    }
    bool close(std::uint64_t id) noexcept {
        const auto found = session(id);
        if (!found) return known_closed(id);
        if (!found->close_checked()) return false;
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            const auto current = sessions_.find(id);
            if (current == sessions_.end() || current->second != found) return known_closed_locked(id);
            if (id == closed_prefix_ + 1) {
                ++closed_prefix_;
                while (closed_out_of_order_.erase(closed_prefix_ + 1)) ++closed_prefix_;
            } else if (id > closed_prefix_) {
                // If allocation fails, keep the already-Closed session record.
                // Repeated close remains confirmed without a second native call.
                closed_out_of_order_.insert(id);
            }
            sessions_.erase(current); // only AFTER checked release1/ownerNULL
            return true;
        } catch (...) { return true; } // exact Closed session remains retained
    }
    bool known_closed(std::uint64_t id) noexcept {
        if (id == 0 || id > kMaxId) return false;
        try { std::lock_guard<std::mutex> lock(mutex_); return known_closed_locked(id); }
        catch (...) { return false; }
    }
#ifdef LIP_CHECKED_BARK_TESTING
    struct TestProfile { std::uint64_t bytes; const char* sha256; int padding; };
    static CheckedSessionRegistry& isolated_for_test() { return *new CheckedSessionRegistry; }
    std::uint64_t create_test_ticket(TestProfile test, CheckedSessionOps ops,
                                    starling_ggml_device_policy policy = STARLING_GGML_DEVICE_AUTOMATIC) noexcept {
        CheckedBarkSession::Profile profile;
        profile.id = -2;
        profile.bytes = test.bytes;
        profile.padding = test.padding;
        if (!copy_digest(test.sha256, profile.sha) || test.bytes == 0 || test.bytes > SealedModelSnapshot::kMaxBytes ||
                (test.padding != 0 && test.padding != 4000) || !ops.valid() ||
                (policy != STARLING_GGML_DEVICE_CPU && policy != STARLING_GGML_DEVICE_AUTOMATIC)) return 0;
        return create(profile, ops, policy);
    }
#endif
private:
    static constexpr std::uint64_t kMaxId = static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max());
    std::mutex mutex_;
    std::map<std::uint64_t, std::shared_ptr<CheckedBarkSession>> sessions_;
    std::set<std::uint64_t> closed_out_of_order_;
    std::uint64_t next_id_ = 1;
    std::uint64_t closed_prefix_ = 0;
    const std::shared_ptr<CheckedBarkSession::GlobalState> global_ = std::make_shared<CheckedBarkSession::GlobalState>();
    CheckedSessionRegistry() = default;
    static bool copy_digest(const char* source, std::array<char, 65>& destination) noexcept {
        if (!source) return false;
        for (std::size_t i = 0; i < 64; ++i) {
            const char c = source[i];
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
            destination[i] = c;
        }
        if (source[64] != '\0') return false;
        destination[64] = '\0';
        return true;
    }
    static bool trusted_profile(int id, CheckedBarkSession::Profile& profile) noexcept {
        if (id != 0 && id != 1) return false;
        profile.id = id;
        profile.bytes = 364020736;
        profile.padding = id == 0 ? 0 : 4000;
        return copy_digest(id == 0
            ? "d1eec8fc4b9aab0fb6a83ddba6a56a0f93b094d350d069f885a843e31eaee0cf"
            : "2370bf360720b322a54bdc4cfe6198713b347e79ebec7853956ade93618d5986", profile.sha);
    }
    std::uint64_t create(CheckedBarkSession::Profile profile, CheckedSessionOps ops,
                         starling_ggml_device_policy policy) noexcept {
        if (!ops.valid() || global_->poison.load(std::memory_order_acquire)) return 0;
        try {
            auto value = std::shared_ptr<CheckedBarkSession>(new CheckedBarkSession(profile, ops, global_, policy));
            std::lock_guard<std::mutex> lock(mutex_);
            if (global_->poison.load(std::memory_order_acquire) || next_id_ == 0 || next_id_ > kMaxId) return 0;
            const auto id = next_id_;
            const auto inserted = sessions_.emplace(id, std::move(value));
            if (!inserted.second) return 0;
            ++next_id_; // no skipped/failed IDs; max signed ID never reused
            return id;
        } catch (...) { return 0; }
    }
    bool known_closed_locked(std::uint64_t id) const noexcept {
        if (id == 0 || id >= next_id_ || id > kMaxId) return false;
        return id <= closed_prefix_ || closed_out_of_order_.find(id) != closed_out_of_order_.end();
    }
};

} // namespace lip::speech
