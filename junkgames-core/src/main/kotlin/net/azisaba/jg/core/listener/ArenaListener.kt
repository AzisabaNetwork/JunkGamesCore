package net.azisaba.jg.core.listener

import net.azisaba.jg.api.runtime.GameState
import net.azisaba.jg.api.runtime.TriggerEvent
import net.azisaba.jg.core.Core
import net.azisaba.jg.core.instance.GameInstance
import net.azisaba.jg.core.instance.GamePlayerImpl
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityRegainHealthEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.server.PluginDisableEvent

/**
 * Bukkit events → instance triggers.
 *
 * Thread-safety (spec §12.8 convention 2): on Minogami this plugin is listed in
 * `thread-safety.event-lock-bypass.plugins`, so handlers may run concurrently on different world
 * threads. Each handler only looks up the instance in concurrent maps and acts on the instance
 * owning the world the event happened in — i.e. on that instance's own thread.
 */
class ArenaListener(private val core: Core) : Listener {
    private val instances get() = core.instances

    private fun ctx(p: Player): Pair<GameInstance, GamePlayerImpl>? {
        val inst = instances.byWorld(p.world) ?: return null
        val gp = inst.participant(p.uniqueId) ?: return null
        return inst to gp
    }

    private fun running(inst: GameInstance) = inst.state == GameState.RUNNING

    // ---------------------------------------------------------------- connection

    @EventHandler
    fun onPreLogin(e: AsyncPlayerPreLoginEvent) {
        if (e.loginResult == AsyncPlayerPreLoginEvent.Result.ALLOWED) core.snapshots.preload(e.uniqueId)
    }

    /** Left the arena world by other means (e.g. another plugin's teleport) → leave the match. */
    @EventHandler
    fun onChangedWorld(e: PlayerChangedWorldEvent) {
        val from = instances.byWorld(e.from) ?: return
        if (from.participant(e.player.uniqueId) == null) return
        if (instances.byPlayer(e.player.uniqueId) !== from) return
        from.submit { from.removePlayerExternal(e.player.uniqueId) }
    }

    @EventHandler(ignoreCancelled = true)
    fun onCommand(e: PlayerCommandPreprocessEvent) {
        val inst = instances.byPlayer(e.player.uniqueId) ?: return
        if (inst.state == GameState.RESET) return
        val label = e.message.removePrefix("/").substringBefore(' ').lowercase().substringAfter(':')
        if (e.player.hasPermission("junkgames.bypass.commands")) return
        if (label !in core.settings.allowedCommandsInGame) {
            e.isCancelled = true
            e.player.sendRichMessage("<red>ゲーム中はこのコマンドを使えません。<gray>/leave で退出できます")
        }
    }

    // ---------------------------------------------------------------- movement

    @EventHandler(ignoreCancelled = true)
    fun onMove(e: PlayerMoveEvent) {
        if (!e.hasChangedBlock()) return
        val (inst, gp) = ctx(e.player) ?: return
        if (!running(inst) || !gp.isAlive) return
        inst.onMove(gp, e.to, e)
    }

