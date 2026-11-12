package net.azisaba.jg

import net.azisaba.jg.sdk.JunkGamesSdk
import org.bukkit.Location
import org.bukkit.World
import java.util.Random

/**
 * Source compatibility with the old `net.azisaba.jg.JunkGames` plugin class:
 * `JunkGames.getPlugin()`, `JunkGames.lobby`, `JunkGames.spawn` and `JunkGames.random` keep working.
 */
object JunkGames {
    /** The lobby world (set by JunkGamesCore on enable). */
    @JvmStatic
    lateinit var lobby: World

    /** The lobby spawn (set by JunkGamesCore on enable). */
    @JvmStatic
    lateinit var spawn: Location

    @JvmField
    val random = Random()

    @JvmStatic
    fun getPlugin(): JunkGamesSdk = JunkGamesSdk.get()
}
