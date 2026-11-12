package net.azisaba.jg.core.block.std

import net.azisaba.jg.api.block.BlockBuilder
import net.azisaba.jg.api.block.BlockDefinition
import net.azisaba.jg.api.block.Category
import net.azisaba.jg.api.block.CompileException
import net.azisaba.jg.api.block.FieldSpec
import net.azisaba.jg.api.runtime.Limits
import net.azisaba.jg.core.block.BlockRegistryImpl
import net.azisaba.jg.core.compile.GameCompiler

/** The MVP standard library (spec §6.2). */
object StdBlocks {
    const val OWNER = "JunkGamesCore"

    fun all(limits: Limits): List<BlockDefinition> = buildList {
        addAll(EventBlocks.all())
        addAll(ControlBlocks.all())
        addAll(GameBlocks.all())
        addAll(PlayerBlocks.all())
        addAll(WorldBlocks.all())
        addAll(UiBlocks.all())
        addAll(ViewBlocks.all())
        addAll(VarBlocks.all(limits.maxListSize))
        addAll(OpBlocks.all(limits.maxStringLength))
        // Calls are compiled by the compiler itself; this entry only lets the editor know the block.
        add(
            BlockBuilder.stack(GameCompiler.CUSTOM_CALL).category(Category.CUSTOM)
                .label("%name を呼ぶ", "call %name")
                .field(FieldSpec.of("name", FieldSpec.Kind.CUSTOM_BLOCK))
                .compiler { _, _ -> throw CompileException("custom.call is compiled by the core") },
        )
    }

    fun registerAll(registry: BlockRegistryImpl, limits: Limits) = all(limits).forEach { registry.register(OWNER, it) }
}
