package dev.hotreload.spike;

import com.android.tools.deploy.liveedit.LiveEditStubs;

import java.util.Base64;

// Entry point invoked by the toy app's PatchReceiver ("run" extra) after the spike dex is
// injected. Exercises the production LiveEdit entry path: init -> addClasses -> stub<T>,
// exactly as the StubTransform dex prologue would call it.
public final class SpikeDriver {
    public static String run() {
        try {
            ClassLoader loader = SpikeDriver.class.getClassLoader();

            // T30 item 1: super.toString()/lockedCompute below hit interpreter.JNI's natives
            // (invokespecialL / enterMonitor / exitMonitor in runtime-client's
            // interpreter_jni.cpp, compiled verbatim into the toy app's .so and bridged by
            // interp_bridge.cpp). They are bound via RegisterNatives, never name-mangling, so
            // register them ONCE before any interpreted invocation — same ordering as production
            // LiveEditInterp.ensureInitialized (register natives, then LiveEditStubs.init).
            // Reflection because this driver compiles against android.jar only, not toy classes.
            Class<?> jniClass =
                    Class.forName("com.android.tools.deploy.interpreter.JNI", false, loader);
            Class<?> hotSwap = Class.forName("dev.hotreload.toy.HotSwap", false, loader);
            Object hotSwapInstance = hotSwap.getField("INSTANCE").get(null); // Kotlin object
            int registerRc =
                    (int)
                            hotSwap.getMethod("nativeRegisterInterpreterJni", Class.class)
                                    .invoke(hotSwapInstance, jniClass);
            if (registerRc != 0) {
                return "SPIKE FAIL nativeRegisterInterpreterJni rc=" + registerRc;
            }

            byte[] v2 = Base64.getDecoder().decode(TargetBytes.B64);
            LiveEditStubs.init(loader);
            LiveEditStubs.addClasses(new byte[][] {v2}, new byte[0][], false);
            Object bc = LiveEditStubs.getClassBytecode("dev/hotreload/spike/SpikeTarget");

            // Compiled v1 runs natively; the interpreter must run the v2 bytes instead.
            int nativeFib = SpikeTarget.fib(10);
            int interpFib = LiveEditStubs.stubI(bc, "fib", "(I)I", args(10));
            Object greet =
                    LiveEditStubs.stubL(bc, "greet", "(Ljava/lang/String;)Ljava/lang/String;", args("ART"));
            Object parseOk =
                    LiveEditStubs.stubL(bc, "parse", "(Ljava/lang/String;)Ljava/lang/String;", args("42"));

            // T30 item 1a: invokespecial super-call. victim is a real v1-compiled instance;
            // interpreting v2's toString() override must dispatch `super.toString()` through
            // interpreter_jni.cpp's invokespecialL native to the real (compiled) Object.toString().
            SpikeTarget victim = new SpikeTarget();
            String nativeToString = victim.toString();
            Object interpToString =
                    LiveEditStubs.stubL(bc, "toString", "()Ljava/lang/String;", instanceArgs(victim));
            boolean superOk =
                    interpToString != null
                            && interpToString.equals("v2super:" + nativeToString);
            // Log each sub-verdict IMMEDIATELY (same tag run.sh greps): the monitor case below
            // can abort the process (CheckJNI), which would otherwise eat this verdict too.
            android.util.Log.i(
                    "PatchReceiver",
                    "super-call: nativeToString=" + nativeToString
                            + " interpToString=" + interpToString
                            + " -> " + (superOk ? "SUPER PASS" : "SUPER FAIL"));

            // Known AOSP interpreter limitation (verified live + by inspection of
            // ByteCodeInterpreter.doInterpret): exceptions thrown by *compiled* callees are
            // sneaky-rethrown past the interpreted method's try/catch; only interpreted ATHROW
            // routes to the handler table. So parse("x")'s NumberFormatException must escape.
            Object parseNan;
            try {
                parseNan = LiveEditStubs.stubL(bc, "parse", "(Ljava/lang/String;)Ljava/lang/String;", args("x"));
            } catch (NumberFormatException e) {
                parseNan = "escaped:NumberFormatException";
            }

            // Aggregate of every case EXCEPT the monitor one — logged before attempting it,
            // because that case aborts the whole process under CheckJNI (see below). run.sh
            // asserts SYNC PASS separately, so a crash there still fails the run loudly.
            boolean pass =
                    nativeFib == 55
                            && interpFib == 1055
                            && "v2:Hello, ART!".equals(greet)
                            && "int:42".equals(parseOk)
                            && "escaped:NumberFormatException".equals(parseNan)
                            && superOk;

            StringBuilder out = new StringBuilder(pass ? "SPIKE PASS" : "SPIKE FAIL");
            out.append(" nativeFib=").append(nativeFib);
            out.append(" interpFib=").append(interpFib);
            out.append(" greet=").append(greet);
            out.append(" parse42=").append(parseOk);
            out.append(" parseX=").append(parseNan);
            out.append(" nativeToString=").append(nativeToString);
            out.append(" interpToString=").append(interpToString);
            out.append(" ").append(superOk ? "SUPER PASS" : "SUPER FAIL");
            android.util.Log.i("PatchReceiver", out.toString());

            // T30 item 1b, deliberately LAST: monitorenter/monitorexit. v2's `synchronized`
            // block routes through interpreter_jni.cpp's enterMonitor/exitMonitor natives.
            // KNOWN-FATAL on debuggable apps (T30 item 1 outcome, 2026-07-10): CheckJNI is
            // late-enabled for every debuggable process, and its end-of-native-method check
            // aborts the VM the moment JNI.enterMonitor returns while holding the monitor it
            // just acquired ("JNI DETECTED ERROR ... Still holding a locked object on JNI
            // end"). That is inherent to the AOSP enterMonitor design, not our port — every
            // verdict above is logged durably before this line on purpose. run.sh asserts the
            // abort signature itself as the documented behavior; the SYNC PASS/FAIL log below
            // only ever prints if the platform behavior changes.
            // NB: this marker must NOT contain the abort-signature substring run.sh greps for
            // ("Still holding a locked object ..."), or the assert would trivially self-match.
            android.util.Log.i(
                    "PatchReceiver",
                    "synchronized: attempting interpreted lockedCompute — expected FATAL"
                            + " (CheckJNI held-monitor abort; see T30 item 1)");
            int nativeLocked = SpikeTarget.lockedCompute(21);
            int interpLocked = LiveEditStubs.stubI(bc, "lockedCompute", "(I)I", args(21));
            boolean syncOk = nativeLocked == 21 && interpLocked == 43;
            android.util.Log.i(
                    "PatchReceiver",
                    "synchronized: nativeLocked=" + nativeLocked
                            + " interpLocked=" + interpLocked
                            + " -> " + (syncOk ? "SYNC PASS" : "SYNC FAIL"));
            return out + (syncOk ? " SYNC PASS" : " SYNC FAIL");
        } catch (Throwable t) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            return "SPIKE FAIL exception: " + sw;
        }
    }

    // doStub layout: params[0] unused, params[1] = 'this' (null for static), rest = args.
    private static Object[] args(Object... a) {
        Object[] packed = new Object[a.length + 2];
        System.arraycopy(a, 0, packed, 2, a.length);
        return packed;
    }

    // Same layout as args(), but for instance methods: params[1] carries the real 'this'.
    private static Object[] instanceArgs(Object thisObj, Object... a) {
        Object[] packed = new Object[a.length + 2];
        packed[1] = thisObj;
        System.arraycopy(a, 0, packed, 2, a.length);
        return packed;
    }
}
