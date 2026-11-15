package dev.juliainsight

import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.util.Key
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.juliainsight.repl.JuliaBridgeServer
import dev.juliainsight.repl.JuliaCommandLines
import dev.juliainsight.repl.JuliaPlot
import dev.juliainsight.repl.JuliaPlotListener
import dev.juliainsight.sdk.JuliaInstallations
import dev.juliainsight.settings.JuliaSettings
import java.io.File
import java.nio.charset.StandardCharsets

/** Talks to a real Julia. Enabled when the system property `julia.insight.test.julia` points to a julia executable. */
class JuliaIntegrationTest : BasePlatformTestCase() {

    private val julia: String? get() = System.getProperty("julia.insight.test.julia")?.takeIf { it.isNotBlank() && File(it).isFile }

    override fun setUp() {
        super.setUp()
        JuliaSettings.getInstance().loadState(JuliaSettings.State().apply {
            juliaExecutable = julia.orEmpty()
            replThreads = "1"
        })
        JuliaInstallations.getInstance().resetDetection()
    }

    private fun waitUntil(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for $what")
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(50)
        }
    }

    fun testReplBracketedPasteAndWorkspace() {
        if (julia == null) return
        val inst = JuliaInstallations.getInstance().installation()!!
        val cmd = JuliaCommandLines.base(project, inst, null, kind = "repl", interactive = true)
        cmd.withWorkingDirectory(java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")))
        val pty = PtyCommandLine(cmd).withConsoleMode(false).withInitialColumns(120).withInitialRows(40)
        val handler = KillableProcessHandler(pty.createProcess(), pty.commandLineString, StandardCharsets.UTF_8)
        val out = StringBuffer()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                out.append(event.text)
            }
        })
        handler.startNotify()
        try {
            waitUntil(120_000, "the julia> prompt") { out.contains("julia>") }
            val code = "x = 1 + 1\ny = x * 3\nfunction foo(a)\n    a + 1\nend\nprintln(\"RESULT=\", foo(y))"
            handler.processInput!!.apply {
                write("\u001b[200~$code\u001b[201~\r".toByteArray(StandardCharsets.UTF_8))
                flush()
            }
            waitUntil(60_000, "the pasted block to run, output so far:\n$out") { out.contains("RESULT=7") }

            val bridge = JuliaBridgeServer.getInstance(project)
            waitUntil(30_000, "the REPL bridge connection") { bridge.isReplConnected }
            val vars = bridge.requestWorkspace(20_000).get()
            assertNotNull("workspace answer", vars)
            val byName = vars!!.associateBy { it.name }
            assertEquals("vars: $vars", "2", byName["x"]?.value)
            assertEquals("Int64", byName["y"]?.type)
            assertEquals("function", byName["foo"]?.kind)
        } finally {
            handler.destroyProcess()
            handler.waitFor(20_000)
            Thread.sleep(500) // let Windows release the working directory
        }
    }

    fun testPlotsReachTheIde() {
        if (julia == null) return
        val received = ArrayList<JuliaPlot>()
        project.messageBus.connect(testRootDisposable).subscribe(JuliaPlotListener.TOPIC, JuliaPlotListener { received += it })
        val script = File.createTempFile("plot", ".jl").apply {
            deleteOnExit()
            writeText(
                """
                struct FakePlot end
                Base.show(io::IO, ::MIME"image/png", ::FakePlot) = write(io, UInt8[0x89, 0x50, 0x4e, 0x47, 1, 2, 3])
                struct FakeSvg end
                Base.show(io::IO, ::MIME"image/svg+xml", ::FakeSvg) = print(io, "<svg xmlns='http://www.w3.org/2000/svg'/>")
                display(FakePlot())
                display(FakeSvg())
                display(42)            # plain values must still go to the console
                println("DONE")
                """.trimIndent(),
            )
        }
        val inst = JuliaInstallations.getInstance().installation()!!
        val cmd = JuliaCommandLines.base(project, inst, null, kind = "run", interactive = false)
        cmd.addParameter(script.absolutePath)
        cmd.withWorkingDirectory(java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")))
        val handler = KillableProcessHandler(cmd)
        val out = StringBuffer()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                out.append(event.text)
            }
        })
        handler.startNotify()
        try {
            waitUntil(120_000, "two plots; output:\n$out") { received.size >= 2 }
            waitUntil(30_000, "script end; output:\n$out") { out.contains("DONE") }
            assertEquals("image/png", received[0].mime)
            assertEquals(listOf(0x89, 0x50, 0x4e, 0x47, 1, 2, 3), received[0].data.map { it.toInt() and 0xff })
            assertEquals("image/svg+xml", received[1].mime)
            assertTrue(String(received[1].data).startsWith("<svg"))
            assertTrue("42 printed to the console:\n$out", out.contains("42"))
        } finally {
            handler.destroyProcess()
            handler.waitFor(20_000)
            Thread.sleep(500) // let Windows release the working directory
        }
    }
}
