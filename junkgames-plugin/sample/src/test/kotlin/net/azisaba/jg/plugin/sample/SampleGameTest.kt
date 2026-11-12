package net.azisaba.jg.plugin.sample

import net.azisaba.jg.api.runtime.Limits
import net.azisaba.jg.core.block.BlockRegistryImpl
import net.azisaba.jg.core.block.std.StdBlocks
import net.azisaba.jg.core.compile.GameCompiler
import net.azisaba.jg.core.ir.IrParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Compiles the bundled game with the standard blocks + this plugin's blocks. No server needed. */
class SampleGameTest {
    @Test
    fun `bundled game compiles with the sample blocks`() {
        val registry = BlockRegistryImpl()
        StdBlocks.registerAll(registry, Limits())
        SampleBlocks.all("JunkGamesSample").forEach { registry.register("JunkGamesSample", it) }

        val json = javaClass.getResource("/games/sample_storm.jgame.json")!!.readText()
        val parser = IrParser { registry[it] }
        val def = parser.parseGame(json)!!
        assertTrue(parser.diagnostics.isEmpty(), parser.diagnostics.toString())

        val compiler = GameCompiler({ registry[it] }, Limits())
        val game = compiler.compile(def)
        assertTrue(compiler.diagnostics.none { it.isError }, compiler.diagnostics.toString())
        assertEquals(setOf("JunkGamesCore", "JunkGamesSample"), game!!.usedBlockOwners)
    }
}
