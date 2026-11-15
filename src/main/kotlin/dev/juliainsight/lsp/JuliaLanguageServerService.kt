package dev.juliainsight.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.util.execution.ParametersListUtil
import dev.juliainsight.sdk.JuliaInstallation
import dev.juliainsight.sdk.JuliaInstallations
import dev.juliainsight.sdk.JuliaVersion
import dev.juliainsight.settings.JuliaConfigurable
import dev.juliainsight.settings.JuliaSettings
import dev.juliainsight.settings.LanguageServerKind
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.exists

/** How to start the language server. */
sealed class LaunchSpec(val presentableName: String) {
    abstract fun commandLine(project: Project): GeneralCommandLine

    class LanguageServerJl(private val julia: JuliaInstallation, private val envDir: Path) : LaunchSpec("Julia (LanguageServer.jl)") {
        override fun commandLine(project: Project): GeneralCommandLine {
            val script = JuliaScripts.path("ls_run.jl")
            val env = JuliaInstallations.environmentFor(project) ?: ""
            return GeneralCommandLine(
                julia.executable, "--startup-file=no", "--history-file=no", "--project=$envDir", script.toString(), env,
            ).withWorkingDirectory(JuliaInstallations.workingDirectory(project)).withCharset(Charsets.UTF_8)
        }
    }

    class Jetls(private val executable: String) : LaunchSpec("Julia (JETLS)") {
        override fun commandLine(project: Project): GeneralCommandLine =
            GeneralCommandLine(executable, "serve").withWorkingDirectory(JuliaInstallations.workingDirectory(project)).withCharset(Charsets.UTF_8)
    }

    class Custom(private val command: String) : LaunchSpec("Julia (custom server)") {
        override fun commandLine(project: Project): GeneralCommandLine =
            GeneralCommandLine(ParametersListUtil.parse(command)).withWorkingDirectory(JuliaInstallations.workingDirectory(project))
                .withCharset(Charsets.UTF_8)
    }
}

/** Bundled Julia scripts are copied to the IDE system directory so Julia can run them. */
object JuliaScripts {
    private val dir: Path by lazy { Paths.get(PathManager.getSystemPath(), "julia-insight", "scripts") }

    @Synchronized
    fun path(name: String): Path {
        val target = dir.resolve(name)
        val bytes = JuliaScripts::class.java.getResourceAsStream("/julia/$name")?.use { it.readBytes() }
            ?: error("missing bundled script $name")
        if (!target.exists() || !Files.readAllBytes(target).contentEquals(bytes)) {
            Files.createDirectories(dir)
            Files.write(target, bytes)
        }
        return target
    }
}

@Service(Service.Level.APP)
class JuliaLanguageServerService {
    private val LOG = logger<JuliaLanguageServerService>()
    private val busy = AtomicBoolean(false)
    private val detectionScheduled = AtomicBoolean(false)
    @Volatile private var installOffered = false
    @Volatile private var missingJuliaReported = false
    @Volatile private var missingJetlsReported = false

    fun lsEnvDir(version: JuliaVersion): Path =
        JuliaInstallations.getInstance().depotPath().resolve("environments").resolve("julia-insight-ls-v${version.majorMinor}")

    fun isInstalled(version: JuliaVersion): Boolean = lsEnvDir(version).resolve(".julia-insight-ok").exists()

    fun findJetls(): String? {
        val configured = JuliaSettings.getInstance().state.jetlsExecutable.trim()
        if (configured.isNotEmpty()) return configured
        val bin = JuliaInstallations.getInstance().depotPath().resolve("bin")
        val names = if (SystemInfo.isWindows) listOf("jetls.bat", "jetls.cmd", "jetls.exe", "jetls") else listOf("jetls")
        for (n in names) bin.resolve(n).takeIf { Files.isRegularFile(it) }?.let { return it.toString() }
        return com.intellij.execution.configurations.PathEnvironmentVariableUtil.findInPath(if (SystemInfo.isWindows) "jetls.bat" else "jetls")?.path
    }

