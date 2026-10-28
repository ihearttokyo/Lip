#pragma once

#include <atomic>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory>
#include <new>
#include <utility>

#if defined(__linux__)
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#if defined(__has_include)
#if __has_include(<linux/memfd.h>)
#include <linux/memfd.h>
#endif
#endif
#endif

#if defined(__linux__) && defined(SYS_memfd_create) && defined(MFD_CLOEXEC) && \
    defined(MFD_ALLOW_SEALING) && defined(F_DUPFD_CLOEXEC) && defined(F_ADD_SEALS) && \
    defined(F_GET_SEALS) && defined(F_SEAL_WRITE) && defined(F_SEAL_GROW) && \
    defined(F_SEAL_SHRINK) && defined(F_SEAL_SEAL)
#define LIP_SPEECH_SEALED_SNAPSHOT_LINUX_AVAILABLE 1
#else
#define LIP_SPEECH_SEALED_SNAPSHOT_LINUX_AVAILABLE 0
#endif

namespace lip::speech {

enum class SnapshotStatus : std::uint8_t {
    CopiedSealedHashUnverified,
    Cancelled,
    InvalidInput,
    Unavailable,
    SystemError,
};

// Initially unintegrated prerequisite, NOT a model-ready/admission token. Only a
// later exact-profile digest of these sealed bytes may establish model identity.
// The factory is synchronous and never stores the borrowed cancellation reference.
class SealedModelSnapshot final {
public:
    using Status = SnapshotStatus;
    static constexpr std::uint64_t kMaxBytes = 512ULL * 1024ULL * 1024ULL;
    static constexpr std::size_t kWorkingBufferBytes = 1024U * 1024U;

    SealedModelSnapshot() noexcept = default;
    ~SealedModelSnapshot() noexcept { close_once(fd_); }
    SealedModelSnapshot(const SealedModelSnapshot&) = delete;
    SealedModelSnapshot& operator=(const SealedModelSnapshot&) = delete;

    SealedModelSnapshot(SealedModelSnapshot&& other) noexcept { take_from(other); }
    SealedModelSnapshot& operator=(SealedModelSnapshot&& other) noexcept {
        if (this != &other) {
            close_once(fd_);
            take_from(other);
        }
        return *this;
    }

    // Borrowed descriptor: do not close it or change descriptor flags. No partial
    // destination is published. A successful descriptor has all four mandatory
    // seals, exact size and CLOEXEC, but its model hash is still UNVERIFIED.
    int fd() const noexcept { return fd_; }
    std::uint64_t size() const noexcept { return size_; }
    Status status() const noexcept { return status_; }
    int error_number() const noexcept { return error_number_; }

