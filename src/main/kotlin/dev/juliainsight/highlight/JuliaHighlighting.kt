package dev.juliainsight.highlight

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as D
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType
import dev.juliainsight.JuliaIcons
import dev.juliainsight.lexer.JuliaLexer
import dev.juliainsight.lexer.JuliaTokenTypes as T
import javax.swing.Icon

object JuliaColors {
    @JvmField val KEYWORD = createTextAttributesKey("JULIA_KEYWORD", D.KEYWORD)
    @JvmField val IDENTIFIER = createTextAttributesKey("JULIA_IDENTIFIER", D.IDENTIFIER)
    @JvmField val NUMBER = createTextAttributesKey("JULIA_NUMBER", D.NUMBER)
    @JvmField val CONSTANT = createTextAttributesKey("JULIA_CONSTANT", D.CONSTANT)
    @JvmField val STRING = createTextAttributesKey("JULIA_STRING", D.STRING)
    @JvmField val STRING_ESCAPE = createTextAttributesKey("JULIA_STRING_ESCAPE", D.VALID_STRING_ESCAPE)
    @JvmField val STRING_INVALID_ESCAPE = createTextAttributesKey("JULIA_STRING_INVALID_ESCAPE", D.INVALID_STRING_ESCAPE)
    @JvmField val INTERPOLATION = createTextAttributesKey("JULIA_INTERPOLATION", D.TEMPLATE_LANGUAGE_COLOR)
    @JvmField val STRING_MACRO = createTextAttributesKey("JULIA_STRING_MACRO", D.METADATA)
    @JvmField val COMMAND = createTextAttributesKey("JULIA_COMMAND", D.STRING)
    @JvmField val CHAR = createTextAttributesKey("JULIA_CHAR", D.STRING)
    @JvmField val SYMBOL = createTextAttributesKey("JULIA_SYMBOL", D.CONSTANT)
    @JvmField val MACRO = createTextAttributesKey("JULIA_MACRO", D.METADATA)
    @JvmField val LINE_COMMENT = createTextAttributesKey("JULIA_LINE_COMMENT", D.LINE_COMMENT)
    @JvmField val BLOCK_COMMENT = createTextAttributesKey("JULIA_BLOCK_COMMENT", D.BLOCK_COMMENT)
    @JvmField val DOCSTRING = createTextAttributesKey("JULIA_DOCSTRING", D.DOC_COMMENT)
    @JvmField val OPERATOR = createTextAttributesKey("JULIA_OPERATOR", D.OPERATION_SIGN)
    @JvmField val DOT = createTextAttributesKey("JULIA_DOT", D.DOT)
    @JvmField val COMMA = createTextAttributesKey("JULIA_COMMA", D.COMMA)
    @JvmField val SEMICOLON = createTextAttributesKey("JULIA_SEMICOLON", D.SEMICOLON)
    @JvmField val PARENTHESES = createTextAttributesKey("JULIA_PARENTHESES", D.PARENTHESES)
    @JvmField val BRACKETS = createTextAttributesKey("JULIA_BRACKETS", D.BRACKETS)
    @JvmField val BRACES = createTextAttributesKey("JULIA_BRACES", D.BRACES)
    @JvmField val FUNCTION_DECLARATION = createTextAttributesKey("JULIA_FUNCTION_DECLARATION", D.FUNCTION_DECLARATION)
    @JvmField val FUNCTION_CALL = createTextAttributesKey("JULIA_FUNCTION_CALL", D.FUNCTION_CALL)
    @JvmField val TYPE = createTextAttributesKey("JULIA_TYPE", D.CLASS_NAME)
    @JvmField val MODULE_NAME = createTextAttributesKey("JULIA_MODULE_NAME", D.CLASS_NAME)
    @JvmField val PARAMETER = createTextAttributesKey("JULIA_PARAMETER", D.PARAMETER)
    @JvmField val BAD_CHARACTER = createTextAttributesKey("JULIA_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)
}

class JuliaSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = JuliaLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> =
        pack(MAP[tokenType])

