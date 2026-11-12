package net.azisaba.jg.core.util

import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/**
 * All external I/O (files now; WebSocket / DB later) goes through here so that no world tick ever
 * blocks on it (spec §12.8 convention 6).
 */
class Outbox(private val log: Logger, private val errorLog: Path) : AutoCloseable {
    val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "JunkGames-IO").apply { isDaemon = true } }
    private val recent = ArrayDeque<String>()

    fun submit(task: () -> Unit) {
        try {
            io.execute {
                try {
                    task()
                } catch (e: Exception) {
                    log.log(Level.WARNING, "I/O task failed", e)
                }
            }
        } catch (_: RejectedExecutionException) {
            task() // shutting down: run inline
        }
    }

    /** Records a script error (logger + errors.log as JSON lines). Safe from any thread. */
    fun scriptError(instance: String, game: String, version: Int, script: String, nodeId: String?, message: String, warning: Boolean) {
        val line = "${if (warning) "[警告]" else "[エラー]"} $instance $game v$version $script${nodeId?.let { " [$it]" } ?: ""}: $message"
        synchronized(recent) {
            recent.addLast(line)
            while (recent.size > 200) recent.removeFirst()
        }
        val o = JsonObject().apply {
            addProperty("time", Instant.now().toString())
            addProperty("level", if (warning) "warning" else "error")
            addProperty("instance", instance)
            addProperty("game", game)
            addProperty("version", version)
            addProperty("script", script)
            addProperty("nodeId", nodeId)
            addProperty("message", message)
        }
        submit {
            if (warning) log.fine(line) else log.warning(line)
            Files.createDirectories(errorLog.parent)
            Files.writeString(errorLog, "$o\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    fun recent(n: Int): List<String> = synchronized(recent) { recent.toList().takeLast(n) }

    override fun close() {
        io.shutdown()
        io.awaitTermination(10, TimeUnit.SECONDS)
    }
}

/** Minimal SemVer range check: `>=1.2.0`, `>1.0`, `<=2`, `=1.0.0`, `^1.2`, `1.2.3`, `*`; space = AND. */
object SemVer {
    fun satisfies(version: String, range: String?): Boolean {
        if (range.isNullOrBlank() || range == "*") return true
        return range.trim().split(Regex("\\s+")).all { single(version, it) }
    }

    private fun single(version: String, r: String): Boolean {
        val op = listOf(">=", "<=", ">", "<", "=", "^").firstOrNull { r.startsWith(it) } ?: ""
        val v = r.removePrefix(op)
        val c = compare(version, v)
        return when (op) {
            ">=" -> c >= 0
            "<=" -> c <= 0
            ">" -> c > 0
            "<" -> c < 0
            "^" -> c >= 0 && parts(version)[0] == parts(v)[0]
            else -> c == 0
        }
    }

    fun compare(a: String, b: String): Int {
        val x = parts(a)
        val y = parts(b)
        for (i in 0..2) if (x[i] != y[i]) return x[i].compareTo(y[i])
        return 0
    }

    private fun parts(v: String): IntArray {
        val s = v.split('-', '+')[0].split('.')
        return IntArray(3) { i -> s.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0 }
    }
}
