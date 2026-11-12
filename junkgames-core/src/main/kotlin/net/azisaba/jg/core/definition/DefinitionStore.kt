package net.azisaba.jg.core.definition

import net.azisaba.jg.api.block.BlockDefinition
import net.azisaba.jg.api.ir.Diagnostic
import net.azisaba.jg.api.ir.MapDefinition
import net.azisaba.jg.api.ir.MapRequirements
import net.azisaba.jg.api.runtime.Limits
import net.azisaba.jg.core.compile.CompiledGame
import net.azisaba.jg.core.compile.GameCompiler
import net.azisaba.jg.core.ir.IrParser
import net.azisaba.jg.core.provider.CapabilityRegistry
import net.azisaba.jg.core.util.SemVer
import org.bukkit.Bukkit
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Loaded game / map definitions. In P0/P1 they are read from local files (`games/<id>.jgame.json`,
 * `maps/<id>.jmap.json`); the repository client (P2) will feed the same store. A new version only
 * affects matches started afterwards — running instances keep their own [CompiledGame] (spec §10.2).
 */
class DefinitionStore(
    val gamesDir: Path,
    val mapsDir: Path,
    private val coreVersion: String,
    private val blocks: (String) -> BlockDefinition?,
    private val limits: Limits,
    private val capabilities: CapabilityRegistry,
) {
    data class LoadedGame(
        val id: String,
        val compiled: CompiledGame?,
        val diagnostics: List<Diagnostic>,
        val disabledReason: String?,
        val file: Path,
    ) {
        val playable: Boolean get() = compiled != null && disabledReason == null
    }

    data class LoadedMap(val id: String, val map: MapDefinition?, val diagnostics: List<Diagnostic>, val file: Path)

    @Volatile
    private var games: Map<String, LoadedGame> = emptyMap()

    @Volatile
    private var maps: Map<String, LoadedMap> = emptyMap()

    /** Reads and compiles everything. Blocking I/O: call off the tick threads. */
    @Synchronized
    fun reload() {
        Files.createDirectories(gamesDir)
        Files.createDirectories(mapsDir)
        val g = sortedMapOf<String, LoadedGame>()
        for (f in list(gamesDir, ".jgame.json")) {
            val lg = loadGame(Files.readString(f), f)
            val prev = g.putIfAbsent(lg.id, lg)
            if (prev != null) {
                g["${lg.id}@${f.name}"] = lg.copy(
                    compiled = null, disabledReason = "重複",
                    diagnostics = lg.diagnostics + Diagnostic.error(null, "/id", "ゲームIDが ${prev.file.name} と重複しています"),
                )
            }
        }
        val m = sortedMapOf<String, LoadedMap>()
        for (f in list(mapsDir, ".jmap.json")) {
            val p = IrParser(blocks)
            val md = p.parseMap(Files.readString(f))
            val id = md?.id ?: f.name
            m[id] = LoadedMap(id, if (p.hasErrors) null else md, p.diagnostics.toList(), f)
        }
        games = g.toMap()
        maps = m.toMap()
    }

    /** Parses, validates and compiles one game (also used by `/jg validate`). */
    fun loadGame(json: String, file: Path): LoadedGame {
        val parser = IrParser(blocks)
        val def = parser.parseGame(json)
        val diags = ArrayList(parser.diagnostics)
        val id = def?.id ?: file.name
        if (def == null || parser.hasErrors) return LoadedGame(id, null, diags, "検証エラー", file)

        val compiler = GameCompiler(blocks, limits)
        val compiled = compiler.compile(def)
        diags += compiler.diagnostics
        if (compiled == null) return LoadedGame(id, null, diags, "コンパイルエラー", file)

        var disabled: String? = null
        if (!SemVer.satisfies(coreVersion, def.requiresCore)) {
            disabled = "コアの版 $coreVersion は要求 ${def.requiresCore} を満たしません"
        }
        for ((name, range) in def.requiresAddons) {
            val addon = Bukkit.getPluginManager().getPlugin(name)
            disabled = when {
                addon == null || !addon.isEnabled -> "アドオン $name がありません"
                !SemVer.satisfies(addon.pluginMeta.version, range) -> "アドオン $name の版が要求 $range を満たしません"
                else -> disabled
            }
        }
        val missing = capabilities.unsatisfied(compiled.requiredCapabilities)
        if (missing.isNotEmpty()) disabled = "このサーバーで使えない能力があります: ${missing.joinToString(", ")}"
        return LoadedGame(id, compiled, diags, disabled, file)
    }

    fun games(): Collection<LoadedGame> = games.values
    fun game(id: String): LoadedGame? = games[id]
    fun maps(): Collection<LoadedMap> = maps.values
    fun map(id: String): MapDefinition? = maps[id]?.map

    /** Maps that declare the game compatible and satisfy its requirements. */
    fun compatibleMaps(game: CompiledGame): List<MapDefinition> = maps.values.mapNotNull { it.map }
        .filter { game.id in it.compatibleGames && problems(game.definition.mapRequirements, it).isEmpty() }

    companion object {
        /** Why a map cannot host a game (empty = OK). Used on map save (spec §11) and on /play. */
        fun problems(req: MapRequirements, map: MapDefinition): List<String> = buildList {
            for ((name, pr) in req.points) {
                val n = map.points[name]?.size ?: 0
                val min = maxOf(1, pr.min)
                if (n < min) add("地点 $name が $n 個しかありません（必要: $min 個以上）")
                if (!pr.multiple && n > 1) add("地点 $name は1つだけにしてください")
            }
            for (r in req.regions.keys) if (map.regions[r].isNullOrEmpty()) add("領域 $r がありません")
        }

        private fun list(dir: Path, suffix: String): List<Path> =
            Files.list(dir).use { s -> s.filter { it.name.endsWith(suffix) }.sorted().toList() }
    }
}
