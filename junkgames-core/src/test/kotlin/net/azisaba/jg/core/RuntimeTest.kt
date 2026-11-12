package net.azisaba.jg.core

import net.azisaba.jg.api.runtime.Limits
import net.azisaba.jg.core.TestSupport.game
import net.azisaba.jg.core.TestSupport.run
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuntimeTest {
    private val numVar = """[{"name":"x","scope":"instance","type":"number","default":0},
                             {"name":"i","scope":"instance","type":"number","default":0}]"""

    private fun set(name: String, value: String) =
        """{"type":"var.set","fields":{"name":"$name"},"inputs":{"value":$value}}"""

    private fun change(name: String, by: String) =
        """{"type":"var.change","fields":{"name":"$name"},"inputs":{"delta":$by}}"""

    private fun get(name: String) = """{"type":"var.get","fields":{"name":"$name"}}"""

    @Test
    fun `repeat without loop yield runs in one tick`() {
        val g = run(
            game(
                """[{"id":"s","trigger":{"type":"event.game_start"},"body":[
                  {"type":"control.repeat","inputs":{"times":{"lit":10}},"body":{"do":[
                    ${change("i", """{"lit":1}""")},
                    ${change("x", get("i"))}
                  ]}}
                ]}]""",
                numVar, meta = """, "loopYield": false""",
            ),
        )
        g.fire("game_start")
        assertEquals(55.0, g.vars["x"])
        assertTrue(g.errors.isEmpty(), g.errors.toString())
    }

    @Test
    fun `loops yield one tick per iteration by default (Scratch)`() {
        val g = run(
            game(
                """[{"id":"s","trigger":{"type":"event.game_start"},"body":[
                  {"type":"control.repeat","inputs":{"times":{"lit":3}},"body":{"do":[${change("x", """{"lit":1}""")}]}}
                ]}]""",
                numVar,
            ),
        )
        g.fire("game_start")
        assertEquals(1.0, g.vars["x"])
        g.ticks(1)
        assertEquals(2.0, g.vars["x"])
        g.ticks(1)
        assertEquals(3.0, g.vars["x"])
    }

    @Test
    fun `wait suspends the runner`() {
        val g = run(
            game(
                """[{"id":"s","trigger":{"type":"event.game_start"},"body":[
                  {"type":"control.wait_ticks","inputs":{"ticks":{"lit":5}}},
                  ${set("x", """{"lit":7}""")}
                ]}]""",
                numVar,
            ),
        )
        g.fire("game_start")
        g.ticks(4)
        assertEquals(null, g.vars["x"])
        g.ticks(1)
        assertEquals(7.0, g.vars["x"])
    }

    @Test
    fun `recursive custom reporter`() {
        val fact = """[{"name":"fact","params":[{"name":"n","type":"number"}],"returns":"number","body":[
          {"type":"control.if_else","inputs":{"condition":{"type":"op.lt","inputs":{"a":{"type":"var.get","fields":{"name":"n"}},"b":{"lit":2}}}},
           "body":{"do":[{"type":"control.return","inputs":{"value":{"lit":1}}}],
                   "else":[{"type":"control.return","inputs":{"value":{"type":"op.mul","inputs":{
                      "a":{"type":"var.get","fields":{"name":"n"}},
                      "b":{"type":"custom.call","fields":{"name":"fact"},"inputs":{"n":{"type":"op.sub","inputs":{"a":{"type":"var.get","fields":{"name":"n"}},"b":{"lit":1}}}}}}}}}]}}
        ]}]"""
        val g = run(
            game(
                """[{"id":"s","trigger":{"type":"event.game_start"},"body":[
                  ${set("x", """{"type":"custom.call","fields":{"name":"fact"},"inputs":{"n":{"lit":5}}}""")}
                ]}]""",
                numVar, fact,
            ),
        )
        g.fire("game_start")
        assertEquals(120.0, g.vars["x"])
        assertTrue(g.errors.isEmpty(), g.errors.toString())
    }

    @Test
    fun `recursion depth is limited`() {
        val forever = """[{"name":"f","params":[],"returns":null,"body":[{"type":"custom.call","fields":{"name":"f"}}]}]"""
        val g = run(
            game("""[{"id":"s","trigger":{"type":"event.game_start"},"body":[{"id":"call","type":"custom.call","fields":{"name":"f"}}]}]""", numVar, forever),
            Limits(maxCallDepth = 16),
        )
        g.fire("game_start")
        assertEquals(1, g.errors.size)
        assertTrue("深すぎ" in g.errors[0], g.errors[0])
    }

    @Test
    fun `runaway loop is stopped with the node id`() {
        val g = run(
            game(
                """[{"id":"s","trigger":{"type":"event.game_start"},"body":[
                  {"id":"loop","type":"control.forever","body":{"do":[${change("x", """{"lit":1}""")}]}}
                ]}]""",
                numVar, meta = """, "loopYield": false""",
            ),
            Limits(perRunnerBudget = 50, runawayTicks = 3),
        )
        g.fire("game_start")
        g.ticks(5)
        assertEquals(1, g.errors.size, g.errors.toString())
        assertTrue("暴走" in g.errors[0])
        assertEquals(0, g.runtime.runnerCount)
    }

    @Test
    fun `instance budget carries runners over to the next tick`() {
        val g = run(
            game(
                """[{"id":"s","trigger":{"type":"event.game_start"},"body":[
                  {"type":"control.repeat","inputs":{"times":{"lit":100}},"body":{"do":[${change("x", """{"lit":1}""")}]}}
                ]}]""",
                numVar, meta = """, "loopYield": false""",
            ),
            Limits(perRunnerBudget = 40),
        )
        g.fire("game_start")
        val afterFirst = g.vars["x"] as Double
        assertTrue(afterFirst < 100.0)
        g.ticks(20)
        assertEquals(100.0, g.vars["x"])
        assertTrue(g.errors.isEmpty())
    }

    @Test
    fun `broadcast and wait`() {
        val g = run(
            game(
                """[
                  {"id":"a","trigger":{"type":"event.game_start"},"body":[
                    {"type":"control.broadcast_and_wait","fields":{"message":"go"}},
                    ${set("x", """{"type":"op.add","inputs":{"a":${get("i")},"b":{"lit":100}}}""")}
                  ]},
                  {"id":"b","trigger":{"type":"event.message_received","fields":{"message":"go"}},"body":[
                    {"type":"control.wait_ticks","inputs":{"ticks":{"lit":3}}},
                    ${set("i", """{"lit":5}""")}
                  ]}
                ]""",
                numVar,
            ),
        )
        g.fire("game_start")
        g.ticks(2)
        assertEquals(null, g.vars["x"])
        g.ticks(3)
        assertEquals(105.0, g.vars["x"])
    }

    @Test
    fun `stop all from inside a script is safe`() {
        val g = run(
            game(
                """[
                  {"id":"a","trigger":{"type":"event.game_start"},"body":[
                    {"type":"control.forever","body":{"do":[${change("x", """{"lit":1}""")}]}}
                  ]},
                  {"id":"b","trigger":{"type":"event.game_start"},"body":[
                    {"type":"control.wait_ticks","inputs":{"ticks":{"lit":2}}},
                    {"type":"control.stop_all"}
                  ]}
                ]""",
                numVar,
            ),
        )
        g.fire("game_start")
        g.ticks(10)
        assertEquals(0, g.runtime.runnerCount)
        assertTrue(g.errors.isEmpty(), g.errors.toString())
    }

    @Test
    fun `restart concurrency stops the previous run`() {
        val g = run(
            game(
                """[{"id":"s","trigger":{"type":"event.message_received","fields":{"message":"m"}},"concurrency":"restart","body":[
                    {"type":"control.wait_ticks","inputs":{"ticks":{"lit":3}}},
                    ${change("x", """{"lit":1}""")}
                ]}]""",
                numVar,
            ),
        )
        g.broadcast("m", null)
        g.ticks(1)
        g.broadcast("m", null)
        g.ticks(5)
        assertEquals(1.0, g.vars["x"])
    }

    @Test
    fun `game end block`() {
        val g = run(game("""[{"id":"s","trigger":{"type":"event.game_start"},"body":[{"type":"game.end","fields":{"kind":"none"}}]}]"""))
        g.fire("game_start")
        assertTrue(g.ended)
    }
}
