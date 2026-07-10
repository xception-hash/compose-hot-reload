// T30 item 1: bridge the toy app's HotSwap to interpreter_jni.cpp's registration export.
//
// interpreter_jni.cpp (compiled verbatim from runtime-client — see CMakeLists.txt) exposes
// exactly one export, JNI-mangled for the production class dev.hotreload.client.HotSwap:
//   jint Java_dev_hotreload_client_HotSwap_nativeRegisterInterpreterJni(JNIEnv*, jobject, jclass)
// The toy app has no dev.hotreload.client.HotSwap, so this file defines the toy-side mangled
// name (dev.hotreload.toy.HotSwap.nativeRegisterInterpreterJni) and forwards. No logic here —
// the hard rule is that interpreter_jni.cpp itself is never modified.
#include <jni.h>

extern "C" JNIEXPORT jint JNICALL
Java_dev_hotreload_client_HotSwap_nativeRegisterInterpreterJni(JNIEnv* env, jobject self,
                                                               jclass jniClass);

extern "C" JNIEXPORT jint JNICALL
Java_dev_hotreload_toy_HotSwap_nativeRegisterInterpreterJni(JNIEnv* env, jobject self,
                                                            jclass jniClass) {
  return Java_dev_hotreload_client_HotSwap_nativeRegisterInterpreterJni(env, self, jniClass);
}
