package dev.juliainsight.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.execution.ParametersListUtil
import dev.juliainsight.JuliaIcons
import dev.juliainsight.isJuliaFile
import dev.juliainsight.repl.JuliaCommandLines
import dev.juliainsight.sdk.JuliaInstallations
import java.io.File
import java.nio.file.Paths
import javax.swing.JComponent

class JuliaRunConfigurationType : ConfigurationTypeBase(ID, "Julia", "Run a Julia script", JuliaIcons.FILE), DumbAware {
    init {
        addFactory(object : ConfigurationFactory(this) {
            override fun getId(): String = ID
            override fun createTemplateConfiguration(project: Project): RunConfiguration = JuliaRunConfiguration(project, this, "Julia")
            override fun getOptionsClass(): Class<JuliaRunConfigurationOptions> = JuliaRunConfigurationOptions::class.java
        })
    }

    companion object {
        const val ID = "JuliaInsightRunConfiguration"
        fun getInstance(): JuliaRunConfigurationType = ConfigurationTypeUtil.findConfigurationType(JuliaRunConfigurationType::class.java)
    }
}

class JuliaRunConfigurationOptions : LocatableRunConfigurationOptions() {
    var scriptPathValue by string("")
    var scriptArgsValue by string("")
    var juliaArgsValue by string("")
    var environmentValue by string("")
    var workingDirectoryValue by string("")
    var showPlots by property(true)

    var scriptPath: String
        get() = scriptPathValue.orEmpty()
        set(v) { scriptPathValue = v }
    var scriptArgs: String
        get() = scriptArgsValue.orEmpty()
        set(v) { scriptArgsValue = v }
    var juliaArgs: String
        get() = juliaArgsValue.orEmpty()
        set(v) { juliaArgsValue = v }
    var environment: String
        get() = environmentValue.orEmpty()
        set(v) { environmentValue = v }
    var workingDirectory: String
        get() = workingDirectoryValue.orEmpty()
        set(v) { workingDirectoryValue = v }
}

class JuliaRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<JuliaRunConfigurationOptions>(project, factory, name) {

    public override fun getOptions(): JuliaRunConfigurationOptions = super.getOptions() as JuliaRunConfigurationOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = JuliaRunSettingsEditor(project)

    override fun checkConfiguration() {
        val path = options.scriptPath
        if (path.isBlank()) throw RuntimeConfigurationError("No Julia script selected")
        if (!File(path).isFile) throw RuntimeConfigurationError("Script not found: $path")
    }

    override fun suggestedName(): String? = options.scriptPath.takeIf { it.isNotBlank() }?.let { File(it).name }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = object : CommandLineState(environment) {
        override fun startProcess(): ProcessHandler {
            val inst = JuliaInstallations.getInstance().installation()
                ?: throw ExecutionException("Julia was not found. Configure it in Settings | Languages & Frameworks | Julia.")
            val o = options
            val env = o.environment.ifBlank { JuliaInstallations.environmentFor(project) ?: "" }
            val cmd = JuliaCommandLines.base(project, inst, env, kind = "run", interactive = false, extraJuliaArgs = ParametersListUtil.parse(o.juliaArgs))
            cmd.addParameter(o.scriptPath)
            cmd.addParameters(ParametersListUtil.parse(o.scriptArgs))
            val wd = o.workingDirectory.takeIf { it.isNotBlank() }?.let { Paths.get(it) } ?: JuliaInstallations.workingDirectory(project)
            cmd.withWorkingDirectory(wd)
            if (!o.showPlots) cmd.environment["JULIA_INSIGHT_PLOTS"] = "0"
            val handler = KillableColoredProcessHandler(cmd)
            ProcessTerminatedListener.attach(handler)
            return handler
        }

        override fun createConsole(executor: Executor): ConsoleView? {
            val builder = TextConsoleBuilderFactory.getInstance().createBuilder(project)
            builder.addFilter(JuliaStackTraceFilter(project, options.workingDirectory.ifBlank { project.basePath }))
            return builder.console
        }
    }
}

class JuliaRunSettingsEditor(private val project: Project) : SettingsEditor<JuliaRunConfiguration>() {
    private var scriptPath = ""
    private var scriptArgs = ""
    private var juliaArgs = ""
    private var environment = ""
    private var workingDirectory = ""
    private var showPlots = true

