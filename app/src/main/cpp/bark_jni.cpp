// Lip's adapter over Starling's MIT C API. Per-context cooperative cancellation.
#include <jni.h>
#include <atomic>
#include <algorithm>
#include <string>
#include <vector>
#include <memory>
#include <cmath>
#include "starling_ggml.h"
#include "runtime/call_abort.hpp"

namespace {
struct Context {
    starling_ggml_ctx* model = nullptr;
    std::atomic<bool> aborted{false};
    int padding = 0;
    ~Context() { starling_ggml_free(model); }
};
void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
bool stopped(void* value) { return static_cast<Context*>(value)->aborted.load(); }
std::string bytes(JNIEnv* env, jbyteArray value) {
    std::string result(env->GetArrayLength(value), '\0');
    env->GetByteArrayRegion(value, 0, result.size(), reinterpret_cast<jbyte*>(result.data()));
    return result;
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_dev_lip_speech_BarkNativeCalls_openWithPadding(JNIEnv* env, jobject, jbyteArray path, jint padding) {
    try {
        if (padding != 0 && padding != 4000) { fail(env, "Unsupported model padding"); return 0; }
        auto context = std::make_unique<Context>();
        context->padding = padding;
        context->model = starling_ggml_load(STARLING_GGML_PARAKEET_TDT, bytes(env, path).c_str());
        if (!context->model) { fail(env, "Cannot load local Bark model"); return 0; }
        return reinterpret_cast<jlong>(context.release());
    } catch (...) { fail(env, "Cannot allocate local Bark engine"); return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_dev_lip_speech_BarkNativeCalls_abort(JNIEnv*, jobject, jlong handle, jboolean abort) {
    if (handle) reinterpret_cast<Context*>(handle)->aborted.store(abort == JNI_TRUE);
}
extern "C" JNIEXPORT void JNICALL
Java_dev_lip_speech_BarkNativeCalls_close(JNIEnv*, jobject, jlong handle) { delete reinterpret_cast<Context*>(handle); }
extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_lip_speech_BarkNativeCalls_transcribe(JNIEnv* env, jobject, jlong handle, jfloatArray audio, jbyteArray language, jbyteArray) {
    try {
        auto* context = reinterpret_cast<Context*>(handle);
        if (!context || bytes(env, language) != "en") { fail(env, "Bark requires English"); return nullptr; }
        const auto size = env->GetArrayLength(audio);
        if (size < 1 || size > 480000) { fail(env, "Invalid Bark audio window"); return nullptr; }
        // Clean uses no added silence; Pro uses the publisher's evaluated 250 ms pad.
        std::vector<float> pcm(size + context->padding, 0.f);
        env->GetFloatArrayRegion(audio, 0, size, pcm.data() + context->padding);
        if (env->ExceptionCheck()) return nullptr;
        starling::ggml::CallAbortScope scope(stopped, context);
        starling_ggml_word* wordsRaw = nullptr;
        int64_t count = 0;
        char* textRaw = starling_ggml_transcribe_pcm_words(context->model, pcm.data(), pcm.size(), 16000, &wordsRaw, &count);
        std::unique_ptr<char, decltype(&starling_ggml_free_string)> text(textRaw, starling_ggml_free_string);
        std::unique_ptr<starling_ggml_word, decltype(&starling_ggml_free_words)> words(wordsRaw, starling_ggml_free_words);
        if (!text || context->aborted.load()) { fail(env, "Bark inference failed or canceled"); return nullptr; }
        const std::string value(text.get());
        const auto type = env->FindClass("dev/lip/speech/WhisperEngine$Segment");
        const auto ctor = env->GetMethodID(type, "<init>", "([BJJF)V");
        // Timestamp-less output cannot satisfy the capture window's finalization contract.
        if ((!words || count <= 0) && !value.empty()) { fail(env, "Bark did not return word timestamps"); return nullptr; }
        if (value.empty() && count <= 0) count = 0;
        if (count < 0 || count > 32768) { fail(env, "Invalid Bark word count"); return nullptr; }
        auto result = env->NewObjectArray(count, type, nullptr);
        if (!result) return nullptr;
        size_t previous = 0;
        jlong previousEnd = 0;
        for (int64_t i = 0; i < count; ++i) {
            const auto& word = words.get()[i];
            if (!std::isfinite(word.start_s) || !std::isfinite(word.end_s) || word.text_begin < 0 || word.text_end < word.text_begin || static_cast<size_t>(word.text_end) > value.size() ||
                static_cast<size_t>(word.text_begin) < previous) { fail(env, "Invalid Bark word offsets"); return nullptr; }
            // Include intervening whitespace, and preserve final punctuation exactly.
            size_t end = (i + 1 == count) ? value.size() : static_cast<size_t>(word.text_end);
            auto utf8 = env->NewByteArray(end - previous);
            if (!utf8) return nullptr;
            env->SetByteArrayRegion(utf8, 0, end - previous, reinterpret_cast<const jbyte*>(value.data() + previous));
            jlong start = std::clamp<jlong>(static_cast<jlong>(word.start_s * 1000) - context->padding / 16, previousEnd, size / 16);
            jlong finish = std::clamp<jlong>(static_cast<jlong>(word.end_s * 1000) - context->padding / 16, start, size / 16);
            auto segment = env->NewObject(type, ctor, utf8, start, finish, 0.f);
            if (!segment) return nullptr;
            env->SetObjectArrayElement(result, i, segment);
            env->DeleteLocalRef(segment); env->DeleteLocalRef(utf8);
            previous = end; previousEnd = finish;
        }
        return result;
    } catch (...) { fail(env, "Bark inference allocation failed"); return nullptr; }
}

#include "bark_checked_jni.hpp"

// Android app-data SELinux denies hardlinks. Use the kernel's atomic NOREPLACE
// rename instead; never emulate it with check-then-rename or overwriting rename.
#include <cerrno>
#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>
extern "C" JNIEXPORT jint JNICALL
Java_dev_lip_speech_BundledModelPublisher_publishNoReplace(JNIEnv* env, jobject,
                                                         jbyteArray from, jbyteArray to) {
    if (!from || !to) return EINVAL;
    try {
        const auto source = bytes(env, from);
        if (env->ExceptionCheck()) return ENOMEM;
        const auto target = bytes(env, to);
        if (env->ExceptionCheck()) return ENOMEM;
        if (source.empty() || target.empty() || source.front() != '/' || target.front() != '/' ||
            source.find('\0') != std::string::npos || target.find('\0') != std::string::npos ||
            source == target || source.substr(0, source.find_last_of('/')) != target.substr(0, target.find_last_of('/')))
            return EINVAL;
#if defined(__NR_renameat2)
        constexpr unsigned int no_replace = 1; // Linux RENAME_NOREPLACE
        if (syscall(__NR_renameat2, AT_FDCWD, source.c_str(), AT_FDCWD, target.c_str(), no_replace) == 0) return 0;
        return errno;
#else
        return ENOSYS; // Fail closed, no non-atomic fallback.
#endif
    } catch (...) { return ENOMEM; }
}
