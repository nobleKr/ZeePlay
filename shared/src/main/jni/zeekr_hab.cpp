/*
 * ZeePlay — Zeekr adaptation (zeekr edition only). GPLv3 (see LICENSE).
 *
 * Clean-room JNI shim for the Zeekr 001 HUD (HAB) frame channel.
 *
 * It talks to the *vendor system* library /system/lib64/libHabCamera.so, which
 * is the firmware component that bridges Android to the QNX VM over Qualcomm HAB
 * (Hypervisor Architecture Bridge, habmm_socket_*). The QNX side owns the HUD
 * surface; we hand it frames through libHabCamera's callback model.
 *
 * This is OUR implementation, written against the vendor's exported ABI only.
 * No code or binary from the third-party `com.pc.hablib` / `libhabmanager.so`
 * is used; we merely mirror the same documented vendor entry points:
 *
 *   vendor C entry points (dlsym'd from libHabCamera.so):
 *     void* hab_camera_create(void);
 *     int   hab_camera_open(unsigned int channel);
 *     int   hab_camera_close(void);
 *     int   hab_camera_release(void);
 *     int   registerFrameCallback(int listenerId,
 *                                 com::driving::parking::HabCameraListener*);
 *     int   unregisterFrameCallback(int listenerId);   // 0 = ok, -1 = fail
 *
 * The vendor keeps a singleton HabCameraHandler internally (created by
 * hab_camera_create); registerFrameCallback forwards our listener to it. The
 * handler then calls our listener's virtuals on each HUD buffer cycle. For HUD
 * *output* the relevant virtual is notifyHabWriteBuffer(): the vendor hands us
 * one of its QNX-shared buffers and we copy the latest rendered frame into it.
 *
 * The com::driving::parking::HabCameraListener vtable order was recovered from
 * the firmware binaries (libHabCamera.so + the third-party build), verbatim:
 *   [vdtor][vdtor] receiveFrameBuffer(HCB*)  notifyHabRelease()
 *                  set_image_attribute(HCB*) notifyHabWriteBuffer(HCB*)
 * We reproduce exactly that declaration order so our subclass is ABI-compatible.
 *
 * Built with APP_STL := none and -fno-rtti/-fno-exceptions: no C++ runtime
 * dependency. One process-wide session (the HUD channel is a singleton, as is
 * the vendor's handler), so no operator new / STL containers are used.
 *
 * The NDK platform here is android-24, but the AHardwareBuffer_* APIs are 26+,
 * so we do NOT link them (the api-24 libandroid stub lacks them). They are
 * resolved at runtime via dlsym from libandroid.so — the Zeekr DHU is API 30+.
 *
 * The one device value that remains is the HUD channel id for hab_camera_open()
 * (kListenerId/channel). The HabCameraBuffer AHardwareBuffer-ptr offset (0x20) is
 * firmware-verified and cross-checked against libhabmanager.so; dumpBufferCandidates()
 * still logs the first words on the first run as a belt-and-suspenders check.
 */
#include <jni.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

// APP_STL := none provides no C++ runtime. Our only objects are static (no heap
// use), but a virtual destructor's deleting variant references operator delete,
// so we supply thin malloc/free-backed operators to satisfy the linker.
void* operator new(size_t n)        { return malloc(n); }
void* operator new[](size_t n)      { return malloc(n); }
void  operator delete(void* p) noexcept        { free(p); }
void  operator delete[](void* p) noexcept      { free(p); }
void  operator delete(void* p, size_t) noexcept   { free(p); }
void  operator delete[](void* p, size_t) noexcept { free(p); }

