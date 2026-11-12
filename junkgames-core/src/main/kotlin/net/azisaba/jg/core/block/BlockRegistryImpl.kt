package net.azisaba.jg.core.block

import net.azisaba.jg.api.block.BlockDefinition
import net.azisaba.jg.api.block.BlockRegistry
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Thread-safe block registry. Changes notify listeners (the core recompiles games). */
class BlockRegistryImpl : BlockRegistry {
    private val blocks = ConcurrentHashMap<String, BlockDefinition>()
    private val listeners = CopyOnWriteArrayList<Runnable>()

    override fun register(owner: Plugin, definition: BlockDefinition) = register(owner.name, definition)

    fun register(owner: String, definition: BlockDefinition) {
        val def = if (definition.owner == owner) definition else Owned(definition, owner)
        val prev = blocks.putIfAbsent(def.id, def)
        require(prev == null) { "block already registered: ${def.id} (by ${prev?.owner})" }
        listeners.forEach { it.run() }
    }

    override fun unregisterAll(owner: Plugin) {
        if (blocks.values.removeIf { it.owner == owner.name }) listeners.forEach { it.run() }
    }

    override fun get(id: String): BlockDefinition? = blocks[id]

    override fun all(): Collection<BlockDefinition> = blocks.values.sortedBy { it.id }

    fun addListener(r: Runnable) {
        listeners += r
    }

    /** Overrides [BlockDefinition.owner] with the registering plugin. */
    private class Owned(private val d: BlockDefinition, override val owner: String) : BlockDefinition by d
}
