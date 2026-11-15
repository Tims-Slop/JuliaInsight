package dev.juliainsight.lexer

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import dev.juliainsight.JuliaLanguage

class JuliaTokenType(debugName: String) : IElementType(debugName, JuliaLanguage) {
    override fun toString(): String = "Julia:" + super.toString()
}

@Suppress("MemberVisibilityCanBePrivate")
object JuliaTokenTypes {
    @JvmField val WHITE_SPACE: IElementType = TokenType.WHITE_SPACE
    @JvmField val NEWLINE = JuliaTokenType("NEWLINE")
    @JvmField val BAD_CHARACTER: IElementType = TokenType.BAD_CHARACTER

    @JvmField val LINE_COMMENT = JuliaTokenType("LINE_COMMENT")
    @JvmField val BLOCK_COMMENT = JuliaTokenType("BLOCK_COMMENT")

    @JvmField val IDENTIFIER = JuliaTokenType("IDENTIFIER")
    @JvmField val BUILTIN_CONSTANT = JuliaTokenType("BUILTIN_CONSTANT")
    @JvmField val BOOL = JuliaTokenType("BOOL")
    @JvmField val NUMBER = JuliaTokenType("NUMBER")
    @JvmField val CHAR_LITERAL = JuliaTokenType("CHAR_LITERAL")
    @JvmField val SYMBOL = JuliaTokenType("SYMBOL")
    @JvmField val MACRO = JuliaTokenType("MACRO")

    // strings: "..." / """...""" / prefix"..." (string macros, raw) / `cmd`
    @JvmField val STRING_MACRO_PREFIX = JuliaTokenType("STRING_MACRO_PREFIX")
    @JvmField val STRING_START = JuliaTokenType("STRING_START")
    @JvmField val STRING_CONTENT = JuliaTokenType("STRING_CONTENT")
    @JvmField val STRING_ESCAPE = JuliaTokenType("STRING_ESCAPE")
    @JvmField val STRING_INVALID_ESCAPE = JuliaTokenType("STRING_INVALID_ESCAPE")
    @JvmField val STRING_END = JuliaTokenType("STRING_END")
    @JvmField val CMD_START = JuliaTokenType("CMD_START")
    @JvmField val CMD_CONTENT = JuliaTokenType("CMD_CONTENT")
    @JvmField val CMD_END = JuliaTokenType("CMD_END")
    /** `$` that starts a `$( ... )` interpolation inside a string or command. */
    @JvmField val INTERP_DOLLAR = JuliaTokenType("INTERP_DOLLAR")
    /** `$name` interpolation inside a string or command. */
    @JvmField val INTERP_VAR = JuliaTokenType("INTERP_VAR")

    // block keywords
    @JvmField val FUNCTION_KW = JuliaTokenType("function")
    @JvmField val MACRO_KW = JuliaTokenType("macro")
    @JvmField val MODULE_KW = JuliaTokenType("module")
    @JvmField val STRUCT_KW = JuliaTokenType("struct")
    @JvmField val MUTABLE_KW = JuliaTokenType("mutable")
    @JvmField val ABSTRACT_KW = JuliaTokenType("abstract")
    @JvmField val PRIMITIVE_KW = JuliaTokenType("primitive")
    @JvmField val TYPE_KW = JuliaTokenType("type")
    @JvmField val IF_KW = JuliaTokenType("if")
    @JvmField val ELSEIF_KW = JuliaTokenType("elseif")
    @JvmField val ELSE_KW = JuliaTokenType("else")
    @JvmField val FOR_KW = JuliaTokenType("for")
    @JvmField val WHILE_KW = JuliaTokenType("while")
    @JvmField val BEGIN_KW = JuliaTokenType("begin")
    @JvmField val LET_KW = JuliaTokenType("let")
    @JvmField val QUOTE_KW = JuliaTokenType("quote")
    @JvmField val TRY_KW = JuliaTokenType("try")
    @JvmField val CATCH_KW = JuliaTokenType("catch")
    @JvmField val FINALLY_KW = JuliaTokenType("finally")
    @JvmField val DO_KW = JuliaTokenType("do")
    @JvmField val END_KW = JuliaTokenType("end")
    @JvmField val CONST_KW = JuliaTokenType("const")
    /** All other keywords: return, break, continue, using, import, export, public, global, local, where, in, isa, as, outer. */
    @JvmField val KEYWORD = JuliaTokenType("KEYWORD")

