package net.azisaba.jg.core

import net.azisaba.jg.api.JunkGamesApi
import net.azisaba.jg.api.block.BlockRegistry
import net.azisaba.jg.core.block.BlockExporter
import net.azisaba.jg.core.block.BlockRegistryImpl
import net.azisaba.jg.core.block.std.StdBlocks
import net.azisaba.jg.core.command.AdminCommand
import net.azisaba.jg.core.command.LeaveCommand
import net.azisaba.jg.core.command.MapCommand
import net.azisaba.jg.core.command.JunkGamesCommand
import net.azisaba.jg.core.command.PlayCommand
import net.azisaba.jg.core.hub.Hub
import net.azisaba.jg.core.hub.HubListener
import net.azisaba.jg.sdk.JunkGamesSdk
import net.azisaba.jg.core.command.SimpleCommand
import net.azisaba.jg.core.config.CoreConfig
import net.azisaba.jg.core.definition.DefinitionStore
import net.azisaba.jg.core.instance.InstanceManager
import net.azisaba.jg.core.instance.SnapshotStore
import net.azisaba.jg.core.listener.ArenaListener
import net.azisaba.jg.core.map.MapEditor
import net.azisaba.jg.core.provider.CapabilityRegistry
import net.azisaba.jg.core.provider.paper.FoliaExecutionProvider
import net.azisaba.jg.core.provider.paper.PaperExecutionProvider
import net.azisaba.jg.core.provider.paper.PaperStructureProvider
import net.azisaba.jg.core.provider.paper.PaperViewProvider
import net.azisaba.jg.core.provider.paper.PaperWorldProvider
import net.azisaba.jg.core.util.Outbox
import net.azisaba.jg.platform.StructureProvider
import net.azisaba.jg.platform.ThreadingModel
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/**
 * JunkGamesCore: block-based minigame engine for the JunkGames server.
 * Games are written as IR (`.jgame.json`, produced by the web block editor) and run here.
 */
class JunkGamesCorePlugin : JavaPlugin(), Core, JunkGamesApi {
    override val plugin get() = this
    override lateinit var settings: CoreConfig
        private set
    override val capabilities = CapabilityRegistry()
    override lateinit var outbox: Outbox
        private set
    override lateinit var snapshots: SnapshotStore
        private set
    override lateinit var definitions: DefinitionStore
        private set
    override lateinit var instances: InstanceManager
        private set
    override val registry = BlockRegistryImpl()
    override val blocks: BlockRegistry get() = registry
    private lateinit var mapEditor: MapEditor
    private val reloadPending = AtomicBoolean(false)
    @Volatile private var started = false

    /** Lobby, SDK game registry and selection screen (restored from 0.x). */
    val hub = Hub(this)

    init {
        // SDK games register themselves in their constructors, which run before our onEnable
        JunkGamesSdk.set(hub)
    }

