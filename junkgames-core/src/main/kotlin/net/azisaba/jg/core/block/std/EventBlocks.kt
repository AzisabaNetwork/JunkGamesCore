package net.azisaba.jg.core.block.std

import net.azisaba.jg.api.block.BlockBuilder
import net.azisaba.jg.api.block.BlockDefinition
import net.azisaba.jg.api.block.Category
import net.azisaba.jg.api.block.CompileContext
import net.azisaba.jg.api.block.CompileException
import net.azisaba.jg.api.block.FieldSpec
import net.azisaba.jg.api.ir.IrNode
import net.azisaba.jg.api.runtime.Exec
import net.azisaba.jg.api.runtime.Expr
import net.azisaba.jg.api.runtime.Region
import net.azisaba.jg.api.runtime.TriggerEvent
import net.azisaba.jg.api.runtime.TriggerSpec
import net.azisaba.jg.api.runtime.Values
import net.azisaba.jg.api.type.ValueType
import net.azisaba.jg.core.compile.RegionRef
import org.bukkit.Material

/**
 * `event.*`: hats (triggers), event context reporters and cancellation.
 * Trigger inputs must be constants (they are evaluated at compile time).
 */
internal object EventBlocks {
    /** Event type names produced by the core's listeners / lifecycle. */
    object Types {
        const val GAME_START = "game_start"
        const val GAME_END = "game_end"
        const val TIME_UP = "time_up"
        const val STATE_WAITING = "state_waiting"
        const val COUNTDOWN = "countdown"
        const val PLAYER_JOIN = "player_join"
        const val PLAYER_QUIT = "player_quit"
        const val PLAYER_DEATH = "player_death"
        const val PLAYER_RESPAWN = "player_respawn"
        const val PLAYER_KILL = "player_kill"
        const val REGION_ENTER = "region_enter"
        const val REGION_EXIT = "region_exit"
        const val BLOCK_BREAK = "block_break"
        const val BLOCK_PLACE = "block_place"
        const val MOVE_ONTO_BLOCK = "move_onto_block"
        const val ITEM_USE = "item_use"
        const val PLAYER_ATTACK = "player_attack"
        const val PLAYER_DAMAGE = "player_damage"
        const val TIMER = "timer"
        const val MESSAGE = "message"
        const val VARIABLE_CHANGED = "variable_changed"
    }

    private val ALWAYS = TriggerSpec.ActiveIn.ALWAYS
    private val RUNNING = TriggerSpec.ActiveIn.RUNNING

