#include <jni.h>
#include <dlfcn.h>
#include <sys/socket.h>
#include <linux/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <iomanip>
#include <unwind.h>
#include <sstream>
#include <fstream>
#include <thread>
#include <codecvt>

#include "logging.h"
#include "native_api.h"
#include "KittyMemory/KittyInclude.hpp"

#ifdef __aarch64__
#include "InlineHook/InlineHook.hpp"
#endif

#include "PhantomBridge.h"
#include "hook_compat.h"

struct UnknownArgs {
    char data[1024];
};

jobject j_phantomManager = nullptr;
JavaVM* JVM = nullptr;
PhantomBridge* g_phantomBridge = nullptr;

HookFunType hook_func = nullptr;
UnhookFunType unhook_func = nullptr;

int need_log = 5;
size_t acc_frame_count = 0;
int acc_offset = 0;

int32_t (*obtainBuffer_backup)(void*, void*, void*, void*, void*) = nullptr;
int32_t obtainBuffer_hook(void* v0, void* v1, void* v2, void* v3, void* v4) {
    int32_t status = obtainBuffer_backup ? obtainBuffer_backup(v0, v1, v2, v3, v4) : -1;
    if (v1 != nullptr && g_phantomBridge != nullptr) {
        size_t frameCount = * (size_t*) v1;
        size_t size = * (size_t*) ((uintptr_t) v1 + sizeof(size_t));
        char* raw = * (char**) ((uintptr_t) v1 + sizeof(size_t) * 2);

        if (raw != nullptr && size > 0) {
            g_phantomBridge->overwrite_buffer(raw, size);
        }

        if (need_log > 0) {
            need_log--;
            LOGI("[%zu] Inside obtainBuffer (size = %zu)", acc_frame_count, size);
        }

        acc_frame_count += frameCount;
        acc_offset += size;
    }
    return status;
}

ssize_t (*read_backup)(void*, void*, size_t, bool) = nullptr;
ssize_t read_hook(void* thiz, void* buffer, size_t size, bool blocking) {
    ssize_t bytesRead = read_backup ? read_backup(thiz, buffer, size, blocking) : -1;
    if (bytesRead > 0 && buffer != nullptr && g_phantomBridge != nullptr) {
        g_phantomBridge->overwrite_buffer((char*) buffer, (size_t) bytesRead);
    }
    return bytesRead;
}

int32_t (*aaudio_read_backup)(void*, void*, int32_t, int64_t) = nullptr;
int32_t aaudio_read_hook(void* stream, void* buffer, int32_t numFrames, int64_t timeoutNanoseconds) {
    int32_t framesRead = aaudio_read_backup ? aaudio_read_backup(stream, buffer, numFrames, timeoutNanoseconds) : -1;
    if (framesRead > 0 && buffer != nullptr && g_phantomBridge != nullptr) {
        // Standard 16-bit PCM (2 bytes per sample, 1-2 channels)
        size_t bytes = (size_t) (framesRead * 2);
        g_phantomBridge->overwrite_buffer((char*) buffer, bytes);
    }
    return framesRead;
}

void (*stop_backup)(void*) = nullptr;
void stop_hook(void* thiz) {
    if (stop_backup) {
        stop_backup(thiz);
    }

    if (JVM != nullptr && g_phantomBridge != nullptr) {
        JNIEnv* env = nullptr;
        JVM->AttachCurrentThread(&env, nullptr);
        LOGI("AudioRecord::stop()");
        if (env != nullptr) {
            g_phantomBridge->unload(env);
        }
    }
}

int32_t (*set_backup)(void* thiz, int32_t inputSource, uint32_t sampleRate, uint32_t format,
                      uint32_t channelMask, size_t frameCount, void* callback_ptr, void* callback_refs,
                      uint32_t notificationFrames, bool threadCanCallJava, int32_t sessionId,
                      int transferType, uint32_t flags, uint32_t uid, int32_t pid, void* pAttributes,
                      int selectedDeviceId, int selectedMicDirection, float microphoneFieldDimension,
                      int32_t maxSharedAudioHistoryMs) = nullptr;
int32_t set_hook(void* thiz, int32_t inputSource, uint32_t sampleRate, uint32_t format,
                 uint32_t channelMask, size_t frameCount, void* callback_ptr, void* callback_refs,
                 uint32_t notificationFrames, bool threadCanCallJava, int32_t sessionId,
                 int transferType, uint32_t flags, uint32_t uid, int32_t pid, void* pAttributes,
                 int selectedDeviceId, int selectedMicDirection, float microphoneFieldDimension,
                 int32_t maxSharedAudioHistoryMs) {

    int32_t result = set_backup ? set_backup(thiz, inputSource, sampleRate, format, channelMask, frameCount,
                                callback_ptr, callback_refs, notificationFrames, threadCanCallJava,
                                sessionId, transferType, flags, uid, pid, pAttributes,
                                selectedDeviceId, selectedMicDirection, microphoneFieldDimension,
                                maxSharedAudioHistoryMs) : -1;

    LOGI("AudioRecord::set(...): %d, sampleRate=%u", result, sampleRate);

    if (JVM != nullptr && g_phantomBridge != nullptr) {
        JNIEnv* env = nullptr;
        JVM->AttachCurrentThread(&env, nullptr);
        if (env != nullptr) {
            g_phantomBridge->update_audio_format(env, sampleRate, format, channelMask);
            g_phantomBridge->load(env);
        }
    }

    return result;
}

