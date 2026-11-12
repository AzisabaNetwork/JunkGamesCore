package net.azisaba.jg.core.ir

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.azisaba.jg.api.block.BlockDefinition
import net.azisaba.jg.api.ir.Diagnostic
import net.azisaba.jg.api.ir.GameDefinition
import net.azisaba.jg.api.ir.GameMeta
import net.azisaba.jg.api.ir.IrCustomBlock
import net.azisaba.jg.api.ir.IrNode
import net.azisaba.jg.api.ir.IrScript
import net.azisaba.jg.api.ir.IrVariable
import net.azisaba.jg.api.ir.MapDefinition
import net.azisaba.jg.api.ir.MapRequirements
import net.azisaba.jg.api.type.ValueType
import net.azisaba.jg.platform.BlockBox
import net.azisaba.jg.platform.ResetMode
import net.azisaba.jg.platform.WorldSpec
import java.security.MessageDigest

/**
 * Loader / validator for IR JSON (spec §5): structural checks, defaults and block migrations.
 * Type checking happens in the compiler. One parser per document.
 */
class IrParser(private val blocks: (String) -> BlockDefinition?) {
    val diagnostics = ArrayList<Diagnostic>()
    private val nodeIds = HashSet<String>()

    val hasErrors: Boolean get() = diagnostics.any { it.isError }

    // ------------------------------------------------------------------ game

    fun parseGame(json: String): GameDefinition? {
        val root = parseRoot(json) ?: return null
        return parseGame(root, sha256(json))
    }

    fun parseGame(o: JsonObject, hash: String): GameDefinition? {
        if (o.str("format") != GameDefinition.FORMAT) error(null, "/format", "format は \"${GameDefinition.FORMAT}\" である必要があります")
        val formatVersion = o.int("formatVersion", GameDefinition.FORMAT_VERSION)
        if (formatVersion > GameDefinition.FORMAT_VERSION) {
            error(null, "/formatVersion", "このコアが読めない formatVersion です: $formatVersion")
            return null
        }
        var id = o.str("id")
        if (id == null || !ID.matches(id)) {
            error(null, "/id", "id は [a-z0-9_] の1〜64文字で指定してください")
            id = "invalid"
        }
        val meta = parseMeta(o.obj("meta"), "/meta")
        val req = parseRequirements(o.obj("mapRequirements"))
        val vars = parseVariables(o.arr("variables"))
        val customs = parseCustomBlocks(o.arr("customBlocks"))
        val scripts = parseScripts(o.arr("scripts"))
        val requires = o.obj("requires")
        val addons = LinkedHashMap<String, String>()
        requires?.obj("addons")?.entrySet()?.forEach { addons[it.key] = it.value.asString }
        return GameDefinition(
            formatVersion, id, o.int("version", 0), meta, req, vars, scripts, customs,
            requires?.str("core") ?: ">=0.0.0", addons, hash,
        )
    }

    private fun parseMeta(m0: JsonObject?, path: String): GameMeta {
        val m = m0 ?: JsonObject().also { error(null, path, "meta がありません") }
        val min = m.int("minPlayers", 1)
        val max = m.int("maxPlayers", 16)
        if (min < 1) error(null, "$path/minPlayers", "minPlayers は1以上にしてください")
        if (max < min) error(null, "$path/maxPlayers", "maxPlayers は minPlayers 以上にしてください")

        var teams = GameMeta.Teams.none()
        m.obj("teams")?.let { t ->
            when (t.str("mode") ?: "none") {
                "teams" -> {
                    val list = ArrayList<GameMeta.TeamDef>()
                    t.arr("list")?.forEach { e ->
                        val to = e.asJsonObject
                        val tid = to.str("id")
                        if (tid == null || !ID.matches(tid)) error(null, "$path/teams/list", "チームIDが不正です: $tid")
                        else list += GameMeta.TeamDef(tid, to.str("name") ?: tid, to.str("color") ?: "white")
                    }
                    if (list.size < 2) error(null, "$path/teams/list", "teams モードでは2チーム以上が必要です")
                    teams = GameMeta.Teams(GameMeta.Teams.Mode.TEAMS, list, t.bool("friendlyFire", false))
                }
                "none" -> {}
                else -> error(null, "$path/teams/mode", "teams.mode は none か teams です")
            }
        }

        val rules = m.obj("rules")?.let { r ->
            GameMeta.Rules(
                r.bool("blockBreak", true), r.bool("blockPlace", true), r.bool("pvp", true), r.bool("hunger", true),
                r.bool("fallDamage", true), r.bool("itemDrop", true), r.bool("naturalRegen", true),
            )
        } ?: GameMeta.Rules()

        val kits = LinkedHashMap<String, List<JsonElement>>()
        m.obj("kits")?.entrySet()?.forEach { (name, v) ->
            if (v.isJsonArray) kits[name] = v.asJsonArray.toList()
            else error(null, "$path/kits/$name", "キットはアイテムの配列で指定してください")
        }

        val onDisconnect = runCatching { GameMeta.OnDisconnect.valueOf((m.str("onDisconnect") ?: "eliminate").uppercase()) }
            .getOrElse {
                error(null, "$path/onDisconnect", "onDisconnect は eliminate か keep です")
                GameMeta.OnDisconnect.ELIMINATE
            }
        // accept both timeLimitSeconds (spec §5.2) and timeLimit (spec §4)
        val timeLimit = m.int("timeLimitSeconds", m.int("timeLimit", 0))
        return GameMeta(
            displayName = m.str("displayName") ?: "?",
            description = m.str("description") ?: "",
            minPlayers = min, maxPlayers = max, teams = teams,
            countdownSeconds = maxOf(0, m.int("countdownSeconds", 10)),
            timeLimitSeconds = maxOf(0, timeLimit),
            allowLateJoin = m.bool("allowLateJoin", false),
            spectators = m.bool("spectators", true),
            onDisconnect = onDisconnect,
            loopYield = m.bool("loopYield", true),
            endingSeconds = maxOf(0, m.int("endingSeconds", 5)),
            rules = rules, kits = kits,
            favicon = m.str("favicon") ?: "minecraft:paper",
            category = m.str("category") ?: "",
            lore = m.arr("lore")?.map { it.asString } ?: emptyList(),
            priority = m.int("priority", 100),
        )
    }

