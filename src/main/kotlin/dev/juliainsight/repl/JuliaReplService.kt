package dev.juliainsight.repl

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.TerminalExecutionConsole
import com.intellij.terminal.TerminalExecutionConsoleBuilder
import com.intellij.util.execution.ParametersListUtil
import dev.juliainsight.lsp.JuliaLanguageServerService
import dev.juliainsight.lsp.JuliaScripts
import dev.juliainsight.sdk.JuliaInstallation
import dev.juliainsight.sdk.JuliaInstallations
import dev.juliainsight.settings.JuliaSettings
import java.nio.charset.StandardCharsets
import java.nio.file.Paths

/** Builds `julia` command lines shared by the REPL and run configurations. */
object JuliaCommandLines {
    fun base(
        project: Project,
        inst: JuliaInstallation,
        environment: String?,
        kind: String,
        interactive: Boolean,
        extraJuliaArgs: List<String> = emptyList(),
    ): GeneralCommandLine {
        val s = JuliaSettings.getInstance().state
        val args = mutableListOf(inst.executable, "--color=yes")
        if (interactive) args += "-i"
        s.replThreads.trim().takeIf { it.isNotEmpty() }?.let { args += "--threads=$it" }
        if (!environment.isNullOrBlank()) args += "--project=$environment"
        args += ParametersListUtil.parse(s.replExtraArgs)
        args += extraJuliaArgs
        args += listOf("-L", JuliaScripts.path("ide_bridge.jl").toString())
        val cmd = GeneralCommandLine(args).withCharset(StandardCharsets.UTF_8)
        cmd.withWorkingDirectory(JuliaInstallations.workingDirectory(project))
        cmd.environment.putAll(JuliaBridgeServer.getInstance(project).environment(kind))
        return cmd
    }
}

/** The project's Julia REPL, running in a real terminal emulator (PTY) inside the "Julia" tool window. */
@Service(Service.Level.PROJECT)
class JuliaReplService(private val project: Project) : Disposable {
    private var handler: KillableProcessHandler? = null
    private var console: TerminalExecutionConsole? = null
    @Volatile private var starting = false
    @Volatile private var ready = false
    @Volatile private var focusOnStart = false
    private val pending = ArrayList<String>()
    private val outputTail = StringBuilder()

    /** Set by the tool window. */
    var panel: JuliaReplPanel? = null
        set(value) {
            field = value
            console?.let { value?.showConsole(it) }
        }

    val isRunning: Boolean get() = handler?.let { !it.isProcessTerminated && !it.isProcessTerminating } == true

    fun showToolWindow(activate: Boolean) {
        val tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        if (activate) tw.activate(null, true) else tw.show()
    }

    /** Starts the REPL; [focus] moves keyboard focus into the terminal (explicit start), otherwise the editor keeps it. */
    fun start(focus: Boolean = false) {
        if (isRunning || starting) return
        starting = true
        focusOnStart = focus
        ready = false
        showToolWindow(activate = false)
        panel?.showMessage("Starting Julia…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val inst = JuliaInstallations.getInstance().installation()
            if (inst == null) {
                starting = false
                ApplicationManager.getApplication().invokeLater { panel?.showMessage("Julia was not found. Configure it in Settings | Languages & Frameworks | Julia.") }
                JuliaLanguageServerService.getInstance().notify(project, "Julia not found",
                    "Set the path to the julia executable in Settings | Languages & Frameworks | Julia.", NotificationType.WARNING)
                return@executeOnPooledThread
            }
            val cmd = JuliaCommandLines.base(project, inst, JuliaInstallations.environmentFor(project), kind = "repl", interactive = true)
            ApplicationManager.getApplication().invokeLater({ doStart(cmd) }, project.disposed)
        }
    }

    private fun doStart(cmd: GeneralCommandLine) {
        try {
            val pty = PtyCommandLine(cmd).withConsoleMode(false).withInitialColumns(140).withInitialRows(30)
            pty.environment["TERM"] = "xterm-256color"
            val process = pty.createProcess()
            val h = KillableProcessHandler(process, pty.commandLineString, StandardCharsets.UTF_8)
            h.setShouldKillProcessSoftly(false)
            val c = TerminalExecutionConsoleBuilder(project).build()
            Disposer.register(this, c)
            c.addMessageFilter(dev.juliainsight.run.JuliaStackTraceFilter(project, project.basePath))
            c.attachToProcess(h)
            h.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (ready) return
                    synchronized(outputTail) {
                        outputTail.append(event.text)
                        if (outputTail.contains("julia>")) {
                            ready = true
                            outputTail.setLength(0)
                            ApplicationManager.getApplication().invokeLater({ flushPending() }, project.disposed)
                        } else if (outputTail.length > 20_000) outputTail.delete(0, 10_000)
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    ready = false
                    ApplicationManager.getApplication().invokeLater({
                        panel?.processTerminated(event.exitCode)
                    }, project.disposed)
                }
            })
            console?.let { old -> Disposer.dispose(old) }
            handler = h
            console = c
            panel?.showConsole(c)
            if (focusOnStart) c.preferredFocusableComponent?.requestFocusInWindow()
            h.startNotify()
        } catch (e: ExecutionException) {
            panel?.showMessage("Could not start Julia: ${e.message}")
        } finally {
            starting = false
        }
    }

    private fun flushPending() {
        val items = synchronized(pending) { pending.toList().also { pending.clear() } }
        items.forEach { write(it) }
    }

    /** Sends code to the REPL as a bracketed paste so multi-line blocks are evaluated as one input. */
    fun execute(code: String, activate: Boolean = false) {
        val normalized = code.replace("\r\n", "\n").trimEnd('\n', ' ', '\t')
        if (normalized.isBlank()) return
        val payload = "\u001b[200~$normalized\u001b[201~\r"
        showToolWindow(activate)
        if (!isRunning || !ready) {
            synchronized(pending) { pending += payload }
            if (!isRunning) start()
            return
        }
        write(payload)
    }

    private fun write(text: String) {
        val out = handler?.processInput ?: return
        try {
            out.write(text.toByteArray(StandardCharsets.UTF_8))
            out.flush()
        } catch (_: Exception) {
        }
    }

    fun interrupt() = write("\u0003")

    fun stop() {
        handler?.destroyProcess()
    }

    fun restart() {
        val h = handler
        if (h == null || h.isProcessTerminated) {
            start(focus = true)
            return
        }
        h.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                ApplicationManager.getApplication().invokeLater({ start(focus = true) }, project.disposed)
            }
        })
        h.destroyProcess()
    }

    fun clear() {
        console?.clear()
    }

    override fun dispose() {
        handler?.destroyProcess()
    }

    companion object {
        const val TOOL_WINDOW_ID = "Julia"
        fun getInstance(project: Project): JuliaReplService = project.service()
    }
}
