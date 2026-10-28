#pragma once

#include "snapshot_hash_contract.hpp"
#include "sealed_model_snapshot.hpp"
#include <jni.h>
#include <algorithm>
#include <array>
#include <cerrno>
#include <cstring>
#include <memory>
#include <new>

namespace lip::speech {

// Uses the platform provider, not a new/native-private cryptographic library.
// The synchronous caller retains the sealed owner and cancel state throughout.
inline HashVerification verify_sealed_sha256(JNIEnv* env, int fd, std::uint64_t bytes,
    const char* expected_sha256, const std::atomic<bool>& cancel) noexcept {
    if (cancel.load(std::memory_order_acquire)) return HashVerification::Cancelled;
#if defined(__linux__) && defined(F_GET_SEALS) && defined(F_SEAL_WRITE) && \
    defined(F_SEAL_GROW) && defined(F_SEAL_SHRINK) && defined(F_SEAL_SEAL)
    if (!env || env->ExceptionCheck() || fd < 0 || !bytes ||
        bytes > SealedModelSnapshot::kMaxBytes || !expected_sha256 ||
        strnlen(expected_sha256, 65) != 64) return HashVerification::Error;
    constexpr int required = F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL;
    auto sealed_exact = [&]() noexcept {
        struct stat info{};
        int seals = fcntl(fd, F_GET_SEALS);
        return seals >= 0 && (seals & required) == required &&
            fstat(fd, &info) == 0 && S_ISREG(info.st_mode) && info.st_size > 0 &&
            static_cast<std::uint64_t>(info.st_size) == bytes;
    };
    if (!sealed_exact()) return HashVerification::Unavailable;
    std::array<unsigned char, 32> expected{};
    auto nibble = [](char c) noexcept -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    };
    for (std::size_t i = 0; i < expected.size(); ++i) {
        int a = nibble(expected_sha256[2*i]), b = nibble(expected_sha256[2*i+1]);
        if (a < 0 || b < 0) return HashVerification::Error;
        expected[i] = static_cast<unsigned char>((a << 4) | b);
    }
    if (env->PushLocalFrame(12) < 0) return HashVerification::Error;
    struct Frame {
        JNIEnv* env;
        ~Frame() noexcept { env->PopLocalFrame(nullptr); }
    } frame{env};
    auto type = env->FindClass("java/security/MessageDigest");
    if (!type || env->ExceptionCheck()) return HashVerification::Error;
    auto get_instance = env->GetStaticMethodID(type, "getInstance",
        "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    if (!get_instance || env->ExceptionCheck()) return HashVerification::Error;
    auto algorithm = env->NewStringUTF("SHA-256");
    if (!algorithm || env->ExceptionCheck()) return HashVerification::Error;
    auto digest = env->CallStaticObjectMethod(type, get_instance, algorithm);
    if (!digest || env->ExceptionCheck()) return HashVerification::Error;
    auto update = env->GetMethodID(type, "update", "([BII)V");
    if (!update || env->ExceptionCheck()) return HashVerification::Error;
    auto finish = env->GetMethodID(type, "digest", "()[B");
    if (!finish || env->ExceptionCheck()) return HashVerification::Error;
    const std::size_t capacity = static_cast<std::size_t>(std::min<std::uint64_t>(
        bytes, SealedModelSnapshot::kWorkingBufferBytes));
    std::unique_ptr<jbyte[]> buffer(new (std::nothrow) jbyte[capacity]);
    if (!buffer) return HashVerification::Error;
    auto chunk = env->NewByteArray(static_cast<jsize>(capacity));
    if (!chunk || env->ExceptionCheck()) return HashVerification::Error;
    std::uint64_t offset = 0;
    while (offset < bytes) {
        if (cancel.load(std::memory_order_acquire)) return HashVerification::Cancelled;
        const std::size_t wanted = static_cast<std::size_t>(std::min<std::uint64_t>(
            bytes - offset, capacity));
        ssize_t got = pread(fd, buffer.get(), wanted, static_cast<off_t>(offset));
        if (got < 0 && errno == EINTR) continue;
        if (got <= 0 || static_cast<std::size_t>(got) > wanted) return HashVerification::Error;
        if (cancel.load(std::memory_order_acquire)) return HashVerification::Cancelled;
        env->SetByteArrayRegion(chunk, 0, static_cast<jsize>(got), buffer.get());
        if (env->ExceptionCheck()) return HashVerification::Error;
        env->CallVoidMethod(digest, update, chunk, 0, static_cast<jint>(got));
        if (env->ExceptionCheck()) return HashVerification::Error;
        if (cancel.load(std::memory_order_acquire)) return HashVerification::Cancelled;
        offset += static_cast<std::uint64_t>(got);
    }
    auto result = static_cast<jbyteArray>(env->CallObjectMethod(digest, finish));
    if (!result || env->ExceptionCheck()) return HashVerification::Error;
    if (env->GetArrayLength(result) != 32 || env->ExceptionCheck()) return HashVerification::Error;
    std::array<jbyte, 32> actual{};
    env->GetByteArrayRegion(result, 0, 32, actual.data());
    if (env->ExceptionCheck()) return HashVerification::Error;
    if (cancel.load(std::memory_order_acquire)) return HashVerification::Cancelled;
    if (!sealed_exact()) return HashVerification::Unavailable;
    unsigned int difference = 0;
    for (std::size_t i = 0; i < actual.size(); ++i)
        difference |= static_cast<unsigned char>(actual[i]) ^ expected[i];
    return difference ? HashVerification::Mismatch : HashVerification::Verified;
#else
    (void)env; (void)fd; (void)bytes; (void)expected_sha256;
    return HashVerification::Unavailable;
#endif
}

} // namespace lip::speech
