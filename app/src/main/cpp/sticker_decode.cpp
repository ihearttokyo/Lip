// Bounded pixel decode for Lip's permissionless, off-main isolated worker only.
// API semantics: local NDK30 android/imagedecoder.h and
// https://developer.android.com/ndk/reference/group/image-decoder
#include <jni.h>
#include <android/bitmap.h>
#include <android/data_space.h>
#include <android/imagedecoder.h>
#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <new>
#include <sys/resource.h>
#include <unistd.h>
#include <utility>
#include <vector>

namespace {
enum Code : jint { SUCCESS=0, FORMAT=1, LIMIT=2, CODEC=3, RESOURCE=4, STATE=5 };
constexpr size_t kMaxSource=8u*1024u*1024u;
constexpr int32_t kMaxAxis=4096;
constexpr uint64_t kMaxFramePixels=16777216u;
constexpr int32_t kMaxFrames=256;
constexpr int64_t kMaxDurationMs=60000;
constexpr int64_t kMinFrameMs=10;
constexpr int64_t kMaxFrameMs=10000;
constexpr uint64_t kMaxCanvasWork=67108864u;
constexpr int32_t kTargetEdge=256;
constexpr size_t kMaxRgba=262144u;
constexpr int64_t kNanosPerMs=1000000;

struct Result {
    Code code=STATE;
    jint sourceWidth=0;
    jint sourceHeight=0;
    jint width=0;
    jint height=0;
    jint frames=0;
    jlong durationMs=0;
    std::vector<uint8_t> rgba;
};
Result error(Code code) {
    Result result;
    result.code=code;
    return result;
}

struct DecoderDelete { void operator()(AImageDecoder* p) const noexcept { AImageDecoder_delete(p); } };
struct FrameDelete { void operator()(AImageDecoderFrameInfo* p) const noexcept { AImageDecoderFrameInfo_delete(p); } };
using Decoder=std::unique_ptr<AImageDecoder,DecoderDelete>;
using Frame=std::unique_ptr<AImageDecoderFrameInfo,FrameDelete>;

Code decoderError(int status) {
    switch(status) {
        case ANDROID_IMAGE_DECODER_INTERNAL_ERROR: return RESOURCE;
        case ANDROID_IMAGE_DECODER_BAD_PARAMETER:
        case ANDROID_IMAGE_DECODER_INVALID_STATE:
        case ANDROID_IMAGE_DECODER_FINISHED: return STATE;
        default: return CODEC; // Includes INCOMPLETE and ERROR: never partial success.
    }
}

jlong peakRssKb() noexcept {
    struct rusage usage{};
    // Android/Linux ru_maxrss is the process high-water mark in KiB, not an
    // estimate from pixel sizes and not a physical-device memory guarantee.
    if(getrusage(RUSAGE_SELF,&usage)!=0||usage.ru_maxrss<=0)return 0;
    const auto value=static_cast<uint64_t>(usage.ru_maxrss);
    return static_cast<jlong>(std::min<uint64_t>(value,std::numeric_limits<jlong>::max()));
}

bool isolatedWorkerThread(JNIEnv* env) {
    // A call from a regular app/IME or the worker's main thread is a state error,
    // before any encoded input is copied or passed to a platform codec.
    if(gettid()<=0||gettid()==getpid())return false;
    jclass process=env->FindClass("android/os/Process");
    if(!process||env->ExceptionCheck()) {
        if(env->ExceptionCheck())env->ExceptionClear();
        if(process)env->DeleteLocalRef(process);
        return false;
    }
    const jmethodID method=env->GetStaticMethodID(process,"isIsolated","()Z");
    if(!method||env->ExceptionCheck()) {
        if(env->ExceptionCheck())env->ExceptionClear();
        env->DeleteLocalRef(process);
        return false;
    }
    const jboolean isolated=env->CallStaticBooleanMethod(process,method);
    const bool exception=env->ExceptionCheck();
    if(exception)env->ExceptionClear();
    env->DeleteLocalRef(process);
    return !exception&&isolated==JNI_TRUE;
}

bool acceptedSignature(const std::vector<uint8_t>& bytes) {
    constexpr std::array<uint8_t,8> png{{0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a}};
    return (bytes.size()>=png.size()&&std::memcmp(bytes.data(),png.data(),png.size())==0)||
        (bytes.size()>=12&&std::memcmp(bytes.data(),"RIFF",4)==0&&std::memcmp(bytes.data()+8,"WEBP",4)==0);
}

Result decodeBytes(const std::vector<uint8_t>& bytes) {
    if(bytes.empty())return error(FORMAT);
    if(bytes.size()>kMaxSource)return error(LIMIT);
    if(!acceptedSignature(bytes))return error(FORMAT);

    // The buffer stays alive until the decoder is destroyed. No pinned/critical
    // Java array is held while the platform codec parses or decodes anything.
    AImageDecoder* raw=nullptr;
    int status=AImageDecoder_createFromBuffer(bytes.data(),bytes.size(),&raw);
    if(status!=ANDROID_IMAGE_DECODER_SUCCESS)return error(decoderError(status));
    if(!raw)return error(STATE);
    Decoder decoder(raw);
    const auto* header=AImageDecoder_getHeaderInfo(decoder.get());
    if(!header)return error(STATE);
    const char* mime=AImageDecoderHeaderInfo_getMimeType(header);
    if(!mime||(std::strcmp(mime,"image/png")!=0&&std::strcmp(mime,"image/webp")!=0))return error(FORMAT);
    const int32_t sourceWidth=AImageDecoderHeaderInfo_getWidth(header);
    const int32_t sourceHeight=AImageDecoderHeaderInfo_getHeight(header);
    if(sourceWidth<=0||sourceHeight<=0)return error(FORMAT);
    if(sourceWidth>kMaxAxis||sourceHeight>kMaxAxis)return error(LIMIT);
    const uint64_t sourcePixels=static_cast<uint64_t>(sourceWidth)*static_cast<uint64_t>(sourceHeight);
    if(sourcePixels>kMaxFramePixels)return error(LIMIT);

    int32_t width=sourceWidth;
    int32_t height=sourceHeight;
    const int32_t longest=std::max(sourceWidth,sourceHeight);
    if(longest>kTargetEdge) {
        // Nearest proportional integer pixel; the short edge has a one-pixel
        // minimum. No edge exceeds its source dimension, and there is no crop.
        if(sourceWidth>=sourceHeight) {
            width=kTargetEdge;
            height=std::max<int32_t>(1,static_cast<int32_t>((static_cast<int64_t>(sourceHeight)*kTargetEdge+sourceWidth/2)/sourceWidth));
        } else {
            height=kTargetEdge;
            width=std::max<int32_t>(1,static_cast<int32_t>((static_cast<int64_t>(sourceWidth)*kTargetEdge+sourceHeight/2)/sourceHeight));
        }
    }
    if(width<=0||height<=0||width>kTargetEdge||height>kTargetEdge||width>sourceWidth||height>sourceHeight)return error(STATE);
    const size_t stride=static_cast<size_t>(width)*4u;
    const size_t pixelBytes=stride*static_cast<size_t>(height);
    if(pixelBytes==0||pixelBytes>kMaxRgba)return error(LIMIT);

    status=AImageDecoder_setAndroidBitmapFormat(decoder.get(),ANDROID_BITMAP_FORMAT_RGBA_8888);
    if(status!=ANDROID_IMAGE_DECODER_SUCCESS)return error(decoderError(status));
    status=AImageDecoder_setUnpremultipliedRequired(decoder.get(),false);
    if(status!=ANDROID_IMAGE_DECODER_SUCCESS)return error(decoderError(status));
    status=AImageDecoder_setDataSpace(decoder.get(),ADATASPACE_SRGB);
    if(status!=ANDROID_IMAGE_DECODER_SUCCESS)return error(decoderError(status));
    status=AImageDecoder_setTargetSize(decoder.get(),width,height);
    if(status!=ANDROID_IMAGE_DECODER_SUCCESS)return error(decoderError(status));
    if(AImageDecoder_getMinimumStride(decoder.get())!=stride)return error(STATE);
    // Keep the decoder's default ownership of any prior-disposal cache. The same
    // output canvas is preserved across ALL frames, including blends/partial rects.
    AImageDecoder_setInternallyHandleDisposePrevious(decoder.get(),true);
    Frame frame(AImageDecoderFrameInfo_create());
    if(!frame)return error(RESOURCE);
    const bool animated=AImageDecoder_isAnimated(decoder.get());
    std::vector<uint8_t> canvas(pixelBytes,0);
    std::vector<uint8_t> first;
    int32_t frames=0;
    int64_t durationMs=0;
    while(true) {
        if(frames>=kMaxFrames||sourcePixels*static_cast<uint64_t>(frames+1)>kMaxCanvasWork)return error(LIMIT);
        status=AImageDecoder_getFrameInfo(decoder.get(),frame.get());
        if(status!=ANDROID_IMAGE_DECODER_SUCCESS)return error(decoderError(status));
        const ARect rect=AImageDecoderFrameInfo_getFrameRect(frame.get());
        if(rect.left<0||rect.top<0||rect.right<=rect.left||rect.bottom<=rect.top||
            rect.right>sourceWidth||rect.bottom>sourceHeight)return error(CODEC);
        const uint64_t framePixels=static_cast<uint64_t>(rect.right-rect.left)*static_cast<uint64_t>(rect.bottom-rect.top);
        if(framePixels>kMaxFramePixels)return error(LIMIT);
        const int64_t nanos=AImageDecoderFrameInfo_getDuration(frame.get());
        if(nanos<0||nanos%kNanosPerMs!=0)return error(CODEC);
        const int64_t frameMs=nanos/kNanosPerMs;
        if((animated||frameMs!=0)&&(frameMs<kMinFrameMs||frameMs>kMaxFrameMs))return error(LIMIT);
        if(frameMs>kMaxDurationMs-durationMs)return error(LIMIT);
        durationMs+=frameMs;
        if(AImageDecoder_getMinimumStride(decoder.get())!=stride)return error(STATE);
        status=AImageDecoder_decodeImage(decoder.get(),canvas.data(),stride,pixelBytes);
        if(status!=ANDROID_IMAGE_DECODER_SUCCESS)return error(decoderError(status));
        ++frames;
        if(frames==1)first=canvas;

        // isAnimated=false still has ONE real decoded frame and getFrameInfo may
        // retain an encoded one-frame animation duration. advanceFrame is invalid
        // for non-animated decoders; do not mistake its BAD_PARAMETER for FINISHED.
        if(!animated)break;
        status=AImageDecoder_advanceFrame(decoder.get());
        if(status==ANDROID_IMAGE_DECODER_FINISHED)break;
        if(status!=ANDROID_IMAGE_DECODER_SUCCESS)return error(decoderError(status));
    }
    Result result;
    result.code=SUCCESS;
    result.sourceWidth=sourceWidth;result.sourceHeight=sourceHeight;
    result.width=width;result.height=height;result.frames=frames;
    result.durationMs=durationMs;result.rgba=std::move(first);
    return result;
}

jobject emit(JNIEnv* env,const Result& input) {
    jclass type=env->FindClass("dev/lip/stickers/NativeStickerCodecResult");
    if(!type||env->ExceptionCheck())return nullptr;
    const jmethodID constructor=env->GetMethodID(type,"<init>","(IIIIIIJJ[B)V");
    if(!constructor||env->ExceptionCheck()){env->DeleteLocalRef(type);return nullptr;}
    jbyteArray empty=env->NewByteArray(0);
    if(!empty||env->ExceptionCheck()){env->DeleteLocalRef(type);return nullptr;}
    jbyteArray pixels=empty;
    const Result allocationError=error(RESOURCE);
    const Result invariantError=error(STATE);
    const Result* result=&input;
    if(input.code==SUCCESS) {
        if(input.rgba.empty()||input.rgba.size()>kMaxRgba) {
            result=&invariantError;
        } else {
            pixels=env->NewByteArray(static_cast<jsize>(input.rgba.size()));
            if(!pixels||env->ExceptionCheck()) {
                // A bounded output allocation can still fail under process-wide
                // pressure. Try one zero-pixel resource result, never partial RGBA.
                if(env->ExceptionCheck())env->ExceptionClear();
                if(pixels)env->DeleteLocalRef(pixels);
                pixels=empty;result=&allocationError;
            } else {
                env->SetByteArrayRegion(pixels,0,static_cast<jsize>(input.rgba.size()),reinterpret_cast<const jbyte*>(input.rgba.data()));
                if(env->ExceptionCheck()) {
                    env->ExceptionClear();env->DeleteLocalRef(pixels);
                    pixels=empty;result=&allocationError;
                }
            }
        }
    }
    jvalue args[9]{};
    args[0].i=result->code;args[1].i=result->sourceWidth;args[2].i=result->sourceHeight;
    args[3].i=result->width;args[4].i=result->height;args[5].i=result->frames;
    args[6].j=result->durationMs;args[7].j=peakRssKb();args[8].l=pixels;
    jobject output=env->NewObjectA(type,constructor,args);
    if(pixels!=empty)env->DeleteLocalRef(pixels);
    env->DeleteLocalRef(empty);env->DeleteLocalRef(type);
    // If even the tiny result object cannot be allocated, the pending JVM
    // allocation/linkage exception propagates to the service's static failure gate.
    return output;
}
} // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_dev_lip_stickers_NativeStickerCodec_decode(JNIEnv* env,jobject,jbyteArray bytes) {
    if(!env)return nullptr;
    Result result;
    try {
        if(!isolatedWorkerThread(env)) {
            result=error(STATE);
        } else if(!bytes) {
            result=error(FORMAT);
        } else {
            const jsize length=env->GetArrayLength(bytes);
            if(env->ExceptionCheck()) {
                env->ExceptionClear();result=error(STATE);
            } else if(length<=0) {
                result=error(FORMAT);
            } else if(static_cast<size_t>(length)>kMaxSource) {
                result=error(LIMIT);
            } else {
                std::vector<uint8_t> encoded(static_cast<size_t>(length));
                env->GetByteArrayRegion(bytes,0,length,reinterpret_cast<jbyte*>(encoded.data()));
                if(env->ExceptionCheck()) {
                    env->ExceptionClear();result=error(RESOURCE);
                } else {
                    result=decodeBytes(encoded);
                }
            }
        }
    } catch(const std::bad_alloc&) {
        result=error(RESOURCE);
    } catch(...) {
        result=error(STATE);
    }
    try {
        return emit(env,result);
    } catch(...) {
        // No C++ exception may unwind through JNI. A pending JVM allocation
        // exception is handled by the isolated service; otherwise return null
        // for its invariant-failure path rather than constructing partial data.
        return nullptr;
    }
}
