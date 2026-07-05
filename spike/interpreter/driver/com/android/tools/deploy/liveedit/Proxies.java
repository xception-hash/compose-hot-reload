package com.android.tools.deploy.liveedit;

import java.util.Set;

// Stub for the codegen'd lambda-proxy lookup table (AOSP generates this with
// LambdaGenerator + javapoet). LiveEditContext requires the class to exist in the app
// classloader; the spike never creates new lambdas, so a null lookup is fine.
public final class Proxies {
    public static Class<?> getProxyInterface(Set<Class<?>> supertypes) {
        return null;
    }
}
