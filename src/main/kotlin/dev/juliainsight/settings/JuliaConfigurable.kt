package dev.juliainsight.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import dev.juliainsight.lsp.JuliaLanguageServerService
import dev.juliainsight.sdk.JuliaInstallations

class JuliaConfigurable(private val project: Project) : BoundConfigurable("Julia") {

    private fun lsSignature(s: JuliaSettings.State) = listOf(
        s.juliaExecutable, s.languageServer, s.customServerCommand, s.jetlsExecutable, s.symbolCacheDownload,
        s.inlayHints, s.completionMode, JuliaProjectSettings.getInstance(project).state.environmentPath,
    )

    override fun createPanel(): DialogPanel {
        val s = JuliaSettings.getInstance().state
        val ps = JuliaProjectSettings.getInstance(project).state
        val detected = JuliaInstallations.getInstance().executableNoProcess()
        return panel {
            group("Julia") {
                row("Julia executable:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Julia Executable"), project)
                        .bindText(s::juliaExecutable)
                        .align(AlignX.FILL)
                        .comment("Leave empty to detect automatically (juliaup, PATH, /Applications/Julia-*.app, Homebrew, %LOCALAPPDATA%\\Programs).<br>" +
                            "Currently: ${detected ?: "not found"}")
                }
                row("Project environment:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Julia Environment"), project)
                        .bindText({ ps.environmentPath.orEmpty() }, { ps.environmentPath = it })
                        .align(AlignX.FILL)
                        .comment("Folder containing Project.toml, used by the REPL, run configurations and the language server. " +
                            "Empty: the project root (if it has a Project.toml).")
                }
            }
            group("Language Server") {
                row("Server:") {
                    comboBox(LanguageServerKind.entries, textListCellRenderer { it?.presentable })
                        .bindItem(s::languageServer.toNullableProperty())
                }
                row("Custom command:") {
                    textField().bindText(s::customServerCommand).align(AlignX.FILL)
                        .comment("Used when the server is set to Custom, e.g. <code>julia --project=/path/env -e \"using LanguageServer; runserver()\"</code>")
                }
                row("JETLS executable:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("jetls Executable"), project)
                        .bindText(s::jetlsExecutable).align(AlignX.FILL)
                        .comment("Empty: ~/.julia/bin/jetls")
                }
                row { checkBox("Inlay hints (inferred variable types, parameter names)").bindSelected(s::inlayHints) }
                row { checkBox("Download pre-built symbol caches for registered packages (much faster first indexing)").bindSelected(s::symbolCacheDownload) }
                row {
                    button("Install / Repair") { JuliaLanguageServerService.getInstance().install(project, update = false) }
                    button("Update") { JuliaLanguageServerService.getInstance().install(project, update = true) }
                    button("Install JETLS") { JuliaLanguageServerService.getInstance().installJetls(project) }
                    button("Restart") { JuliaLanguageServerService.getInstance().restartAll() }
                }
            }
            group("Math Input") {
                row { checkBox("Replace LaTeX names with symbols (\\theta → θ, \\in → ∈, x\\hat → x̂)").bindSelected(s::latexAutoReplace) }
                buttonsGroup("Replace LaTeX names and subscripts:") {
                    row {
                        radioButton("When pressing Space or Return (the word before the cursor is converted)", ReplaceMode.ON_SPACE_OR_ENTER)
                    }
                    row {
                        radioButton("While typing (as soon as a name is complete and unambiguous)", ReplaceMode.WHILE_TYPING)
                    }
                }.bind(s::replaceMode)
                row { checkBox("Subscript digits (v_0 → v₀, \\omega_0 → ω₀, x_12 → x₁₂)").bindSelected(s::subscriptDigits) }
                row { checkBox("Subscript single letters (x_i → xᵢ)").bindSelected(s::subscriptLetters) }
                row("Subscripts only after names of at most") {
                    intTextField(0..50).bindIntText(s::maxSubscriptBase).columns(3)
                    label("characters (0 = any; keeps step_1 as it is)")
                }
                row {
                    checkBox("Also in comments").bindSelected(s::unicodeInComments)
                    checkBox("Also in strings (never in raw strings like L\"…\")").bindSelected(s::unicodeInStrings)
                }
                row {
                    comment("Backspace right after a replacement restores what you typed. Tab completes \\names like the Julia REPL, " +
                        "Ctrl+Space after a backslash lists all symbols, and the status bar shows how to type the symbol at the caret.")
                }
            }
            group("Editing") {
                row { checkBox("Insert matching 'end' after pressing Enter on a block opener").bindSelected(s::autoInsertEnd) }
            }
            group("REPL && Running") {
                row("Threads:") { textField().bindText(s::replThreads).columns(8).comment("Value for --threads, e.g. auto or 4") }
                row("Extra Julia arguments:") { textField().bindText(s::replExtraArgs).align(AlignX.FILL) }
                row { checkBox("Show plots in the IDE (Julia Plots tool window)").bindSelected(s::plotsInIde) }
            }
        }
    }

    override fun apply() {
        val s = JuliaSettings.getInstance().state
        val before = lsSignature(s)
        super.apply()
        JuliaInstallations.getInstance().resetDetection()
        ApplicationManager.getApplication().messageBus.syncPublisher(JuliaSettings.TOPIC).settingsChanged()
        if (lsSignature(s) != before) {
            ApplicationManager.getApplication().executeOnPooledThread {
                JuliaInstallations.getInstance().installation() // warm the cache so the server can start right away
                JuliaLanguageServerService.getInstance().restartAll()
            }
        }
    }
}