    private fun parseRequirements(o: JsonObject?): MapRequirements {
        if (o == null) return MapRequirements.empty()
        val points = LinkedHashMap<String, MapRequirements.PointReq>()
        o.obj("points")?.entrySet()?.forEach { (name, v) ->
            checkName(name, "/mapRequirements/points")
            val spec = if (v.isJsonObject) v.asJsonObject else JsonObject()
            points[name] = MapRequirements.PointReq(spec.bool("multiple", false), spec.int("min", 1))
        }
        val regions = LinkedHashMap<String, MapRequirements.RegionReq>()
        o.obj("regions")?.entrySet()?.forEach { (name, _) ->
            checkName(name, "/mapRequirements/regions")
            regions[name] = MapRequirements.RegionReq()
        }
        return MapRequirements(points, regions)
    }

    private fun parseVariables(arr: JsonArray?): List<IrVariable> {
        val out = ArrayList<IrVariable>()
        val names = HashSet<String>()
        arr?.forEachIndexed { i, e ->
            val path = "/variables/$i"
            val v = e.asJsonObject
            val name = v.str("name")
            if (name == null || !checkName(name, "$path/name")) return@forEachIndexed
            if (!names.add(name)) {
                error(null, path, "変数名が重複しています: $name")
                return@forEachIndexed
            }
            val scope = runCatching { IrVariable.Scope.parse(v.str("scope") ?: "instance") }.getOrElse {
                error(null, "$path/scope", "scope は instance / player / team です")
                return@forEachIndexed
            }
            val type = runCatching { ValueType.parse(v.str("type") ?: "any") }.getOrElse {
                error(null, "$path/type", it.message ?: "bad type")
                return@forEachIndexed
            }
            out += IrVariable(name, scope, type, v.get("default"))
        }
        return out
    }

    private fun parseCustomBlocks(arr: JsonArray?): List<IrCustomBlock> {
        val out = ArrayList<IrCustomBlock>()
        val names = HashSet<String>()
        arr?.forEachIndexed { i, e ->
            val path = "/customBlocks/$i"
            val c = e.asJsonObject
            val name = c.str("name")
            if (name == null || !checkName(name, "$path/name")) return@forEachIndexed
            if (!names.add(name)) {
                error(null, path, "自作ブロック名が重複しています: $name")
                return@forEachIndexed
            }
            val params = ArrayList<IrCustomBlock.Param>()
            c.arr("params")?.forEach { pe ->
                val po = pe.asJsonObject
                val pn = po.str("name")
                if (pn != null && checkName(pn, "$path/params")) {
                    runCatching { params += IrCustomBlock.Param(pn, ValueType.parse(po.str("type") ?: "any")) }
                        .onFailure { error(null, "$path/params/$pn", it.message ?: "bad type") }
                }
            }
            val returns = c.get("returns")?.takeUnless { it.isJsonNull }?.let { r ->
                runCatching { ValueType.parse(r.asString) }.getOrElse {
                    error(null, "$path/returns", it.message ?: "bad type")
                    null
                }
            }
            out += IrCustomBlock(name, params, returns, parseSeq(c.arr("body"), "$path/body"))
        }
        return out
    }

