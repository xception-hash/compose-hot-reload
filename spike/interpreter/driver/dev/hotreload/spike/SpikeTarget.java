package dev.hotreload.spike;

// v1 — compiled into the spike dex and loaded by the app classloader. The interpreter
// must run the *v2* bytes (TargetBytes) instead of this loaded version.
public class SpikeTarget {
    public static int fib(int n) {
        int a = 0, b = 1;
        for (int i = 0; i < n; i++) {
            int t = a + b;
            a = b;
            b = t;
        }
        return a;
    }

    public static String greet(String who) {
        StringBuilder sb = new StringBuilder("v1:Hello, ");
        sb.append(who).append("!");
        return sb.toString();
    }

    public static String parse(String s) {
        return "v1";
    }
}
