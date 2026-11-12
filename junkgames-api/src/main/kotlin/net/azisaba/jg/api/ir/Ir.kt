package net.azisaba.jg.api.ir

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import net.azisaba.jg.api.type.ValueType
import net.azisaba.jg.platform.BlockBox
import net.azisaba.jg.platform.WorldSpec

/**
 * One block in the IR (spec §5.3), or a literal (`{"lit": ...}`). Immutable.
 * Statement sequences are arrays, not Blockly `next` chains.
 */
class IrNode private constructor(
    /** Editor-assigned id; null for literals and synthetic nodes. */
    val id: String?,
    val type: String,
    val v: Int,
    val fields: Map<String, JsonElement>,
    val inputs: Map<String, IrNode>,
    val body: Map<String, List<IrNode>>,
    val lit: JsonElement?,
) {
    val isLiteral: Boolean get() = lit != null

    fun fieldString(name: String): String? {
        val e = fields[name] ?: return null
        if (e.isJsonNull) return null
        return if (e.isJsonPrimitive) e.asString else e.toString()
    }

    override fun toString(): String = if (isLiteral) "lit($lit)" else "$type#$id"

    companion object {
        @JvmStatic
        fun block(
            id: String?, type: String, v: Int,
            fields: Map<String, JsonElement>, inputs: Map<String, IrNode>, body: Map<String, List<IrNode>>,
        ) = IrNode(id, type, v, fields.toMap(), inputs.toMap(), body.toMap(), null)

        @JvmStatic
        fun literal(value: JsonElement) = IrNode(null, "", 0, emptyMap(), emptyMap(), emptyMap(), value)

        @JvmStatic
        fun literal(value: Number) = literal(JsonPrimitive(value))

        @JvmStatic
        fun literal(value: String) = literal(JsonPrimitive(value))

        /** Synthetic node, used for input defaults such as `event.player`. */
        @JvmStatic
        fun synthetic(type: String) = IrNode(null, type, 1, emptyMap(), emptyMap(), emptyMap(), null)
    }
}

/** A script: a hat (trigger) and a body (spec §5.4). */
data class IrScript(val id: String, val trigger: IrNode, val concurrency: Concurrency, val body: List<IrNode>) {
    /** What happens when the trigger fires again while a run is still active. */
    enum class Concurrency {
        /** Start a new run every time (default). */
        PARALLEL,

        /** Stop the running one and start over (Scratch). */
        RESTART,

        /** Ignore while running. */
        DROP,

        /** Like DROP, but per context player. */
        PER_PLAYER;

        companion object {
            @JvmStatic
            fun parse(s: String) = valueOf(s.uppercase())
        }
    }
}

/** A user-defined block (function), spec §5.6. `returns == null` means a stack block. */
data class IrCustomBlock(val name: String, val params: List<Param>, val returns: ValueType?, val body: List<IrNode>) {
    data class Param(val name: String, val type: ValueType)
}

/** A declared variable. */
data class IrVariable(val name: String, val scope: Scope, val type: ValueType, val defaultValue: JsonElement?) {
    enum class Scope {
        /** One value per instance (match). */
        INSTANCE,

        /** One value per participant. */
        PLAYER,

        /** One value per team. */
        TEAM;

        companion object {
            @JvmStatic
            fun parse(s: String) = valueOf(s.uppercase())
        }
    }
}

/**
 * Game meta settings (spec §5.2). Everything the core handles itself (lifecycle, lobby, teams,
 * isolation) is configured here rather than written in blocks.
 *
 * @property loopYield whether loops implicitly yield one tick per iteration (Scratch: true)
 * @property endingSeconds how long results are shown before the world is reset
 * @property kits named kits for `player.apply_kit`: name -> item JSON list
 */
