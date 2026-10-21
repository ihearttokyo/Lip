// SPDX-License-Identifier: MIT
// Parameter setup adapted from whisper.cpp's Android JNI sample.
// Copyright (c) 2023-2026 The ggml authors. See third_party/whisper.cpp/LICENSE.
#include <jni.h>
#include "whisper.h"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <exception>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <thread>
#include <vector>

namespace {
struct Engine {
    whisper_context *context = nullptr;
    std::atomic<bool> aborted{false};
    ~Engine() { if (context) whisper_free(context); }
};

void fail(JNIEnv *env, const char *type, const char *message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass(type);
    if (cls) { env->ThrowNew(cls, message); env->DeleteLocalRef(cls); }
}

bool bytes(JNIEnv *env, jbyteArray input, int maximum, std::string &output) {
    if (!input || env->GetArrayLength(input) > maximum) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid or oversized speech input");
        return false;
    }
    output.resize(env->GetArrayLength(input));
    if (!output.empty()) env->GetByteArrayRegion(input, 0, output.size(), reinterpret_cast<jbyte *>(output.data()));
    if (env->ExceptionCheck()) return false;
    if (output.find('\0') != std::string::npos) {
        fail(env, "java/lang/IllegalArgumentException", "Speech input contains a NUL byte");
        return false;
    }
    return true;
}

bool should_abort(void *data) { return static_cast<Engine *>(data)->aborted.load(); }
std::once_flag logging;
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_lip_speech_WhisperEngine_nativeOpen(JNIEnv *env, jobject, jbyteArray path) {
    try {
        std::string model_path;
        if (!bytes(env, path, 4096, model_path)) return 0;
        if (model_path.empty()) { fail(env, "java/lang/IllegalArgumentException", "Speech model path is empty"); return 0; }
        std::call_once(logging, [] { whisper_log_set([](ggml_log_level, const char *, void *) {}, nullptr); });
        auto engine = std::make_unique<Engine>();
        auto context_params = whisper_context_default_params();
        context_params.use_gpu = false;
        engine->context = whisper_init_from_file_with_params(model_path.c_str(), context_params);
        if (!engine->context) { fail(env, "java/lang/IllegalStateException", "Cannot load the local speech model"); return 0; }
        if (!whisper_is_multilingual(engine->context)) {
            fail(env, "java/lang/IllegalArgumentException", "Use a multilingual Whisper model");
            return 0;
        }
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::bad_alloc &) {
        fail(env, "java/lang/OutOfMemoryError", "Not enough memory for the local speech model");
        return 0;
    } catch (const std::exception &) {
        fail(env, "java/lang/IllegalStateException", "Cannot initialize local speech inference");
        return 0;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_lip_speech_WhisperEngine_nativeTranscribe(JNIEnv *env, jobject, jlong handle,
                                                 jfloatArray audio, jbyteArray language, jbyteArray prompt) {
    try {
        auto *engine = reinterpret_cast<Engine *>(handle);
        if (!engine || !audio) { fail(env, "java/lang/IllegalStateException", "Speech engine or audio is unavailable"); return nullptr; }
        const jsize length = env->GetArrayLength(audio);
        if (length <= 0 || length > WHISPER_SAMPLE_RATE * 30) {
            fail(env, "java/lang/IllegalArgumentException", "Use 16 kHz mono PCM windows of at most 30 seconds"); return nullptr;
        }
        std::string lang, dictionary;
        if (!bytes(env, language, 2, lang) || !bytes(env, prompt, 3072, dictionary)) return nullptr;
        if (lang != "en" && lang != "ja" && lang != "zh") {
            fail(env, "java/lang/IllegalArgumentException", "Unsupported speech language"); return nullptr;
        }
        std::vector<float> pcm(length);
        env->GetFloatArrayRegion(audio, 0, length, pcm.data());
        if (env->ExceptionCheck()) return nullptr;
        for (float sample : pcm) {
            if (!std::isfinite(sample) || sample < -1.0f || sample > 1.0f) {
                fail(env, "java/lang/IllegalArgumentException", "PCM must be finite and normalized to [-1, 1]"); return nullptr;
            }
        }
        auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = static_cast<int>(std::min(4u, std::max(1u, std::thread::hardware_concurrency())));
        params.translate = false;
        params.no_context = true;
        params.print_progress = false;
        params.print_realtime = false;
        params.print_timestamps = false;
        params.print_special = false;
        params.language = lang.c_str();
        params.initial_prompt = dictionary.empty() ? nullptr : dictionary.c_str();
        params.n_max_text_ctx = 224;
        params.abort_callback = should_abort;
        params.abort_callback_user_data = engine;
        params.encoder_begin_callback = [](whisper_context *, whisper_state *, void *data) { return !should_abort(data); };
        params.encoder_begin_callback_user_data = engine;
        const int status = whisper_full(engine->context, params, pcm.data(), length);
        if (engine->aborted.load()) { fail(env, "java/util/concurrent/CancellationException", "Speech inference canceled"); return nullptr; }
        if (status != 0) { fail(env, "java/lang/IllegalStateException", "Local speech inference failed"); return nullptr; }
        const int count = whisper_full_n_segments(engine->context);
        if (count < 0 || count > 1024) { fail(env, "java/lang/IllegalStateException", "Speech output exceeded its safe limit"); return nullptr; }
        jclass segment_class = env->FindClass("dev/lip/speech/WhisperEngine$Segment");
        if (!segment_class) return nullptr;
        jmethodID constructor = env->GetMethodID(segment_class, "<init>", "([BJJF)V");
        if (!constructor) return nullptr;
        jobjectArray result = env->NewObjectArray(count, segment_class, nullptr);
        if (!result) return nullptr;
        size_t total_bytes = 0;
        for (int index = 0; index < count; ++index) {
            const char *text = whisper_full_get_segment_text(engine->context, index);
            const size_t size = std::strlen(text);
            total_bytes += size;
            if (total_bytes > 32768) { fail(env, "java/lang/IllegalStateException", "Speech output exceeded its safe text limit"); return nullptr; }
            jbyteArray utf8 = env->NewByteArray(static_cast<jsize>(size));
            if (!utf8) return nullptr;
            env->SetByteArrayRegion(utf8, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte *>(text));
            if (env->ExceptionCheck()) return nullptr;
            jobject segment = env->NewObject(segment_class, constructor, utf8,
                static_cast<jlong>(whisper_full_get_segment_t0(engine->context, index) * 10),
                static_cast<jlong>(whisper_full_get_segment_t1(engine->context, index) * 10),
                whisper_full_get_segment_no_speech_prob(engine->context, index));
            env->DeleteLocalRef(utf8);
            if (!segment) return nullptr;
            env->SetObjectArrayElement(result, index, segment);
            env->DeleteLocalRef(segment);
            if (env->ExceptionCheck()) return nullptr;
        }
        env->DeleteLocalRef(segment_class);
        return result;
    } catch (const std::bad_alloc &) {
        fail(env, "java/lang/OutOfMemoryError", "Not enough memory for local speech inference");
        return nullptr;
    } catch (const std::exception &) {
        fail(env, "java/lang/IllegalStateException", "Local speech inference failed");
        return nullptr;
    }
}

// Kotlin holds the state lock for these calls, and the inference lock before freeing.
extern "C" JNIEXPORT void JNICALL
Java_dev_lip_speech_WhisperEngine_nativeAbort(JNIEnv *, jobject, jlong handle, jboolean abort) {
    if (handle) reinterpret_cast<Engine *>(handle)->aborted.store(abort);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_lip_speech_WhisperEngine_nativeClose(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Engine *>(handle);
}