    static SealedModelSnapshot copy_from_fd(int source, std::uint64_t expected,
                                            const std::atomic<bool>& cancel) noexcept {
        if (is_cancelled(cancel)) return failure(Status::Cancelled);
        if (source < 0 || expected == 0 || expected > kMaxBytes)
            return failure(Status::InvalidInput);
#if LIP_SPEECH_SEALED_SNAPSHOT_LINUX_AVAILABLE
        if (expected > static_cast<std::uint64_t>(std::numeric_limits<off_t>::max()))
            return failure(Status::InvalidInput);

        // Atomic CLOEXEC duplication fixes descriptor identity without sharing any
        // seek operation. pread below never changes the caller's shared cursor.
        OwnedFd input(retry_fcntl(source, F_DUPFD_CLOEXEC, 0, cancel));
        if (input.get() < 0) {
            const int error = errno;
            return io_failure(cancel, error == EBADF ? Status::InvalidInput : Status::SystemError, error);
        }
        const int input_flags = retry_fcntl(input.get(), F_GETFL, 0, cancel);
        if (input_flags < 0) return io_failure(cancel, Status::SystemError, errno);
        if ((input_flags & O_ACCMODE) == O_WRONLY)
            return failure(Status::InvalidInput);
#ifdef O_PATH
        if ((input_flags & O_PATH) != 0) return failure(Status::InvalidInput);
#endif
        const int input_fd_flags = retry_fcntl(input.get(), F_GETFD, 0, cancel);
        if (input_fd_flags < 0) return io_failure(cancel, Status::SystemError, errno);
        if ((input_fd_flags & FD_CLOEXEC) == 0) return failure(Status::SystemError, EIO);
        struct stat before{};
        if (retry_fstat(input.get(), &before, cancel) < 0)
            return io_failure(cancel, Status::SystemError, errno);
        if (!exact_regular(before, expected)) return failure(Status::InvalidInput);

        // No path, proc-file, temporary disk file, weaker-seal or network route.
        // Missing syscall/seal support is an explicit fail-closed result.
        if (is_cancelled(cancel)) return failure(Status::Cancelled);
        int raw_destination = -1;
        for (;;) {
            if (is_cancelled(cancel)) return failure(Status::Cancelled);
            const long value = ::syscall(SYS_memfd_create, "lip-model-snapshot",
                                         MFD_CLOEXEC | MFD_ALLOW_SEALING);
            if (value >= 0) {
                raw_destination = static_cast<int>(value);
                break;
            }
            const int error = errno;
            if (error == EINTR) continue;
            return io_failure(cancel, unavailable_error(error) ? Status::Unavailable : Status::SystemError, error);
        }
        OwnedFd destination(raw_destination);
        if (is_cancelled(cancel)) return failure(Status::Cancelled);

        const std::size_t buffer_size = expected < kWorkingBufferBytes
            ? static_cast<std::size_t>(expected) : kWorkingBufferBytes;
        std::unique_ptr<unsigned char[]> buffer(new (std::nothrow) unsigned char[buffer_size]);
        if (!buffer) return io_failure(cancel, Status::SystemError, ENOMEM);
        std::uint64_t offset = 0;
        while (offset < expected) {
            if (is_cancelled(cancel)) return failure(Status::Cancelled);
            const std::size_t wanted = static_cast<std::size_t>(
                expected - offset < buffer_size ? expected - offset : buffer_size);
            ssize_t got = -1;
            for (;;) {
                if (is_cancelled(cancel)) return failure(Status::Cancelled);
                got = ::pread(input.get(), buffer.get(), wanted, static_cast<off_t>(offset));
                if (got < 0 && errno == EINTR) continue;
                break;
            }
            if (got < 0) return io_failure(cancel, Status::SystemError, errno);
            if (is_cancelled(cancel)) return failure(Status::Cancelled);
            if (got == 0) return failure(Status::InvalidInput); // observed truncation/incomplete source
            std::size_t written = 0;
            const auto read_size = static_cast<std::size_t>(got);
            while (written < read_size) {
                if (is_cancelled(cancel)) return failure(Status::Cancelled);
                const ssize_t count = ::pwrite(destination.get(), buffer.get() + written,
                    read_size - written, static_cast<off_t>(offset + written));
                if (count < 0) {
                    const int error = errno;
                    if (error == EINTR) continue;
                    return io_failure(cancel, Status::SystemError, error);
                }
                if (count == 0) return io_failure(cancel, Status::SystemError, EIO);
                written += static_cast<std::size_t>(count);
                if (is_cancelled(cancel)) return failure(Status::Cancelled);
            }
            offset += static_cast<std::uint64_t>(got);
        }
        if (is_cancelled(cancel)) return failure(Status::Cancelled);

        // Size changes observed across the copy fail closed. Same-size writes,
        // or changes restored between observations, require the later sealed-hash
        // gate; this primitive never claims to detect those or prove readiness.
        struct stat after{};
        if (retry_fstat(input.get(), &after, cancel) < 0)
            return io_failure(cancel, Status::SystemError, errno);
        if (!exact_regular(after, expected)) return failure(Status::InvalidInput);
        struct stat copied{};
        if (retry_fstat(destination.get(), &copied, cancel) < 0)
            return io_failure(cancel, Status::SystemError, errno);
        if (!exact_regular(copied, expected)) return failure(Status::SystemError, EIO);
        if (is_cancelled(cancel)) return failure(Status::Cancelled);

        constexpr int required = F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL;
        if (retry_fcntl(destination.get(), F_ADD_SEALS, required, cancel) < 0) {
            const int error = errno;
            return io_failure(cancel, unavailable_error(error) ? Status::Unavailable : Status::SystemError, error);
        }
        if (is_cancelled(cancel)) return failure(Status::Cancelled);
        const int seals = retry_fcntl(destination.get(), F_GET_SEALS, 0, cancel);
        if (seals < 0) {
            const int error = errno;
            return io_failure(cancel, unavailable_error(error) ? Status::Unavailable : Status::SystemError, error);
        }
        if ((seals & required) != required) return failure(Status::Unavailable);
        struct stat sealed{};
        if (retry_fstat(destination.get(), &sealed, cancel) < 0)
            return io_failure(cancel, Status::SystemError, errno);
        if (!exact_regular(sealed, expected)) return failure(Status::SystemError, EIO);
        const int fd_flags = retry_fcntl(destination.get(), F_GETFD, 0, cancel);
        if (fd_flags < 0) return io_failure(cancel, Status::SystemError, errno);
        if ((fd_flags & FD_CLOEXEC) == 0) return failure(Status::SystemError, EIO);
        if (is_cancelled(cancel)) return failure(Status::Cancelled); // final pre-publication boundary

        SealedModelSnapshot result;
        result.fd_ = destination.release();
        result.size_ = expected;
        result.status_ = Status::CopiedSealedHashUnverified;
        return result;
#else
        (void)expected;
        return failure(Status::Unavailable);
#endif
    }

private:
    int fd_ = -1;
    std::uint64_t size_ = 0;
    Status status_ = Status::InvalidInput;
    int error_number_ = 0;

