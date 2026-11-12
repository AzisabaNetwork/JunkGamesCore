package net.azisaba.jg.core.instance

import io.papermc.paper.scoreboard.numbers.NumberFormat
import net.azisaba.jg.api.runtime.GamePlayer
import net.azisaba.jg.api.runtime.GameTeam
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard
import java.util.TreeMap
import java.util.UUID

/** A participant of one instance. Mutated only on the instance's thread. */
class GamePlayerImpl internal constructor(p: Player, val snapshot: PlayerSnapshot) : GamePlayer {
    override val uuid: UUID = p.uniqueId
    override val name: String = p.name

    @Volatile internal var handle: Player? = p
    @Volatile internal var alive = true
    @Volatile internal var spectator = false
    @Volatile var left = false
        internal set

    /** Disconnected with `onDisconnect: keep`; may rejoin. */
    @Volatile internal var disconnected = false
    internal var teamImpl: GameTeamImpl? = null

    /** Regions (by name) the player is currently inside, for enter/exit triggers. */
    internal val regions = HashSet<String>()
    internal var lastBlockX = Int.MIN_VALUE
    internal var lastBlockY = 0
    internal var lastBlockZ = 0

    override val player: Player?
        get() = handle?.takeIf { !left && !disconnected && it.isOnline }
    override val isAlive: Boolean get() = alive && !left
    override val isSpectator: Boolean get() = spectator
    override val team: GameTeam? get() = teamImpl

    override fun equals(other: Any?) = other is GamePlayer && other.uuid == uuid
    override fun hashCode() = uuid.hashCode()
    override fun toString() = name
}

class GameTeamImpl internal constructor(override val id: String, override val displayName: String, override val color: NamedTextColor) : GameTeam {
    internal val memberList = ArrayList<GamePlayerImpl>()
    override val members: List<GamePlayer> get() = memberList.filter { !it.left }
    override fun toString() = displayName
}

/**
 * Per-instance display: every participant gets a private scoreboard (nothing leaks between
 * instances or to the lobby) and instance-scoped boss bars (spec §8.3).
 */
internal class InstanceUi {
    private var globalTitle: Component = Component.empty()
    private val titles = HashMap<UUID, Component>()
    private val globalLines = TreeMap<Int, Component>()
    private val lines = HashMap<UUID, TreeMap<Int, Component>>()
    private val boards = HashMap<UUID, Scoreboard>()
    private val globalBars = HashMap<String, BossBar>()
    private val bars = HashMap<UUID, MutableMap<String, BossBar>>()
    private var sidebarUsed = false

    fun attach(p: Player) {
        val sb = Bukkit.getScoreboardManager().newScoreboard
        boards[p.uniqueId] = sb
        p.scoreboard = sb
        globalBars.values.forEach { p.showBossBar(it) }
        if (sidebarUsed) render(p)
    }

    fun detach(p: Player) {
        val u = p.uniqueId
        boards.remove(u)
        titles.remove(u)
        lines.remove(u)
        globalBars.values.forEach { p.hideBossBar(it) }
        bars.remove(u)?.values?.forEach { p.hideBossBar(it) }
        p.scoreboard = Bukkit.getScoreboardManager().mainScoreboard
    }

    /** Colors team members' names on everyone's board. */
    fun syncTeams(teams: List<GameTeamImpl>) {
        for (sb in boards.values) for (t in teams) {
            val team = sb.getTeam("jg_${t.id}") ?: sb.registerNewTeam("jg_${t.id}")
            team.color(t.color)
            team.prefix(Component.text("[${t.displayName}] ", t.color))
            t.memberList.forEach { if (!team.hasEntry(it.name)) team.addEntry(it.name) }
        }
    }

    fun setTitle(target: Player?, mm: String, everyone: Iterable<Player>) {
        sidebarUsed = true
        val c = MM.deserialize(mm)
        if (target == null) {
            globalTitle = c
            titles.clear()
            everyone.forEach { render(it) }
        } else {
            titles[target.uniqueId] = c
            render(target)
        }
    }

    fun setLine(target: Player?, line: Int, mm: String?, everyone: Iterable<Player>) {
        if (line !in 1..MAX_LINES) return
        sidebarUsed = true
        val c = mm?.let { MM.deserialize(it) }
        if (target == null) {
            if (c == null) globalLines.remove(line) else globalLines[line] = c
            lines.values.forEach { it.remove(line) }
            everyone.forEach { render(it) }
        } else {
            val own = lines.getOrPut(target.uniqueId) { TreeMap() }
            if (c == null) own.remove(line) else own[line] = c
            render(target)
        }
    }

    private fun render(p: Player) {
        val sb = boards[p.uniqueId] ?: return
        sb.getObjective("jg_side")?.unregister()
        val o = sb.registerNewObjective("jg_side", Criteria.DUMMY, titles[p.uniqueId] ?: globalTitle)
        o.displaySlot = DisplaySlot.SIDEBAR
        o.numberFormat(NumberFormat.blank())
        val merged = TreeMap(globalLines)
        lines[p.uniqueId]?.let { merged.putAll(it) }
        for ((line, text) in merged) {
            val s = o.getScore("§${line.toString(16)}§r")
            s.score = MAX_LINES + 1 - line
            s.customName(text)
        }
    }

    fun showBar(id: String, target: Player?, mm: String, progress: Float, color: String, everyone: Iterable<Player>) {
        val c = runCatching { BossBar.Color.valueOf(color.uppercase()) }.getOrDefault(BossBar.Color.WHITE)
        val name = MM.deserialize(mm)
        val map = if (target == null) globalBars else bars.getOrPut(target.uniqueId) { HashMap() }
        val bar = map[id]
        if (bar != null) {
            bar.name(name).progress(progress).color(c)
            return
        }
        val nb = BossBar.bossBar(name, progress, c, BossBar.Overlay.PROGRESS)
        map[id] = nb
        if (target == null) everyone.forEach { it.showBossBar(nb) } else target.showBossBar(nb)
    }

    fun hideBar(id: String, target: Player?, everyone: Iterable<Player>) {
        if (target == null) globalBars.remove(id)?.let { b -> everyone.forEach { it.hideBossBar(b) } }
        else bars[target.uniqueId]?.remove(id)?.let { target.hideBossBar(it) }
    }

    private companion object {
        val MM = MiniMessage.miniMessage()
        const val MAX_LINES = 15
    }
}