void on_library_loaded(const char *name, void *handle) {
    if (name == nullptr || hook_func == nullptr) return;

    if (strstr(name, "libaaudio.so") != nullptr) {
        void* sym = dlsym(handle, "AAudioStream_read");
        if (sym != nullptr) {
            LOGI("Found AAudioStream_read at %p", sym);
            hook_func(sym, (void*) aaudio_read_hook, (void**) &aaudio_read_backup);
        }
    }
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
jint JNI_OnLoad(JavaVM *jvm, void*) {
    JNIEnv *env = nullptr;
    jvm->GetEnv((void **)&env, JNI_VERSION_1_6);
    LOGI("JNI_OnLoad");

    JVM = jvm;

    return JNI_VERSION_1_6;
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    if (entries != nullptr) {
        hook_func = entries->hook_func;
        unhook_func = entries->unhook_func;
    }
    return on_library_loaded;
}

extern "C"
JNIEXPORT void JNICALL
Java_tn_amin_phantom_1mic_PhantomManager_nativeHook(JNIEnv *env, jobject thiz) {
    if (j_phantomManager != nullptr) {
        env->DeleteGlobalRef(j_phantomManager);
    }
    j_phantomManager = env->NewGlobalRef(thiz);
    if (g_phantomBridge != nullptr) {
        delete g_phantomBridge;
    }
    g_phantomBridge = new PhantomBridge(j_phantomManager);

    LOGI("Performing native audio hooks");

    if (hook_func == nullptr) {
        LOGI("hook_func is null, skipping native inline hooks");
        return;
    }

    ElfScanner g_libTargetELF = ElfScanner::createWithPath(HookCompat::get_library_name());

    uintptr_t set_symbol = HookCompat::get_set_symbol(g_libTargetELF);
    LOGI("AudioRecord::set at %p", (void*) set_symbol);
    if (set_symbol != 0) {
        hook_func((void*) set_symbol, (void*) set_hook, (void**) &set_backup);
    }

    uintptr_t obtainBuffer_symbol = HookCompat::get_obtainBuffer_symbol(g_libTargetELF);
    LOGI("AudioRecord::obtainBuffer at %p", (void*) obtainBuffer_symbol);
    if (obtainBuffer_symbol != 0) {
        hook_func((void*) obtainBuffer_symbol, (void*) obtainBuffer_hook, (void**) &obtainBuffer_backup);
    }

    uintptr_t read_symbol = HookCompat::get_read_symbol(g_libTargetELF);
    LOGI("AudioRecord::read at %p", (void*) read_symbol);
    if (read_symbol != 0) {
        hook_func((void*) read_symbol, (void*) read_hook, (void**) &read_backup);
    }

    uintptr_t stop_symbol = HookCompat::get_stop_symbol(g_libTargetELF);
    LOGI("AudioRecord::stop at %p", (void*) stop_symbol);
    if (stop_symbol != 0) {
        hook_func((void*) stop_symbol, (void*) stop_hook, (void**) &stop_backup);
    }

    // Try hooking AAudio if already loaded in process
    void* aaudio_handle = dlopen("libaaudio.so", RTLD_NOLOAD);
    if (aaudio_handle != nullptr) {
        void* sym = dlsym(aaudio_handle, "AAudioStream_read");
        if (sym != nullptr) {
            LOGI("Found AAudioStream_read in loaded libaaudio.so at %p", sym);
            hook_func(sym, (void*) aaudio_read_hook, (void**) &aaudio_read_backup);
        }
        dlclose(aaudio_handle);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_tn_amin_phantom_1mic_audio_AudioMaster_onBufferChunkLoaded(JNIEnv *env, jobject thiz,
                                                                jbyteArray buffer_chunk) {
    if (g_phantomBridge == nullptr || buffer_chunk == nullptr) return;

    jbyte* buffer = env->GetByteArrayElements(buffer_chunk, nullptr);
    int size = env->GetArrayLength(buffer_chunk);

    g_phantomBridge->on_buffer_chunk_loaded(buffer, size);

    env->ReleaseByteArrayElements(buffer_chunk, buffer, 0);
}

extern "C"
JNIEXPORT void JNICALL
Java_tn_amin_phantom_1mic_audio_AudioMaster_onLoadDone(JNIEnv *env, jobject thiz) {
    if (g_phantomBridge != nullptr) {
        g_phantomBridge->on_load_done();
    }
}