    static bool is_cancelled(const std::atomic<bool>& cancel) noexcept {
        return cancel.load(std::memory_order_acquire);
    }
    static void close_once(int fd) noexcept {
#if defined(__linux__)
        if (fd >= 0) {
            const int saved = errno;
            // Linux releases the fd even on close EINTR; retry could close a
            // descriptor reused by another thread. Never retry close here.
            (void)::close(fd);
            errno = saved;
        }
#else
        (void)fd;
#endif
    }
    void take_from(SealedModelSnapshot& other) noexcept {
        fd_ = std::exchange(other.fd_, -1);
        size_ = std::exchange(other.size_, 0);
        status_ = std::exchange(other.status_, Status::InvalidInput);
        error_number_ = std::exchange(other.error_number_, 0);
    }
    static SealedModelSnapshot failure(Status status, int error = 0) noexcept {
        SealedModelSnapshot result;
        result.status_ = status;
        result.error_number_ = error;
        return result;
    }
    static SealedModelSnapshot io_failure(const std::atomic<bool>& cancel, Status status, int error) noexcept {
        return is_cancelled(cancel) ? failure(Status::Cancelled) : failure(status, error);
    }
#if LIP_SPEECH_SEALED_SNAPSHOT_LINUX_AVAILABLE
    class OwnedFd final {
    public:
        explicit OwnedFd(int fd) noexcept : fd_(fd) {}
        ~OwnedFd() noexcept { close_once(fd_); }
        OwnedFd(const OwnedFd&) = delete;
        OwnedFd& operator=(const OwnedFd&) = delete;
        int get() const noexcept { return fd_; }
        int release() noexcept { return std::exchange(fd_, -1); }
    private:
        int fd_;
    };
    static int retry_fcntl(int fd, int command, int argument, const std::atomic<bool>& cancel) noexcept {
        for (;;) {
            if (is_cancelled(cancel)) { errno = ECANCELED; return -1; }
            const int result = ::fcntl(fd, command, argument);
            if (result >= 0 || errno != EINTR) return result;
        }
    }
    static int retry_fstat(int fd, struct stat * value, const std::atomic<bool>& cancel) noexcept {
        for (;;) {
            if (is_cancelled(cancel)) { errno = ECANCELED; return -1; }
            const int result = ::fstat(fd, value);
            if (result == 0 || errno != EINTR) return result;
        }
    }
    static bool exact_regular(const struct stat& value, std::uint64_t expected) noexcept {
        return S_ISREG(value.st_mode) && value.st_size >= 0 &&
               static_cast<std::uint64_t>(value.st_size) == expected;
    }
    static bool unavailable_error(int error) noexcept {
        return error == ENOSYS || error == EINVAL || error == ENOTTY || error == EOPNOTSUPP;
    }
#endif
};

} // namespace lip::speech

#undef LIP_SPEECH_SEALED_SNAPSHOT_LINUX_AVAILABLE
