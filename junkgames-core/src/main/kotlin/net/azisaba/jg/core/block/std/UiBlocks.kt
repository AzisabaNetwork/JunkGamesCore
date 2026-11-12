package net.azisaba.jg.core.block.std

import net.azisaba.jg.api.block.BlockBuilder
import net.azisaba.jg.api.block.BlockDefinition
import net.azisaba.jg.api.block.Category
import net.azisaba.jg.api.block.FieldSpec
import net.azisaba.jg.api.runtime.Exec
import net.azisaba.jg.api.runtime.GamePlayer
import net.azisaba.jg.api.runtime.ScriptError
import net.azisaba.jg.api.type.ValueType
import net.azisaba.jg.platform.ViewProvider
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.title.Title
import org.bukkit.entity.Entity
import java.time.Duration

/** `ui.*`: sidebar, boss bar and instance-wide announcements (separated per instance). */
internal object UiBlocks {
    fun all(): List<BlockDefinition> = listOf(
        BlockBuilder.stack("ui.sidebar_title").category(Category.UI)
            .label("スコアボードのタイトルを %text にする（対象: %target）", "set sidebar title to %text for %target")
            .input("text", ValueType.STRING, "<yellow><bold>JunkGames").optionalInput("target", ValueType.PLAYER)
            .exec { ex, a -> ex.game.setSidebarTitle(a.player("target"), a.string("text")) },

        BlockBuilder.stack("ui.sidebar_line").category(Category.UI)
            .label("スコアボードの %line 行目を %text にする（対象: %target）", "set sidebar line %line to %text for %target")
            .input("line", ValueType.NUMBER, 1).input("text", ValueType.STRING, "")
            .optionalInput("target", ValueType.PLAYER)
            .exec { ex, a -> ex.game.setSidebarLine(a.player("target"), a.integer("line"), a.string("text")) },

        BlockBuilder.stack("ui.sidebar_clear_line").category(Category.UI)
            .label("スコアボードの %line 行目を消す（対象: %target）", "clear sidebar line %line for %target")
            .input("line", ValueType.NUMBER, 1).optionalInput("target", ValueType.PLAYER)
            .exec { ex, a -> ex.game.setSidebarLine(a.player("target"), a.integer("line"), null) },

        BlockBuilder.stack("ui.bossbar_show").category(Category.UI)
            .label(
                "ボスバー %id に %text を表示（進み具合 %progress、色 %color、対象: %target）",
                "show boss bar %id: %text progress %progress color %color for %target",
            )
            .field(FieldSpec.text("id", "main")).input("text", ValueType.STRING, "")
            .input("progress", ValueType.NUMBER, 1)
            .dropdown("color", "white", "white", "red", "blue", "green", "yellow", "purple", "pink")
            .optionalInput("target", ValueType.PLAYER)
            .exec { ex, a ->
                ex.game.showBossBar(
                    a.field("id"), a.player("target"), a.string("text"),
                    a.number("progress").coerceIn(0.0, 1.0).toFloat(), a.field("color"),
                )
            },

        BlockBuilder.stack("ui.bossbar_hide").category(Category.UI)
            .label("ボスバー %id を消す（対象: %target）", "hide boss bar %id for %target")
            .field(FieldSpec.text("id", "main")).optionalInput("target", ValueType.PLAYER)
            .exec { ex, a -> ex.game.hideBossBar(a.field("id"), a.player("target")) },

        BlockBuilder.stack("ui.announce").category(Category.UI)
            .label("全員に %text と言う", "announce %text")
            .input("text", ValueType.STRING, "")
            .exec { ex, a -> ex.game.announce(a.string("text")) },

        BlockBuilder.stack("ui.title_all").category(Category.UI)
            .label("全員にタイトル %title サブタイトル %subtitle を表示", "show title %title %subtitle to everyone")
            .input("title", ValueType.STRING, "").input("subtitle", ValueType.STRING, "")
            .exec { ex, a ->
                val mm = MiniMessage.miniMessage()
                val t = Title.title(
                    mm.deserialize(a.string("title")), mm.deserialize(a.string("subtitle")),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500)),
                )
                ex.game.players().forEach { it.player?.showTitle(t) }
            },
    )
}

/**
 * `view.*`: per-player visuals through the `view` capability (spec §12.7). Only meaningful
 * operations are exposed — never raw packets. Handles are released by the core when the viewer
 * leaves or the instance ends.
 */
internal object ViewBlocks {
    fun all(): List<BlockDefinition> = listOf(
        BlockBuilder.stack("view.fake_block").category(Category.VIEW)
            .label("%viewer にだけ見えるブロック %block を %location に置く", "show %block at %location only to %viewer")
            .requires("view:FAKE_BLOCK")
            .input("viewer", ValueType.PLAYER).input("location", ValueType.LOCATION)
            .input("block", ValueType.MATERIAL, "minecraft:glass")
            .exec { ex, a ->
                val gp = a.player("viewer") ?: return@exec
                val viewer = gp.player ?: return@exec
                val loc = a.location("location") ?: return@exec
                val m = a.material("block") ?: return@exec
                if (!m.isBlock) throw ScriptError("${m.key} はブロックではありません")
                ex.game.trackView(gp, view(ex).fakeBlock(viewer, loc, m.createBlockData()))
            },

        BlockBuilder.stack("view.glow").category(Category.VIEW)
            .label("%viewer にだけ %target を %color で発光させる", "make %target glow %color only for %viewer")
            .requires("view:GLOW")
            .input("viewer", ValueType.PLAYER).input("target", ValueType.ENTITY)
            .dropdown("color", "white", "white", "red", "blue", "green", "yellow", "aqua", "gold", "light_purple")
            .exec { ex, a ->
                val gp = a.player("viewer") ?: return@exec
                val viewer = gp.player ?: return@exec
                val target = entity(a.get("target")) ?: return@exec
                val color = NamedTextColor.NAMES.value(a.field("color")) ?: NamedTextColor.WHITE
                ex.game.trackView(gp, view(ex).glow(viewer, target, color))
            },

        BlockBuilder.stack("view.hide").category(Category.VIEW)
            .label("%viewer から %target を隠す", "hide %target from %viewer")
            .requires("view:HIDE_ENTITY")
            .input("viewer", ValueType.PLAYER).input("target", ValueType.ENTITY)
            .exec { ex, a ->
                val gp = a.player("viewer") ?: return@exec
                val viewer = gp.player ?: return@exec
                val target = entity(a.get("target")) ?: return@exec
                ex.game.trackView(gp, view(ex).hide(viewer, target))
            },
    )

    private fun view(ex: Exec) = ex.game.provider(ViewProvider::class.java) ?: throw ScriptError("view 能力がありません")

    private fun entity(o: Any?): Entity? = when (o) {
        is GamePlayer -> o.player
        is Entity -> o.takeIf { it.isValid }
        else -> null
    }
}