    private val dialogPanel = panel {
        row("Script:") {
            textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileDescriptor("jl").withTitle("Julia Script"), project)
                .bindText(::scriptPath).align(AlignX.FILL)
        }
        row("Script arguments:") { textField().bindText(::scriptArgs).align(AlignX.FILL) }
        row("Julia options:") {
            textField().bindText(::juliaArgs).align(AlignX.FILL).comment("e.g. -O3 --check-bounds=no (threads come from the Julia settings)")
        }
        row("Environment:") {
            textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Julia Environment"), project)
                .bindText(::environment).align(AlignX.FILL).comment("Folder with Project.toml; empty = project environment")
        }
        row("Working directory:") {
            textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Working Directory"), project)
                .bindText(::workingDirectory).align(AlignX.FILL)
        }
        row { checkBox("Show plots in the Julia Plots tool window").bindSelected(::showPlots) }
    }

    override fun resetEditorFrom(s: JuliaRunConfiguration) {
        val o = s.options
        scriptPath = o.scriptPath
        scriptArgs = o.scriptArgs
        juliaArgs = o.juliaArgs
        environment = o.environment
        workingDirectory = o.workingDirectory
        showPlots = o.showPlots
        dialogPanel.reset()
    }

    override fun applyEditorTo(s: JuliaRunConfiguration) {
        dialogPanel.apply()
        val o = s.options
        o.scriptPath = scriptPath
        o.scriptArgs = scriptArgs
        o.juliaArgs = juliaArgs
        o.environment = environment
        o.workingDirectory = workingDirectory
        o.showPlots = showPlots
    }

    override fun createEditor(): JComponent = dialogPanel
}

/** Right-click a .jl file → Run. */
class JuliaRunConfigurationProducer : LazyRunConfigurationProducer<JuliaRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory = JuliaRunConfigurationType.getInstance().configurationFactories[0]

    override fun setupConfigurationFromContext(
        configuration: JuliaRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = context.location?.virtualFile ?: return false
        if (!file.isJuliaFile()) return false
        configuration.options.scriptPath = file.path
        configuration.name = file.name
        return true
    }

    override fun isConfigurationFromContext(configuration: JuliaRunConfiguration, context: ConfigurationContext): Boolean {
        val file = context.location?.virtualFile ?: return false
        return FileUtilPaths.same(configuration.options.scriptPath, file.path)
    }
}

internal object FileUtilPaths {
    fun same(a: String, b: String): Boolean = runCatching { Paths.get(a).normalize() == Paths.get(b).normalize() }.getOrDefault(a == b)
}

/** Makes `@ Main C:\path\file.jl:12`, `file.jl:12` and `in expression starting at …` clickable. */
class JuliaStackTraceFilter(private val project: Project, private val baseDir: String?) : Filter {
    private val atRe = Regex("""@ \S+ (.+?\.jl):(\d+)\s*$""")
    private val genericRe = Regex("""((?:[A-Za-z]:[\\/]|[~/.]|\b)[^\s"'(){}\[\]<>|*?,;]*?\.jl):(\d+)""")

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val lineStart = entireLength - line.length
        val items = ArrayList<Filter.ResultItem>()
        val at = atRe.find(line)
        if (at != null) {
            link(at.groupValues[1], at.groupValues[2], lineStart + at.groups[1]!!.range.first, lineStart + at.groups[2]!!.range.last + 1)?.let { items += it }
        } else {
            for (m in genericRe.findAll(line)) {
                link(m.groupValues[1], m.groupValues[2], lineStart + m.range.first, lineStart + m.range.last + 1)?.let { items += it }
            }
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    private fun link(path: String, lineNo: String, start: Int, end: Int): Filter.ResultItem? {
        val file = resolve(path) ?: return null
        val line = (lineNo.toIntOrNull() ?: 1) - 1
        return Filter.ResultItem(start, end, OpenFileHyperlinkInfo(project, file, maxOf(line, 0)))
    }

    private fun resolve(raw: String): VirtualFile? {
        var p = raw.trim()
        if (p.startsWith("~")) p = System.getProperty("user.home") + p.substring(1)
        val f = File(p)
        val candidates = if (f.isAbsolute) listOf(f) else listOfNotNull(baseDir?.let { File(it, p) }, project.basePath?.let { File(it, p) })
        for (c in candidates) {
            if (c.isFile) return LocalFileSystem.getInstance().findFileByIoFile(c)
        }
        return null
    }
}
