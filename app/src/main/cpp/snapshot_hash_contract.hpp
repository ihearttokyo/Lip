#pragma once

#include <atomic>
#include <cstdint>

namespace lip::speech {

// Verification is synchronous; the callback must not retain its borrowed cancel
// reference/context. Only the trusted native profile supplies the expected hash.
enum class HashVerification : std::uint8_t {
    Verified,
    Cancelled,
    Mismatch,
    Unavailable,
    Error,
};
using SnapshotHashVerifier = HashVerification (*)(int fd, std::uint64_t bytes,
    const char* expected_sha256, const std::atomic<bool>& cancel, void* context) noexcept;

} // namespace lip::speech
