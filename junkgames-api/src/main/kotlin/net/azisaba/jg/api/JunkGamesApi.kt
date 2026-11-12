package net.azisaba.jg.api

import net.azisaba.jg.api.block.BlockRegistry

/** Entry point for add-ons. Also registered in Bukkit's ServicesManager. */
interface JunkGamesApi {
    val blocks: BlockRegistry

    /** Reloads and recompiles all game / map definitions (e.g. after registering blocks late). */
    fun reloadDefinitions()

    companion object {
        @Volatile
        private var instance: JunkGamesApi? = null

        @JvmStatic
        fun get(): JunkGamesApi = instance ?: throw IllegalStateException("JunkGamesCore is not enabled")

        /** Internal: called by the core plugin. */
        @JvmStatic
        fun set(api: JunkGamesApi?) {
            instance = api
        }
    }
}
