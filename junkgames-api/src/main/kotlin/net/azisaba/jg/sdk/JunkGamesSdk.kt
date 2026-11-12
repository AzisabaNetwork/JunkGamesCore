package net.azisaba.jg.sdk

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player

/**
 * The hub side of JunkGamesCore, as seen by SDK games (formerly the `JunkGames` plugin class).
 * Obtain it with [JunkGamesSdk.get] or, as before, `JunkGames.getPlugin()`.
 */
interface JunkGamesSdk {
    /** Registers a game. [JunkGame] does this by itself in its constructor. */
    fun register(game: IJunkGame)

    fun unregister(game: IJunkGame)

    /** The game that declared [world], or null (lobby / block-made arenas / other worlds). */
    fun getJunkGame(world: World): IJunkGame?

    fun getJunkGame(player: Player): IJunkGame?

    /** Registered games, sorted by priority (lowest first). */
    fun getJunkGames(): List<IJunkGame>

    fun getLobby(): World

    fun getSpawn(): Location

    /** Opens the game selection screen (`/junkgames`). */
    fun openMenu(player: Player)

    /** Connects a player to a game (same as clicking it in the selection screen). */
    fun connect(player: Player, game: IJunkGame)

    companion object {
        @Volatile
        private var instance: JunkGamesSdk? = null

        @JvmStatic
        fun get(): JunkGamesSdk = instance ?: throw IllegalStateException("JunkGamesCore is not loaded (add `depend: [JunkGamesCore]`)")

        @JvmStatic
        fun getOrNull(): JunkGamesSdk? = instance

        /** Internal: called by the core plugin. */
        @JvmStatic
        fun set(sdk: JunkGamesSdk?) {
            instance = sdk
        }
    }
}