    /**
     * Called from [JuliaLspIntegrationProvider.fileOpened] (background thread, read lock): must not block.
     * Returns how to launch the server, or schedules whatever is missing (Julia detection, installation) and returns null.
     */
    fun launchSpecOrSchedule(project: Project): LaunchSpec? {
        val s = JuliaSettings.getInstance().state
        return when (s.languageServer) {
            LanguageServerKind.DISABLED -> null
            LanguageServerKind.CUSTOM -> s.customServerCommand.takeIf { it.isNotBlank() }?.let { LaunchSpec.Custom(it) }
            LanguageServerKind.JETLS -> findJetls()?.let { LaunchSpec.Jetls(it) } ?: run {
                if (!missingJetlsReported) {
                    missingJetlsReported = true
                    notifyJetlsMissing(project)
                }
                null
            }
            LanguageServerKind.LANGUAGESERVER_JL -> {
                val inst = JuliaInstallations.getInstance().installationIfCached()
                if (inst == null) {
                    scheduleDetection(project)
                    return null
                }
                if (isInstalled(inst.version)) LaunchSpec.LanguageServerJl(inst, lsEnvDir(inst.version))
                else {
                    offerInstall(project, inst)
                    null
                }
            }
        }
    }

    private fun scheduleDetection(project: Project) {
        if (!detectionScheduled.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val inst = JuliaInstallations.getInstance().installation()
                if (inst == null) {
                    if (!missingJuliaReported) {
                        missingJuliaReported = true
                        notify(project, "Julia not found",
                            "Install Julia (e.g. with juliaup) or set the path to the julia executable in the settings.",
                            NotificationType.WARNING, settingsAction())
                    }
                } else restartAll()
            } finally {
                detectionScheduled.set(false)
            }
        }
    }

    private fun offerInstall(project: Project, inst: JuliaInstallation) {
        if (installOffered || busy.get()) return
        installOffered = true
        ApplicationManager.getApplication().invokeLater {
            val n = group().createNotification(
                "Julia language server",
                "Code completion, go to definition, hover docs and diagnostics need LanguageServer.jl. " +
                    "It is installed once into <code>${lsEnvDir(inst.version)}</code> and takes a few minutes.",
                NotificationType.INFORMATION,
            )
            n.addAction(NotificationAction.createSimpleExpiring("Install LanguageServer.jl") { install(project, update = false) })
            n.addAction(settingsAction())
            n.notify(project)
        }
    }

    /** Installs (or updates) LanguageServer.jl in a background task, then (re)starts the servers. */
    fun install(project: Project?, update: Boolean) {
        if (!busy.compareAndSet(false, true)) return
        val title = if (update) "Updating Julia language server" else "Installing Julia language server (LanguageServer.jl)"
        object : Task.Backgroundable(project, title, true) {
            private val log = StringBuilder()
            private var ok = false

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Looking for Julia…"
                val inst = JuliaInstallations.getInstance().installation()
                if (inst == null) {
                    log.append("Julia executable not found. Configure it in Settings | Languages & Frameworks | Julia.")
                    return
                }
                val envDir = lsEnvDir(inst.version)
                indicator.text = if (update) "Updating LanguageServer.jl (Julia ${inst.version})…" else "Installing LanguageServer.jl (Julia ${inst.version})…"
                ok = runJulia(inst, listOf(JuliaScripts.path("ls_install.jl").toString(), envDir.toString(), if (update) "update" else "install"), indicator, log)
                    && isInstalled(inst.version)
            }

            override fun onFinished() {
                busy.set(false)
                if (ok) {
                    notify(project, "Julia language server ready", "LanguageServer.jl is installed. The first start may still take a moment.", NotificationType.INFORMATION)
                    restartAll()
                } else {
                    installOffered = false
                    val tail = log.lines().filter { it.isNotBlank() }.takeLast(15).joinToString("<br>") { com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(it) }
                    val n = group().createNotification("Installing the Julia language server failed", tail, NotificationType.ERROR)
                    n.addAction(NotificationAction.createSimpleExpiring("Retry") { install(project, update) })
                    n.addAction(settingsAction())
                    n.notify(project)
                }
            }
        }.queue()
    }

    fun installJetls(project: Project?) {
        if (!busy.compareAndSet(false, true)) return
        object : Task.Backgroundable(project, "Installing JETLS.jl", true) {
            private val log = StringBuilder()
            private var ok = false
            override fun run(indicator: ProgressIndicator) {
                val inst = JuliaInstallations.getInstance().installation()
                if (inst == null) {
                    log.append("Julia executable not found.")
                    return
                }
                if (inst.version < JuliaVersion(1, 12, 2, "1.12.2")) {
                    log.append("JETLS needs Julia 1.12.2 or newer (found ${inst.version}).")
                    return
                }
                indicator.text = "Installing JETLS.jl with Pkg.Apps (this precompiles for several minutes)…"
                ok = runJulia(inst, listOf("-e", "using Pkg; Pkg.Apps.add(; url=\"https://github.com/aviatesk/JETLS.jl\", rev=\"release\")"), indicator, log)
                    && findJetls() != null
            }

            override fun onFinished() {
                busy.set(false)
                missingJetlsReported = false
                if (ok) {
                    notify(project, "JETLS installed", "The Julia language server now uses JETLS.", NotificationType.INFORMATION)
                    restartAll()
                } else {
                    val tail = log.lines().filter { it.isNotBlank() }.takeLast(15).joinToString("<br>") { com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(it) }
                    notify(project, "Installing JETLS failed", tail, NotificationType.ERROR, settingsAction())
                }
            }
        }.queue()
    }

    private fun runJulia(inst: JuliaInstallation, args: List<String>, indicator: ProgressIndicator, log: StringBuilder): Boolean {
        val cmd = GeneralCommandLine(listOf(inst.executable, "--startup-file=no", "--history-file=no") + args).withCharset(Charsets.UTF_8)
        cmd.environment["JULIA_PKG_PRECOMPILE_AUTO"] = "1"
        return try {
            val handler = OSProcessHandler(cmd)
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val text = event.text
                    synchronized(log) { log.append(text) }
                    text.lines().lastOrNull { it.isNotBlank() }?.let { indicator.text2 = it.trim().take(120) }
                }
            })
            handler.startNotify()
            while (!handler.waitFor(500)) {
                if (indicator.isCanceled) {
                    handler.destroyProcess()
                    log.append("\nCancelled.")
                    return false
                }
            }
            val code = handler.exitCode ?: -1
            LOG.info("julia ${args.firstOrNull()} finished with exit code $code")
            code == 0
        } catch (e: Exception) {
            log.append(e.message)
            false
        }
    }

    fun restartAll() {
        for (p in ProjectManager.getInstance().openProjects) {
            if (!p.isDisposed) LspClientManager.getInstance(p).stopAndRestartClientsIfNeeded(JuliaLspIntegrationProvider::class.java)
        }
    }

    private fun notifyJetlsMissing(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            val n = group().createNotification("JETLS not found",
                "The language server is set to JETLS but the <code>jetls</code> executable was not found.", NotificationType.WARNING)
            n.addAction(NotificationAction.createSimpleExpiring("Install JETLS") { installJetls(project) })
            n.addAction(settingsAction())
            n.notify(project)
        }
    }

    /** The Julia page is a project-level settings page, so it must be opened for a project (null → "Cannot find configurable"). */
    private fun settingsAction(): NotificationAction = NotificationAction.create("Settings…") { e, _ -> openSettings(e.project) }

    fun openSettings(project: Project?) {
        val p = project?.takeIf { !it.isDisposed } ?: ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
            ?: ProjectManager.getInstance().defaultProject
        ShowSettingsUtil.getInstance().showSettingsDialog(p, JuliaConfigurable::class.java)
    }

    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup("Julia Insight")

    fun notify(project: Project?, title: String, content: String, type: NotificationType, vararg actions: NotificationAction) {
        ApplicationManager.getApplication().invokeLater {
            val n: Notification = group().createNotification(title, content, type)
            actions.forEach { n.addAction(it) }
            n.notify(project)
        }
    }

    companion object {
        @JvmStatic fun getInstance(): JuliaLanguageServerService = service()
    }
}

class RestartJuliaLanguageServerAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) = JuliaLanguageServerService.getInstance().restartAll()
}

class UpdateJuliaLanguageServerAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) = JuliaLanguageServerService.getInstance().install(e.project, update = true)
}

class InstallJuliaLanguageServerAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val s = JuliaSettings.getInstance().state
        if (s.languageServer == LanguageServerKind.JETLS) JuliaLanguageServerService.getInstance().installJetls(e.project)
        else JuliaLanguageServerService.getInstance().install(e.project, update = false)
    }
}