    private fun parseScripts(arr: JsonArray?): List<IrScript> {
        val out = ArrayList<IrScript>()
        val ids = HashSet<String>()
        arr?.forEachIndexed { i, e ->
            val path = "/scripts/$i"
            if (!e.isJsonObject) {
                error(null, path, "スクリプトはオブジェクトです")
                return@forEachIndexed
            }
            val s = e.asJsonObject
            val id = s.str("id") ?: "s_$i"
            if (!ids.add(id)) warning(null, "$path/id", "スクリプトIDが重複しています: $id")
            val trig = s.obj("trigger")
            if (trig == null) {
                error(null, "$path/trigger", "trigger がありません")
                return@forEachIndexed
            }
            val trigger = parseNode(trig, "$path/trigger") ?: return@forEachIndexed
            val conc = runCatching { IrScript.Concurrency.parse(s.str("concurrency") ?: "parallel") }.getOrElse {
                error(trigger.id, "$path/concurrency", "concurrency は parallel / restart / drop / per_player です")
                IrScript.Concurrency.PARALLEL
            }
            out += IrScript(id, trigger, conc, parseSeq(s.arr("body"), "$path/body"))
        }
        return out
    }

    private fun parseSeq(arr: JsonArray?, path: String): List<IrNode> {
        val out = ArrayList<IrNode>()
        arr?.forEachIndexed { i, e ->
            if (!e.isJsonObject) error(null, "$path/$i", "文はオブジェクトです")
            else parseNode(e.asJsonObject, "$path/$i")?.let { out += it }
        }
        return out
    }

    /** Parses a node or a literal, applying migrations of the block's schema. */
    fun parseNode(o0: JsonObject, path: String): IrNode? {
        var o = o0
        if (o.has("lit")) return IrNode.literal(o.get("lit"))
        val id = o.str("id")
        val type = o.str("type") ?: run {
            error(id, path, "type がありません")
            return null
        }
        if (id != null && !nodeIds.add(id)) warning(id, path, "ノードIDが重複しています: $id")
        var v = o.int("v", 1)

        val def = blocks(type)
        if (def != null) {
            val current = def.schemaVersion
            if (v > current) {
                error(id, path, "$type の版 v$v はこのサーバー(v$current)より新しいため読めません")
                return null
            }
            while (v < current) {
                val m = def.migrations.firstOrNull { it.fromVersion == v } ?: run {
                    error(id, path, "$type の v$v から v$current への変換がありません")
                    return null
                }
                o = m.migrate(o.deepCopy())
                v = maxOf(v + 1, o.int("v", v + 1))
            }
        } // unknown types are reported by the compiler (so that the editor gets the node id)

        val fields = LinkedHashMap<String, JsonElement>()
        o.obj("fields")?.entrySet()?.forEach { fields[it.key] = it.value }
        val inputs = LinkedHashMap<String, IrNode>()
        o.obj("inputs")?.entrySet()?.forEach { (k, value) ->
            if (!value.isJsonObject) error(id, "$path/inputs/$k", "入力は {\"lit\": ...} かブロックで指定してください")
            else parseNode(value.asJsonObject, "$path/inputs/$k")?.let { inputs[k] = it }
        }
        val body = LinkedHashMap<String, List<IrNode>>()
        o.obj("body")?.entrySet()?.forEach { (k, value) ->
            if (!value.isJsonArray) error(id, "$path/body/$k", "body は文の配列で指定してください")
            else body[k] = parseSeq(value.asJsonArray, "$path/body/$k")
        }
        return IrNode.block(id, type, v, fields, inputs, body)
    }

    // ------------------------------------------------------------------ map

    fun parseMap(json: String): MapDefinition? = parseRoot(json)?.let { parseMap(it) }

