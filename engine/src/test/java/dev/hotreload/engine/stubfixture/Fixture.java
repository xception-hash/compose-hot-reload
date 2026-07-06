package dev.hotreload.engine.stubfixture;

/**
 * Realistic fixture with a spread of method shapes for {@link dev.hotreload.engine.StubTransform}
 * tests: static/instance, every return kind, a >4-arg method (exercises Object[] packing including
 * two-slot long/double args), and a constructor with a real body (exercises the stubConstructor
 * tail). Compiled to `.class` on the test classpath; the test reads those bytes, transforms them,
 * and loads the result in a child classloader so it links against the fake {@code LiveEditStubs}.
 */
public class Fixture {
    public int id;
    public boolean touched;

    public Fixture() {
        this.id = 7;
    }

    public static int add(int a, int b) {
        return a + b;
    }

    public String greet(String name) {
        return name;
    }

    public void touch() {
        this.touched = true;
    }

    public static int many(int a, long b, String c, int d, double e, Object f) {
        return a + d;
    }

    public static long lng() {
        return 123L;
    }

    public static double dbl() {
        return 2.5d;
    }

    public static float flt() {
        return 1.5f;
    }

    public static boolean flag() {
        return false;
    }

    public static char ch() {
        return 'x';
    }
}
