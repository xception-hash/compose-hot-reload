// Interpreter JNI dispatch for the on-device LiveEdit bytecode interpreter (T27 step 1).
//
// Port of AOSP tools-base deploy/agent/native/jni_dispatch/jni_dispatch.cc (Apache-2.0; pin in
// third_party/PINNED.txt). Provides the 12 natives the interpreter's
// com.android.tools.deploy.interpreter.JNI class declares: the invokespecial* family (used by
// AndroidEval for super.m() calls — argument unboxing + CallNonvirtual*MethodA) and
// enter/exitMonitor (for `synchronized` in interpreted bodies).
//
// Differences from the AOSP original: it uses android/log.h instead of the AOSP event/log
// helpers, and the dispatch functions are file-local and bound via an explicit RegisterNatives
// (invoked lazily from Kotlin AFTER interp.dex is injected — the JNI class does not exist before
// that) rather than by JNI name-mangling, so nothing leaks into this .so's dynamic symbol table
// except the one HotSwap export. The unbox-flag constants are byte-identical to the original (and
// thus to AndroidEval.java) — do not change them independently.
#include <jni.h>
#include <android/log.h>

#include <string>

#define ITAG "HotReloadInterp"
#define ILOGI(...) __android_log_print(ANDROID_LOG_INFO, ITAG, __VA_ARGS__)
#define ILOGE(...) __android_log_print(ANDROID_LOG_ERROR, ITAG, __VA_ARGS__)

namespace {

// Must match AndroidEval.java's unbox flags (via jni_dispatch.cc). One flag per arg in the
// parallel `unbox` int[]; kNoUnbox passes the boxed reference through untouched.
constexpr int32_t kNoUnbox = 0;
constexpr int32_t kUnboxBool = 1 << 0;
constexpr int32_t kUnboxByte = 1 << 1;
constexpr int32_t kUnboxChar = 1 << 2;
constexpr int32_t kUnboxShort = 1 << 3;
constexpr int32_t kUnboxInt = 1 << 4;
constexpr int32_t kUnboxLong = 1 << 5;
constexpr int32_t kUnboxFloat = 1 << 6;
constexpr int32_t kUnboxDouble = 1 << 7;

void ThrowIllegalStateException(JNIEnv* env, const char* text) {
  jclass clazz = env->FindClass("java/lang/IllegalStateException");
  env->ThrowNew(clazz, text);
}

std::string GetClassName(JNIEnv* env, jclass cls) {
  jclass clazz = env->FindClass("java/lang/Class");
  jmethodID mid = env->GetMethodID(clazz, "getName", "()Ljava/lang/String;");
  jstring name = (jstring)env->CallObjectMethod(cls, mid);
  const char* string = env->GetStringUTFChars(name, nullptr);
  std::string ret(string);
  env->ReleaseStringUTFChars(name, string);
  return ret;
}

bool CheckClass(JNIEnv* env, jclass expected, jobject obj) {
  if (!env->IsInstanceOf(obj, expected)) {
    std::string msg = "Unbox expected ";
    msg += GetClassName(env, expected);
    msg += " but got ";
    msg += GetClassName(env, env->GetObjectClass(obj));
    ThrowIllegalStateException(env, msg.c_str());
    return false;
  }
  return true;
}

#define Unbox(METHOD_NAME, JAVA_TYPE, JAVA_METHOD, JAVA_DESC, JNI_TYPE, \
              JNI_METHOD)                                               \
  inline bool METHOD_NAME(JNIEnv* env, jobject obj, JNI_TYPE* v) {      \
    jclass cls = env->FindClass(JAVA_TYPE);                             \
    if (!CheckClass(env, cls, obj)) {                                   \
      return false;                                                     \
    }                                                                   \
    jmethodID method = env->GetMethodID(cls, JAVA_METHOD, JAVA_DESC);   \
    *v = env->JNI_METHOD(obj, method);                                  \
    return true;                                                        \
  }

