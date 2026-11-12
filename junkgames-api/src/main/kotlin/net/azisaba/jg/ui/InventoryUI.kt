package net.azisaba.jg.ui

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.concurrent.CopyOnWriteArrayList

interface IInventoryUI {
    fun getInventory(): Inventory
    fun onClick(event: InventoryClickEvent)
    fun onClose(event: InventoryCloseEvent)
}

/**
 * Chest-GUI helper (restored). Opening one closes the player's previous UI. Clicks are cancelled;
 * slots registered with [addListener] run a command or a callback. JunkGamesCore routes the
 * inventory events, so game plugins only need to subclass this.
 */
open class InventoryUI(@JvmField protected val player: Player, @JvmField protected val inventory: Inventory) : IInventoryUI {
    /** @property serverside run the command as the console */
    data class ListenerSlot(val command: String?, val serverside: Boolean, val action: ((Player) -> Unit)? = null)

    @JvmField protected val listeners = HashMap<Int, ListenerSlot>()

    init {
        getInstance(player)?.onClose(InventoryCloseEvent(player.openInventory))
        player.openInventory(inventory)
        @Suppress("LeakingThis")
        instances += this
    }

    override fun getInventory(): Inventory = inventory

    override fun onClick(event: InventoryClickEvent) {
        event.isCancelled = true
        if (event.currentItem == null || event.clickedInventory !== inventory) return
        val l = listeners[event.slot] ?: return
        l.action?.invoke(player)
        l.command?.let { Bukkit.dispatchCommand(if (l.serverside) Bukkit.getConsoleSender() else player, it) }
    }

    override fun onClose(event: InventoryCloseEvent) {
        instances.remove(this)
    }

    protected fun addListener(index: Int, stack: ItemStack, command: String, serverside: Boolean) {
        inventory.setItem(index, stack)
        listeners[index] = ListenerSlot(command, serverside)
    }

    protected fun addListener(index: Int, stack: ItemStack, command: String) = addListener(index, stack, command, false)

    /** Callback version (new). */
    protected fun addListener(index: Int, stack: ItemStack, action: (Player) -> Unit) {
        inventory.setItem(index, stack)
        listeners[index] = ListenerSlot(null, false, action)
    }

    companion object {
        private val instances = CopyOnWriteArrayList<IInventoryUI>()

        @JvmStatic
        fun getInstance(inventory: Inventory): IInventoryUI? = instances.lastOrNull { it.getInventory() === inventory }

        @JvmStatic
        fun getInstance(player: Player): IInventoryUI? = getInstance(player.openInventory.topInventory)

        @Deprecated("internal")
        @JvmStatic
        fun getInstances(): List<IInventoryUI> = instances
    }
}

/** ANSI colors and separators for console output (restored). */
object CLI {
    const val RED = "\u001b[00;31m"
    const val GREEN = "\u001b[00;32m"
    const val YELLOW = "\u001b[00;33m"
    const val PURPLE = "\u001b[00;34m"
    const val PINK = "\u001b[00;35m"
    const val CYAN = "\u001b[00;36m"
    const val END = "\u001b[00m"
    const val SEPARATOR = "⩵⩵⩵⩵⩵⩵⩵⩵⩵⩵⩵⩵⩵⩵⩵⩵⩵⩵⩵⩵⩵⩵⩵⩵⩵"
    const val SHORT_SEPARATOR = "-----------------------"

    @JvmStatic
    fun getSpaces(length: Int): String = " ".repeat(maxOf(0, length))
}
