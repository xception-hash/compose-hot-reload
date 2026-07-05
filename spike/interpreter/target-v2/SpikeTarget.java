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
}