Unbox(ToBool, "java/lang/Boolean", "booleanValue", "()Z", jboolean, CallBooleanMethod);
Unbox(ToChar, "java/lang/Character", "charValue", "()C", jchar, CallCharMethod);
Unbox(ToByte, "java/lang/Byte", "byteValue", "()B", jbyte, CallByteMethod);
Unbox(ToShort, "java/lang/Short", "shortValue", "()S", jshort, CallShortMethod);
Unbox(ToInt, "java/lang/Integer", "intValue", "()I", jint, CallIntMethod);
Unbox(ToLong, "java/lang/Long", "longValue", "()J", jlong, CallLongMethod);
Unbox(ToFloat, "java/lang/Float", "floatValue", "()F", jfloat, CallFloatMethod);
Unbox(ToDouble, "java/lang/Double", "doubleValue", "()D", jdouble, CallDoubleMethod);

// Resolves the target method and unboxes the argument array into a jvalue[] per the parallel
// unbox flags, throwing (and reporting via Get()==false) on any mismatch.
class CallInfo {
 public:
  CallInfo(JNIEnv* env, jclass cls, jstring method, jstring desc,
           jobjectArray args, jintArray unbox)
      : env_(env),
        cls_(cls),
        methodName_(method),
        methodDesc_(desc),
        args_(args),
        unboxArray_(unbox) {}

  bool Get() {
    method_name_ = env_->GetStringUTFChars(methodName_, nullptr);
    method_desc_ = env_->GetStringUTFChars(methodDesc_, nullptr);
    unboxes_ = env_->GetIntArrayElements(unboxArray_, nullptr);
    if (!GetMethodID()) return false;
    if (!PrepareArguments()) return false;
    return true;
  }

  ~CallInfo() {
    delete[] values_;
    env_->ReleaseStringUTFChars(methodName_, method_name_);
    env_->ReleaseStringUTFChars(methodDesc_, method_desc_);
    env_->ReleaseIntArrayElements(unboxArray_, unboxes_, JNI_ABORT);
  }

  bool GetMethodID() {
    mid_ = env_->GetMethodID(cls_, method_name_, method_desc_);
    return mid_ != nullptr;  // an exception is already pending on failure
  }

  bool PrepareArguments() {
    int num_unbox = env_->GetArrayLength(unboxArray_);
    int num_args = env_->GetArrayLength(args_);
    if (num_args != num_unbox) {
      std::string msg("Error: '");
      msg += method_name_;
      msg += method_desc_;
      msg += "' args size '" + std::to_string(num_args);
      msg += "' does not match unbox size '" + std::to_string(num_unbox) + "'";
      ThrowIllegalStateException(env_, msg.c_str());
      return false;
    }

    values_ = new jvalue[num_args];
    for (int i = 0; i < num_args; i++) {
      jobject arg = env_->GetObjectArrayElement(args_, i);
      switch (unboxes_[i]) {
        case kUnboxBool:
          if (!ToBool(env_, arg, &values_[i].z)) return false;
          break;
        case kUnboxInt:
          if (!ToInt(env_, arg, &values_[i].i)) return false;
          break;
        case kUnboxChar:
          if (!ToChar(env_, arg, &values_[i].c)) return false;
          break;
        case kUnboxByte:
          if (!ToByte(env_, arg, &values_[i].b)) return false;
          break;
        case kUnboxShort:
          if (!ToShort(env_, arg, &values_[i].s)) return false;
          break;
        case kUnboxLong:
          if (!ToLong(env_, arg, &values_[i].j)) return false;
          break;
        case kUnboxFloat:
          if (!ToFloat(env_, arg, &values_[i].f)) return false;
          break;
        case kUnboxDouble:
          if (!ToDouble(env_, arg, &values_[i].d)) return false;
          break;
        case kNoUnbox:
          values_[i].l = arg;
          break;
        default: {
          std::string msg("JNI_INTERPRETER: Unexpected unboxing value '");
          msg += std::to_string(unboxes_[i]) + "' for ";
          msg += method_name_;
          msg += method_desc_;
          ThrowIllegalStateException(env_, msg.c_str());
          return false;
        }
      }
    }
    return true;
  }

