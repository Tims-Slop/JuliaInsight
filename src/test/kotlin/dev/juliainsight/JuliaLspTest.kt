package dev.juliainsight

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspClientManagerListener
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import dev.juliainsight.lsp.JuliaLspIntegrationProvider
import dev.juliainsight.sdk.JuliaInstallations
import dev.juliainsight.settings.JuliaSettings
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Starts LanguageServer.jl through the IDE's LSP client and checks completion, diagnostics and hover.
 * Needs `julia.insight.test.julia` and a depot with LanguageServer.jl installed (env JULIA_DEPOT_PATH).
 */
class JuliaLspTest : UsefulTestCase() {
    private var fixture: CodeInsightTestFixture? = null
    private lateinit var root: java.nio.file.Path
    private val julia: String? get() = System.getProperty("julia.insight.test.julia")?.takeIf { it.isNotBlank() && File(it).isFile }
    private val enabled get() = julia != null && !System.getenv("JULIA_DEPOT_PATH").isNullOrBlank()

    override fun setUp() {
        super.setUp()
        if (!enabled) return
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val projectBuilder = factory.createFixtureBuilder(name)
        root = Files.createTempDirectory("julia-lsp-test")
        projectBuilder.addModule(com.intellij.testFramework.builders.EmptyModuleFixtureBuilder::class.java).addContentRoot(root.toString())
        fixture = factory.createCodeInsightFixture(projectBuilder.fixture).also { it.setUp() }
    }

    override fun tearDown() {
        try {
            fixture?.let { f ->
                LspClientManager.getInstance(f.project).stopClients(JuliaLspIntegrationProvider::class.java)
                f.tearDown()
            }
        } finally {
            fixture = null
            super.tearDown()
        }
    }

    private fun waitFor(seconds: Int, what: String, cond: () -> Boolean) {
        PlatformTestUtil.waitWithEventsDispatching("$what not reached in $seconds s", cond, seconds)
    }

    fun testLanguageServerEndToEnd() {
        val f = fixture ?: return
        JuliaSettings.getInstance().loadState(JuliaSettings.State().apply { juliaExecutable = julia!!; symbolCacheDownload = System.getenv("JULIA_INSIGHT_TEST_SYMBOL_DOWNLOAD") == "1" })
        JuliaInstallations.getInstance().resetDetection()
        assertNotNull(JuliaInstallations.getInstance().installation())

        val text = """
            module Demo
            "Kinetic energy of mass `m` at speed `v`."
            kinetic(m, v) = m * v^2 / 2

            function demo()
                e = kinetic(2.0, 3.0)
                y = undefined_thing + 1
                printl
            end
            end
        """.trimIndent()
        val path = root.resolve("demo.jl")
        Files.writeString(path, text)
        val vf: VirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!

        val opened = AtomicBoolean()
        val diagnostics = AtomicInteger()
        val crashed = AtomicBoolean()
        LspClientManager.getInstance(f.project).addListener(object : LspClientManagerListener {
            override fun fileOpened(lspClient: LspClient, file: VirtualFile) {
                if (file == vf) opened.set(true)
            }
            override fun diagnosticsReceived(lspClient: LspClient, file: VirtualFile) {
                if (file == vf) diagnostics.incrementAndGet()
            }
            override fun serverStateChanged(lspClient: LspClient) {
                if (lspClient.state == LspServerState.ShutdownUnexpectedly) crashed.set(true)
            }
        }, testRootDisposable, true)

        f.openFileInEditor(vf)
        // the editor-open hook is not active in tests: go through the same entry point the IDE uses
        val spec = dev.juliainsight.lsp.JuliaLanguageServerService.getInstance().launchSpecOrSchedule(f.project)
        assertNotNull("LanguageServer.jl must be installed in JULIA_DEPOT_PATH", spec)
        LspClientManager.getInstance(f.project).ensureClientStarted(
            JuliaLspIntegrationProvider::class.java, dev.juliainsight.lsp.JuliaLspClientDescriptor(f.project, spec!!),
        )
        waitFor(300, "language server opened the file") { opened.get() || crashed.get() }
        assertFalse("language server crashed", crashed.get())
        waitFor(300, "diagnostics") { diagnostics.get() > 0 }

        val client = LspClientManager.getInstance(f.project).getClients(JuliaLspIntegrationProvider::class.java).single()
        assertEquals(LspServerState.Running, client.state)

        // hover over the call to `kinetic` shows its docstring
        val line = text.lines().indexOfFirst { it.contains("e = kinetic") }
        val col = text.lines()[line].indexOf("kinetic") + 2
        var hoverText = ""
        waitFor(120, "hover with the docstring (last: $hoverText)") {
            val hover = client.sendRequestSync(20_000) { it.textDocumentService.hover(HoverParams(client.getDocumentIdentifier(vf), Position(line, col))) }
            hoverText = hover?.contents?.let { c -> if (c.isRight) c.right.value else c.left.joinToString { e -> if (e.isLeft) e.left else e.right.value } }.orEmpty()
            hoverText.contains("Kinetic energy")
        }

        // completion goes through the IDE's completion machinery
        f.editor.caretModel.moveToOffset(f.editor.document.text.indexOf("printl") + "printl".length)
        var lookups: List<String> = emptyList()
        waitFor(120, "completion containing println (last: ${lookups.take(10)})") {
            val items = f.completeBasic()
            lookups = items?.map { it.lookupString } ?: listOf(f.editor.document.text.substringAfter("    ").lineSequence().drop(2).first().trim())
            f.lookup?.hideLookup(true)
            lookups.any { it == "println" }
        }

        // LanguageServer.jl's lint warning shows up in the editor
        waitFor(180, "lint warning in highlighting") {
            f.doHighlighting().any { it.description?.contains("assigned but not used", ignoreCase = true) == true }
        }
    }
}
