package net.azisaba.jg.plugin.junksurvival

import net.azisaba.jg.sdk.event.JunkGameHandler
import net.azisaba.jg.sdk.event.JunkGameListener
import net.azisaba.jg.sdk.event.PlayerChatEvent
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import kotlin.random.Random

/** World rules inside the survival worlds: random drops, border, deaths, mob rules, enchanting. */
class SurvivalListener(private val plugin: JunkSurvival) : Listener {
    private val droppable: List<Material> = Material.entries.filter { it.isItem && !it.isAir && !it.isLegacy }
    private val randomMobs: List<EntityType> = EntityType.entries.filter {
        it.isSpawnable && it.isAlive && it != EntityType.PLAYER && it != EntityType.ENDER_DRAGON && it != EntityType.WITHER
    }
    private val chances = SpecialItem.entries.associateWith { plugin.config.getInt("special-drop-chance.${it.id}", 0) }
    private var deathCount = 0

    @EventHandler(ignoreCancelled = true)
    fun onBreak(e: BlockBreakEvent) {
        val block = e.block
        val player = e.player
        if (!plugin.isSurvivalWorld(block.world) || player.gameMode != GameMode.SURVIVAL) return
        val m = block.type
        if (!(m.isOccluding || m == Material.GLASS || m.name.endsWith("_STAINED_GLASS"))) return
        val loc = block.location
        repeat(Random.nextInt(2, 11)) {
            val mat = droppable[Random.nextInt(droppable.size)]
            val drop = block.world.dropItem(loc, ItemStack(mat, Random.nextInt(1, mat.maxStackSize + 1)))
            drop.ticksLived = 4800 // despawns sooner than normal, like the old plugin
            rollSpecial()?.let { special ->
                block.world.dropItem(loc, plugin.items.create(special))
                player.sendMessage(plugin.mm.deserialize(if (special.good) "<green><bold>すげーアイテムがドロップしました" else "<red><bold>やべーアイテムがドロップしました"))
            }
        }
    }

    /** One roll per random item, with the old plugin's cumulative ranges. */
    private fun rollSpecial(): SpecialItem? {
        var roll = Random.nextInt(100)
        for ((item, chance) in chances) {
            if (roll < chance) return item
            roll -= chance
        }
        return null
    }

