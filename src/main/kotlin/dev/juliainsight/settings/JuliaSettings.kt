package dev.juliainsight.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/** When LaTeX names and subscripts are turned into symbols. */
enum class ReplaceMode {
    /** `\theta` stays as typed until Space or Return converts the word before the caret (default). */
    ON_SPACE_OR_ENTER,
    /** Replace while typing: as soon as a name is complete and unambiguous, or on the next character. */
    WHILE_TYPING,
}

enum class LanguageServerKind(val presentable: String) {
    LANGUAGESERVER_JL("LanguageServer.jl (installed automatically)"),
    JETLS("JETLS.jl (needs Julia ≥ 1.12.2, installed on request)"),
    CUSTOM("Custom command"),
    DISABLED("Disabled"),
}

@Service(Service.Level.APP)
@State(name = "JuliaInsightSettings", storages = [Storage("julia-insight.xml")])
class JuliaSettings : PersistentStateComponent<JuliaSettings.State> {

    class State {
        // Julia
        var juliaExecutable: String = ""

        // language server
        var languageServer: LanguageServerKind = LanguageServerKind.LANGUAGESERVER_JL
        var customServerCommand: String = ""
        var jetlsExecutable: String = ""
        var symbolCacheDownload: Boolean = true
        var inlayHints: Boolean = true
        var inlayHintsVariableTypes: Boolean = true
        var inlayHintsParameterNames: String = "literals"
        var completionMode: String = "import"

        // math input
        var latexAutoReplace: Boolean = true
        var replaceMode: ReplaceMode = ReplaceMode.ON_SPACE_OR_ENTER
        var subscriptDigits: Boolean = true
        var subscriptLetters: Boolean = false
        var maxSubscriptBase: Int = 2
        var unicodeInComments: Boolean = true
        var unicodeInStrings: Boolean = false

        // editing
        var autoInsertEnd: Boolean = true

        // REPL & running
        var replThreads: String = "auto"
        var replExtraArgs: String = ""
        var plotsInIde: Boolean = true
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        @JvmStatic fun getInstance(): JuliaSettings = service()

        /** Fired after the settings page is applied. */
        @JvmField val TOPIC: Topic<JuliaSettingsListener> = Topic.create("Julia Insight settings", JuliaSettingsListener::class.java)
    }
}

fun interface JuliaSettingsListener {
    fun settingsChanged()
}

@Service(Service.Level.PROJECT)
@State(name = "JuliaInsightProjectSettings", storages = [Storage("julia-insight.xml")])
class JuliaProjectSettings : PersistentStateComponent<JuliaProjectSettings.State> {
    class State : BaseState() {
        /** Directory containing Project.toml to use for this project; empty = detect automatically. */
        var environmentPath by string("")
    }

    private var state = State()
    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        @JvmStatic fun getInstance(project: Project): JuliaProjectSettings = project.service()
    }
}
