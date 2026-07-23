#!/usr/bin/env bash
# Resolve the public Gradle plugin and runtime AAR from mavenLocal or one repository URL.
set -euo pipefail

if [ "$#" -ne 2 ]; then
    echo "usage: $0 <version> <mavenLocal|repository-url>" >&2
    exit 2
fi

VERSION="$1"
REPOSITORY="$2"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# Keep this scratch consumer on the repository's JBR, independent from a caller's
# target-project JDK selection.
unset JAVA_HOME
source "$REPO_ROOT/scripts/env.sh"

if [ "$REPOSITORY" = "mavenLocal" ]; then
    REPOSITORY_BLOCK="mavenLocal()"
    # java-gradle-plugin publishes its conventional marker locally.
    MARKER_GROUP="dev.hotreload"
else
    REPOSITORY_BLOCK="maven { url = uri(\"$REPOSITORY\") }"
    # JitPack exposes every module from this multi-module repository under its
    # repository group. The README's resolutionStrategy maps the plugin ID to the
    # direct module; this probe additionally proves JitPack's real marker POM.
    MARKER_GROUP="com.github.xception-hash.compose-hot-reload"
fi

cat > "$TMP_DIR/settings.gradle.kts" <<EOF
pluginManagement {
    repositories {
        $REPOSITORY_BLOCK
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "dev.hotreload") {
                useModule("com.github.xception-hash.compose-hot-reload:gradle-plugin:$VERSION")
            }
        }
    }
}
dependencyResolutionManagement {
    repositories {
        $REPOSITORY_BLOCK
        google()
        mavenCentral()
    }
}
rootProject.name = "hotreload-release-resolution"
EOF

cat > "$TMP_DIR/build.gradle.kts" <<EOF
plugins {
    id("dev.hotreload") version "$VERSION" apply false
}

configurations.create("runtimeProbe")
configurations.create("markerProbe")
dependencies {
    add("runtimeProbe", "com.github.xception-hash.compose-hot-reload:runtime-client:$VERSION")
    add("markerProbe", "$MARKER_GROUP:dev.hotreload.gradle.plugin:$VERSION")
}

tasks.register("verifyReleaseArtifacts") {
    doLast {
        val runtime = configurations.getByName("runtimeProbe").resolve()
        val marker = configurations.getByName("markerProbe").resolve()
        check(runtime.any { it.name == "runtime-client-$VERSION.aar" }) {
            "runtime AAR was not resolved: \$runtime"
        }
        check(marker.isNotEmpty()) {
            "plugin marker was not resolved: \$marker"
        }
        println("RESOLVE_OK: plugin marker ($MARKER_GROUP), module, and runtime-client:$VERSION")
    }
}
EOF

"$REPO_ROOT/gradlew" -p "$TMP_DIR" --no-daemon verifyReleaseArtifacts
