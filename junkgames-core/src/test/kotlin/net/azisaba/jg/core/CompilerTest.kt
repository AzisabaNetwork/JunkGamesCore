package net.azisaba.jg.core

import net.azisaba.jg.core.TestSupport.compile
import net.azisaba.jg.core.TestSupport.game
import net.azisaba.jg.core.util.SemVer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompilerTest {
    private fun errorsOf(scripts: String, vars: String = "[]") = compile(game(scripts, vars)).errors

    @Test
    fun `bundled TNT Run sample compiles`() {
        val json = javaClass.getResource("/games/tnt_run.jgame.json")!!.readText()
        val r = compile(json)
        assertTrue(r.errors.isEmpty(), r.errors.toString())
        val g = r.compiled!!
        assertEquals(7, g.scripts.size)
        assertEquals(setOf("JunkGamesCore"), g.usedBlockOwners)
    }

    @Test
    fun `type mismatch is reported with the node id`() {
        val errors = errorsOf(
            """[{"id":"s","trigger":{"type":"event.game_start"},"body":[
               {"id":"bad","type":"control.wait_seconds","inputs":{"seconds":{"id":"p","type":"event.player"}}}]}]""",
        )
        assertEquals(1, errors.size, errors.toString())
        assertEquals("bad", errors[0].nodeId)
        assertTrue("型が合いません" in errors[0].message)
    }

    @Test
    fun `unknown block`() {
        val errors = errorsOf("""[{"id":"s","trigger":{"type":"event.game_start"},"body":[{"id":"u","type":"foo.bar"}]}]""")
        assertEquals("u", errors.single().nodeId)
    }

    @Test
    fun `nothing may follow a cap block`() {
        val errors = errorsOf(
            """[{"id":"s","trigger":{"type":"event.game_start"},"body":[
               {"id":"a","type":"control.stop_script"},{"id":"b","type":"ui.announce","inputs":{"text":{"lit":"x"}}}]}]""",
        )
        assertEquals("b", errors.single().nodeId)
    }

    @Test
    fun `undeclared variable and map references are compile errors`() {
        val e1 = errorsOf("""[{"id":"s","trigger":{"type":"event.game_start"},"body":[{"id":"v","type":"var.set","fields":{"name":"nope"},"inputs":{"value":{"lit":1}}}]}]""")
        assertEquals("v", e1.single().nodeId)
        val e2 = errorsOf("""[{"id":"s","trigger":{"type":"event.region_enter","id":"h","inputs":{"region":{"lit":{"regionRef":"missing"}}}},"body":[]}]""")
        assertEquals("h", e2.single().nodeId)
    }

    @Test
    fun `waiting inside a custom reporter is rejected`() {
        val r = compile(
            game(
                "[]",
                customBlocks = """[{"name":"f","params":[],"returns":"number","body":[{"id":"w","type":"control.wait_ticks","inputs":{"ticks":{"lit":1}}}]}]""",
            ),
        )
        assertEquals("w", r.errors.single().nodeId)
    }

    @Test
    fun `dropdown values are validated`() {
        val errors = errorsOf("""[{"id":"s","trigger":{"type":"event.game_start"},"body":[{"id":"e","type":"game.end","fields":{"kind":"draw"}}]}]""")
        assertEquals("e", errors.single().nodeId)
    }

    @Test
    fun `newer block schema versions are refused`() {
        val r = compile(game("""[{"id":"s","trigger":{"type":"event.game_start"},"body":[{"id":"n","type":"control.stop_script","v":9}]}]"""))
        assertFalse(r.errors.isEmpty())
    }

    @Test
    fun semver() {
        assertTrue(SemVer.satisfies("1.0.0-SNAPSHOT", ">=1.0.0"))
        assertTrue(SemVer.satisfies("1.4.2", "^1.2"))
        assertFalse(SemVer.satisfies("2.0.0", "^1.2"))
        assertTrue(SemVer.satisfies("1.5.0", ">=1.0 <2"))
    }
}