#define TAG "zeekr_hab_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Vendor ABI (declaration-order-sensitive — do not reorder the virtuals).
// All four are non-pure (empty defaults) so the base vtable needs no
// __cxa_pure_virtual, which APP_STL := none would not provide.
// ---------------------------------------------------------------------------
namespace com { namespace driving { namespace parking {

class HabCameraBuffer;  // opaque vendor type; layout probed on-device

class HabCameraListener {
public:
    HabCameraListener() {}
    virtual ~HabCameraListener() {}
    // slot order matches the firmware vtable exactly:
    virtual void receiveFrameBuffer(HabCameraBuffer* buf) { (void) buf; }  // camera-input dir
    virtual void notifyHabRelease() {}
    virtual void set_image_attribute(HabCameraBuffer* buf) { (void) buf; }
    virtual void notifyHabWriteBuffer(HabCameraBuffer* buf) { (void) buf; }
};

}}}  // namespace com::driving::parking

using com::driving::parking::HabCameraBuffer;
using com::driving::parking::HabCameraListener;

namespace {

// AHardwareBuffer_* (API 26+) resolved at runtime from libandroid.so so the
// module links clean against the android-24 stub.
struct Ahb {
    AHardwareBuffer* (*fromHardwareBuffer)(JNIEnv*, jobject);
    void (*acquire)(AHardwareBuffer*);
    void (*release)(AHardwareBuffer*);
    void (*describe)(const AHardwareBuffer*, AHardwareBuffer_Desc*);
    int  (*lock)(AHardwareBuffer*, uint64_t, int32_t, const ARect*, void**);
    int  (*unlock)(AHardwareBuffer*, int32_t*);
    bool ok;

    void load() {
        ok = false;
        void* a = dlopen("libandroid.so", RTLD_NOW | RTLD_GLOBAL);
        if (!a) { LOGE("dlopen libandroid.so: %s", dlerror()); return; }
        fromHardwareBuffer = reinterpret_cast<AHardwareBuffer*(*)(JNIEnv*, jobject)>(
            dlsym(a, "AHardwareBuffer_fromHardwareBuffer"));
        acquire  = reinterpret_cast<void(*)(AHardwareBuffer*)>(dlsym(a, "AHardwareBuffer_acquire"));
        release  = reinterpret_cast<void(*)(AHardwareBuffer*)>(dlsym(a, "AHardwareBuffer_release"));
        describe = reinterpret_cast<void(*)(const AHardwareBuffer*, AHardwareBuffer_Desc*)>(
            dlsym(a, "AHardwareBuffer_describe"));
        lock = reinterpret_cast<int(*)(AHardwareBuffer*, uint64_t, int32_t, const ARect*, void**)>(
            dlsym(a, "AHardwareBuffer_lock"));
        unlock = reinterpret_cast<int(*)(AHardwareBuffer*, int32_t*)>(
            dlsym(a, "AHardwareBuffer_unlock"));
        ok = fromHardwareBuffer && acquire && release && describe && lock && unlock;
        if (!ok) LOGE("AHardwareBuffer_* symbols unresolved");
    }
};

Ahb g_ahb;

// Vendor entry-point signatures.
typedef void* (*hab_create_fn)();
typedef int   (*hab_open_fn)(unsigned int);
typedef int   (*hab_close_fn)();
typedef int   (*hab_release_fn)();
typedef int   (*hab_reg_fn)(int, HabCameraListener*);
typedef int   (*hab_unreg_fn)(int);

// Byte offset of the buffer (AHardwareBuffer/GraphicBuffer) pointer inside a
// HabCameraBuffer. Pinned from the firmware: both set_image_attribute() and
// receiveFrameBuffer() read this descriptor as five 32-bit ints at
// +0x00/0x04/0x0c/0x10/0x14 (width/height/stride/format/size) and a single
// 64-bit pointer at +0x20 (`ldr x, [buf, #0x20]`). AHardwareBuffer* and
// GraphicBuffer* are the same object on Android. Confirmed against the third-party
// libhabmanager.so (HabCameraBuffer ctor zeroes ints at +0x00..+0x1c and stores an
// 8-byte ptr at +0x20; its Listener callbacks read `ldr x,[buf,#0x20]`).
// dumpBufferCandidates() still logs the first words on the first call as a check.
static const size_t       kHcbBufferPtrOffset = 0x20;
// TODO(device): HUD channel id for hab_camera_open() and the listener id.
static const int          kListenerId         = 0;

// Pull an AHardwareBuffer* out of the opaque vendor buffer at the probed offset.
AHardwareBuffer* bufferAhb(HabCameraBuffer* buf) {
    if (!buf) return 0;
    void* raw = *reinterpret_cast<void**>(reinterpret_cast<uint8_t*>(buf) + kHcbBufferPtrOffset);
    return reinterpret_cast<AHardwareBuffer*>(raw);
}

// One-time diagnostic: log the first pointer-sized words of a HabCameraBuffer so
// the real GraphicBuffer-pointer offset can be read off a single on-car run.
void dumpBufferCandidates(HabCameraBuffer* buf) {
    if (!buf) return;
    uintptr_t* w = reinterpret_cast<uintptr_t*>(buf);
    for (size_t i = 0; i < 8; ++i)
        LOGI("HCB[+0x%02zx] = %p", i * sizeof(void*), reinterpret_cast<void*>(w[i]));
}

// Row-aware copy between two locked RGBA buffers, honouring each stride.
void copyRgba(const AHardwareBuffer_Desc& sd, const uint8_t* src,
              const AHardwareBuffer_Desc& dd, uint8_t* dst) {
    const uint32_t w = sd.width < dd.width ? sd.width : dd.width;
    const uint32_t h = sd.height < dd.height ? sd.height : dd.height;
    const size_t sPitch = static_cast<size_t>(sd.stride) * 4;
    const size_t dPitch = static_cast<size_t>(dd.stride) * 4;
    const size_t row = static_cast<size_t>(w) * 4;
    for (uint32_t y = 0; y < h; ++y) memcpy(dst + y * dPitch, src + y * sPitch, row);
}

// Our HUD-output listener: holds the latest frame and blits it on request.
class HudListener : public HabCameraListener {
public:
    HudListener() : frame_(0), dumped_(false) { pthread_mutex_init(&m_, 0); }
    ~HudListener() { setFrame(0); pthread_mutex_destroy(&m_); }

