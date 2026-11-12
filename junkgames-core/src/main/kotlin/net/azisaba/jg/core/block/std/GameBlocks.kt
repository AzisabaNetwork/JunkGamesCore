package net.azisaba.jg.core.block.std

import net.azisaba.jg.api.block.BlockBuilder
import net.azisaba.jg.api.block.BlockDefinition
import net.azisaba.jg.api.block.Category
import net.azisaba.jg.api.block.CompileException
import net.azisaba.jg.api.runtime.GamePlayer
import net.azisaba.jg.api.runtime.GameTeam
import net.azisaba.jg.api.runtime.SimpleStmt
import net.azisaba.jg.api.type.ValueType

/** `game.*`. The lifecycle itself is owned by the core; blocks may only end the game (spec §4). */
internal object GameBlocks {
    fun all(): List<BlockDefinition> = listOf(
        BlockBuilder.cap("game.end").category(Category.GAME)
            .label("ゲームを終了する（勝者: %kind %winner）", "end game (winner: %kind %winner)")
            .dropdown("kind", "none", "none", "player", "team")
            .optionalInput("winner", ValueType.ANY)
            .compiler { ctx, ir ->
                val winner = when (ctx.field(ir, "kind")) {
                    "player" -> ctx.input(ir, "winner", ValueType.PLAYER) ?: throw CompileException("勝者のプレイヤーを指定してください")
                    "team" -> ctx.input(ir, "winner", ValueType.TEAM) ?: throw CompileException("勝者のチームを指定してください")
                    else -> null
                }
                SimpleStmt { ex ->
                    val w = winner?.let { ex.eval(it) }
                    ex.stopScript()
                    ex.game.endGame(if (w is GamePlayer || w is GameTeam) w else null)
                }
            },

        BlockBuilder.stack("game.eliminate").category(Category.GAME)
            .label("%player を脱落させる", "eliminate %player")
            .playerInput("player")
            .exec { ex, a -> a.player("player")?.takeIf { it.isValid }?.let { ex.game.eliminate(it) } },

        BlockBuilder.stack("game.make_spectator").category(Category.GAME)
            .label("%player を観戦者にする", "make %player a spectator")
            .playerInput("player")
            .exec { ex, a -> a.player("player")?.takeIf { it.isValid }?.let { ex.game.makeSpectator(it) } },

        BlockBuilder.reporter("game.time_left", ValueType.NUMBER).category(Category.GAME)
            .label("残り時間（秒）", "time left").eval { ex, _ -> ex.game.timeLeftSeconds.toDouble() },
        BlockBuilder.reporter("game.player_count", ValueType.NUMBER).category(Category.GAME)
            .label("参加人数", "player count").eval { ex, _ -> ex.game.players().size.toDouble() },
        BlockBuilder.reporter("game.alive_count", ValueType.NUMBER).category(Category.GAME)
            .label("生存人数", "alive count").eval { ex, _ -> ex.game.alivePlayers().size.toDouble() },
        BlockBuilder.reporter("game.players", ValueType.listOf(ValueType.PLAYER)).category(Category.GAME)
            .label("参加者のリスト", "all players").eval { ex, _ -> ArrayList<Any?>(ex.game.players()) },
        BlockBuilder.reporter("game.alive_players", ValueType.listOf(ValueType.PLAYER)).category(Category.GAME)
            .label("生存者のリスト", "alive players").eval { ex, _ -> ArrayList<Any?>(ex.game.alivePlayers()) },
        BlockBuilder.reporter("game.teams", ValueType.listOf(ValueType.TEAM)).category(Category.GAME)
            .label("チームのリスト", "teams").eval { ex, _ -> ArrayList<Any?>(ex.game.teams()) },
        BlockBuilder.reporter("game.team_members", ValueType.listOf(ValueType.PLAYER)).category(Category.GAME)
            .label("%team のメンバー（%which）", "members of %team")
            .input("team", ValueType.TEAM).dropdown("which", "alive", "alive", "all")
            .eval { _, a ->
                val t = a.team("team") ?: return@eval ArrayList<Any?>()
                ArrayList<Any?>(if (a.field("which") == "all") t.members else t.aliveMembers)
            },
        BlockBuilder.reporter("game.team_by_id", ValueType.TEAM).category(Category.GAME)
            .label("チーム %id", "team %id")
            .input("id", ValueType.STRING, "red")
            .eval { ex, a ->
                val id = a.string("id")
                ex.game.teams().firstOrNull { it.id == id }
            },
        BlockBuilder.bool("game.is_alive").category(Category.GAME)
            .label("%player が生存している", "%player is alive")
            .playerInput("player")
            .eval { _, a -> a.player("player")?.let { it.isValid && it.isAlive } ?: false },
    )
}
