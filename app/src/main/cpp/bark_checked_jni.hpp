#pragma once
#ifdef LIP_CHECKED_BARK_TESTING
#error "Production checked JNI must not compile arbitrary-profile/preverified-FD test seams"
#endif

#include "bark_checked_session.hpp"
#include "jni_snapshot_hash.hpp"
#include <jni.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <limits>
#include <string>

namespace lip::speech::checked_jni {

inline void raise(JNIEnv* env, const char* type, const char* message) noexcept {
    if (!env || env->ExceptionCheck()) return;
    jclass error = env->FindClass(type);
    if (!error || env->ExceptionCheck()) return;
    (void)env->ThrowNew(error, message);
    env->DeleteLocalRef(error);
}
inline void fail(JNIEnv* env, const char* message) noexcept {
    raise(env, "java/lang/IllegalStateException", message);
}
inline std::uint64_t id(jlong handle) noexcept { return handle > 0 ? static_cast<std::uint64_t>(handle) : 0; }
inline HashVerification hash_snapshot(int fd, std::uint64_t size, const char* expected,
        const std::atomic<bool>& cancel, void* context) noexcept {
    // Immediate synchronous borrowed context only. Neither core nor registry
    // stores JNIEnv, Java references, callbacks or stack-owned hash state.
    return verify_sealed_sha256(static_cast<JNIEnv*>(context), fd, size, expected, cancel);
}
inline bool path_bytes(JNIEnv* env, jbyteArray input, std::string& output) {
    if (!env || env->ExceptionCheck()) return false;
    if (!input) { raise(env, "java/lang/IllegalArgumentException", "Model path is required"); return false; }
    const jsize count = env->GetArrayLength(input);
    if (env->ExceptionCheck()) return false;
    if (count <= 0 || count > 4096) { raise(env, "java/lang/IllegalArgumentException", "Invalid model path"); return false; }
    output.resize(static_cast<std::size_t>(count));
    env->GetByteArrayRegion(input, 0, count, reinterpret_cast<jbyte*>(output.data()));
    if (env->ExceptionCheck()) return false;
    if (output.find('\0') != std::string::npos) { raise(env, "java/lang/IllegalArgumentException", "Invalid model path"); return false; }
    return true;
}
struct DecodeContext {
    JNIEnv* env;
    jfloatArray input;
    jobjectArray output = nullptr;
};
inline bool fill_pcm(float* output, std::size_t size, void* context) noexcept {
    auto& call = *static_cast<DecodeContext*>(context);
    if (call.env->ExceptionCheck()) return false;
    call.env->GetFloatArrayRegion(call.input, 0, static_cast<jsize>(size), output);
    if (call.env->ExceptionCheck()) return false;
    for (std::size_t i = 0; i < size; ++i) {
        if (!std::isfinite(output[i])) {
            raise(call.env, "java/lang/IllegalArgumentException", "Audio contains a non-finite sample");
            return false;
        }
    }
    return true;
}
inline bool consume_transcript(const CheckedBarkSession::Request& request, void* context) noexcept {
    auto& call = *static_cast<DecodeContext*>(context);
    JNIEnv* env = call.env;
    try {
        if (env->ExceptionCheck() || !request.text) return false;
        constexpr std::size_t kMaxTextBytes = 1024U * 1024U;
        const std::size_t text_size = ::strnlen(request.text, kMaxTextBytes + 1);
        if (text_size > kMaxTextBytes) { fail(env, "Bark transcript exceeds the bounded output limit"); return false; }
        std::int64_t count = request.word_count;
        if (count < -1 || count > 32768) { fail(env, "Invalid Bark word count"); return false; }
        if (text_size != 0 && (!request.words || count <= 0)) { fail(env, "Bark did not return word timestamps"); return false; }
        if (text_size == 0 && count <= 0) count = 0;
        if (count > 0 && !request.words) { fail(env, "Missing Bark word records"); return false; }
        jclass type = env->FindClass("dev/lip/speech/WhisperEngine$Segment");
        if (!type || env->ExceptionCheck()) return false;
        jmethodID constructor = env->GetMethodID(type, "<init>", "([BJJF)V");
        if (!constructor || env->ExceptionCheck()) { env->DeleteLocalRef(type); return false; }
        jobjectArray result = env->NewObjectArray(static_cast<jsize>(count), type, nullptr);
        if (!result || env->ExceptionCheck()) { env->DeleteLocalRef(type); return false; }
        std::size_t previous = 0;
        jlong previous_end = 0;
        const double max_ms = static_cast<double>(request.original_samples) / 16.0;
        for (std::int64_t i = 0; i < count; ++i) {
            const auto& word = request.words[i];
            if (!std::isfinite(word.start_s) || !std::isfinite(word.end_s) || word.start_s < 0 || word.end_s < word.start_s ||
                    word.text_begin < 0 || word.text_end <= word.text_begin ||
                    static_cast<std::size_t>(word.text_end) > text_size ||
                    static_cast<std::size_t>(word.text_begin) < previous) {
                fail(env, "Invalid Bark word offsets or timestamps");
                env->DeleteLocalRef(result); env->DeleteLocalRef(type);
                return false;
            }
            const std::size_t end = i + 1 == count ? text_size : static_cast<std::size_t>(word.text_end);
            jbyteArray utf8 = env->NewByteArray(static_cast<jsize>(end - previous));
            if (!utf8 || env->ExceptionCheck()) { env->DeleteLocalRef(result); env->DeleteLocalRef(type); return false; }
            env->SetByteArrayRegion(utf8, 0, static_cast<jsize>(end - previous),
                                   reinterpret_cast<const jbyte*>(request.text + previous));
            if (env->ExceptionCheck()) { env->DeleteLocalRef(utf8); env->DeleteLocalRef(result); env->DeleteLocalRef(type); return false; }
            // Clamp in double BEFORE converting to jlong: a finite but enormous
            // native timestamp must not cause an out-of-range integer cast.
            const double pad_ms = static_cast<double>(request.padding_samples) / 16.0;
            const double start_value = std::clamp(static_cast<double>(word.start_s) * 1000.0 - pad_ms,
                                                   static_cast<double>(previous_end), max_ms);
            const jlong start = static_cast<jlong>(start_value);
            const double finish_value = std::clamp(static_cast<double>(word.end_s) * 1000.0 - pad_ms,
                                                    static_cast<double>(start), max_ms);
            const jlong finish = static_cast<jlong>(finish_value);
            jobject segment = env->NewObject(type, constructor, utf8, start, finish, 0.f);
            if (!segment || env->ExceptionCheck()) { env->DeleteLocalRef(utf8); env->DeleteLocalRef(result); env->DeleteLocalRef(type); return false; }
            env->SetObjectArrayElement(result, static_cast<jsize>(i), segment);
            env->DeleteLocalRef(segment); env->DeleteLocalRef(utf8);
            if (env->ExceptionCheck()) { env->DeleteLocalRef(result); env->DeleteLocalRef(type); return false; }
            previous = end;
            previous_end = finish;
        }
        env->DeleteLocalRef(type);
        call.output = result; // only a complete local array; core still rechecks cancellation
        return true;
    } catch (const std::bad_alloc&) {
        raise(env, "java/lang/OutOfMemoryError", "Cannot allocate checked transcript output");
        return false;
    } catch (...) {
        fail(env, "Cannot marshal checked transcript output");
        return false;
    }
}
inline bool validate_decode_inputs(JNIEnv* env, jfloatArray pcm, jbyteArray language, jbyteArray prompt,
                                   std::size_t& samples) noexcept {
    if (!env || env->ExceptionCheck()) return false;
    if (!pcm || !language || !prompt) { raise(env, "java/lang/IllegalArgumentException", "Audio, language and prompt are required"); return false; }
    const jsize count = env->GetArrayLength(pcm);
    if (env->ExceptionCheck()) return false;
    if (count < 1 || count > 480000) { raise(env, "java/lang/IllegalArgumentException", "Invalid Bark audio window"); return false; }
    const jsize language_size = env->GetArrayLength(language);
    if (env->ExceptionCheck()) return false;
    if (language_size != 2) { raise(env, "java/lang/IllegalArgumentException", "Bark requires English"); return false; }
    std::array<jbyte, 2> lang{};
    env->GetByteArrayRegion(language, 0, 2, lang.data());
    if (env->ExceptionCheck()) return false;
    if (lang[0] != 'e' || lang[1] != 'n') { raise(env, "java/lang/IllegalArgumentException", "Bark requires English"); return false; }
    const jsize prompt_size = env->GetArrayLength(prompt);
    if (env->ExceptionCheck()) return false;
    if (prompt_size < 0 || prompt_size > 4096) { raise(env, "java/lang/IllegalArgumentException", "Invalid Bark prompt"); return false; }
    std::array<jbyte, 4096> prompt_bytes{};
    if (prompt_size) env->GetByteArrayRegion(prompt, 0, prompt_size, prompt_bytes.data());
    if (env->ExceptionCheck()) return false;
    for (jsize i = 0; i < prompt_size; ++i) {
        if (prompt_bytes[static_cast<std::size_t>(i)] == 0) { raise(env, "java/lang/IllegalArgumentException", "Invalid Bark prompt"); return false; }
    }
    samples = static_cast<std::size_t>(count);
    return true;
}

} // namespace lip::speech::checked_jni

