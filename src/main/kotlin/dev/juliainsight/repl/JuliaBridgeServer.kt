package dev.juliainsight.repl

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import dev.juliainsight.settings.JuliaSettings
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

data class JuliaPlot(val mime: String, val data: ByteArray, val title: String, val timestamp: Long = System.currentTimeMillis())

data class JuliaVariable(val name: String, val kind: String, val type: String, val value: String)

fun interface JuliaPlotListener {
    fun plotReceived(plot: JuliaPlot)

    companion object {
        @JvmField val TOPIC: Topic<JuliaPlotListener> = Topic.create("Julia plots", JuliaPlotListener::class.java)
    }
}

/**
 * Local socket server the Julia processes started by the IDE connect to (see ide_bridge.jl).
 * Only accepts connections from localhost that present the per-session random token.
 */
@Service(Service.Level.PROJECT)
class JuliaBridgeServer(private val project: Project) : Disposable {
    private val LOG = logger<JuliaBridgeServer>()
    private val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private var server: ServerSocket? = null
    @Volatile private var replClient: Client? = null
    private val pendingWorkspace = ConcurrentLinkedQueue<CompletableFuture<List<JuliaVariable>>>()

    private inner class Client(val socket: Socket) {
        val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
        var kind: String = "?"

        @Synchronized
        fun send(line: String) {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }
    }

    @Synchronized
    private fun ensureStarted(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        server = s
        AppExecutorUtil.getAppExecutorService().execute { acceptLoop(s) }
        return s.localPort
    }

    /** Environment variables that make a Julia process (with ide_bridge.jl loaded) connect to this server. */
    fun environment(kind: String): Map<String, String> {
        val port = ensureStarted()
        return mapOf(
            "JULIA_INSIGHT_PORT" to port.toString(),
            "JULIA_INSIGHT_TOKEN" to token,
            "JULIA_INSIGHT_KIND" to kind,
            "JULIA_INSIGHT_PLOTS" to if (JuliaSettings.getInstance().state.plotsInIde) "1" else "0",
        )
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val socket = try {
                s.accept()
            } catch (_: Exception) {
                break
            }
            AppExecutorUtil.getAppExecutorService().execute { handle(Client(socket)) }
        }
    }

    private fun handle(client: Client) {
        var authenticated = false
        try {
            val reader = BufferedReader(InputStreamReader(client.socket.getInputStream(), StandardCharsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val msg = try {
                    JsonParser.parseString(line).asJsonObject
                } catch (e: Exception) {
                    LOG.debug("Bad bridge message", e)
                    continue
                }
                val type = msg.str("type")
                if (!authenticated) {
                    if (type != "hello" || msg.str("token") != token) break
                    authenticated = true
                    client.kind = msg.str("kind") ?: "?"
                    if (client.kind == "repl") replClient = client
                    continue
                }
                when (type) {
                    "plot" -> onPlot(msg)
                    "workspace" -> {
                        val vars = msg.getAsJsonArray("vars")?.mapNotNull { e ->
                            val o = e.asJsonObject
                            JuliaVariable(o.str("name") ?: return@mapNotNull null, o.str("kind") ?: "", o.str("type") ?: "", o.str("value") ?: "")
                        } ?: emptyList()
                        pendingWorkspace.poll()?.complete(vars)
                    }
                }
            }
        } catch (e: Exception) {
            LOG.debug("Bridge connection closed", e)
        } finally {
            if (replClient === client) replClient = null
            runCatching { client.socket.close() }
        }
    }

    private fun onPlot(msg: JsonObject) {
        val mime = msg.str("mime") ?: return
        val raw = msg.str("data") ?: return
        val bytes = if (mime == "image/png") Base64.getDecoder().decode(raw) else raw.toByteArray(StandardCharsets.UTF_8)
        val plot = JuliaPlot(mime, bytes, msg.str("title") ?: "Plot")
        ApplicationManager.getApplication().invokeLater({
            project.messageBus.syncPublisher(JuliaPlotListener.TOPIC).plotReceived(plot)
        }, project.disposed)
    }

    val isReplConnected: Boolean get() = replClient != null

    /** Asks the REPL for its global variables. Completes with null if the REPL is busy or not connected. */
    fun requestWorkspace(timeoutMs: Long = 4000): CompletableFuture<List<JuliaVariable>?> {
        val client = replClient ?: return CompletableFuture.completedFuture(null)
        val f = CompletableFuture<List<JuliaVariable>>()
        pendingWorkspace.add(f)
        try {
            client.send("workspace")
        } catch (e: Exception) {
            pendingWorkspace.remove(f)
            return CompletableFuture.completedFuture(null)
        }
        return f.orTimeout(timeoutMs, TimeUnit.MILLISECONDS).handle { v, _ ->
            if (v == null) pendingWorkspace.remove(f)
            v
        }
    }

    override fun dispose() {
        runCatching { server?.close() }
        replClient?.let { runCatching { it.socket.close() } }
    }

    companion object {
        fun getInstance(project: Project): JuliaBridgeServer = project.service()
        private fun JsonObject.str(name: String): String? = get(name)?.takeIf { !it.isJsonNull }?.asString
    }
}
