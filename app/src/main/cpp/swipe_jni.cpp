// Lip's bounded JNI adapter. Upstream MIT neural/beam files remain unmodified.
#include <jni.h>
#include <cmath>
#include <map>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <stdexcept>
#include <type_traits>
#include <utility>
#include "swipekb/swipe_net.hpp"
#include "swipekb/swipe_beam.hpp"
namespace {
constexpr const char* letters="qwertyuiopasdfghjklzxcvbnm";
std::mutex registryMutex;
size_t liveContexts=0;
void require(bool ok){if(!ok)throw std::invalid_argument("Invalid neural swipe input");}
// A slot spans loading, registered lifetime AND a closed-but-in-flight engine.
struct Slot {
    Slot(){std::lock_guard<std::mutex> lock(registryMutex);require(liveContexts<4);++liveContexts;}
    ~Slot(){std::lock_guard<std::mutex> lock(registryMutex);--liveContexts;}
};
struct Engine {
    std::unique_ptr<Slot> slot; // Destroyed last, after all heavy model/trie storage.
    swipekb::SwipeNet model;
    swipekb::SwipeLayout layout;
    swipekb::SwipeLexicon lexicon;
    std::unique_ptr<swipekb::SwipeBeamSearch> decoder;
    float aspect=0;
    std::mutex mutex;
};
std::map<jlong,std::shared_ptr<Engine>> registry;
jlong nextId=1;
void fail(JNIEnv* e,const char* kind,const char* message){if(!e->ExceptionCheck())e->ThrowNew(e->FindClass(kind),message);}
std::shared_ptr<Engine> get(jlong id){std::lock_guard<std::mutex> lock(registryMutex);auto i=registry.find(id);require(i!=registry.end());return i->second;}
std::vector<float> floats(JNIEnv* e,jfloatArray input,int maximum){require(input);const auto n=e->GetArrayLength(input);require(n>0&&n<=maximum);std::vector<float> out(n);e->GetFloatArrayRegion(input,0,n,out.data());require(!e->ExceptionCheck());for(float f:out)require(std::isfinite(f));return out;}
bool wordAllowed(const std::string& word){if(word.empty()||word.size()>48)return false;bool previousLetter=false;for(char c:word){if(c>='a'&&c<='z')previousLetter=true;else if(c=='\''&&previousLetter)previousLetter=false;else return false;}return previousLetter;}
swipekb::SwipeLayout readLayout(JNIEnv* e,jfloatArray rects,jfloat aspect){
    require(std::isfinite(aspect)&&aspect>=.01f&&aspect<=2.f);
    auto geometry=floats(e,rects,104);require(geometry.size()==104);
    std::vector<swipekb::SwipeKey> keys;keys.reserve(26);
    for(int i=0;i<26;i++){
        auto p=geometry.data()+i*4;
        require(p[0]>=0&&p[0]<=1&&p[1]>=0&&p[1]<=1&&p[2]>0&&p[2]<=.5f&&p[3]>0&&p[3]<=.5f);
        for(const auto& k:keys)require(k.cx!=p[0]||k.cy!=p[1]);
        keys.push_back({char32_t(letters[i]),p[0],p[1],p[2],p[3]});
    }
    return swipekb::SwipeLayout(std::move(keys));
}
void replaceLayout(Engine& engine,swipekb::SwipeLayout& replacement,float aspect){
    require(engine.layout.size()==26);
    for(int i=0;i<26;i++)require(engine.layout.keys()[i].label==char32_t(letters[i]));
    static_assert(std::is_nothrow_swappable<swipekb::SwipeLayout>::value,"Layout replacement must be atomic on allocation failure");
    std::swap(engine.layout,replacement);engine.aspect=aspect;
}
std::vector<swipekb::SwipePoint> readTrace(JNIEnv* e,jfloatArray points,jint top){
    require(top>=1&&top<=8);auto values=floats(e,points,4096*3);require(values.size()>=6&&values.size()%3==0);
    std::vector<swipekb::SwipePoint> trace;trace.reserve(values.size()/3);float previous=-1;
    for(size_t i=0;i<values.size();i+=3){float x=values[i],y=values[i+1],t=values[i+2];require(x>=-.25f&&x<=1.25f&&y>=-.25f&&y<=1.25f&&t>=0&&t<=30000&&t>=previous);previous=t;trace.push_back({x,y,t});}
    require(trace.back().t>trace.front().t);return trace;
}
// Caller holds engine.mutex across any layout change and this complete inference.
jobjectArray decodeResult(JNIEnv* e,Engine& engine,const std::vector<swipekb::SwipePoint>& trace,jint top){
    swipekb::SwipeInput input;require(engine.model.buildInput(trace,engine.layout,engine.aspect,input));
    std::vector<float> logp;require(engine.model.forward(input,logp)&&logp.size()==64*27);
    for(float value:logp)require(std::isfinite(value));
    auto candidates=engine.decoder->decode(logp.data(),64,26,swipekb::BeamParams{},top);
    auto result=e->NewObjectArray(candidates.size(),e->FindClass("java/lang/String"),nullptr);if(!result)return nullptr;
    for(size_t i=0;i<candidates.size();i++){auto text=e->NewStringUTF(engine.lexicon.text(candidates[i].word).c_str());if(!text)return nullptr;e->SetObjectArrayElement(result,i,text);e->DeleteLocalRef(text);}return result;
}


}
extern "C" JNIEXPORT jlong JNICALL Java_dev_lip_keyboard_SwipeNative_open(JNIEnv* e,jobject,jbyteArray bytes,jfloatArray rects,jfloat aspect,jobjectArray words,jintArray frequencies){
 try {
    require(bytes&&e->GetArrayLength(bytes)==2115260&&words&&frequencies&&std::isfinite(aspect)&&aspect>=.01f&&aspect<=2.f);
    auto reserved=std::make_unique<Slot>(); // Refuse capacity before model/trie allocation.
    auto layout=readLayout(e,rects,aspect);
    const auto count=e->GetArrayLength(words);require(count>=1&&count<=250000&&e->GetArrayLength(frequencies)==count);
    std::vector<jint> freq(count);e->GetIntArrayRegion(frequencies,0,count,freq.data());require(!e->ExceptionCheck());
    auto engine=std::make_shared<Engine>();engine->slot=std::move(reserved);engine->layout=std::move(layout);engine->aspect=aspect;
    std::vector<uint8_t> model(2115260);e->GetByteArrayRegion(bytes,0,model.size(),reinterpret_cast<jbyte*>(model.data()));require(!e->ExceptionCheck());
    // Kotlin hashes these exact bytes before crossing JNI; never reopen a mutable path.
    require(engine->model.loadBytes(model)&&engine->model.steps()==64&&engine->model.parameterCount()==528802);
    for(int i=0;i<count;i++){
        auto value=static_cast<jstring>(e->GetObjectArrayElement(words,i));require(value&&freq[i]>=0&&freq[i]<=255);
        const auto length=e->GetStringUTFLength(value);require(length>=1&&length<=48);
        const char* utf=e->GetStringUTFChars(value,nullptr);require(utf);std::string word(utf);e->ReleaseStringUTFChars(value,utf);e->DeleteLocalRef(value);
        require(wordAllowed(word)&&engine->layout.wordKeys(word).size()<=40);engine->lexicon.add(word,freq[i],engine->layout);
    }
    engine->lexicon.finish();require(engine->lexicon.wordCount()>0);engine->decoder=std::make_unique<swipekb::SwipeBeamSearch>(engine->lexicon);
    std::lock_guard<std::mutex> lock(registryMutex);require(nextId>0&&nextId<std::numeric_limits<jlong>::max());const jlong id=nextId++;registry.emplace(id,std::move(engine));return id;
 } catch(const std::bad_alloc&){fail(e,"java/lang/IllegalStateException","Cannot allocate neural swipe engine");}catch(...){fail(e,"java/lang/IllegalArgumentException","Invalid neural swipe model, layout or lexicon");}return 0;
}
// Geometry changes cannot change key indices: the admitted English trie uses this exact label order.
extern "C" JNIEXPORT void JNICALL Java_dev_lip_keyboard_SwipeNative_reconfigure(JNIEnv* e,jobject,jlong id,jfloatArray rects,jfloat aspect){
 try{
    auto replacement=readLayout(e,rects,aspect); // Fully validate/allocate BEFORE mutating a live context.
    auto engine=get(id);std::lock_guard<std::mutex> lock(engine->mutex);
    replaceLayout(*engine,replacement,aspect);
 }catch(const std::bad_alloc&){fail(e,"java/lang/IllegalStateException","Cannot allocate neural swipe layout");}catch(...){fail(e,"java/lang/IllegalArgumentException","Invalid neural swipe layout or closed engine");}
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_dev_lip_keyboard_SwipeNative_decode(JNIEnv* e,jobject,jlong id,jfloatArray points,jint top){
 try{
    auto trace=readTrace(e,points,top);
    auto engine=get(id);std::lock_guard<std::mutex> lock(engine->mutex);
    return decodeResult(e,*engine,trace,top);
 }catch(const std::bad_alloc&){fail(e,"java/lang/IllegalStateException","Cannot allocate neural swipe inference");}catch(...){fail(e,"java/lang/IllegalArgumentException","Invalid neural swipe trace or closed engine");}return nullptr;
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_dev_lip_keyboard_SwipeNative_decodeForGeometry(JNIEnv* e,jobject,jlong id,jfloatArray rects,jfloat aspect,jfloatArray points,jint top){
 try{
    auto replacement=readLayout(e,rects,aspect);auto trace=readTrace(e,points,top); // Both validated BEFORE mutation.
    auto engine=get(id);std::lock_guard<std::mutex> lock(engine->mutex);
    replaceLayout(*engine,replacement,aspect);
    return decodeResult(e,*engine,trace,top);
 }catch(const std::bad_alloc&){fail(e,"java/lang/IllegalStateException","Cannot allocate shaped neural swipe inference");}catch(...){fail(e,"java/lang/IllegalArgumentException","Invalid neural swipe layout, trace or closed engine");}return nullptr;
}
extern "C" JNIEXPORT void JNICALL Java_dev_lip_keyboard_SwipeNative_close(JNIEnv*,jobject,jlong id){
    std::shared_ptr<Engine> retired;
    {std::lock_guard<std::mutex> lock(registryMutex);auto i=registry.find(id);if(i!=registry.end()){retired=std::move(i->second);registry.erase(i);}}
    // Destruction releases the slot outside the registry mutex; in-flight shared owners pin it.
}
