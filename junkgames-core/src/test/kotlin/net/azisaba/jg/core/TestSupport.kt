package net.azisaba.jg.core

import net.azisaba.jg.api.block.BlockDefinition
import net.azisaba.jg.api.ir.Diagnostic
import net.azisaba.jg.api.runtime.BroadcastHandle
import net.azisaba.jg.api.runtime.GameContext
import net.azisaba.jg.api.runtime.GamePlayer
import net.azisaba.jg.api.runtime.GameState
import net.azisaba.jg.api.runtime.GameTeam
import net.azisaba.jg.api.runtime.Limits
import net.azisaba.jg.api.runtime.Region
import net.azisaba.jg.api.runtime.TriggerEvent
import net.azisaba.jg.core.block.BlockRegistryImpl
import net.azisaba.jg.core.block.std.StdBlocks
import net.azisaba.jg.core.compile.CompiledGame
import net.azisaba.jg.core.compile.GameCompiler
import net.azisaba.jg.core.ir.IrParser
import net.azisaba.jg.core.runtime.InstanceRuntime
import net.azisaba.jg.core.runtime.RuntimeListener
import net.azisaba.jg.platform.Provider
import net.azisaba.jg.platform.ViewHandle
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.Random

/** Compiles IR and runs it on a fake instance — no Minecraft server needed. */
object TestSupport {
    val registry = BlockRegistryImpl().also { StdBlocks.registerAll(it, Limits()) }
    val blocks: (String) -> BlockDefinition? = { registry[it] }

    fun game(scripts: String, variables: String = "[]", customBlocks: String = "[]", meta: String = ""): String = """
        {
          "format": "junkgames/game", "formatVersion": 1, "id": "test", "version": 1,
          "meta": { "displayName": "Test", "minPlayers": 1, "maxPlayers": 4 $meta },
          "mapRequirements": { "points": { "spawn": { "multiple": true } }, "regions": { "floor": {} } },
          "variables": $variables,
          "scripts": $scripts,
          "customBlocks": $customBlocks
        }
    """.trimIndent()

    class Result(val compiled: CompiledGame?, val diagnostics: List<Diagnostic>) {
        val errors get() = diagnostics.filter { it.isError }
    }

    fun compile(json: String, limits: Limits = Limits()): Result {
        val p = IrParser(blocks)
        val def = p.parseGame(json)
        if (def == null || p.hasErrors) return Result(null, p.diagnostics)
        val c = GameCompiler(blocks, limits)
        val g = c.compile(def)
        return Result(g, p.diagnostics + c.diagnostics)
    }

    fun run(json: String, limits: Limits = Limits()): Fake {
        val r = compile(json, limits)
        check(r.compiled != null) { "compile failed: ${r.diagnostics}" }
        return Fake(r.compiled, limits)
    }

    class Fake(game: CompiledGame, limits: Limits) : GameContext {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        val vars = HashMap<String, Any?>()
        var ended = false
        val runtime = InstanceRuntime(game, this, limits, object : RuntimeListener {
            override fun runtimeError(scriptId: String, nodeId: String?, message: String) {
                errors += "$nodeId: $message"
            }

            override fun runtimeWarning(scriptId: String, nodeId: String?, message: String) {
                warnings += message
            }
        })

        fun fire(type: String) = runtime.fireSync(TriggerEvent(type, false))
        fun ticks(n: Int) = repeat(n) { runtime.runTick() }

        override val instanceId = "test-1"
        override val gameId = "test"
        override val gameVersion = 1
        override val mapId = "m"
        override val world: World get() = throw UnsupportedOperationException()
        override var state = GameState.RUNNING
        override val tick: Long get() = runtime.tick
        override val timeLeftSeconds = -1
        override val random = Random(1)
        override fun players(): List<GamePlayer> = emptyList()
        override fun alivePlayers(): List<GamePlayer> = emptyList()
        override fun wrap(player: Player): GamePlayer? = null
        override fun teams(): List<GameTeam> = emptyList()
        override fun points(name: String): List<Location> = emptyList()
        override fun region(name: String?): Region? = null
        override fun endGame(winner: Any?) {
            ended = true
        }

        override fun eliminate(player: GamePlayer) {}
        override fun makeSpectator(player: GamePlayer) {}
        override fun stopAll() = runtime.stopAll()
        override fun broadcast(message: String, value: Any?): BroadcastHandle = runtime.broadcast(message, value)
        override fun getVariable(name: String, target: Any?): Any? = vars[name] ?: 0.0
        override fun setVariable(name: String, target: Any?, value: Any?) {
            vars[name] = value
        }

        override val limits: Limits = limits
        override fun spawnTracked(location: Location, type: EntityType): Entity? = null
        override fun trackView(owner: GamePlayer, handle: ViewHandle) {}
        override fun <T : Provider> provider(type: Class<T>): T? = null
        override fun kit(name: String): List<ItemStack> = emptyList()
        override fun setSidebarTitle(target: GamePlayer?, miniMessage: String) {}
        override fun setSidebarLine(target: GamePlayer?, line: Int, miniMessage: String?) {}
        override fun showBossBar(id: String, target: GamePlayer?, miniMessage: String, progress: Float, color: String) {}
        override fun hideBossBar(id: String, target: GamePlayer?) {}
        override fun announce(miniMessage: String) {}
        override fun warn(message: String) {
            warnings += message
        }
    }
}
