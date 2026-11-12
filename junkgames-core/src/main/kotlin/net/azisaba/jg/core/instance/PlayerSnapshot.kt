package net.azisaba.jg.core.instance

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.azisaba.jg.core.util.Outbox
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.io.path.isRegularFile

/**
 * Player state saved when joining an instance and restored when leaving (spec §8.3).
 * Persisted to disk so that a crash never loses inventories.
 */
class PlayerSnapshot(
    val uuid: UUID,
    private val inventory: ByteArray,
    private val health: Double,
    private val food: Int,
    private val saturation: Float,
    private val level: Int,
    private val exp: Float,
    private val gameMode: GameMode,
    private val worldKey: String?,
    private val x: Double, private val y: Double, private val z: Double,
    private val yaw: Float, private val pitch: Float,
    private val effects: List<PotionEffect>,
    private val allowFlight: Boolean,
) {
    /** Restores everything except the location (the caller teleports to [location]). */
    fun restoreState(p: Player) {
        p.inventory.clear()
        try {
            p.inventory.contents = ItemStack.deserializeItemsFromBytes(inventory)
        } catch (e: RuntimeException) {
            Bukkit.getLogger().severe("[JunkGamesCore] インベントリを復元できませんでした: $uuid $e")
        }
        p.activePotionEffects.forEach { p.removePotionEffect(it.type) }
        effects.forEach { p.addPotionEffect(it) }
        p.gameMode = gameMode
        p.health = health.coerceIn(0.5, p.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0)
        p.foodLevel = food
        p.saturation = saturation
        p.level = level
        p.exp = exp
        p.fireTicks = 0
        p.fallDistance = 0f
        p.allowFlight = allowFlight || gameMode == GameMode.CREATIVE || gameMode == GameMode.SPECTATOR
    }

    fun location(): Location? {
        val w = worldKey?.let { NamespacedKey.fromString(it) }?.let { Bukkit.getWorld(it) } ?: return null
        return Location(w, x, y, z, yaw, pitch)
    }

    fun toJson(): String = JsonObject().apply {
        addProperty("uuid", uuid.toString())
        addProperty("inventory", Base64.getEncoder().encodeToString(inventory))
        addProperty("health", health)
        addProperty("food", food)
        addProperty("saturation", saturation)
        addProperty("level", level)
        addProperty("exp", exp)
        addProperty("gameMode", gameMode.name)
        addProperty("world", worldKey)
        addProperty("x", x)
        addProperty("y", y)
        addProperty("z", z)
        addProperty("yaw", yaw)
        addProperty("pitch", pitch)
        addProperty("allowFlight", allowFlight)
        add("effects", JsonArray().apply {
            effects.forEach { e ->
                add(JsonObject().apply {
                    addProperty("type", e.type.key.toString())
                    addProperty("duration", e.duration)
                    addProperty("amplifier", e.amplifier)
                    addProperty("ambient", e.isAmbient)
                    addProperty("particles", e.hasParticles())
                    addProperty("icon", e.hasIcon())
                })
            }
        })
    }.toString()

    companion object {
        fun capture(p: Player): PlayerSnapshot {
            val l = p.location
            return PlayerSnapshot(
                p.uniqueId, ItemStack.serializeItemsAsBytes(p.inventory.contents), p.health, p.foodLevel, p.saturation,
                p.level, p.exp, p.gameMode, l.world.key.toString(), l.x, l.y, l.z, l.yaw, l.pitch,
                p.activePotionEffects.toList(), p.allowFlight,
            )
        }

        /** Clears the player for a match. Must run on the player's current world thread. */
        fun reset(p: Player, mode: GameMode) {
            p.inventory.clear()
            p.activePotionEffects.forEach { p.removePotionEffect(it.type) }
            p.health = p.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
            p.foodLevel = 20
            p.saturation = 5f
            p.level = 0
            p.exp = 0f
            p.fireTicks = 0
            p.fallDistance = 0f
            p.allowFlight = false
            p.isFlying = false
            p.gameMode = mode
        }

        fun fromJson(json: String): PlayerSnapshot {
            val o = JsonParser.parseString(json).asJsonObject
            val effects = o.getAsJsonArray("effects")?.mapNotNull { e ->
                val j = e.asJsonObject
                val t = NamespacedKey.fromString(j["type"].asString)?.let { Registry.MOB_EFFECT.get(it) } ?: return@mapNotNull null
                PotionEffect(t, j["duration"].asInt, j["amplifier"].asInt, j["ambient"].asBoolean, j["particles"].asBoolean, j["icon"].asBoolean)
            } ?: emptyList()
            return PlayerSnapshot(
                UUID.fromString(o["uuid"].asString), Base64.getDecoder().decode(o["inventory"].asString),
                o["health"].asDouble, o["food"].asInt, o["saturation"].asFloat, o["level"].asInt, o["exp"].asFloat,
                GameMode.valueOf(o["gameMode"].asString), o["world"]?.takeUnless { it.isJsonNull }?.asString,
                o["x"].asDouble, o["y"].asDouble, o["z"].asDouble, o["yaw"].asFloat, o["pitch"].asFloat,
                effects, o["allowFlight"]?.asBoolean ?: false,
            )
        }
    }
}

/**
 * Persists player snapshots (`snapshots/<uuid>.json`). Writes go through the outbox; reads happen
 * in `AsyncPlayerPreLoginEvent` so that login never blocks a tick.
 */
class SnapshotStore(private val dir: Path, private val outbox: Outbox, private val log: Logger) {
    private val preloaded = ConcurrentHashMap<UUID, PlayerSnapshot>()

    fun save(s: PlayerSnapshot) {
        val json = s.toJson()
        outbox.submit {
            Files.createDirectories(dir)
            val tmp = dir.resolve("${s.uuid}.json.tmp")
            Files.writeString(tmp, json)
            Files.move(tmp, dir.resolve("${s.uuid}.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    fun delete(uuid: UUID) {
        preloaded.remove(uuid)
        outbox.submit { Files.deleteIfExists(dir.resolve("$uuid.json")) }
    }

    /** Blocking read; call only from async threads (pre-login). */
    fun preload(uuid: UUID) {
        val f = dir.resolve("$uuid.json")
        if (!f.isRegularFile()) return
        try {
            preloaded[uuid] = PlayerSnapshot.fromJson(Files.readString(f))
        } catch (e: Exception) {
            log.log(Level.SEVERE, "スナップショットを読めません: $f", e)
        }
    }

    fun takePreloaded(uuid: UUID): PlayerSnapshot? = preloaded.remove(uuid)
}
