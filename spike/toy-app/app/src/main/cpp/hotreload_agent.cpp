// Minimal JVMTI agent for the Phase 0 spike.
// Proves: attach on a debuggable app, RedefineClasses (body swap) and the ART
// structural-redefinition extension, both fed with single-class dex bytes.
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include "jvmti.h"

#define TAG "HotReloadAgent"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static jvmtiEnv* g_jvmti = nullptr;

// ART extension: structural class redefinition (API 30+). Discovered by id at attach.
using StructuralRedefineFn = jvmtiError (*)(jvmtiEnv*, jint, const jvmtiClassDefinition*);
static StructuralRedefineFn g_structural = nullptr;

// ART extension: inject dex bytes into an existing classloader (new-class/lambda path).
using AddToLoaderInMemoryFn = jvmtiError (*)(jvmtiEnv*, jobject, const unsigned char*, jint);
static AddToLoaderInMemoryFn g_addToLoader = nullptr;

// ART extension: add a dex FILE path to an existing classloader (fallback for the
// in-memory variant, whose memfd trips ART's writable-dex check on API 36).
using AddToLoaderFileFn = jvmtiError (*)(jvmtiEnv*, jobject, const char*);
static AddToLoaderFileFn g_addToLoaderFile = nullptr;

// ART extension: human-readable reason for the last failed jvmti call.
using GetLastErrorFn = jvmtiError (*)(jvmtiEnv*, char**);
static GetLastErrorFn g_lastError = nullptr;

static void logLastError(const char* what) {
    if (g_lastError == nullptr) return;
    char* msg = nullptr;
    if (g_lastError(g_jvmti, &msg) == JVMTI_ERROR_NONE && msg != nullptr) {
        LOGE("%s last error: %s", what, msg);
        g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(msg));
    }
}

static void discoverExtensions() {
    jint count = 0;
    jvmtiExtensionFunctionInfo* exts = nullptr;
    jvmtiError err = g_jvmti->GetExtensionFunctions(&count, &exts);
    if (err != JVMTI_ERROR_NONE) {
        LOGE("GetExtensionFunctions failed: %d", err);
        return;
    }
    for (jint i = 0; i < count; i++) {
        LOGI("jvmti extension[%d]: %s (%d params)", i, exts[i].id, exts[i].param_count);
        if (strstr(exts[i].id, "structurally_redefine") != nullptr) {
            g_structural = reinterpret_cast<StructuralRedefineFn>(exts[i].func);
            LOGI("  -> using as structural redefine");
        }
        if (strstr(exts[i].id, "add_to_dex_class_loader_in_memory") != nullptr) {
            g_addToLoader = reinterpret_cast<AddToLoaderInMemoryFn>(exts[i].func);
            LOGI("  -> using as in-memory classloader injection");
        }
        if (strcmp(exts[i].id, "com.android.art.classloader.add_to_dex_class_loader") == 0) {
            g_addToLoaderFile = reinterpret_cast<AddToLoaderFileFn>(exts[i].func);
            LOGI("  -> using as file-based classloader injection");
        }
        if (strstr(exts[i].id, "get_last_error_message") != nullptr) {
            g_lastError = reinterpret_cast<GetLastErrorFn>(exts[i].func);
        }
    }
}

static jint redefine(JNIEnv* env, jclass target, jbyteArray dexBytes, bool structural) {
    if (g_jvmti == nullptr) return -100;
    if (structural && g_structural == nullptr) return -101;
    jsize len = env->GetArrayLength(dexBytes);
    jbyte* buf = env->GetByteArrayElements(dexBytes, nullptr);
    jvmtiClassDefinition def;
    def.klass = target;
    def.class_byte_count = static_cast<jint>(len);
    def.class_bytes = reinterpret_cast<unsigned char*>(buf);
    jvmtiError err = structural ? g_structural(g_jvmti, 1, &def)
                                : g_jvmti->RedefineClasses(1, &def);
    env->ReleaseByteArrayElements(dexBytes, buf, JNI_ABORT);
    LOGI("%s -> %d (%d dex bytes)", structural ? "structural redefine" : "RedefineClasses", err, len);
    if (err != JVMTI_ERROR_NONE) logLastError("redefine");
    return static_cast<jint>(err);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_hotreload_toy_HotSwap_nativeRedefine(JNIEnv* env, jobject, jclass target, jbyteArray dexBytes) {
    return redefine(env, target, dexBytes, false);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_hotreload_toy_HotSwap_nativeRedefineStructural(JNIEnv* env, jobject, jclass target, jbyteArray dexBytes) {
    return redefine(env, target, dexBytes, true);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_hotreload_toy_HotSwap_nativeHasStructural(JNIEnv*, jobject) {
    return g_structural != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_hotreload_toy_HotSwap_nativeInjectDex(JNIEnv* env, jobject, jobject loader, jbyteArray dexBytes) {
    if (g_addToLoader == nullptr) return -102;
    jsize len = env->GetArrayLength(dexBytes);
    jbyte* buf = env->GetByteArrayElements(dexBytes, nullptr);
    jvmtiError err = g_addToLoader(g_jvmti, loader,
                                   reinterpret_cast<unsigned char*>(buf), static_cast<jint>(len));
    env->ReleaseByteArrayElements(dexBytes, buf, JNI_ABORT);
    LOGI("add_to_dex_class_loader_in_memory -> %d (%d dex bytes)", err, len);
    if (err != JVMTI_ERROR_NONE) logLastError("injectDex");
    return static_cast<jint>(err);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_hotreload_toy_HotSwap_nativeInjectDexFile(JNIEnv* env, jobject, jobject loader, jstring path) {
    if (g_addToLoaderFile == nullptr) return -103;
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    jvmtiError err = g_addToLoaderFile(g_jvmti, loader, cpath);
    LOGI("add_to_dex_class_loader(%s) -> %d", cpath, err);
    if (err != JVMTI_ERROR_NONE) logLastError("injectDexFile");
    env->ReleaseStringUTFChars(path, cpath);
    return static_cast<jint>(err);
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
    LOGI("attached; AddCapabilities(redefine, retransform) -> %d", err);
    discoverExtensions();
    return JNI_OK;
}
