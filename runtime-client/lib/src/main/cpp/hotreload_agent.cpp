// Hot-reload JVMTI agent (production; grown from the Phase 0 spike agent).
// Attached at runtime via Debug.attachJvmtiAgent on a debuggable app. Provides:
//  - RedefineClasses over N classes atomically (body-only edits)
//  - ART structural redefinition extension (added members)
//  - file-based dex injection into an existing classloader (new classes/lambdas;
//    the in-memory variant is unusable: its memfd trips ART's writable-dex check)
//  - last-error retrieval for diagnostics
// JNI natives bind to dev.hotreload.client.HotSwap (runtime-client AAR).
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <vector>
#include "jvmti.h"

#define TAG "HotReloadAgent"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static jvmtiEnv* g_jvmti = nullptr;
static bool g_canRedefine = false;

// ART extension: structural class redefinition (API 30+). Discovered by id at attach.
using StructuralRedefineFn = jvmtiError (*)(jvmtiEnv*, jint, const jvmtiClassDefinition*);
static StructuralRedefineFn g_structural = nullptr;

// ART extension: add a dex FILE path to an existing classloader.
using AddToLoaderFileFn = jvmtiError (*)(jvmtiEnv*, jobject, const char*);
static AddToLoaderFileFn g_addToLoaderFile = nullptr;

// ART extension: human-readable reason for the last failed jvmti call.
using GetLastErrorFn = jvmtiError (*)(jvmtiEnv*, char**);
static GetLastErrorFn g_lastError = nullptr;

static void discoverExtensions() {
    jint count = 0;
    jvmtiExtensionFunctionInfo* exts = nullptr;
    jvmtiError err = g_jvmti->GetExtensionFunctions(&count, &exts);
    if (err != JVMTI_ERROR_NONE) {
        LOGE("GetExtensionFunctions failed: %d", err);
        return;
    }
    for (jint i = 0; i < count; i++) {
        if (strstr(exts[i].id, "structurally_redefine") != nullptr) {
            g_structural = reinterpret_cast<StructuralRedefineFn>(exts[i].func);
            LOGI("found extension %s", exts[i].id);
        } else if (strcmp(exts[i].id, "com.android.art.classloader.add_to_dex_class_loader") == 0) {
            g_addToLoaderFile = reinterpret_cast<AddToLoaderFileFn>(exts[i].func);
            LOGI("found extension %s", exts[i].id);
        } else if (strstr(exts[i].id, "get_last_error_message") != nullptr) {
            g_lastError = reinterpret_cast<GetLastErrorFn>(exts[i].func);
        }
    }
}

// Redefine `targets` (java.lang.Class[]) from `dexArrays` (byte[][], parallel) in one
// JVMTI call, so multi-class patches apply atomically or not at all.
extern "C" JNIEXPORT jint JNICALL
Java_dev_hotreload_client_HotSwap_nativeRedefine(
        JNIEnv* env, jobject, jobjectArray targets, jobjectArray dexArrays, jboolean structural) {
    if (g_jvmti == nullptr || !g_canRedefine) return -100;
    if (structural && g_structural == nullptr) return -101;

    jsize n = env->GetArrayLength(targets);
    if (n == 0 || n != env->GetArrayLength(dexArrays)) return -104;

    std::vector<jvmtiClassDefinition> defs(static_cast<size_t>(n));
    std::vector<jbyteArray> arrays(static_cast<size_t>(n));
    std::vector<jbyte*> bufs(static_cast<size_t>(n));
    for (jsize i = 0; i < n; i++) {
        arrays[i] = static_cast<jbyteArray>(env->GetObjectArrayElement(dexArrays, i));
        bufs[i] = env->GetByteArrayElements(arrays[i], nullptr);
        defs[i].klass = static_cast<jclass>(env->GetObjectArrayElement(targets, i));
        defs[i].class_byte_count = env->GetArrayLength(arrays[i]);
        defs[i].class_bytes = reinterpret_cast<unsigned char*>(bufs[i]);
    }
    jvmtiError err = structural ? g_structural(g_jvmti, n, defs.data())
                                : g_jvmti->RedefineClasses(n, defs.data());
    for (jsize i = 0; i < n; i++) {
        env->ReleaseByteArrayElements(arrays[i], bufs[i], JNI_ABORT);
    }
    LOGI("%s(%d classes) -> %d", structural ? "structural redefine" : "RedefineClasses", n, err);
    return static_cast<jint>(err);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_hotreload_client_HotSwap_nativeInjectDexFile(JNIEnv* env, jobject, jobject loader, jstring path) {
    if (g_addToLoaderFile == nullptr) return -103;
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    jvmtiError err = g_addToLoaderFile(g_jvmti, loader, cpath);
    LOGI("add_to_dex_class_loader(%s) -> %d", cpath, err);
    env->ReleaseStringUTFChars(path, cpath);
    return static_cast<jint>(err);
}

// Human-readable reason for the most recent failed call, or null.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_hotreload_client_HotSwap_nativeGetLastError(JNIEnv* env, jobject) {
    if (g_lastError == nullptr || g_jvmti == nullptr) return nullptr;
    char* msg = nullptr;
    if (g_lastError(g_jvmti, &msg) != JVMTI_ERROR_NONE || msg == nullptr) return nullptr;
    jstring result = env->NewStringUTF(msg);
    g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(msg));
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_hotreload_client_HotSwap_nativeCanRedefine(JNIEnv*, jobject) {
    return g_canRedefine ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_hotreload_client_HotSwap_nativeHasStructural(JNIEnv*, jobject) {
    return g_structural != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_hotreload_client_HotSwap_nativeHasInjectFile(JNIEnv*, jobject) {
    return g_addToLoaderFile != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Agent_OnAttach(JavaVM* vm, char* /*options*/, void* /*reserved*/) {
    jint res = vm->GetEnv(reinterpret_cast<void**>(&g_jvmti), JVMTI_VERSION_1_2);
    if (res != JNI_OK || g_jvmti == nullptr) {
        LOGE("GetEnv(JVMTI_VERSION_1_2) failed: %d", res);
        return JNI_ERR;
    }
    jvmtiCapabilities caps;
    memset(&caps, 0, sizeof(caps));
    caps.can_redefine_classes = 1;
    caps.can_retransform_classes = 1;
    jvmtiError err = g_jvmti->AddCapabilities(&caps);
    g_canRedefine = (err == JVMTI_ERROR_NONE);
    LOGI("attached; AddCapabilities(redefine, retransform) -> %d", err);
    discoverExtensions();
    return JNI_OK;
}