    companion object {
        private val MAP: Map<IElementType, TextAttributesKey> = HashMap<IElementType, TextAttributesKey>().apply {
            for (t in T.KEYWORDS.types) put(t, JuliaColors.KEYWORD)
            put(T.IDENTIFIER, JuliaColors.IDENTIFIER)
            put(T.NUMBER, JuliaColors.NUMBER)
            put(T.BOOL, JuliaColors.CONSTANT)
            put(T.BUILTIN_CONSTANT, JuliaColors.CONSTANT)
            put(T.STRING_START, JuliaColors.STRING)
            put(T.STRING_CONTENT, JuliaColors.STRING)
            put(T.STRING_END, JuliaColors.STRING)
            put(T.STRING_ESCAPE, JuliaColors.STRING_ESCAPE)
            put(T.STRING_INVALID_ESCAPE, JuliaColors.STRING_INVALID_ESCAPE)
            put(T.INTERP_DOLLAR, JuliaColors.INTERPOLATION)
            put(T.INTERP_VAR, JuliaColors.INTERPOLATION)
            put(T.DOLLAR, JuliaColors.INTERPOLATION)
            put(T.STRING_MACRO_PREFIX, JuliaColors.STRING_MACRO)
            put(T.CMD_START, JuliaColors.COMMAND)
            put(T.CMD_CONTENT, JuliaColors.COMMAND)
            put(T.CMD_END, JuliaColors.COMMAND)
            put(T.CHAR_LITERAL, JuliaColors.CHAR)
            put(T.SYMBOL, JuliaColors.SYMBOL)
            put(T.MACRO, JuliaColors.MACRO)
            put(T.LINE_COMMENT, JuliaColors.LINE_COMMENT)
            put(T.BLOCK_COMMENT, JuliaColors.BLOCK_COMMENT)
            for (t in T.OPERATORS.types) put(t, JuliaColors.OPERATOR)
            put(T.DOT, JuliaColors.DOT)
            put(T.COMMA, JuliaColors.COMMA)
            put(T.SEMICOLON, JuliaColors.SEMICOLON)
            put(T.LPAR, JuliaColors.PARENTHESES)
            put(T.RPAR, JuliaColors.PARENTHESES)
            put(T.LBRACKET, JuliaColors.BRACKETS)
            put(T.RBRACKET, JuliaColors.BRACKETS)
            put(T.LBRACE, JuliaColors.BRACES)
            put(T.RBRACE, JuliaColors.BRACES)
            put(T.BAD_CHARACTER, JuliaColors.BAD_CHARACTER)
        }
    }
}

class JuliaSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = JuliaSyntaxHighlighter()
}

class JuliaColorSettingsPage : ColorSettingsPage {
    override fun getIcon(): Icon = JuliaIcons.FILE
    override fun getHighlighter(): SyntaxHighlighter = JuliaSyntaxHighlighter()
    override fun getDisplayName(): String = "Julia"
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = mapOf(
        "fdecl" to JuliaColors.FUNCTION_DECLARATION,
        "fcall" to JuliaColors.FUNCTION_CALL,
        "type" to JuliaColors.TYPE,
        "mod" to JuliaColors.MODULE_NAME,
        "param" to JuliaColors.PARAMETER,
        "doc" to JuliaColors.DOCSTRING,
    )