    fun all(): List<BlockDefinition> = listOf(
        simpleHat("event.game_start", "ゲームが始まったとき", Types.GAME_START, RUNNING),
        simpleHat("event.game_end", "ゲームが終わったとき", Types.GAME_END, ALWAYS),
        simpleHat("event.time_up", "制限時間になったとき", Types.TIME_UP, RUNNING),
        simpleHat("event.state_waiting", "参加待ちになったとき", Types.STATE_WAITING, ALWAYS),
        simpleHat("event.countdown_start", "カウントダウンが始まったとき", Types.COUNTDOWN, ALWAYS),
        simpleHat("event.player_join", "プレイヤーが参加したとき", Types.PLAYER_JOIN, ALWAYS),
        simpleHat("event.player_quit", "プレイヤーが退出したとき", Types.PLAYER_QUIT, ALWAYS),
        simpleHat("event.player_death", "プレイヤーが死んだとき", Types.PLAYER_DEATH, RUNNING),
        simpleHat("event.player_respawn", "プレイヤーがリスポーンしたとき", Types.PLAYER_RESPAWN, RUNNING),
        simpleHat("event.player_kill", "プレイヤーがプレイヤーをキルしたとき", Types.PLAYER_KILL, RUNNING),

        BlockBuilder.hat("event.region_enter").category(Category.EVENT)
            .label("プレイヤーが %region に入ったとき（止める: %cancel）")
            .input("region", ValueType.REGION).field(cancelField())
            .trigger { ctx, ir ->
                TriggerSpec(Types.REGION_ENTER, key = regionName(ctx, ir, "region"), autoCancel = cancel(ctx, ir))
            },
        BlockBuilder.hat("event.region_exit").category(Category.EVENT)
            .label("プレイヤーが %region から出たとき（止める: %cancel）")
            .input("region", ValueType.REGION).field(cancelField())
            .trigger { ctx, ir ->
                TriggerSpec(Types.REGION_EXIT, key = regionName(ctx, ir, "region"), autoCancel = cancel(ctx, ir))
            },

        blockHat("event.block_break", "プレイヤーがブロック %material を壊したとき（領域: %region, キャンセル: %cancel）", Types.BLOCK_BREAK),
        blockHat("event.block_place", "プレイヤーがブロック %material を置いたとき（領域: %region, キャンセル: %cancel）", Types.BLOCK_PLACE),

        BlockBuilder.hat("event.player_move_onto_block").category(Category.EVENT)
            .label("プレイヤーがブロック %material の上に乗ったとき（%filter %region）")
            .dropdown("filter", "any", "any", "in_region")
            .optionalInput("region", ValueType.REGION).optionalInput("material", ValueType.MATERIAL)
            .trigger { ctx, ir ->
                val region = if (ctx.field(ir, "filter") == "in_region") regionName(ctx, ir, "region") else null
                val mat = constantMaterial(ctx, ir, "material")
                TriggerSpec(Types.MOVE_ONTO_BLOCK, filter = { g, e -> blockMatches(g.region(region), region, mat, e) })
            },

        BlockBuilder.hat("event.item_use").category(Category.EVENT)
            .label("プレイヤーがアイテム %material を使ったとき（キャンセル: %cancel）")
            .optionalInput("material", ValueType.MATERIAL).field(cancelField())
            .trigger { ctx, ir ->
                val mat = constantMaterial(ctx, ir, "material")
                TriggerSpec(Types.ITEM_USE, autoCancel = cancel(ctx, ir), filter = { _, e -> mat == null || mat == e.material })
            },

        BlockBuilder.hat("event.player_attack").category(Category.EVENT)
            .label("プレイヤーがプレイヤーを攻撃したとき（キャンセル: %cancel）")
            .field(cancelField())
            .trigger { ctx, ir -> TriggerSpec(Types.PLAYER_ATTACK, autoCancel = cancel(ctx, ir)) },

        BlockBuilder.hat("event.player_damage").category(Category.EVENT)
            .label("プレイヤーが %cause でダメージを受けたとき（キャンセル: %cancel）")
            .dropdown("cause", "any", "any", "fall", "fire", "lava", "void", "drowning", "explosion", "projectile", "entity", "player")
            .field(cancelField())
            .trigger { ctx, ir ->
                val cause = ctx.field(ir, "cause")
                TriggerSpec(Types.PLAYER_DAMAGE, autoCancel = cancel(ctx, ir), filter = { _, e -> cause == "any" || cause == e.key })
            },

        BlockBuilder.hat("event.every_second").category(Category.EVENT)
            .label("毎秒", "every second")
            .trigger { _, _ -> TriggerSpec(Types.TIMER, intervalTicks = 20) },
        BlockBuilder.hat("event.every_n_ticks").category(Category.EVENT)
            .label("%ticks ティックごと", "every %ticks ticks")
            .input("ticks", ValueType.NUMBER, 20)
            .trigger { ctx, ir ->
                val e = ctx.input(ir, "ticks") as? Expr.Constant ?: throw CompileException("間隔は定数で指定してください")
                val t = Values.toNumber(e.value).toInt()
                if (t < 1) throw CompileException("間隔は1以上にしてください")
                TriggerSpec(Types.TIMER, intervalTicks = t)
            },

        BlockBuilder.hat("event.message_received").category(Category.EVENT)
            .label("メッセージ %message を受け取ったとき", "when I receive %message")
            .field(FieldSpec.of("message", FieldSpec.Kind.MESSAGE))
            .trigger { ctx, ir -> TriggerSpec(Types.MESSAGE, key = ctx.field(ir, "message"), activeIn = ALWAYS) },

        BlockBuilder.hat("event.variable_changed").category(Category.EVENT)
            .label("変数 %name が変わったとき", "when %name changes")
            .field(FieldSpec.of("name", FieldSpec.Kind.VARIABLE))
            .trigger { ctx, ir ->
                val name = ctx.field(ir, "name")
                ctx.variable(name) ?: throw CompileException("変数 '$name' は宣言されていません")
                TriggerSpec(Types.VARIABLE_CHANGED, key = name)
            },

        // ---- context reporters (spec §7.3) ----
        BlockBuilder.reporter("event.player", ValueType.PLAYER).category(Category.EVENT)
            .label("このプレイヤー", "this player").eval { ex, _ -> ex.event.player },
        BlockBuilder.reporter("event.other_player", ValueType.PLAYER).category(Category.EVENT)
            .label("相手のプレイヤー", "other player").eval { ex, _ -> ex.event.otherPlayer },
        BlockBuilder.reporter("event.block_location", ValueType.LOCATION).category(Category.EVENT)
            .label("ブロックの位置", "block location").eval { ex, _ -> ex.event.blockLocation?.clone() },
        BlockBuilder.reporter("event.block_material", ValueType.MATERIAL).category(Category.EVENT)
            .label("ブロック・アイテムの種類", "event material").eval { ex, _ -> ex.event.material },
        BlockBuilder.reporter("event.message_value", ValueType.ANY).category(Category.EVENT)
            .label("受け取った値", "message value").eval { ex, _ -> Values.copyIfList(ex.event.value) },
        BlockBuilder.reporter("event.damage", ValueType.NUMBER).category(Category.EVENT)
            .label("ダメージ量", "damage").eval { ex, _ -> ex.event.amount },
        BlockBuilder.reporter("event.entity", ValueType.ENTITY).category(Category.EVENT)
            .label("イベントのエンティティ", "event entity").eval { ex, _ -> ex.event.entity },

        BlockBuilder.stack("event.cancel").category(Category.EVENT)
            .label("イベントをキャンセルする", "cancel event")
            .exec { ex, _ -> setCancelled(ex, true) },
        BlockBuilder.stack("event.allow").category(Category.EVENT)
            .label("イベントを許可する（キャンセルを取り消す）", "allow event")
            .exec { ex, _ -> setCancelled(ex, false) },
        BlockBuilder.stack("event.set_damage").category(Category.EVENT)
            .label("ダメージ量を %amount にする", "set damage to %amount")
            .input("amount", ValueType.NUMBER, 0)
            .exec { ex, a ->
                val v = maxOf(0.0, a.number("amount"))
                if (!ex.inSyncSection) {
                    ex.warn("待機の後ではダメージ量を変更できません（無視しました）")
                } else {
                    ex.event.amount = v
                    ex.event.extra["damageChanged"] = true
                }
            },
    )

