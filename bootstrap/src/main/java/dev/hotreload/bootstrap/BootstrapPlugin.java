package dev.hotreload.bootstrap;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AGP-free zero-touch bootstrap. Android and Kotlin APIs are deliberately accessed by
 * reflection so this class can load under Gradle 8/JDK 17 and Gradle 9/JDK 21 without
 * linking to one AGP/KGP version.
 */
public final class BootstrapPlugin implements Plugin<Project> {
    public static final String PREFIX = "dev.hotreload.bootstrap.";
    public static final String RUNTIME_AAR = PREFIX + "runtimeAar";
    public static final String MODULES = PREFIX + "modules";
    public static final String APP_MODULE = PREFIX + "appModule";
    public static final String VARIANT = PREFIX + "variant";

    private static final List<String> CLASS_SHAPE_FLAGS = Arrays.asList(
        "-Xlambdas=class",
        "-Xsam-conversions=class",
        "-Xstring-concat=inline"
    );

    @Override
    public void apply(Project project) {
        String modulesValue = required(project, MODULES);
        List<String> watchedModules = Arrays.asList(modulesValue.split(",", -1));
        for (String module : watchedModules) validateGradlePath(project, module, MODULES);
        if (!watchedModules.contains(project.getPath())) {
            throw failure(project, "module allowlist", "project was applied outside " + watchedModules, null);
        }
        String appModule = required(project, APP_MODULE);
        validateGradlePath(project, appModule, APP_MODULE);
        if (!watchedModules.contains(appModule)) {
            throw failure(project, "module allowlist", "app module " + appModule + " is not watched", null);
        }
        String selectedVariant = required(project, VARIANT);
        if (!selectedVariant.matches("[A-Za-z0-9_.-]+")) {
            throw failure(project, "variant guard", "invalid selected variant '" + selectedVariant + "'", null);
        }
        File runtimeAar = validatedRuntime(project, required(project, RUNTIME_AAR));
        boolean appProject = project.getPath().equals(appModule);

        AtomicBoolean supported = new AtomicBoolean(false);
        AtomicBoolean androidProject = new AtomicBoolean(false);
        AtomicBoolean flagsApplied = new AtomicBoolean(false);
        AtomicBoolean composeMetadataApplied = new AtomicBoolean(false);
        AtomicBoolean selectedVariantSeen = new AtomicBoolean(false);

        project.getPluginManager().withPlugin("com.android.application", ignored -> {
            supported.set(true);
            androidProject.set(true);
            if (!appProject) {
                throw failure(project, "application module", "watched Android application is not the selected app module " + appModule, null);
            }
            disableCoverageInstrumentation(project);
            configureLegacyJniPackaging(project);
            registerSelectedVariantGuard(project, selectedVariant, selectedVariantSeen);
            maybeApplyCompilerFlags(project, true, flagsApplied);
        });

        project.getPluginManager().withPlugin("com.android.library", ignored -> {
            supported.set(true);
            androidProject.set(true);
            if (appProject) {
                throw failure(project, "application module", "selected app module is an Android library", null);
            }
            maybeApplyCompilerFlags(project, false, flagsApplied);
        });

        // AGP 8 applies standalone Kotlin after the Android plugin. This callback is the
        // second chance for compiler flags when the `kotlin` extension did not exist yet.
        project.getPluginManager().withPlugin("org.jetbrains.kotlin.android", ignored ->
            maybeApplyCompilerFlags(project, appProject, flagsApplied)
        );

        project.getPluginManager().withPlugin("org.jetbrains.kotlin.jvm", ignored -> {
            supported.set(true);
            if (appProject) {
                throw failure(project, "application module", "selected app module is a Kotlin/JVM project", null);
            }
            maybeApplyCompilerFlags(project, false, flagsApplied);
        });

        project.getPluginManager().withPlugin("org.jetbrains.kotlin.plugin.compose", ignored ->
            maybeApplyComposeMetadata(project, composeMetadataApplied)
        );

        // Build types and their configurations are stable after project evaluation. Adding
        // dependencies here still precedes task dependency resolution, and avoids linking to
        // AGP's finalizeDsl signature.
        project.afterEvaluate(ignored -> {
            maybeApplyCompilerFlags(project, appProject, flagsApplied);
            if (project.getPluginManager().hasPlugin("org.jetbrains.kotlin.plugin.compose")) {
                maybeApplyComposeMetadata(project, composeMetadataApplied);
            }
            if (appProject && androidProject.get()) {
                addDebuggableRuntimeDependencies(project, runtimeAar);
            }
        });

        project.getGradle().projectsEvaluated(ignored -> {
            if (!supported.get()) {
                throw failure(project, "plugin detection",
                    "requires com.android.application, com.android.library, or org.jetbrains.kotlin.jvm", null);
            }
            if (!flagsApplied.get()) {
                throw failure(project, "Kotlin compiler flags",
                    "the `kotlin` extension or compilerOptions.freeCompilerArgs API was unavailable", null);
            }
            if (project.getPluginManager().hasPlugin("org.jetbrains.kotlin.plugin.compose")
                && !composeMetadataApplied.get()) {
                throw failure(project, "Compose compiler flags",
                    "FunctionKeyMeta instrumentation could not be enabled", null);
            }
            if (appProject && androidProject.get() && !selectedVariantSeen.get()) {
                throw failure(project, "variant guard",
                    "selected variant '" + selectedVariant + "' was not created", null);
            }
        });
    }

