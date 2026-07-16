rootProject.name = "compose-hot-reload"

include(":bootstrap", ":engine", ":cli", ":protocol")

// The zero-touch CLI bundles a freshly-built runtime-client AAR. Keep the Android build
// isolated (it has its own plugin/toolchain management) and depend on its assemble task from
// :engine instead of folding an Android subproject into the host-side root build.
includeBuild("runtime-client")