    void receiveFrameBuffer(HabCameraBuffer*) {}  // camera input — unused for HUD
    void notifyHabRelease() { setFrame(0); }
    void set_image_attribute(HabCameraBuffer* buf) {
        if (!dumped_) { dumpBufferCandidates(buf); dumped_ = true; }
    }
    void notifyHabWriteBuffer(HabCameraBuffer* buf) {
        AHardwareBuffer* dst = bufferAhb(buf);
        AHardwareBuffer* src = acquireFrame();
        if (!dst || !src) { if (src) g_ahb.release(src); return; }
        AHardwareBuffer_Desc sd, dd;
        memset(&sd, 0, sizeof sd); memset(&dd, 0, sizeof dd);
        g_ahb.describe(src, &sd);
        g_ahb.describe(dst, &dd);
        void *sp = 0, *dp = 0;
        if (g_ahb.lock(src, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY, -1, 0, &sp) == 0 &&
            g_ahb.lock(dst, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, 0, &dp) == 0) {
            copyRgba(sd, static_cast<uint8_t*>(sp), dd, static_cast<uint8_t*>(dp));
        } else {
            LOGW("notifyHabWriteBuffer: lock failed");
        }
        g_ahb.unlock(dst, 0);
        g_ahb.unlock(src, 0);
        g_ahb.release(src);
    }

    void setFrame(AHardwareBuffer* ahb) {
        pthread_mutex_lock(&m_);
        if (ahb) g_ahb.acquire(ahb);
        if (frame_) g_ahb.release(frame_);
        frame_ = ahb;
        pthread_mutex_unlock(&m_);
    }

private:
    AHardwareBuffer* acquireFrame() {
        pthread_mutex_lock(&m_);
        AHardwareBuffer* f = frame_;
        if (f) g_ahb.acquire(f);
        pthread_mutex_unlock(&m_);
        return f;
    }
    pthread_mutex_t  m_;
    AHardwareBuffer* frame_;
    bool             dumped_;
};

// Process-wide session. The HUD HAB channel and the vendor handler are both
// singletons, so one session is enough and keeps us free of operator new.
struct Session {
    void*          lib;
    hab_create_fn  create;
    hab_open_fn    open;
    hab_close_fn   close;
    hab_release_fn release;
    hab_reg_fn     reg;
    hab_unreg_fn   unreg;
    HudListener    listener;
    bool           up;
    Session() : lib(0), create(0), open(0), close(0), release(0),
                reg(0), unreg(0), up(false) {}