    private static void configureLegacyJniPackaging(Project project) {
        try {
            Object android = requiredExtension(project, "android");
            Object packaging = invokeNoArgs(android, "getPackaging");
            Object jniLibs = invokeNoArgs(packaging, "getJniLibs");
            Method setter = method(jniLibs.getClass(), "setUseLegacyPackaging", 1);
            setter.invoke(jniLibs, Boolean.TRUE);
        } catch (Throwable t) {
            throw failure(project, "legacy JNI packaging", "unsupported Android DSL API", unwrap(t));
        }
    }

    /**
     * Android test coverage instruments classes while packaging the APK, after Kotlin has
     * written the class-output directory watched by the engine. A later patch dexed from that
     * directory would therefore omit JaCoCo's synthetic members (for example `$jacocoInit`) and
     * ART would reject even a body-only redefine because the installed method shape differs.
     *
     * Zero-touch builds are temporary development builds, so disable both coverage modes through
     * Android Components' public finalizeDsl lifecycle. This runs after the target build scripts
     * (including convention plugins) have enabled coverage but before variants are finalized.
     */
    private static void disableCoverageInstrumentation(Project project) {
        try {
            Object components = requiredExtension(project, "androidComponents");
            Method finalizeDsl = Arrays.stream(components.getClass().getMethods())
                .filter(m -> m.getName().equals("finalizeDsl") && m.getParameterCount() == 1)
                .filter(m -> Action.class.isAssignableFrom(m.getParameterTypes()[0]))
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException("androidComponents.finalizeDsl(Action)"));
            Action<Object> action = android -> {
                try {
                    Object buildTypes = invokeNoArgs(android, "getBuildTypes");
                    if (!(buildTypes instanceof Iterable<?>)) {
                        throw new IllegalStateException("android.buildTypes is not iterable");
                    }
                    for (Object buildType : (Iterable<?>) buildTypes) {
                        invokeBooleanSetter(buildType, "setEnableAndroidTestCoverage", false);
                        invokeBooleanSetter(buildType, "setEnableUnitTestCoverage", false);
                    }
                } catch (Throwable t) {
                    throw failure(project, "coverage instrumentation", "unsupported Android build-type coverage API", unwrap(t));
                }
            };
            finalizeDsl.invoke(components, action);
        } catch (Throwable t) {
            throw failure(project, "coverage instrumentation", "unsupported Android Components finalizeDsl API", unwrap(t));
        }
    }

    private static void invokeBooleanSetter(Object target, String name, boolean value) throws Exception {
        Method setter = Arrays.stream(target.getClass().getMethods())
            .filter(m -> m.getName().equals(name) && m.getParameterCount() == 1)
            .filter(m -> m.getParameterTypes()[0] == boolean.class || m.getParameterTypes()[0] == Boolean.class)
            .findFirst()
            .orElseThrow(() -> new NoSuchMethodException(name + "(boolean)"));
        setter.invoke(target, value);
    }

    private static void registerSelectedVariantGuard(
        Project project,
        String selectedVariant,
        AtomicBoolean selectedVariantSeen
    ) {
        try {
            Object components = requiredExtension(project, "androidComponents");
            Object selector = invokeNoArgs(components, "selector");
            Object all = invokeNoArgs(selector, "all");
            Method onVariants = Arrays.stream(components.getClass().getMethods())
                .filter(m -> m.getName().equals("onVariants") && m.getParameterCount() == 2)
                .filter(m -> Action.class.isAssignableFrom(m.getParameterTypes()[1]))
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException("onVariants(selector, Action)"));
            Action<Object> action = variant -> {
                String name = String.valueOf(invokeNoArgsUnchecked(project, variant, "getName", "variant name"));
                if (!selectedVariant.equals(name)) return;
                selectedVariantSeen.set(true);
                Object debuggable = invokeNoArgsUnchecked(project, variant, "getDebuggable", "variant debuggable flag");
                if (!Boolean.TRUE.equals(debuggable)) {
                    throw failure(project, "variant guard",
                        "selected variant '" + selectedVariant + "' is not debuggable", null);
                }
            };
            onVariants.invoke(components, all, action);
        } catch (Throwable t) {
            throw failure(project, "variant guard", "unsupported Android Components API", unwrap(t));
        }
    }

    private static void addDebuggableRuntimeDependencies(Project project, File runtimeAar) {
        try {
            Object android = requiredExtension(project, "android");
            Object buildTypesObject = invokeNoArgs(android, "getBuildTypes");
            if (!(buildTypesObject instanceof Iterable<?>)) {
                throw new IllegalStateException("android.buildTypes is not iterable");
            }
            int debuggableCount = 0;
            for (Object buildType : (Iterable<?>) buildTypesObject) {
                Object debuggable = invokeNoArgs(buildType, "isDebuggable", "getDebuggable");
                if (!Boolean.TRUE.equals(debuggable)) continue;
                String name = String.valueOf(invokeNoArgs(buildType, "getName"));
                String configuration = name + "Implementation";
                if (project.getConfigurations().findByName(configuration) == null) {
                    throw new IllegalStateException("missing configuration '" + configuration + "'");
                }
                project.getDependencies().add(configuration, project.files(runtimeAar));
                project.getDependencies().add(configuration, "androidx.startup:startup-runtime:1.2.0");
                debuggableCount++;
            }
            if (debuggableCount == 0) {
                throw new IllegalStateException("no debuggable build type exists");
            }
        } catch (Throwable t) {
            throw failure(project, "runtime dependency wiring", "could not configure debuggable build types", unwrap(t));
        }
    }

    private static void maybeApplyCompilerFlags(Project project, boolean appProject, AtomicBoolean applied) {
        if (applied.get()) return;
        Object kotlin = project.getExtensions().findByName("kotlin");
        if (kotlin == null) return;
        List<String> flags = new ArrayList<>();
        flags.addAll(CLASS_SHAPE_FLAGS);
        if (appProject && "true".equals(String.valueOf(project.findProperty("hotreload.liveLiterals")))) {
            flags.add("-P");
            flags.add("plugin:androidx.compose.compiler.plugins.kotlin:liveLiteralsEnabled=true");
        }
        addFreeCompilerArgs(project, kotlin, flags, "Kotlin compiler flags");
        applied.set(true);
    }

    private static void maybeApplyComposeMetadata(Project project, AtomicBoolean applied) {
        if (applied.get()) return;
        Object kotlin = project.getExtensions().findByName("kotlin");
        if (kotlin == null) return;
        addFreeCompilerArgs(project, kotlin, List.of(
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:generateFunctionKeyMetaAnnotations=true"
        ), "Compose compiler flags");
        applied.set(true);
    }

    private static void addFreeCompilerArgs(
        Project project,
        Object kotlin,
        List<String> flags,
        String operation
    ) {
        try {
            Object compilerOptions = invokeNoArgs(kotlin, "getCompilerOptions");
            Object freeArgs = invokeNoArgs(compilerOptions, "getFreeCompilerArgs");
            Method addAll = Arrays.stream(freeArgs.getClass().getMethods())
                .filter(m -> m.getName().equals("addAll") && m.getParameterCount() == 1)
                .filter(m -> Iterable.class.isAssignableFrom(m.getParameterTypes()[0]))
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException("freeCompilerArgs.addAll(Iterable)"));
            addAll.invoke(freeArgs, flags);
        } catch (Throwable t) {
            throw failure(project, operation, "unsupported Kotlin compilerOptions API", unwrap(t));
        }
    }

    private static File validatedRuntime(Project project, String value) {
        try {
            File file = new File(value).getCanonicalFile();
            if (!file.isAbsolute() || !file.isFile() || !file.getName().endsWith(".aar")) {
                throw new IllegalArgumentException("not an absolute regular .aar file: " + value);
            }
            return file;
        } catch (Exception e) {
            throw failure(project, "runtime artifact", e.getMessage(), e);
        }
    }

    private static void validateGradlePath(Project project, String value, String property) {
        if (!value.matches("(:[A-Za-z0-9_.-]+)+")) {
            throw failure(project, "bootstrap property", "invalid Gradle path in " + property + ": '" + value + "'", null);
        }
    }

    private static String required(Project project, String name) {
        Object value = project.findProperty(name);
        if (value == null || value.toString().isBlank()) {
            throw failure(project, "bootstrap property", "missing required -P" + name, null);
        }
        String text = value.toString();
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\0') >= 0) {
            throw failure(project, "bootstrap property", "control character in " + name, null);
        }
        return text;
    }

    private static Object requiredExtension(Project project, String name) {
        Object value = project.getExtensions().findByName(name);
        if (value == null) throw new IllegalStateException("extension '" + name + "' not found");
        return value;
    }

    private static Object invokeNoArgs(Object target, String... names) throws Exception {
        for (String name : names) {
            try {
                return target.getClass().getMethod(name).invoke(target);
            } catch (NoSuchMethodException ignored) {
                // Try the next compatibility spelling.
            }
        }
        throw new NoSuchMethodException(String.join(" or ", names));
    }

    private static Object invokeNoArgsUnchecked(Project project, Object target, String method, String operation) {
        try {
            return invokeNoArgs(target, method);
        } catch (Throwable t) {
            throw failure(project, operation, "unsupported reflected API", unwrap(t));
        }
    }

    private static Method method(Class<?> type, String name, int parameterCount) throws NoSuchMethodException {
        return Arrays.stream(type.getMethods())
            .filter(m -> m.getName().equals(name) && m.getParameterCount() == parameterCount)
            .findFirst()
            .orElseThrow(() -> new NoSuchMethodException(type.getName() + "." + name));
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException && ((InvocationTargetException) throwable).getCause() != null) {
            return ((InvocationTargetException) throwable).getCause();
        }
        return throwable;
    }

    private static GradleException failure(Project project, String operation, String message, Throwable cause) {
        String text = "Zero-touch bootstrap on project '" + project.getPath() + "' failed during " + operation + ": " + message;
        return cause == null ? new GradleException(text) : new GradleException(text, cause);
    }
}