    // operators & punctuation
    @JvmField val EQ = JuliaTokenType("=")
    @JvmField val DOT = JuliaTokenType(".")
    @JvmField val COLON = JuliaTokenType(":")
    @JvmField val DOUBLE_COLON = JuliaTokenType("::")
    @JvmField val ARROW = JuliaTokenType("->")
    @JvmField val TRANSPOSE = JuliaTokenType("'")
    @JvmField val DOLLAR = JuliaTokenType("$")
    @JvmField val OPERATOR = JuliaTokenType("OPERATOR")
    @JvmField val LPAR = JuliaTokenType("(")
    @JvmField val RPAR = JuliaTokenType(")")
    @JvmField val LBRACKET = JuliaTokenType("[")
    @JvmField val RBRACKET = JuliaTokenType("]")
    @JvmField val LBRACE = JuliaTokenType("{")
    @JvmField val RBRACE = JuliaTokenType("}")
    @JvmField val COMMA = JuliaTokenType(",")
    @JvmField val SEMICOLON = JuliaTokenType(";")

    @JvmField val WHITESPACES = TokenSet.create(WHITE_SPACE, NEWLINE)
    @JvmField val COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT)
    @JvmField val STRING_CONTENTS = TokenSet.create(STRING_CONTENT, CMD_CONTENT)
    @JvmField val STRING_PARTS = TokenSet.create(
        STRING_START, STRING_CONTENT, STRING_ESCAPE, STRING_INVALID_ESCAPE, STRING_END,
        CMD_START, CMD_CONTENT, CMD_END, INTERP_VAR, INTERP_DOLLAR, STRING_MACRO_PREFIX,
    )
    @JvmField val STRING_INNER = TokenSet.create(STRING_CONTENT, STRING_ESCAPE, STRING_INVALID_ESCAPE, CMD_CONTENT)

    @JvmField val BLOCK_OPENERS = TokenSet.create(
        FUNCTION_KW, MACRO_KW, MODULE_KW, STRUCT_KW, MUTABLE_KW, ABSTRACT_KW, PRIMITIVE_KW,
        IF_KW, FOR_KW, WHILE_KW, BEGIN_KW, LET_KW, QUOTE_KW, TRY_KW, DO_KW,
    )
    @JvmField val MID_KEYWORDS = TokenSet.create(ELSEIF_KW, ELSE_KW, CATCH_KW, FINALLY_KW)
    @JvmField val DEFINITION_KEYWORDS = TokenSet.create(FUNCTION_KW, MACRO_KW, STRUCT_KW, MUTABLE_KW, ABSTRACT_KW, PRIMITIVE_KW, MODULE_KW)

    @JvmField val KEYWORDS = TokenSet.orSet(
        BLOCK_OPENERS, MID_KEYWORDS,
        TokenSet.create(TYPE_KW, END_KW, CONST_KW, KEYWORD),
    )

    @JvmField val OPENERS = TokenSet.create(LPAR, LBRACKET, LBRACE)
    @JvmField val CLOSERS = TokenSet.create(RPAR, RBRACKET, RBRACE)

    @JvmField val OPERATORS = TokenSet.create(EQ, COLON, DOUBLE_COLON, ARROW, TRANSPOSE, OPERATOR)

    /** Tokens after which `'` is a transpose operator and `[` starts an indexing expression. */
    @JvmField val EXPRESSION_END = TokenSet.create(
        IDENTIFIER, BUILTIN_CONSTANT, BOOL, NUMBER, CHAR_LITERAL, SYMBOL, RPAR, RBRACKET, RBRACE,
        STRING_END, CMD_END, TRANSPOSE, END_KW,
    )
}