    @EventHandler
    fun onDeath(e: PlayerDeathEvent) {
        val player = e.player
        val world = player.world
        if (!plugin.isSurvivalWorld(world)) return
        val border = world.worldBorder
        border.size = maxOf(1.0, border.size - 1)
        plugin.broadcast("<red><bold>ワールドボーダーが縮小！</bold></red>\n\n${world.name}の現在のワールドボーダーの大きさ : <red><bold>${border.size}")
        world.playSound(player.location, Sound.BLOCK_ANCIENT_DEBRIS_BREAK, 1f, 1f)

        // (the old version's world check was always false, so giants never spawned)
        world.spawnEntity(player.location, EntityType.GIANT)
        if (deathCount >= 5) {
            for (p in Bukkit.getOnlinePlayers()) {
                if (plugin.isSurvivalWorld(p.world)) p.world.spawnEntity(p.location, randomMobs[Random.nextInt(randomMobs.size)])
            }
            deathCount = 0
            plugin.broadcast("プレイヤー合計死亡回数により <red>ランダムなモブ</red> が召喚!\nプレイヤーの合計死亡回数がリセットされました")
        } else {
            deathCount++
            plugin.broadcast("プレイヤーの合計死亡回数 : <red>$deathCount")
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onEnchant(e: EnchantItemEvent) {
        if (!plugin.isSurvivalWorld(e.enchanter.world)) return
        val cost = e.expLevelCost
        e.expLevelCost = cost / 2
        e.enchanter.sendMessage(plugin.mm.deserialize("<light_purple>元のエンチャントコスト <white>: <light_purple>$cost\n<light_purple>消費エンチャントコスト <white>: <light_purple>${e.expLevelCost}"))
    }

    @EventHandler(ignoreCancelled = true)
    fun onSpawn(e: EntitySpawnEvent) {
        if (!plugin.isSurvivalWorld(e.location.world)) return
        if (e.entityType == EntityType.SKELETON || e.entityType == EntityType.CREEPER) e.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(e: EntityDamageByEntityEvent) {
        val player = e.entity as? Player ?: return // the old version cast every entity to Player
        if (!plugin.isSurvivalWorld(player.world)) return
        val d = plugin.difficulty[player.uniqueId] ?: return
        e.damage = e.damage / d.divisor
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlace(e: BlockPlaceEvent) {
        if (e.blockPlaced.type in PREVENT_PLACE) {
            e.isCancelled = true
            e.player.playSound(e.player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f)
        }
    }

    private companion object {
        val PREVENT_PLACE = setOf(Material.BEDROCK, Material.BARRIER, Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK)
    }
}

/** Using special items, choosing difficulty, random enchanting. */
class SpecialItemListener(private val plugin: JunkSurvival) : Listener {
    private val enchantments: List<Enchantment> by lazy {
        RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).toList()
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(e: PlayerInteractEvent) {
        val player = e.player
        if (e.item?.type == Material.COMMAND_BLOCK_MINECART &&
            (e.action == Action.RIGHT_CLICK_AIR || e.action == Action.RIGHT_CLICK_BLOCK)
        ) {
            e.isCancelled = true
            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f)
            return
        }
        if (e.hand != EquipmentSlot.HAND) return

        // difficulty blocks in the lobby
        if (e.action == Action.RIGHT_CLICK_BLOCK && player.world.name == plugin.lobbyWorld) {
            val d = Difficulty.entries.firstOrNull { it.block == e.clickedBlock?.type }
            if (d != null) {
                if (plugin.difficulty[player.uniqueId] == d) player.sendMessage(plugin.mm.deserialize("<light_purple>すでにそのもーどだよ"))
                else {
                    plugin.difficulty[player.uniqueId] = d
                    player.sendMessage(plugin.mm.deserialize(d.message))
                }
                return
            }
        }

        if (!plugin.isSurvivalWorld(player.world)) return
        val item = player.inventory.itemInMainHand
        val special = plugin.items.typeOf(item)

        if (e.action == Action.LEFT_CLICK_AIR || e.action == Action.LEFT_CLICK_BLOCK) {
            if (special == SpecialItem.BORDER) useBorder(player, item)
            return
        }
        if (e.action != Action.RIGHT_CLICK_AIR && e.action != Action.RIGHT_CLICK_BLOCK) return
        when (special) {
            SpecialItem.BOMB -> {
                e.isCancelled = true
                player.world.createExplosion(player.location, 10f)
                consume(player, item, "<red>どかーんをしようしました")
            }
            SpecialItem.JUMP -> {
                e.isCancelled = true
                player.velocity = player.eyeLocation.direction.multiply(5)
                consume(player, item, "<aqua>びよーんをしようしました")
            }
            SpecialItem.KILL -> {
                e.isCancelled = true
                val target = Bukkit.getOnlinePlayers().randomOrNull() ?: return
                if (plugin.isSurvivalWorld(target.world)) {
                    target.damage(target.health)
                    plugin.broadcast("<red>${plugin.mm.escapeTags(target.name)}</red>が通り魔にやられた")
                    consume(player, item, "<red>みちずれをしようしました")
                }
            }
            SpecialItem.POWER -> {
                e.isCancelled = true
                plugin.applyBuffs(player, withStrength = false)
                consume(player, item, "<green>ぱわーをしようしました")
            }
            SpecialItem.BORDER -> e.isCancelled = true
            null -> if (e.action == Action.RIGHT_CLICK_AIR && !item.type.isAir) {
                // "RandomEnchant" (3.2.1): right-clicking the air with any item adds a random enchantment
                item.addUnsafeEnchantment(enchantments.random(), Random.nextInt(1, 256))
            }
        }
    }

    private fun useBorder(player: Player, item: ItemStack) {
        val world = player.world
        val border = world.worldBorder
        border.size = border.size + 10
        item.amount -= 1
        plugin.broadcast("<aqua><bold>${plugin.mm.escapeTags(player.name)}</bold></aqua>さんが${world.name} のワールドボーダーを拡張したよ！\n\n現在のワールドボーダーの大きさ : <aqua><bold>${border.size}")
        world.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)
    }

    private fun consume(player: Player, item: ItemStack, message: String) {
        item.amount -= 1
        player.sendMessage(plugin.mm.deserialize(message))
    }

}

/** Welcome messages, boss bar, difficulty bookkeeping, chat suffix (an SDK listener as well). */
class PlayerListener(private val plugin: JunkSurvival) : Listener, JunkGameListener {
    private val suffix = plugin.config.getString("chat-suffix", "///").orEmpty()

    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        e.player.showBossBar(plugin.welcomeBar)
        plugin.difficulty[e.player.uniqueId] = Difficulty.SUPER_EASY
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        e.player.hideBossBar(plugin.welcomeBar)
        plugin.difficulty.remove(e.player.uniqueId)
    }

    @EventHandler
    fun onChangeWorld(e: PlayerChangedWorldEvent) {
        val p = e.player
        if (!plugin.isSurvivalWorld(p.world)) return
        p.sendMessage(
            plugin.mm.deserialize(
                "TPS激高の鯖で<green><bold>アイテム</bold></green>を壊すと色々なものが<green><bold>ドロップ!?</bold></green>中には<green><bold>ワールドの境界線</bold></green>を広げる<green><bold>アイテム</bold></green>なども!\n" +
                    "めちゃくちゃな世界を楽しもう!\n<green><bold>ワールド移動</bold></green>や<green><bold>一定時間でつよつよなバフ</bold></green>が付与されるよ!",
            ),
        )
        plugin.applyBuffs(p, withStrength = true)
    }

    /** SDK chat event: only fired for chat inside this game's worlds. */
    @JunkGameHandler
    fun onChat(e: PlayerChatEvent) {
        if (suffix.isEmpty()) return
        e.bukkitEvent.message(e.message.append(Component.text(suffix)))
    }
}
