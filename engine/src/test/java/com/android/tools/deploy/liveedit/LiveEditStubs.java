package com.android.tools.deploy.liveedit;

/**
 * Test-only fake of the interpreter's {@code LiveEditStubs} at its exact FQCN, so a class rewritten
 * by {@link dev.hotreload.engine.StubTransform} links against it directly when loaded in a test
 * classloader. It records every prologue call and returns canned values, letting the tests assert
 * the transform's behavior without the real on-device interpreter.
 */
@SuppressWarnings("unused") // Called from transformed bytecode by name.
public final class LiveEditStubs {

    // What getClassBytecode/getInstanceBytecode hand back; null makes the prologue fall through.
    public static Object nextBytecode;

    // Last-call recordings.
    public static String lastGetClassName;
    public static String lastGetInstanceName;
    public static Object lastGetInstance;
    public static String lastStubName;
    public static String lastMethodName;
    public static String lastMethodDesc;
    public static Object[] lastParams;
    public static String lastCtorClass;
    public static Object lastCtorInstance;

    // Canned stub return values per type.
    public static Object retL;
    public static int retI;
    public static long retJ;
    public static float retF;
    public static double retD;
    public static boolean retZ;
    public static char retC;
    public static byte retB;
    public static short retS;

    public static void reset() {
        nextBytecode = null;
        lastGetClassName = null;
        lastGetInstanceName = null;
        lastGetInstance = null;
        lastStubName = null;
        lastMethodName = null;
        lastMethodDesc = null;
        lastParams = null;
        lastCtorClass = null;
        lastCtorInstance = null;
        retL = null;
        retI = 0;
        retJ = 0L;
        retF = 0f;
        retD = 0d;
        retZ = false;
        retC = 0;
        retB = 0;
        retS = 0;
    }

    public static Object getClassBytecode(String internalClassName) {
        lastGetClassName = internalClassName;
        return nextBytecode;
    }

    public static Object getInstanceBytecode(String internalClassName, Object instance) {
        lastGetInstanceName = internalClassName;
        lastGetInstance = instance;
        return nextBytecode;
    }

    public static void stubConstructor(String internalClassName, Object instance) {
        lastCtorClass = internalClassName;
        lastCtorInstance = instance;
    }

    private static void record(String stubName, String methodName, String methodDesc, Object[] params) {
        lastStubName = stubName;
        lastMethodName = methodName;
        lastMethodDesc = methodDesc;
        lastParams = params;
    }

    public static Object stubL(Object bc, String name, String desc, Object[] params) {
        record("stubL", name, desc, params);
        return retL;
    }

    public static void stubV(Object bc, String name, String desc, Object[] params) {
        record("stubV", name, desc, params);
    }

    public static int stubI(Object bc, String name, String desc, Object[] params) {
        record("stubI", name, desc, params);
        return retI;
    }

    public static long stubJ(Object bc, String name, String desc, Object[] params) {
        record("stubJ", name, desc, params);
        return retJ;
    }

    public static float stubF(Object bc, String name, String desc, Object[] params) {
        record("stubF", name, desc, params);
        return retF;
    }

    public static double stubD(Object bc, String name, String desc, Object[] params) {
        record("stubD", name, desc, params);
        return retD;
    }

    public static boolean stubZ(Object bc, String name, String desc, Object[] params) {
        record("stubZ", name, desc, params);
        return retZ;
    }

    public static char stubC(Object bc, String name, String desc, Object[] params) {
        record("stubC", name, desc, params);
        return retC;
    }

    public static byte stubB(Object bc, String name, String desc, Object[] params) {
        record("stubB", name, desc, params);
        return retB;
    }

    public static short stubS(Object bc, String name, String desc, Object[] params) {
        record("stubS", name, desc, params);
        return retS;
    }
}