    override fun getDemoText(): String = """
        module <mod>Kinematics</mod>

        using LinearAlgebra: norm, ⋅
        export <type>Projectile</type>, trajectory

        <doc>${"\"\"\""}
            Projectile{T}

        A point mass under gravity.
        ${"\"\"\""}</doc>
        struct <type>Projectile</type>{<type>T</type><:<type>Real</type>}
            v₀::<type>T</type>      # initial speed
            θ::<type>T</type>       # launch angle
        end

        const g = 9.81 # m/s²
        #= block comments #= nest =# =#

        <fdecl>range</fdecl>(<param>p</param>::<type>Projectile</type>) = p.v₀^2 * <fcall>sin</fcall>(2p.θ) / g

        function <fdecl>trajectory</fdecl>(<param>p</param>::<type>Projectile</type>{<type>T</type>}, <param>n</param>=100) where {<type>T</type>}
            t = <fcall>range</fcall>(0, 2p.v₀ * <fcall>sin</fcall>(p.θ) / g; length = n)
            x = @. p.v₀ * <fcall>cos</fcall>(p.θ) * t
            y = @. p.v₀ * <fcall>sin</fcall>(p.θ) * t - g * t^2 / 2
            @info "flight time ${'$'}(t[end]) s, apex ${'$'}(maximum(y)) m" n
            ok = all(y .≥ 0) && x' ≠ nothing && 'a' != '\n'
            return [x y], :done, r"^\d+[a-z]*${'$'}"i, `ls -l ${'$'}file`, 0x1F, 1.5e-3, 2im, "bad \q escape"
        end

        end # module
    """.trimIndent()

    companion object {
        private val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Keyword", JuliaColors.KEYWORD),
            AttributesDescriptor("Identifier", JuliaColors.IDENTIFIER),
            AttributesDescriptor("Number", JuliaColors.NUMBER),
            AttributesDescriptor("Built-in constant (true, nothing, π, …)", JuliaColors.CONSTANT),
            AttributesDescriptor("Symbol", JuliaColors.SYMBOL),
            AttributesDescriptor("Macro call", JuliaColors.MACRO),
            AttributesDescriptor("Strings//String", JuliaColors.STRING),
            AttributesDescriptor("Strings//Escape sequence", JuliaColors.STRING_ESCAPE),
            AttributesDescriptor("Strings//Invalid escape sequence", JuliaColors.STRING_INVALID_ESCAPE),
            AttributesDescriptor("Strings//Interpolation", JuliaColors.INTERPOLATION),
            AttributesDescriptor("Strings//String macro prefix (r\"\", raw\"\", L\"\")", JuliaColors.STRING_MACRO),
            AttributesDescriptor("Strings//Command literal", JuliaColors.COMMAND),
            AttributesDescriptor("Strings//Character", JuliaColors.CHAR),
            AttributesDescriptor("Comments//Line comment", JuliaColors.LINE_COMMENT),
            AttributesDescriptor("Comments//Block comment", JuliaColors.BLOCK_COMMENT),
            AttributesDescriptor("Comments//Docstring", JuliaColors.DOCSTRING),
            AttributesDescriptor("Operators and punctuation//Operator", JuliaColors.OPERATOR),
            AttributesDescriptor("Operators and punctuation//Dot", JuliaColors.DOT),
            AttributesDescriptor("Operators and punctuation//Comma", JuliaColors.COMMA),
            AttributesDescriptor("Operators and punctuation//Semicolon", JuliaColors.SEMICOLON),
            AttributesDescriptor("Operators and punctuation//Parentheses", JuliaColors.PARENTHESES),
            AttributesDescriptor("Operators and punctuation//Brackets", JuliaColors.BRACKETS),
            AttributesDescriptor("Operators and punctuation//Braces", JuliaColors.BRACES),
            AttributesDescriptor("Semantic//Function declaration", JuliaColors.FUNCTION_DECLARATION),
            AttributesDescriptor("Semantic//Function call", JuliaColors.FUNCTION_CALL),
            AttributesDescriptor("Semantic//Type", JuliaColors.TYPE),
            AttributesDescriptor("Semantic//Module", JuliaColors.MODULE_NAME),
            AttributesDescriptor("Semantic//Parameter", JuliaColors.PARAMETER),
            AttributesDescriptor("Bad character", JuliaColors.BAD_CHARACTER),
        )
    }
}
