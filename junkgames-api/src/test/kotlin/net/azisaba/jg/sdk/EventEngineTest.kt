package net.azisaba.jg.sdk

import net.azisaba.jg.sdk.event.EventEngine
import net.azisaba.jg.sdk.event.JunkGameEvent
import net.azisaba.jg.sdk.event.JunkGameHandler
import net.azisaba.jg.sdk.event.JunkGameListener
import net.azisaba.jg.util.MathUtility
import net.azisaba.jg.util.UuidUtility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventEngineTest {
    open class Base : JunkGameEvent
    class Child : Base()
    class Other : JunkGameEvent

    class Listener : JunkGameListener {
        val got = ArrayList<String>()

        @JunkGameHandler
        fun onChild(e: Child) {
            got += "child"
        }

        @JunkGameHandler
        fun onBase(e: Base) {
            got += "base"
        }

        @JunkGameHandler
        private fun onOther(e: Other) {
            got += "other"
            error("boom")
        }

        @Suppress("unused")
        fun notAHandler(s: String) {
            got += s
        }
    }

    @Test
    fun `dispatches by type, supertypes included, and survives handler errors`() {
        val engine = EventEngine()
        val l = Listener()
        engine.register(l)
        engine.register(l) // registering twice does not duplicate
        engine.call(Child())
        assertEquals(listOf("base", "child"), l.got.sorted())
        engine.call(Other())
        assertTrue("other" in l.got)
        engine.call(Base())
        assertEquals(1, l.got.count { it == "child" })
        assertEquals(listOf(l), engine.listeners())
        engine.unregister(l)
        engine.call(Child())
        assertEquals(1, l.got.count { it == "child" })
    }

    @Test
    fun utilities() {
        assertTrue(MathUtility.isInt("123"))
        assertFalse(MathUtility.isInt("-1"))
        assertTrue(MathUtility.isDouble("1.5"))
        assertTrue(UuidUtility.isUuid("8667ba71-b85a-4004-af54-457a9734eed7"))
        assertFalse(UuidUtility.isUuid("nope"))
    }
}