  jmethodID methodID() { return mid_; }
  jvalue* values() { return values_; }

 private:
  JNIEnv* env_ = nullptr;
  jmethodID mid_ = nullptr;
  jclass cls_ = nullptr;
  jstring methodName_ = nullptr;
  const char* method_name_ = nullptr;
  jstring methodDesc_ = nullptr;
  const char* method_desc_ = nullptr;
  jobjectArray args_ = nullptr;
  jvalue* values_ = nullptr;
  jintArray unboxArray_ = nullptr;
  jint* unboxes_ = nullptr;
};

// The 12 natives of com.android.tools.deploy.interpreter.JNI. Static: bound via RegisterNatives,
// not name-mangling. The second parameter is the JNI jclass (all methods are static).
jobject InvokeSpecialL(JNIEnv* env, jclass, jobject obj, jclass cls, jstring m, jstring d,
                       jobjectArray args, jintArray unbox) {
  CallInfo info(env, cls, m, d, args, unbox);
  if (!info.Get()) return nullptr;
  return env->CallNonvirtualObjectMethodA(obj, cls, info.methodID(), info.values());
}

void InvokeSpecialV(JNIEnv* env, jclass, jobject obj, jclass cls, jstring m, jstring d,
                    jobjectArray args, jintArray unbox) {
  CallInfo info(env, cls, m, d, args, unbox);
  if (!info.Get()) return;
  env->CallNonvirtualVoidMethodA(obj, cls, info.methodID(), info.values());
}

jint InvokeSpecialI(JNIEnv* env, jclass, jobject obj, jclass cls, jstring m, jstring d,
                    jobjectArray args, jintArray unbox) {
  CallInfo info(env, cls, m, d, args, unbox);
  if (!info.Get()) return 0;
  return env->CallNonvirtualIntMethodA(obj, cls, info.methodID(), info.values());
}

jshort InvokeSpecialS(JNIEnv* env, jclass, jobject obj, jclass cls, jstring m, jstring d,
                      jobjectArray args, jintArray unbox) {
  CallInfo info(env, cls, m, d, args, unbox);
  if (!info.Get()) return 0;
  return env->CallNonvirtualShortMethodA(obj, cls, info.methodID(), info.values());
}

jbyte InvokeSpecialB(JNIEnv* env, jclass, jobject obj, jclass cls, jstring m, jstring d,
                     jobjectArray args, jintArray unbox) {
  CallInfo info(env, cls, m, d, args, unbox);
  if (!info.Get()) return 0;
  return env->CallNonvirtualByteMethodA(obj, cls, info.methodID(), info.values());
}

jboolean InvokeSpecialZ(JNIEnv* env, jclass, jobject obj, jclass cls, jstring m, jstring d,
                        jobjectArray args, jintArray unbox) {
  CallInfo info(env, cls, m, d, args, unbox);
  if (!info.Get()) return JNI_FALSE;
  return env->CallNonvirtualBooleanMethodA(obj, cls, info.methodID(), info.values());
}

jlong InvokeSpecialJ(JNIEnv* env, jclass, jobject obj, jclass cls, jstring m, jstring d,
                     jobjectArray args, jintArray unbox) {
  CallInfo info(env, cls, m, d, args, unbox);
  if (!info.Get()) return 0;
  return env->CallNonvirtualLongMethodA(obj, cls, info.methodID(), info.values());
}

jfloat InvokeSpecialF(JNIEnv* env, jclass, jobject obj, jclass cls, jstring m, jstring d,
                      jobjectArray args, jintArray unbox) {
  CallInfo info(env, cls, m, d, args, unbox);
  if (!info.Get()) return 0.0f;
  return env->CallNonvirtualFloatMethodA(obj, cls, info.methodID(), info.values());
}

jdouble InvokeSpecialD(JNIEnv* env, jclass, jobject obj, jclass cls, jstring m, jstring d,
                       jobjectArray args, jintArray unbox) {
  CallInfo info(env, cls, m, d, args, unbox);
  if (!info.Get()) return 0.0;
  return env->CallNonvirtualDoubleMethodA(obj, cls, info.methodID(), info.values());
}

