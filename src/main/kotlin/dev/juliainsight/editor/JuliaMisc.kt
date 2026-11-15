package dev.juliainsight.editor

import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.application.options.IndentOptionsEditor
import com.intellij.application.options.SmartIndentOptionsEditor
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.lang.Language
import com.intellij.lexer.Lexer
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.intellij.psi.impl.cache.impl.BaseFilterLexer
import com.intellij.psi.impl.cache.impl.OccurrenceConsumer
import com.intellij.psi.impl.cache.impl.todo.LexerBasedTodoIndexer
import com.intellij.psi.impl.search.IndexPatternBuilder
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.elementType
import dev.juliainsight.JuliaLanguage
import dev.juliainsight.lexer.JuliaLexer
import dev.juliainsight.lexer.JuliaTokenTypes as T
import dev.juliainsight.psi.JuliaFile

// ---- TODO support ---------------------------------------------------------------------------------------------------

class JuliaIndexPatternBuilder : IndexPatternBuilder {
    override fun getIndexingLexer(file: PsiFile): Lexer? = if (file is JuliaFile) JuliaLexer() else null
    override fun getCommentTokenSet(file: PsiFile): TokenSet? = if (file is JuliaFile) T.COMMENTS else null
    override fun getCommentStartDelta(tokenType: IElementType?): Int = when (tokenType) {
        T.BLOCK_COMMENT -> 2
        T.LINE_COMMENT -> 1
        else -> 0
    }
    override fun getCommentEndDelta(tokenType: IElementType?): Int = if (tokenType == T.BLOCK_COMMENT) 2 else 0
}

class JuliaTodoIndexer : LexerBasedTodoIndexer() {
    override fun createLexer(consumer: OccurrenceConsumer): Lexer = object : BaseFilterLexer(JuliaLexer(), consumer) {
        override fun advance() {
            if (myDelegate.tokenType in T.COMMENTS) advanceTodoItemCountsInToken()
            myDelegate.advance()
        }
    }
}

// ---- code style -------------------------------------------------------------------------------------------------------

class JuliaCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
    override fun getLanguage(): Language = JuliaLanguage
    override fun getConfigurableDisplayName(): String = "Julia"

    override fun customizeDefaults(commonSettings: CommonCodeStyleSettings, indentOptions: CommonCodeStyleSettings.IndentOptions) {
        indentOptions.INDENT_SIZE = 4
        indentOptions.CONTINUATION_INDENT_SIZE = 4
        indentOptions.TAB_SIZE = 4
        indentOptions.USE_TAB_CHARACTER = false
        commonSettings.LINE_COMMENT_AT_FIRST_COLUMN = false
        commonSettings.LINE_COMMENT_ADD_SPACE = true
    }

    override fun getIndentOptionsEditor(): IndentOptionsEditor = SmartIndentOptionsEditor()

    override fun getCodeSample(settingsType: SettingsType): String = """
        function trajectory(v₀, θ; g = 9.81)
            for t in 0:0.1:10
                if t > 5
                    println(t)
                else
                    continue
                end
            end
        end
    """.trimIndent()

    override fun createConfigurable(baseSettings: CodeStyleSettings, modelSettings: CodeStyleSettings): CodeStyleConfigurable =
        object : CodeStyleAbstractConfigurable(baseSettings, modelSettings, "Julia") {
            override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel =
                object : TabbedLanguageCodeStylePanel(JuliaLanguage, currentSettings, settings) {
                    override fun initTabs(settings: CodeStyleSettings) {
                        addIndentOptionsTab(settings)
                    }
                }
        }
}

// ---- live templates -------------------------------------------------------------------------------------------------

class JuliaTemplateContextType : TemplateContextType("Julia") {
    override fun isInContext(templateActionContext: TemplateActionContext): Boolean {
        val file = templateActionContext.file
        if (file.language != JuliaLanguage) return false
        val offset = templateActionContext.startOffset
        if (offset == 0) return true
        val leaf = file.findElementAt(offset - 1) ?: return true
        val t = leaf.elementType
        return t !in T.COMMENTS && t !in T.STRING_INNER
    }
}
