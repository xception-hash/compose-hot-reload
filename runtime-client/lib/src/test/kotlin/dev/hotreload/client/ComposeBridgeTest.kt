package dev.hotreload.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ComposeBridgeTest {
    private object HelperShape {
        @JvmField var enabled = true
        @JvmField var `String$key` = "old"
    }

    private object FinalShape {
        @JvmField var enabled = true
        const val `String$key` = "old"
    }

    @Test
    fun updatesGeneratedBackingFieldAndKeepsStateBranchDisabled() {
        setLiveLiteralBackingField(HelperShape::class.java, "String\$key", "new")

        assertEquals("new", HelperShape.`String$key`)
        assertFalse(HelperShape.enabled)
    }

    @Test
    fun rejectsFinalBackingField() {
        assertThrows(IllegalArgumentException::class.java) {
            setLiveLiteralBackingField(FinalShape::class.java, "String\$key", "new")
        }
    }
}
