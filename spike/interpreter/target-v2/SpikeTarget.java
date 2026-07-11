package dev.hotreload.spike;

// v2 — the "edited" version. Never dexed or loaded; its .class bytes are embedded in the
// spike dex (base64) and executed only via the AOSP bytecode interpreter. Every method
// body differs observably from v1.
public class SpikeTarget {
    public static int fib(int n) {
        int a = 0, b = 1;
        for (int i = 0; i < n; i++) {
            int t = a + b;
            a = b;
            b = t;
        }
        return a + 1000;
    }

    public static String greet(String who) {
        StringBuilder sb = new StringBuilder("v2:Hello, ");
        sb.append(who).append("!");
        return sb.toString();
    }

    public static String parse(String s) {
        try {
            StringBuilder sb = new StringBuilder("int:");
            sb.append(Integer.parseInt(s));
            return sb.toString();
        } catch (NumberFormatException e) {
            StringBuilder sb = new StringBuilder("nan:");
            sb.append(s);
            return sb.toString();
        }
    }

    // T30 item 1: exercises invokespecial (super-call) via the interpreter. v1 has no
    // toString() override, so it falls back to Object's default; v2's override is only
    // reachable by interpreting this exact bytecode, and its body must itself invoke the
    // (compiled, non-interpreted) Object.toString() via invokespecial.
    @Override
    public String toString() {
        return "v2super:" + super.toString();
    }

    // T30 item 1: exercises monitorenter/monitorexit via the interpreter. v1's version has
    // no synchronized block at all (behaviorally distinct, same convention as fib).
    public static int lockedCompute(int n) {
        Object lock = new Object();
        int result;
        synchronized (lock) {
            result = n * 2 + 1;
        }
        return result;
    }
}
