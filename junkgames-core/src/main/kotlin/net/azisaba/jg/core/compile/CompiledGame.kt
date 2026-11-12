package net.azisaba.jg.core.compile

import net.azisaba.jg.api.ir.GameDefinition
import net.azisaba.jg.api.ir.IrCustomBlock
import net.azisaba.jg.api.ir.IrScript
import net.azisaba.jg.api.ir.IrVariable
import net.azisaba.jg.api.runtime.Node
import net.azisaba.jg.api.runtime.Stmt
import net.azisaba.jg.api.runtime.TriggerSpec

/** Result of compiling a game definition: executable node trees, indexed for dispatch. */
class CompiledGame(
    val definition: GameDefinition,
    val scripts: List<Script>,
    private val scriptsByEvent: Map<String, List<Script>>,
    val customBlocks: Map<String, CustomBlock>,
    val variables: Map<String, IrVariable>,
    /** Identity map compiled node -> editor node id (errors, live highlighting). */
    private val nodeIds: Map<Node, String>,
    val requiredCapabilities: Set<String>,
    val usedBlockOwners: Set<String>,
) {
    class Script(val index: Int, val id: String, val trigger: TriggerSpec, val concurrency: IrScript.Concurrency, val body: Stmt)

    /** Mutable holder so that (mutually) recursive custom blocks can reference each other. */
    class CustomBlock(val ir: IrCustomBlock) {
        var body: Stmt = Seq.EMPTY
    }

    val id: String get() = definition.id
    val version: Int get() = definition.version

    fun scriptsFor(eventType: String): List<Script> = scriptsByEvent[eventType] ?: emptyList()

    fun nodeId(node: Node?): String? = node?.let { nodeIds[it] }
}