    // ---------------------------------------------------------------- blocks / items

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBreak(e: BlockBreakEvent) {
        val (inst, gp) = ctx(e.player) ?: return
        if (!running(inst) || !gp.isAlive) {
            e.isCancelled = true
            return
        }
        val te = TriggerEvent("block_break", true).player(gp).blockLocation(e.block.location).material(e.block.type)
        te.cancelled = !inst.meta.rules.blockBreak
        e.isCancelled = inst.fire(te).cancelled
        if (!e.isCancelled && !inst.meta.rules.itemDrop) e.isDropItems = false
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlace(e: BlockPlaceEvent) {
        val (inst, gp) = ctx(e.player) ?: return
        if (!running(inst) || !gp.isAlive) {
            e.isCancelled = true
            return
        }
        val te = TriggerEvent("block_place", true).player(gp).blockLocation(e.block.location).material(e.block.type)
        te.cancelled = !inst.meta.rules.blockPlace
        e.isCancelled = inst.fire(te).cancelled
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(e: PlayerInteractEvent) {
        if (e.action != Action.RIGHT_CLICK_AIR && e.action != Action.RIGHT_CLICK_BLOCK) return
        val item = e.item ?: return
        val (inst, gp) = ctx(e.player) ?: return
        if (!running(inst) || !inst.hasScripts("item_use")) return
        val te = inst.fire(TriggerEvent("item_use", true).player(gp).material(item.type).blockLocation(e.clickedBlock?.location))
        if (te.cancelled) e.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onDrop(e: PlayerDropItemEvent) {
        val (inst, _) = ctx(e.player) ?: return
        if (!running(inst) || !inst.meta.rules.itemDrop) e.isCancelled = true
    }

    // ---------------------------------------------------------------- damage

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(e: EntityDamageEvent) {
        val p = e.entity as? Player ?: return
        val (inst, gp) = ctx(p) ?: return
        if (!running(inst) || !gp.isAlive) {
            e.isCancelled = true
            if (e.cause == EntityDamageEvent.DamageCause.VOID) p.teleportAsync(inst.respawnLocation(gp))
            return
        }
        if (e.cause == EntityDamageEvent.DamageCause.FALL && !inst.meta.rules.fallDamage) {
            e.isCancelled = true
            return
        }
        if (e is EntityDamageByEntityEvent) {
            val attacker = (e.damager as? Player) ?: ((e.damager as? Projectile)?.shooter as? Player)
            if (attacker != null) {
                val ap = inst.participant(attacker.uniqueId)
                if (ap == null || !ap.isAlive || !inst.meta.rules.pvp ||
                    (!inst.meta.teams.friendlyFire && ap.team != null && ap.team === gp.team)
                ) {
                    e.isCancelled = true
                    return
                }
                val te = inst.fire(TriggerEvent("player_attack", true).player(ap).otherPlayer(gp).amount(e.damage))
                if (te.cancelled) {
                    e.isCancelled = true
                    return
                }
                if (te.extra["damageChanged"] == true) e.damage = te.amount
            }
        }
        if (inst.hasScripts("player_damage")) {
            val te = inst.fire(TriggerEvent("player_damage", true).player(gp).key(causeKey(e)).amount(e.damage))
            if (te.cancelled) e.isCancelled = true
            else if (te.extra["damageChanged"] == true) e.damage = te.amount
        }
    }

    private fun causeKey(e: EntityDamageEvent): String = when (e.cause) {
        EntityDamageEvent.DamageCause.FALL -> "fall"
        EntityDamageEvent.DamageCause.FIRE, EntityDamageEvent.DamageCause.FIRE_TICK -> "fire"
        EntityDamageEvent.DamageCause.LAVA -> "lava"
        EntityDamageEvent.DamageCause.VOID -> "void"
        EntityDamageEvent.DamageCause.DROWNING -> "drowning"
        EntityDamageEvent.DamageCause.BLOCK_EXPLOSION, EntityDamageEvent.DamageCause.ENTITY_EXPLOSION -> "explosion"
        EntityDamageEvent.DamageCause.PROJECTILE -> "projectile"
        EntityDamageEvent.DamageCause.ENTITY_ATTACK, EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK ->
            if ((e as? EntityDamageByEntityEvent)?.damager is Player) "player" else "entity"
        else -> e.cause.name.lowercase()
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDeath(e: PlayerDeathEvent) {
        val (inst, gp) = ctx(e.player) ?: return
        // no item leakage out of minigames; scripts decide what death means
        e.drops.clear()
        e.droppedExp = 0
        e.keepInventory = false
        e.deathMessage(null)
        val killer = e.player.killer?.let { inst.participant(it.uniqueId) }
        if (running(inst)) inst.onDeath(gp, killer)
    }

    @EventHandler
    fun onRespawn(e: PlayerRespawnEvent) {
        val (inst, gp) = ctx(e.player) ?: return
        e.respawnLocation = inst.respawnLocation(gp)
        inst.submit { inst.onRespawned(gp) }
    }

    @EventHandler(ignoreCancelled = true)
    fun onFood(e: FoodLevelChangeEvent) {
        val p = e.entity as? Player ?: return
        val (inst, _) = ctx(p) ?: return
        if (!running(inst) || !inst.meta.rules.hunger) e.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onRegain(e: EntityRegainHealthEvent) {
        val p = e.entity as? Player ?: return
        val (inst, _) = ctx(p) ?: return
        if (!inst.meta.rules.naturalRegen && e.regainReason == EntityRegainHealthEvent.RegainReason.SATIATED) e.isCancelled = true
    }

    // ---------------------------------------------------------------- add-ons

    @EventHandler
    fun onPluginDisable(e: PluginDisableEvent) {
        if (e.plugin !== core.plugin) core.registry.unregisterAll(e.plugin)
    }
}
