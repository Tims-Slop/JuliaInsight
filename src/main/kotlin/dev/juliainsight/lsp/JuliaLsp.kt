package dev.juliainsight.lsp

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import com.intellij.platform.lsp.api.customization.LspCompletionCustomizer
import com.intellij.platform.lsp.api.customization.LspCompletionSupport
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspDocumentSymbolCustomizer
import com.intellij.platform.lsp.api.customization.LspDocumentSymbolDisabled
import com.intellij.platform.lsp.api.customization.LspFoldingRangeCustomizer
import com.intellij.platform.lsp.api.customization.LspFoldingRangeDisabled
import com.intellij.platform.lsp.api.customization.LspInlayHintCustomizer
import com.intellij.platform.lsp.api.customization.LspInlayHintDisabled
import com.intellij.platform.lsp.api.customization.LspInlayHintSupport
import com.intellij.platform.lsp.api.customization.LspSemanticTokensCustomizer
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem
import com.intellij.psi.PsiFile
import dev.juliainsight.JuliaIcons
import dev.juliainsight.highlight.JuliaColors
import dev.juliainsight.isJuliaFile
import dev.juliainsight.settings.JuliaConfigurable
import dev.juliainsight.settings.JuliaSettings
import dev.juliainsight.unicode.UnicodeInputEngine
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.SemanticTokenModifiers
import org.eclipse.lsp4j.SemanticTokenTypes

/** Starts the Julia language server when a `.jl` file is opened. */
class JuliaLspIntegrationProvider : LspIntegrationProvider {
    override fun fileOpened(project: Project, file: VirtualFile, clientStarter: LspIntegrationProvider.LspClientStarter) {
        if (!file.isJuliaFile()) return
        val launch = JuliaLanguageServerService.getInstance().launchSpecOrSchedule(project) ?: return
        clientStarter.ensureClientStarted(JuliaLspClientDescriptor(project, launch))
    }

    override fun createWidgetItem(lspClient: LspClient, currentFile: VirtualFile?): LspClientWidgetItem =
        LspClientWidgetItem(lspClient, currentFile, JuliaIcons.FILE, JuliaConfigurable::class.java)
}

class JuliaLspClientDescriptor(project: Project, private val launch: LaunchSpec) :
    ProjectWideLspClientDescriptor(project, launch.presentableName) {

    override fun isSupportedFile(file: VirtualFile): Boolean = file.isJuliaFile()
    override fun createCommandLine(): GeneralCommandLine = launch.commandLine(project)
    override fun getLanguageId(file: VirtualFile): String = "julia"

    override fun getWorkspaceConfiguration(item: ConfigurationItem): Any? {
        val s = JuliaSettings.getInstance().state
        return when (item.section) {
            "julia.completionmode" -> s.completionMode
            "julia.inlayHints.static.enabled" -> s.inlayHints
            "julia.inlayHints.static.variableTypes.enabled" -> s.inlayHintsVariableTypes
            "julia.inlayHints.static.parameterNames.enabled" -> s.inlayHintsParameterNames
            "julia.symbolCacheDownload" -> s.symbolCacheDownload
            "julia.enableDynamicIndexing" -> true
            "julia.enableWorkspaceEnvironmentResolution" -> true
            else -> null
        }
    }

    override val lspCustomization: LspCustomization = JuliaLspCustomization
}

object JuliaLspCustomization : LspCustomization() {
    /** LaTeX names (`\alpha`) are completed by the plugin itself, which shows the glyphs. */
    override val completionCustomizer: LspCompletionCustomizer = object : LspCompletionSupport() {
        override fun shouldRunCodeCompletion(parameters: CompletionParameters): Boolean =
            UnicodeInputEngine.latexStart(parameters.editor.document.charsSequence, parameters.offset) < 0

        // LanguageServer.jl uses `^` to trigger LaTeX completion (`\^2`); the plugin replaces those names itself
        override fun isTriggerCharacterRespected(charTyped: Char): Boolean = charTyped != '^'
    }

    /** JETLS provides semantic tokens; map them onto the Julia color scheme (keywords/strings stay lexer-based). */
    override val semanticTokensCustomizer: LspSemanticTokensCustomizer = object : LspSemanticTokensSupport() {
        override fun shouldAskServerForSemanticTokens(psiFile: PsiFile): Boolean = psiFile.isJuliaFile()
        override fun getTextAttributesKey(tokenType: String, modifiers: List<String>): TextAttributesKey? = when (tokenType) {
            SemanticTokenTypes.Function, SemanticTokenTypes.Method ->
                if (SemanticTokenModifiers.Declaration in modifiers || SemanticTokenModifiers.Definition in modifiers) JuliaColors.FUNCTION_DECLARATION
                else JuliaColors.FUNCTION_CALL
            SemanticTokenTypes.Type, SemanticTokenTypes.Class, SemanticTokenTypes.Struct, SemanticTokenTypes.Interface,
            SemanticTokenTypes.Enum, SemanticTokenTypes.TypeParameter -> JuliaColors.TYPE
            SemanticTokenTypes.Macro, SemanticTokenTypes.Decorator -> JuliaColors.MACRO
            SemanticTokenTypes.Namespace -> JuliaColors.MODULE_NAME
            SemanticTokenTypes.Parameter -> JuliaColors.PARAMETER
            else -> null
        }
    }

    override val inlayHintCustomizer: LspInlayHintCustomizer
        get() = if (JuliaSettings.getInstance().state.inlayHints) LspInlayHintSupport() else LspInlayHintDisabled

    /** Folding and the structure view come from the plugin's own parser (instant, works without the server). */
    override val foldingRangeCustomizer: LspFoldingRangeCustomizer = LspFoldingRangeDisabled
    override val documentSymbolCustomizer: LspDocumentSymbolCustomizer = LspDocumentSymbolDisabled
}
