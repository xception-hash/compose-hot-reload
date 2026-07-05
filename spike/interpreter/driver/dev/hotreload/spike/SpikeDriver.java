package dev.hotreload.spike;

import com.android.tools.deploy.liveedit.LiveEditStubs;

import java.util.Base64;

// Entry point invoked by the toy app's PatchReceiver ("run" extra) after the spike dex is
// injected. Exercises the production LiveEdit entry path: init -> addClasses -> stub<T>,
// exactly as the StubTransform dex prologue would call it.
public final class SpikeDriver {
    public static String run() {
        try {
            byte[] v2 = Base64.getDecoder().decode(TargetBytes.B64);
            LiveEditStubs.init(SpikeDriver.class.getClassLoader());
            LiveEditStubs.addClasses(new byte[][] {v2}, new byte[0][], false);
            Object bc = LiveEditStubs.getClassBytecode("dev/hotreload/spike/SpikeTarget");

            // Compiled v1 runs natively; the interpreter must run the v2 bytes instead.
            int nativeFib = SpikeTarget.fib(10);
            int interpFib = LiveEditStubs.stubI(bc, "fib", "(I)I", args(10));
            Object greet =
                    LiveEditStubs.stubL(bc, "greet", "(Ljava/lang/String;)Ljava/lang/String;", args("ART"));
            Object parseOk =
                    LiveEditStubs.stubL(bc, "parse", "(Ljava/lang/String;)Ljava/lang/String;", args("42"));

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

            boolean pass =
                    nativeFib == 55
                            && interpFib == 1055
                            && "v2:Hello, ART!".equals(greet)
                            && "int:42".equals(parseOk)
                            && "escaped:NumberFormatException".equals(parseNan);

            StringBuilder out = new StringBuilder(pass ? "SPIKE PASS" : "SPIKE FAIL");
            out.append(" nativeFib=").append(nativeFib);
            out.append(" interpFib=").append(interpFib);
            out.append(" greet=").append(greet);
            out.append(" parse42=").append(parseOk);
            out.append(" parseX=").append(parseNan);
            return out.toString();
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
}