    fun parseMap(o: JsonObject): MapDefinition? {
        if (o.str("format") != MapDefinition.FORMAT) error(null, "/format", "format は \"${MapDefinition.FORMAT}\" である必要があります")
        val fv = o.int("formatVersion", 1)
        if (fv > MapDefinition.FORMAT_VERSION) {
            error(null, "/formatVersion", "このコアが読めない formatVersion です: $fv")
            return null
        }
        val id = o.str("id")
        if (id == null || !ID.matches(id)) {
            error(null, "/id", "id は [a-z0-9_] の1〜64文字で指定してください")
            return null
        }
        val compatible = o.arr("compatibleGames")?.map { it.asString } ?: emptyList()

        val points = LinkedHashMap<String, List<MapDefinition.Point>>()
        o.obj("points")?.entrySet()?.forEach { (name, v) ->
            val a = if (v.isJsonArray) v.asJsonArray else JsonArray().also { if (v.isJsonObject) it.add(v) }
            points[name] = a.map { pe ->
                val po = pe.asJsonObject
                MapDefinition.Point(po.dbl("x"), po.dbl("y"), po.dbl("z"), po.dbl("yaw").toFloat(), po.dbl("pitch").toFloat())
            }
        }
        val regions = LinkedHashMap<String, List<BlockBox>>()
        o.obj("regions")?.entrySet()?.forEach { (name, v) ->
            val a = if (v.isJsonArray) v.asJsonArray else JsonArray().also { if (v.isJsonObject) it.add(v) }
            regions[name] = a.mapNotNull { be ->
                val bo = be.asJsonObject
                val min = ints(bo.arr("min"))
                val max = ints(bo.arr("max"))
                if (min == null || max == null) {
                    error(null, "/regions/$name", "領域は {\"min\":[x,y,z],\"max\":[x,y,z]} で指定してください")
                    null
                } else BlockBox.of(min[0], min[1], min[2], max[0], max[1], max[2])
            }
        }
        val world = parseWorld(o.obj("world") ?: JsonObject(), id, regions)
        return MapDefinition(fv, id, o.str("displayName") ?: id, world, compatible, points, regions)
    }

    private fun parseWorld(w: JsonObject, mapId: String, regions: Map<String, List<BlockBox>>): WorldSpec {
        val src = w.obj("source")
        var type = WorldSpec.SourceType.TEMPLATE
        var ref = "templates/$mapId"
        var origin: List<Int>? = null
        if (src != null) {
            type = runCatching { WorldSpec.SourceType.valueOf((src.str("type") ?: "template").uppercase()) }.getOrElse {
                error(null, "/world/source/type", "source.type は template / schematic / generate / existing です")
                WorldSpec.SourceType.TEMPLATE
            }
            ref = src.str("ref") ?: src.str("schematic") ?: ref
            origin = ints(src.arr("origin"))?.toList()
        }
        var ephemeral = !(w.str("lifecycle") ?: "ephemeral").equals("persistent", true)
        if (type == WorldSpec.SourceType.EXISTING) ephemeral = false
        val reset = runCatching { ResetMode.valueOf((w.str("reset") ?: if (ephemeral) "recreate" else "none").uppercase()) }.getOrElse {
            error(null, "/world/reset", "reset は recreate / snapshot / none です")
            ResetMode.RECREATE
        }
        val g = w.get("generator")
        val generator = when {
            g == null -> "void"
            g.isJsonObject -> g.asJsonObject.str("type") ?: "void"
            else -> g.asString
        }
        val snapshotBoxes = w.arr("snapshotRegions")?.flatMap { e ->
            regions[e.asString] ?: emptyList<BlockBox>().also { error(null, "/world/snapshotRegions", "未定義の領域です: ${e.asString}") }
        } ?: regions.values.flatten()
        return WorldSpec(
            type, ref, origin, (w.str("ifMissing") ?: "create").equals("create", true), generator,
            ephemeral, reset, snapshotBoxes,
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun parseRoot(json: String): JsonObject? {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: RuntimeException) {
            error(null, "/", "JSONとして読み込めません: ${e.message}")
            return null
        }
        if (!root.isJsonObject) {
            error(null, "/", "トップレベルはオブジェクトである必要があります")
            return null
        }
        return root.asJsonObject
    }

    private fun checkName(name: String, path: String): Boolean {
        if (!NAME.matches(name)) {
            error(null, path, "名前に使えない文字が含まれています: $name")
            return false
        }
        return true
    }

    private fun error(nodeId: String?, path: String, msg: String) {
        diagnostics += Diagnostic.error(nodeId, path, msg)
    }

    private fun warning(nodeId: String?, path: String, msg: String) {
        diagnostics += Diagnostic.warning(nodeId, path, msg)
    }

    companion object {
        private val ID = Regex("[a-z0-9_]{1,64}")
        private val NAME = Regex("[\\p{L}\\p{N}_]{1,64}")

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun JsonObject.obj(k: String): JsonObject? = get(k)?.takeIf { it.isJsonObject }?.asJsonObject
        private fun JsonObject.arr(k: String): JsonArray? = get(k)?.takeIf { it.isJsonArray }?.asJsonArray
        private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString
        private fun JsonObject.int(k: String, def: Int): Int =
            get(k)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: def
        private fun JsonObject.dbl(k: String): Double = get(k)?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
        private fun JsonObject.bool(k: String, def: Boolean): Boolean = get(k)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: def
        private fun ints(a: JsonArray?): IntArray? =
            if (a == null || a.size() != 3) null else IntArray(3) { a[it].asInt }
    }
}