    bool load() {
        if (lib) return create && open && reg;
        lib = dlopen("/system/lib64/libHabCamera.so", RTLD_NOW | RTLD_LOCAL);
        if (!lib) { LOGW("dlopen libHabCamera.so: %s", dlerror()); return false; }
        create  = reinterpret_cast<hab_create_fn >(dlsym(lib, "hab_camera_create"));
        open    = reinterpret_cast<hab_open_fn   >(dlsym(lib, "hab_camera_open"));
        close   = reinterpret_cast<hab_close_fn  >(dlsym(lib, "hab_camera_close"));
        release = reinterpret_cast<hab_release_fn>(dlsym(lib, "hab_camera_release"));
        reg     = reinterpret_cast<hab_reg_fn    >(dlsym(lib, "registerFrameCallback"));
        unreg   = reinterpret_cast<hab_unreg_fn  >(dlsym(lib, "unregisterFrameCallback"));
        if (!create || !open || !reg) {
            LOGE("libHabCamera missing entry points (create=%p open=%p reg=%p)",
                 reinterpret_cast<void*>(create), reinterpret_cast<void*>(open),
                 reinterpret_cast<void*>(reg));
            return false;
        }
        return true;
    }
};

Session          g_session;
pthread_mutex_t  g_lock = PTHREAD_MUTEX_INITIALIZER;

struct Guard {
    Guard() { pthread_mutex_lock(&g_lock); }
    ~Guard() { pthread_mutex_unlock(&g_lock); }
};

}  // namespace

// ---------------------------------------------------------------------------
// JNI surface: ZeekrHabNative.{open,submitFrame,close}. Handle is a non-zero
// token (we keep a single session); 0/<0 means failure.
// ---------------------------------------------------------------------------
extern "C" {

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_hud_zeekr_ZeekrHabNative_open(
        JNIEnv*, jobject, jint /*width*/, jint /*height*/, jint channel) {
    Guard g;
    if (g_session.up) return 1;  // already open
    if (!g_ahb.ok) { g_ahb.load(); if (!g_ahb.ok) return -4; }
    if (!g_session.load()) return -1;
    g_session.create();  // vendor creates/returns its singleton handler
    unsigned int ch = channel >= 0 ? static_cast<unsigned int>(channel) : 0u;
    if (g_session.open(ch) < 0) { LOGW("hab_camera_open(%u) failed", ch); return -2; }
    if (g_session.reg(kListenerId, &g_session.listener) != 0) {
        LOGW("registerFrameCallback failed");
        if (g_session.close) g_session.close();
        return -3;
    }
    g_session.up = true;
    LOGI("HAB HUD channel %u up, listener registered", ch);
    return 1;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_hud_zeekr_ZeekrHabNative_submitFrame(
        JNIEnv* env, jobject, jlong handle, jobject jbuf) {
    if (handle <= 0 || !g_session.up || !g_ahb.ok) return -1;
    AHardwareBuffer* ahb = g_ahb.fromHardwareBuffer(env, jbuf);
    if (!ahb) return -2;
    g_session.listener.setFrame(ahb);  // vendor pulls it on the next notifyHabWriteBuffer
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_hud_zeekr_ZeekrHabNative_close(
        JNIEnv*, jobject, jlong handle) {
    Guard g;
    if (handle <= 0 || !g_session.up) return -1;
    if (g_session.unreg) g_session.unreg(kListenerId);
    if (g_session.close) g_session.close();
    if (g_session.release) g_session.release();
    g_session.listener.setFrame(0);
    g_session.up = false;
    return 0;
}

}  // extern "C"