    private fun setCancelled(ex: Exec, cancelled: Boolean) {
        val e = ex.event
        when {
            !e.cancellable -> ex.warn("このイベントはキャンセルできません")
            // spec §7.3: no error after a wait, just a warning
            !ex.inSyncSection -> ex.warn("待機の後ではイベントをキャンセルできません（無視しました）")
            else -> e.cancelled = cancelled
        }
    }

    private fun simpleHat(id: String, label: String, type: String, activeIn: TriggerSpec.ActiveIn) =
        BlockBuilder.hat(id).category(Category.EVENT).label(label)
            .trigger { _, _ -> TriggerSpec(type, activeIn = activeIn) }

    private fun blockHat(id: String, label: String, type: String) =
        BlockBuilder.hat(id).category(Category.EVENT).label(label)
            .optionalInput("material", ValueType.MATERIAL).optionalInput("region", ValueType.REGION)
            .field(cancelField())
            .trigger { ctx, ir ->
                val mat = constantMaterial(ctx, ir, "material")
                val region = if ("region" in ir.inputs) regionName(ctx, ir, "region") else null
                TriggerSpec(type, autoCancel = cancel(ctx, ir), filter = { g, e -> blockMatches(g.region(region), region, mat, e) })
            }

    private fun blockMatches(r: Region?, regionName: String?, mat: Material?, e: TriggerEvent): Boolean {
        if (mat != null && mat != e.material) return false
        if (regionName == null) return true
        val loc = e.blockLocation ?: return false
        return r != null && r.containsBlock(loc.blockX, loc.blockY, loc.blockZ)
    }

    private fun cancelField() = FieldSpec("cancel", FieldSpec.Kind.CHECKBOX, emptyList(), "false")

    private fun cancel(ctx: CompileContext, ir: IrNode) = ctx.field(ir, "cancel").equals("true", ignoreCase = true)

    private fun regionName(ctx: CompileContext, ir: IrNode, input: String): String =
        (ctx.input(ir, input, ValueType.REGION) as? RegionRef)?.name
            ?: throw CompileException("トリガーの領域はマップの領域名（regionRef）で指定してください")

    private fun constantMaterial(ctx: CompileContext, ir: IrNode, input: String): Material? {
        val e = ctx.input(ir, input, ValueType.MATERIAL) ?: return null
        return (e as? Expr.Constant)?.value as? Material ?: throw CompileException("トリガーの素材は定数で指定してください")
    }
}