    override fun onEnable() {
        saveDefaultConfig()
        saveIfMissing("providers.yml")
        val data = dataFolder.toPath()
        if (!Files.exists(data.resolve("games"))) {
            // first start: install the sample game (TNT Run) and its map
            saveResource("games/tnt_run.jgame.json", false)
            saveResource("maps/tnt_run_classic.jmap.json", false)
        }
        saveResource("schema/game.schema.json", true)
        saveResource("schema/map.schema.json", true)

        settings = CoreConfig.load(getConfig())
        outbox = Outbox(logger, data.resolve("logs/errors.log"))
        snapshots = SnapshotStore(data.resolve("snapshots"), outbox, logger)
        hub.core = this
        hub.setupLobby()

        StdBlocks.registerAll(registry, settings.limits)
        selectProviders()

        definitions = DefinitionStore(
            data.resolve("games"), data.resolve("maps"), pluginMeta.version,
            { registry[it] }, settings.limits, capabilities,
        )
        instances = InstanceManager(this)
        mapEditor = MapEditor(this)

        server.pluginManager.registerEvents(ArenaListener(this), this)
        server.pluginManager.registerEvents(HubListener(this, hub), this)
        server.pluginManager.registerEvents(mapEditor, this)
        // commands are declared in plugin.yml
        bind("play", PlayCommand(this))
        bind("leave", LeaveCommand(this))
        bind("junkgames", JunkGamesCommand(this))
        bind("jgadmin", AdminCommand(this))
        bind("jgmap", MapCommand(mapEditor))

        JunkGamesApi.set(this)
        server.servicesManager.register(JunkGamesApi::class.java, this, this, ServicePriority.Normal)
        server.servicesManager.register(JunkGamesSdk::class.java, hub, this, ServicePriority.Normal)
        registry.addListener { if (started) reloadDefinitions() }

        // after every plugin is enabled: add-ons have registered their blocks / providers
        Bukkit.getScheduler().runTask(this, Runnable {
            selectProviders() // pick up Native Providers registered by the server fork or other plugins
            capabilities.get(net.azisaba.jg.platform.WorldProvider::class.java)?.cleanupOrphans()
            started = true
            reloadDefinitions {
                definitions.maps().mapNotNull { it.map }.forEach { instances.refillPool(it) }
                exportBlocks()
            }
        })
        if (FoliaExecutionProvider.isFolia) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, { mapEditor.renderParticles() }, 10L, 10L)
        } else {
            Bukkit.getScheduler().runTaskTimer(this, Runnable { mapEditor.renderParticles() }, 10L, 10L)
        }
    }

    override fun onDisable() {
        if (!::outbox.isInitialized) return
        runCatching { mapEditor.shutdown() }.onFailure { logger.log(Level.SEVERE, "map editor shutdown", it) }
        runCatching { instances.shutdown() }.onFailure { logger.log(Level.SEVERE, "instance shutdown", it) }
        JunkGamesApi.set(null)
        JunkGamesSdk.set(null)
        outbox.close()
    }

    private fun selectProviders() {
        val yml = YamlConfiguration.loadConfiguration(dataFolder.resolve("providers.yml"))
        val exec = { capabilities.execution }
        val structure = { capabilities.get(StructureProvider::class.java) }
        capabilities.select(
            yml,
            listOf(
                PaperExecutionProvider(), FoliaExecutionProvider(),
                PaperWorldProvider(
                    this, dataFolder.toPath().resolve("templates"), dataFolder.toPath().resolve("schematics"),
                    { r -> outbox.io.execute(r) }, exec, structure, settings.allowPersistentWorldCreate,
                ),
                PaperStructureProvider(this, exec),
                PaperViewProvider(this),
            ),
            logger,
        )
        val minogami = runCatching { Class.forName("world.minogami.thread.ConcurrentTaskHandler") }.isSuccess
        if (minogami && capabilities.execution.threading != ThreadingModel.PER_WORLD_PARALLEL) {
            logger.warning(
                "Minogami を検出しましたが、PER_WORLD_PARALLEL の execution Provider（Native Provider）が登録されていません。" +
                    "インスタンスはメインスレッドのスケジューラで動作します（並列化されません）",
            )
        }
    }

    fun reloadConfigs() {
        reloadConfig()
        settings = CoreConfig.load(getConfig())
    }

    override fun reloadDefinitions() = reloadDefinitions(null)

    /** Re-reads and recompiles definitions off-thread. Running matches keep their compiled version. */
    fun reloadDefinitions(then: (() -> Unit)?) {
        if (!reloadPending.compareAndSet(false, true)) return
        outbox.submit {
            reloadPending.set(false)
            try {
                definitions.reload()
                val bad = definitions.games().filter { !it.playable }
                logger.info("ゲーム ${definitions.games().size} 件（有効 ${definitions.games().size - bad.size}）、マップ ${definitions.maps().size} 件を読み込みました")
                bad.forEach { g ->
                    logger.warning("ゲーム ${g.id} は無効です: ${g.disabledReason}")
                    g.diagnostics.filter { it.isError }.take(5).forEach { logger.warning("  $it") }
                }
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "定義の読み込みに失敗しました", e)
            }
            then?.invoke()
        }
    }

    private fun exportBlocks() {
        val json = BlockExporter.export(settings.serverId, pluginMeta.version, registry.all(), capabilities)
        outbox.submit {
            val out = dataFolder.toPath().resolve("export/blocks.json")
            Files.createDirectories(out.parent)
            Files.writeString(out, json)
        }
    }

    override fun lobby(): Location = hub.getSpawn()

    private fun bind(name: String, command: SimpleCommand) {
        val c = getCommand(name) ?: error("command '$name' is missing from plugin.yml")
        c.setExecutor(command)
        c.tabCompleter = command
    }

    private fun saveIfMissing(name: String) {
        if (!dataFolder.resolve(name).exists()) saveResource(name, false)
    }
}