extern "C" JNIEXPORT jlong JNICALL
Java_dev_lip_speech_CheckedBarkNativeCalls_createTicket(JNIEnv* env, jobject, jint profile) {
    if (!env || env->ExceptionCheck()) return 0;
    try {
        if (profile != 0 && profile != 1) {
            lip::speech::checked_jni::raise(env, "java/lang/IllegalArgumentException", "Unsupported native Bark profile");
            return 0;
        }
        const auto ticket = lip::speech::CheckedSessionRegistry::instance().create_ticket(profile);
        if (!ticket) lip::speech::checked_jni::fail(env, "Cannot allocate checked Bark ticket or native work is retained");
        return static_cast<jlong>(ticket);
    } catch (...) { lip::speech::checked_jni::fail(env, "Cannot allocate checked Bark ticket"); return 0; }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_lip_speech_CheckedBarkNativeCalls_loadTicket(JNIEnv* env, jobject, jlong handle, jbyteArray path) {
    try {
        auto session = lip::speech::CheckedSessionRegistry::instance().session(lip::speech::checked_jni::id(handle));
        if (!session) { lip::speech::checked_jni::fail(env, "Unknown checked Bark ticket"); return JNI_FALSE; }
        std::string model_path;
        if (!lip::speech::checked_jni::path_bytes(env, path, model_path)) return JNI_FALSE;
        const auto result = session->prepare_from_path(model_path, lip::speech::checked_jni::hash_snapshot, env);
        if (result == lip::speech::PrepareResult::Cancelled) {
            // Preserve an existing provider/JNI exception; cancellation must not
            // replace it. Otherwise distinguish cancellation from bad input.
            lip::speech::checked_jni::raise(env, "java/util/concurrent/CancellationException", "Checked Bark preparation canceled");
            return JNI_FALSE;
        }
        return result == lip::speech::PrepareResult::Ready && !env->ExceptionCheck() ? JNI_TRUE : JNI_FALSE;
    } catch (const std::bad_alloc&) {
        lip::speech::checked_jni::raise(env, "java/lang/OutOfMemoryError", "Cannot allocate checked model path"); return JNI_FALSE;
    } catch (...) { lip::speech::checked_jni::fail(env, "Checked Bark preparation failed"); return JNI_FALSE; }
}
extern "C" JNIEXPORT void JNICALL
Java_dev_lip_speech_CheckedBarkNativeCalls_cancelTicket(JNIEnv*, jobject, jlong handle) {
    try {
        auto session = lip::speech::CheckedSessionRegistry::instance().session(lip::speech::checked_jni::id(handle));
        if (session) session->cancel_loading();
    } catch (...) {}
}
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_lip_speech_CheckedBarkNativeCalls_admission(JNIEnv*, jobject, jlong handle) {
    try { return lip::speech::CheckedSessionRegistry::instance().admission(lip::speech::checked_jni::id(handle)) ? JNI_TRUE : JNI_FALSE; }
    catch (...) { return JNI_FALSE; }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_lip_speech_CheckedBarkNativeCalls_retainedWork(JNIEnv*, jobject, jlong handle) {
    try { return lip::speech::CheckedSessionRegistry::instance().retained_work(lip::speech::checked_jni::id(handle)) ? JNI_TRUE : JNI_FALSE; }
    catch (...) { return JNI_TRUE; }
}
extern "C" JNIEXPORT void JNICALL
Java_dev_lip_speech_CheckedBarkNativeCalls_abortChecked(JNIEnv* env, jobject, jlong handle, jboolean abort) {
    try {
        auto session = lip::speech::CheckedSessionRegistry::instance().session(lip::speech::checked_jni::id(handle));
        if (abort == JNI_TRUE) { if (session) (void)session->abort_checked(true); return; }
        if (!env || env->ExceptionCheck()) return;
        if (!session || !session->abort_checked(false))
            lip::speech::checked_jni::fail(env, "Checked Bark cannot reset cancellation while work is pending or retained");
    } catch (...) { lip::speech::checked_jni::fail(env, "Checked Bark cancellation reset is unconfirmed"); }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_lip_speech_CheckedBarkNativeCalls_closeChecked(JNIEnv*, jobject, jlong handle) {
    try { return lip::speech::CheckedSessionRegistry::instance().close(lip::speech::checked_jni::id(handle)) ? JNI_TRUE : JNI_FALSE; }
    catch (...) { return JNI_FALSE; }
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_lip_speech_CheckedBarkNativeCalls_transcribeChecked(JNIEnv* env, jobject, jlong handle,
                                                           jfloatArray audio, jbyteArray language, jbyteArray prompt) {
    try {
        auto session = lip::speech::CheckedSessionRegistry::instance().session(lip::speech::checked_jni::id(handle));
        if (!session) { lip::speech::checked_jni::fail(env, "Unknown checked Bark ticket"); return nullptr; }
        std::size_t samples = 0;
        if (!lip::speech::checked_jni::validate_decode_inputs(env, audio, language, prompt, samples)) return nullptr;
        lip::speech::checked_jni::DecodeContext call{env, audio, nullptr};
        const auto result = session->transcribe(samples, lip::speech::checked_jni::fill_pcm, &call,
                                                lip::speech::checked_jni::consume_transcript, &call);
        if (result == lip::speech::CheckedTranscriptResult::Completed && !env->ExceptionCheck()) return call.output;
        if (call.output) env->DeleteLocalRef(call.output);
        if (!env->ExceptionCheck()) {
            if (result == lip::speech::CheckedTranscriptResult::Cancelled)
                lip::speech::checked_jni::raise(env, "java/util/concurrent/CancellationException", "Checked Bark transcription canceled");
            else lip::speech::checked_jni::fail(env, "Checked Bark transcription failed or native work remains retained");
        }
        return nullptr;
    } catch (const std::bad_alloc&) {
        lip::speech::checked_jni::raise(env, "java/lang/OutOfMemoryError", "Cannot allocate checked transcription"); return nullptr;
    } catch (...) { lip::speech::checked_jni::fail(env, "Checked Bark transcription failed"); return nullptr; }
}
