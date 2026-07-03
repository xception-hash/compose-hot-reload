package dev.hotreload.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceholderTest {
    @Test
    fun `placeholder exists`() {
        assertEquals("Placeholder", Placeholder::class.simpleName)
    }
}