data class GameMeta(
    val displayName: String,
    val description: String,
    val minPlayers: Int,
    val maxPlayers: Int,
    val teams: Teams,
    val countdownSeconds: Int,
    val timeLimitSeconds: Int,
    val allowLateJoin: Boolean,
    val spectators: Boolean,
    val onDisconnect: OnDisconnect,
    val loopYield: Boolean,
    val endingSeconds: Int,
    val rules: Rules,
    val kits: Map<String, List<JsonElement>>,
    /** Icon in the game selection screen (`/junkgames`). */
    val favicon: String = "minecraft:paper",
    /** Categories shown in the selection screen, separated by spaces. */
    val category: String = "",
    /** Extra lines (MiniMessage) in the selection screen. */
    val lore: List<String> = emptyList(),
    /** Order in the selection screen (lower first), shared with SDK games. */
    val priority: Int = 100,
) {
    enum class OnDisconnect { ELIMINATE, KEEP }

    data class Teams(val mode: Mode, val list: List<TeamDef>, val friendlyFire: Boolean) {
        enum class Mode { NONE, TEAMS }

        companion object {
            @JvmStatic
            fun none() = Teams(Mode.NONE, emptyList(), true)
        }
    }

    /** @property color a NamedTextColor name ("red", "blue", ...) */
    data class TeamDef(val id: String, val name: String, val color: String)

    /** Default world rules inside the arena. Scripts can still cancel / allow individual events. */
    data class Rules(
        val blockBreak: Boolean = true,
        val blockPlace: Boolean = true,
        val pvp: Boolean = true,
        val hunger: Boolean = true,
        val fallDamage: Boolean = true,
        val itemDrop: Boolean = true,
        val naturalRegen: Boolean = true,
    )
}

/** Named points / regions a game needs (spec §3). A map can host a game iff it defines all of them. */
data class MapRequirements(val points: Map<String, PointReq>, val regions: Map<String, RegionReq>) {
    /** @property multiple whether several locations may be registered under the name */
    data class PointReq(val multiple: Boolean, val min: Int)

    class RegionReq

    companion object {
        @JvmStatic
        fun empty() = MapRequirements(emptyMap(), emptyMap())
    }
}

/** Parsed `.jgame.json` (spec §5.2). */
data class GameDefinition(
    val formatVersion: Int,
    val id: String,
    val version: Int,
    val meta: GameMeta,
    val mapRequirements: MapRequirements,
    val variables: List<IrVariable>,
    val scripts: List<IrScript>,
    val customBlocks: List<IrCustomBlock>,
    val requiresCore: String,
    val requiresAddons: Map<String, String>,
    val sourceHash: String,
) {
    companion object {
        const val FORMAT = "junkgames/game"
        const val FORMAT_VERSION = 1
    }
}

/** Parsed `.jmap.json` (spec §5.7). Coordinates are in the arena world. */
data class MapDefinition(
    val formatVersion: Int,
    val id: String,
    val displayName: String,
    val world: WorldSpec,
    val compatibleGames: List<String>,
    val points: Map<String, List<Point>>,
    val regions: Map<String, List<BlockBox>>,
) {
    data class Point(val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)

    companion object {
        const val FORMAT = "junkgames/map"
        const val FORMAT_VERSION = 1
    }
}

/**
 * A validation / compile problem. [nodeId] lets the editor mark the offending block.
 *
 * @property path JSON-pointer-ish location ("/scripts/0/body/2")
 */
data class Diagnostic(val severity: Severity, val nodeId: String?, val path: String, val message: String) {
    enum class Severity { ERROR, WARNING }

    val isError: Boolean get() = severity == Severity.ERROR

    override fun toString() = "$severity $path${if (nodeId != null) " [$nodeId]" else ""}: $message"

    companion object {
        @JvmStatic
        fun error(nodeId: String?, path: String, message: String) = Diagnostic(Severity.ERROR, nodeId, path, message)

        @JvmStatic
        fun warning(nodeId: String?, path: String, message: String) = Diagnostic(Severity.WARNING, nodeId, path, message)
    }
}
