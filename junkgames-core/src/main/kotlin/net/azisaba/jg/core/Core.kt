package net.azisaba.jg.core

import net.azisaba.jg.core.block.BlockRegistryImpl
import net.azisaba.jg.core.config.CoreConfig
import net.azisaba.jg.core.definition.DefinitionStore
import net.azisaba.jg.core.instance.InstanceManager
import net.azisaba.jg.core.instance.SnapshotStore
import net.azisaba.jg.core.provider.CapabilityRegistry
import net.azisaba.jg.core.util.Outbox
import org.bukkit.Location
import org.bukkit.plugin.Plugin

/** Core services, handed to subsystems instead of the plugin class. */
interface Core {
    val plugin: Plugin
    val settings: CoreConfig
    val capabilities: CapabilityRegistry
    val outbox: Outbox
    val snapshots: SnapshotStore
    val definitions: DefinitionStore
    val instances: InstanceManager
    val registry: BlockRegistryImpl

    /** Where players go when no snapshot location is available. */
    fun lobby(): Location
}
