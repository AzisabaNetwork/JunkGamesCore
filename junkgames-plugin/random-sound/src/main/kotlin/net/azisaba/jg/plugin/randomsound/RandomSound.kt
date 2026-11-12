package net.azisaba.jg.plugin.randomsound

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.java.JavaPlugin
import kotlin.random.Random

/**
 * RandomSound: plays a random sound when a player joins, and when they left-click with a stick in
 * the rhythm world. Ported from the Spigot 1.17 version.
 */
class RandomSound : JavaPlugin(), Listener {
    private lateinit var sounds: List<Sound>
    private lateinit var rhythmWorld: String
    private var joinDelay = 140L

    override fun onEnable() {
        saveDefaultConfig()
        rhythmWorld = config.getString("rhythm-world", "RhythmWorld")!!
        joinDelay = config.getLong("join-delay-ticks", 140)
        val excluded = config.getStringList("excluded-sounds").toSet()
        // Sound is a registry (not an enum) since 1.21.3
        sounds = Registry.SOUND_EVENT.filter { Registry.SOUND_EVENT.getKey(it)?.toString() !in excluded }
        server.pluginManager.registerEvents(this, this)
    }

    private fun randomSound(): Sound = sounds[Random.nextInt(sounds.size)]

    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        val player = e.player
        val location = player.location
        val sound = randomSound()
        val volume = Random.nextInt(100).toFloat()
        // the old version used java.util.Timer (an extra thread); use the player's scheduler instead
        player.scheduler.runDelayed(this, { player.playSound(location, sound, volume, 1f) }, null, joinDelay)
    }

    @EventHandler
    fun onInteract(e: PlayerInteractEvent) {
        if (e.hand != EquipmentSlot.HAND) return
        if (e.action != Action.LEFT_CLICK_AIR && e.action != Action.LEFT_CLICK_BLOCK) return
        val player = e.player
        if (player.world.name != rhythmWorld) return
        if (player.inventory.itemInMainHand.type != Material.STICK) return
        val sound = randomSound()
        val location = player.location
        player.sendActionBar(Component.text(Registry.SOUND_EVENT.getKey(sound)?.toString() ?: "?", NamedTextColor.DARK_RED).append(Component.text(" のサウンド再生")))
        player.playSound(location, sound, Random.nextInt(100).toFloat(), 1f)
        player.spawnParticle(Particle.NOTE, location, 10, 1.0, 1.0, 1.0, 10.0)
    }
}