jchar InvokeSpecialC(JNIEnv* env, jclass, jobject obj, jclass cls, jstring m, jstring d,
                     jobjectArray args, jintArray unbox) {
  CallInfo info(env, cls, m, d, args, unbox);
  if (!info.Get()) return 0;
  return env->CallNonvirtualCharMethodA(obj, cls, info.methodID(), info.values());
}

void EnterMonitor(JNIEnv* env, jclass, jobject obj) {
  if (obj == nullptr) {
    ThrowIllegalStateException(env, "Cannot enter monitor with null object");
    return;
  }
  env->MonitorEnter(obj);
}

void ExitMonitor(JNIEnv* env, jclass, jobject obj) {
  if (obj == nullptr) {
    ThrowIllegalStateException(env, "Cannot exit monitor with null object");
    return;
  }
  env->MonitorExit(obj);
}

#define OBJ "Ljava/lang/Object;"
#define CLASS "Ljava/lang/Class;"
#define STR "Ljava/lang/String;"
// (Object, Class, String name, String desc, Object[] args, int[] unbox) -> ...
#define DESC "(" OBJ CLASS STR STR "[" OBJ "[I)"

const JNINativeMethod kInterpreterMethods[] = {
    {const_cast<char*>("invokespecialL"), const_cast<char*>(DESC OBJ), (void*)&InvokeSpecialL},
    {const_cast<char*>("invokespecial"), const_cast<char*>(DESC "V"), (void*)&InvokeSpecialV},
    {const_cast<char*>("invokespecialI"), const_cast<char*>(DESC "I"), (void*)&InvokeSpecialI},
    {const_cast<char*>("invokespecialS"), const_cast<char*>(DESC "S"), (void*)&InvokeSpecialS},
    {const_cast<char*>("invokespecialB"), const_cast<char*>(DESC "B"), (void*)&InvokeSpecialB},
    {const_cast<char*>("invokespecialZ"), const_cast<char*>(DESC "Z"), (void*)&InvokeSpecialZ},
    {const_cast<char*>("invokespecialJ"), const_cast<char*>(DESC "J"), (void*)&InvokeSpecialJ},
    {const_cast<char*>("invokespecialF"), const_cast<char*>(DESC "F"), (void*)&InvokeSpecialF},
    {const_cast<char*>("invokespecialD"), const_cast<char*>(DESC "D"), (void*)&InvokeSpecialD},
    {const_cast<char*>("invokespecialC"), const_cast<char*>(DESC "C"), (void*)&InvokeSpecialC},
    {const_cast<char*>("enterMonitor"), const_cast<char*>("(" OBJ ")V"), (void*)&EnterMonitor},
    {const_cast<char*>("exitMonitor"), const_cast<char*>("(" OBJ ")V"), (void*)&ExitMonitor},
};

}  // namespace

// Lazily register the interpreter's JNI natives on the just-injected interpreter.JNI class.
// Called from Kotlin (LiveEditInterp) with the class resolved via the app classloader, AFTER the
// engine has injected interp.dex — the class cannot be found before that. Idempotent.
extern "C" JNIEXPORT jint JNICALL
Java_dev_hotreload_client_HotSwap_nativeRegisterInterpreterJni(JNIEnv* env, jobject, jclass jniClass) {
  static bool registered = false;
  if (registered) return 0;
  if (jniClass == nullptr) return -1;
  jint rc = env->RegisterNatives(jniClass, kInterpreterMethods,
                                 sizeof(kInterpreterMethods) / sizeof(kInterpreterMethods[0]));
  if (rc != 0) {
    if (env->ExceptionCheck()) env->ExceptionClear();
    ILOGE("RegisterNatives(interpreter.JNI) failed: %d", rc);
    return rc;
  }
  registered = true;
  ILOGI("registered interpreter.JNI natives");
  return 0;
}
