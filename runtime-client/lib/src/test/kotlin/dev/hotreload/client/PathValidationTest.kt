package dev.hotreload.client

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins the injected-dex / overlay-dir path guards (T32 item 2). These are the pure-function
 * extraction of the checks in [PatchServer]'s InjectDex and loadResources paths; if a refactor
 * drops the regex or the `..` traversal reject, these fail. No Context / Robolectric — the
 * peer-cred allowlist and the read-only dex write need a real device and are covered by the e2e.
 */
class PathValidationTest {

    @Test
    fun validateDexName_accepts_single_segment_names() {
        // must not throw
        validateDexName("a.dex")
        validateDexName("patch-7.dex")
        validateDexName("x_1-2")
        validateDexName("primed-3.dex")
    }

    @Test
    fun validateDexName_rejects_traversal_and_paths_and_empty() {
        for (bad in listOf("../x", "a/b", "..", "")) {
            assertThrows(IllegalArgumentException::class.java) { validateDexName(bad) }
        }
    }

    @Test
    fun validateOverlayDir_accepts_single_segment_name() {
        validateOverlayDir("hotreload-overlay-4")
    }

    @Test
    fun validateOverlayDir_rejects_traversal_and_paths() {
        for (bad in listOf("..", "a/b", "../x")) {
            assertThrows(IllegalArgumentException::class.java) { validateOverlayDir(bad) }
        }
    }
}
