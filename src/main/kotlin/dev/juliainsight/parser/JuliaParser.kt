package dev.juliainsight.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import dev.juliainsight.JuliaLanguage
import dev.juliainsight.lexer.JuliaTokenTypes as T

class JuliaElementType(debugName: String) : IElementType(debugName, JuliaLanguage) {
    override fun toString(): String = "Julia:" + super.toString()
}

object JuliaElementTypes {
    @JvmField val FUNCTION_DEF = JuliaElementType("FUNCTION_DEF")
    @JvmField val SHORT_FUNCTION_DEF = JuliaElementType("SHORT_FUNCTION_DEF")
    @JvmField val MACRO_DEF = JuliaElementType("MACRO_DEF")
    @JvmField val STRUCT_DEF = JuliaElementType("STRUCT_DEF")
    @JvmField val ABSTRACT_DEF = JuliaElementType("ABSTRACT_DEF")
    @JvmField val PRIMITIVE_DEF = JuliaElementType("PRIMITIVE_DEF")
    @JvmField val MODULE_DEF = JuliaElementType("MODULE_DEF")
    @JvmField val CONST_DEF = JuliaElementType("CONST_DEF")
    @JvmField val DEF_NAME = JuliaElementType("DEF_NAME")

    @JvmField val IF_BLOCK = JuliaElementType("IF_BLOCK")
    @JvmField val FOR_BLOCK = JuliaElementType("FOR_BLOCK")
    @JvmField val WHILE_BLOCK = JuliaElementType("WHILE_BLOCK")
    @JvmField val LET_BLOCK = JuliaElementType("LET_BLOCK")
    @JvmField val BEGIN_BLOCK = JuliaElementType("BEGIN_BLOCK")
    @JvmField val QUOTE_BLOCK = JuliaElementType("QUOTE_BLOCK")
    @JvmField val TRY_BLOCK = JuliaElementType("TRY_BLOCK")
    @JvmField val DO_BLOCK = JuliaElementType("DO_BLOCK")

    @JvmField val PAREN_GROUP = JuliaElementType("PAREN_GROUP")
    @JvmField val BRACKET_GROUP = JuliaElementType("BRACKET_GROUP")
    @JvmField val INDEX_GROUP = JuliaElementType("INDEX_GROUP")
    @JvmField val BRACE_GROUP = JuliaElementType("BRACE_GROUP")
    @JvmField val INTERPOLATION = JuliaElementType("INTERPOLATION")
    @JvmField val STRING_LITERAL = JuliaElementType("STRING_LITERAL")
    @JvmField val CMD_LITERAL = JuliaElementType("CMD_LITERAL")

    @JvmField val DEFINITIONS = TokenSet.create(
        FUNCTION_DEF, SHORT_FUNCTION_DEF, MACRO_DEF, STRUCT_DEF, ABSTRACT_DEF, PRIMITIVE_DEF, MODULE_DEF, CONST_DEF,
    )
    @JvmField val KEYWORD_BLOCKS = TokenSet.create(
        FUNCTION_DEF, MACRO_DEF, STRUCT_DEF, ABSTRACT_DEF, PRIMITIVE_DEF, MODULE_DEF,
        IF_BLOCK, FOR_BLOCK, WHILE_BLOCK, LET_BLOCK, BEGIN_BLOCK, QUOTE_BLOCK, TRY_BLOCK, DO_BLOCK,
    )
    @JvmField val GROUPS = TokenSet.create(PAREN_GROUP, BRACKET_GROUP, INDEX_GROUP, BRACE_GROUP, INTERPOLATION)
}

/**
 * A deliberately forgiving structural parser. It recognises keyword blocks (`function … end`, `if … end`, …),
 * bracket groups, strings and definitions, which is all the IDE needs for folding, structure view, breadcrumbs,
 * smart editing and sending code to the REPL. It never reports syntax errors: real diagnostics come from the
 * language server, so a half-typed line never turns red because of this parser.
 */
class JuliaParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val rootMarker = builder.mark()
        JuliaParsing(builder).parseFile()
        rootMarker.done(root)
        return builder.treeBuilt
    }
}

private class GroupState {
    var hasExpr = false
    var generator = false
}

private class Ctx(val inIndex: Boolean, val group: GroupState?, val statementLevel: Boolean)

internal class JuliaParsing(private val b: PsiBuilder) {
    private val text: CharSequence = b.originalText
    private val statementCtx = Ctx(inIndex = false, group = null, statementLevel = true)

    fun parseFile() {
        while (!b.eof()) {
            val t = b.tokenType
            if (t == T.END_KW || t in T.CLOSERS || t in T.MID_KEYWORDS) {
                b.advanceLexer() // stray token, keep going
                continue
            }
            parseItem(statementCtx)
        }
    }

    private fun parseItem(ctx: Ctx) {
        val t = b.tokenType ?: return
        when {
            t == T.STRING_MACRO_PREFIX || t == T.STRING_START || t == T.CMD_START -> parseString()
            t == T.LPAR -> parseGroup(JuliaElementTypes.PAREN_GROUP, T.RPAR, ctx.inIndex)
            t == T.LBRACKET -> {
                val index = isIndexBracket()
                parseGroup(if (index) JuliaElementTypes.INDEX_GROUP else JuliaElementTypes.BRACKET_GROUP, T.RBRACKET, ctx.inIndex || index)
            }
            t == T.LBRACE -> parseGroup(JuliaElementTypes.BRACE_GROUP, T.RBRACE, ctx.inIndex)
            ctx.inIndex && (t == T.END_KW || t == T.BEGIN_KW) -> b.advanceLexer()
            t == T.FOR_KW && ctx.group != null && ctx.group.hasExpr -> {
                ctx.group.generator = true
                b.advanceLexer()
            }
            t == T.IF_KW && ctx.group != null && ctx.group.generator -> b.advanceLexer()
            t in T.BLOCK_OPENERS -> parseBlock()
            ctx.statementLevel && t == T.IDENTIFIER && atStatementStart() && looksLikeShortFunctionDef() -> parseShortFunctionDef()
            ctx.statementLevel && t == T.CONST_KW -> parseConstDef()
            else -> b.advanceLexer()
        }
    }

    // ---- blocks ---------------------------------------------------------------------------------------------------

    private fun parseBlock() {
        val kw = b.tokenType!!
        val openerIndent = indentOfLine(b.currentOffset)
        val m = b.mark()
        val type = when (kw) {
            T.FUNCTION_KW -> JuliaElementTypes.FUNCTION_DEF
            T.MACRO_KW -> JuliaElementTypes.MACRO_DEF
            T.STRUCT_KW, T.MUTABLE_KW -> JuliaElementTypes.STRUCT_DEF
            T.ABSTRACT_KW -> JuliaElementTypes.ABSTRACT_DEF
            T.PRIMITIVE_KW -> JuliaElementTypes.PRIMITIVE_DEF
            T.MODULE_KW -> JuliaElementTypes.MODULE_DEF
            T.IF_KW -> JuliaElementTypes.IF_BLOCK
            T.FOR_KW -> JuliaElementTypes.FOR_BLOCK
            T.WHILE_KW -> JuliaElementTypes.WHILE_BLOCK
            T.LET_KW -> JuliaElementTypes.LET_BLOCK
            T.BEGIN_KW -> JuliaElementTypes.BEGIN_BLOCK
            T.QUOTE_KW -> JuliaElementTypes.QUOTE_BLOCK
            T.TRY_KW -> JuliaElementTypes.TRY_BLOCK
            else -> JuliaElementTypes.DO_BLOCK
        }
        b.advanceLexer()
        if (kw == T.MUTABLE_KW && b.tokenType == T.STRUCT_KW) b.advanceLexer()
        if ((kw == T.ABSTRACT_KW || kw == T.PRIMITIVE_KW) && b.tokenType == T.TYPE_KW) b.advanceLexer()
        if (type in JuliaElementTypes.DEFINITIONS) parseDefName(type)

        val recoverOnDefinitions = type == JuliaElementTypes.FUNCTION_DEF || type == JuliaElementTypes.MACRO_DEF ||
            type == JuliaElementTypes.STRUCT_DEF
        while (!b.eof()) {
            val t = b.tokenType
            if (t == T.END_KW) {
                b.advanceLexer()
                m.done(type)
                return
            }
            if (t in T.CLOSERS) break
            if (recoverOnDefinitions && t in T.DEFINITION_KEYWORDS && newlineBefore() &&
                indentOfLine(b.currentOffset) <= openerIndent
            ) break // a sibling definition: the current one is missing its `end`
            if (t in T.MID_KEYWORDS) {
                b.advanceLexer()
                continue
            }
            parseItem(statementCtx)
        }
        m.done(type)
    }

    private fun parseDefName(type: IElementType) {
        val t = b.tokenType
        val nameStart = t == T.IDENTIFIER || t == T.BUILTIN_CONSTANT ||
            (type == JuliaElementTypes.FUNCTION_DEF && t == T.OPERATOR)
        if (!nameStart) return
        val nm = b.mark()
        b.advanceLexer()
        if (t != T.OPERATOR) {
            while (b.tokenType == T.DOT) {
                b.advanceLexer()
                if (b.tokenType == T.COLON) b.advanceLexer()
                val n = b.tokenType
                if (n == T.IDENTIFIER || n == T.SYMBOL || n == T.OPERATOR || n == T.BUILTIN_CONSTANT) b.advanceLexer() else break
            }
        }
        nm.done(JuliaElementTypes.DEF_NAME)
    }

    private fun parseShortFunctionDef() {
        val m = b.mark()
        parseDefName(JuliaElementTypes.FUNCTION_DEF)
        parseRestOfStatement()
        m.done(JuliaElementTypes.SHORT_FUNCTION_DEF)
    }

    private fun parseConstDef() {
        val m = b.mark()
        b.advanceLexer() // const
        if (b.tokenType == T.IDENTIFIER) {
            val nm = b.mark()
            b.advanceLexer()
            nm.done(JuliaElementTypes.DEF_NAME)
        }
        parseRestOfStatement()
        m.done(JuliaElementTypes.CONST_DEF)
    }

    private fun parseRestOfStatement() {
        val ctx = Ctx(inIndex = false, group = null, statementLevel = false)
        while (!b.eof()) {
            val t = b.tokenType
            if (newlineBefore() || t == T.END_KW || t in T.CLOSERS || t in T.MID_KEYWORDS || t == T.SEMICOLON) break
            parseItem(ctx)
        }
    }

    // ---- groups & strings -----------------------------------------------------------------------------------------

    private fun parseGroup(type: IElementType, closer: IElementType, inIndex: Boolean) {
        val m = b.mark()
        b.advanceLexer()
        val gs = GroupState()
        val ctx = Ctx(inIndex, gs, statementLevel = false)
        while (!b.eof()) {
            val t = b.tokenType
            if (t == closer) {
                b.advanceLexer()
                m.done(type)
                return
            }
            if (t in T.CLOSERS) break
            if (!inIndex && (t == T.END_KW || t in T.MID_KEYWORDS)) break // unclosed group inside a block
            if (t == T.COMMA || t == T.SEMICOLON) {
                gs.hasExpr = false
                b.advanceLexer()
                continue
            }
            parseItem(ctx)
            gs.hasExpr = true
        }
        m.done(type)
    }

    private fun parseString() {
        val m = b.mark()
        if (b.tokenType == T.STRING_MACRO_PREFIX) b.advanceLexer()
        val isCmd = b.tokenType == T.CMD_START
        if (b.tokenType == T.STRING_START || b.tokenType == T.CMD_START) b.advanceLexer()
        loop@ while (!b.eof()) {
            when (b.tokenType) {
                T.STRING_END, T.CMD_END -> {
                    b.advanceLexer()
                    break@loop
                }
                T.STRING_CONTENT, T.CMD_CONTENT, T.STRING_ESCAPE, T.STRING_INVALID_ESCAPE, T.INTERP_VAR -> b.advanceLexer()
                T.INTERP_DOLLAR -> {
                    b.advanceLexer()
                    if (b.tokenType == T.LPAR) parseGroup(JuliaElementTypes.INTERPOLATION, T.RPAR, false)
                }
                else -> break@loop
            }
        }
        m.done(if (isCmd) JuliaElementTypes.CMD_LITERAL else JuliaElementTypes.STRING_LITERAL)
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /** `a[` with no whitespace after an expression is indexing; otherwise `[` starts an array literal. */
    private fun isIndexBracket(): Boolean {
        val prev = b.rawLookup(-1) ?: return false
        return prev in T.EXPRESSION_END
    }

    private fun atStatementStart(): Boolean {
        var i = -1
        while (true) {
            val t = b.rawLookup(i) ?: return true
            when (t) {
                T.NEWLINE, T.SEMICOLON -> return true
                T.WHITE_SPACE, T.LINE_COMMENT, T.BLOCK_COMMENT -> i--
                else -> return false
            }
        }
    }

    private fun newlineBefore(): Boolean {
        var i = -1
        while (true) {
            val t = b.rawLookup(i) ?: return false
            when (t) {
                T.NEWLINE -> return true
                T.WHITE_SPACE, T.LINE_COMMENT, T.BLOCK_COMMENT -> i--
                else -> return false
            }
        }
    }

    private fun indentOfLine(offset: Int): Int {
        var p = offset
        while (p > 0 && text[p - 1] != '\n') p--
        var n = 0
        while (p + n < text.length && (text[p + n] == ' ' || text[p + n] == '\t')) n++
        return n
    }

    /** Index just after the group opened at raw position [i], or -1. */
    private fun skipBalanced(start: Int): Int {
        var i = start
        var depth = 0
        while (i - start < 4000) {
            val t = b.rawLookup(i) ?: return -1
            if (t in T.OPENERS) depth++
            else if (t in T.CLOSERS) {
                depth--
                if (depth == 0) return i + 1
            }
            i++
        }
        return -1
    }

    /** `name(args)[::T][ where …] = …` on one statement line. */
    private fun looksLikeShortFunctionDef(): Boolean {
        var i = 0
        if (b.rawLookup(i) != T.IDENTIFIER) return false
        i++
        while (b.rawLookup(i) == T.DOT) {
            i++
            when (b.rawLookup(i)) {
                T.IDENTIFIER, T.SYMBOL -> i++
                T.COLON -> if (b.rawLookup(i + 1) == T.OPERATOR) i += 2 else return false
                else -> return false
            }
        }
        if (b.rawLookup(i) == T.LBRACE) {
            i = skipBalanced(i)
            if (i < 0) return false
        }
        if (b.rawLookup(i) != T.LPAR) return false
        i = skipBalanced(i)
        if (i < 0) return false
        var depth = 0
        var sawAnnotation = false // `::T` or `where …` after the argument list
        while (true) {
            val t = b.rawLookup(i) ?: return false
            when {
                t in T.OPENERS -> if (sawAnnotation) depth++ else return false
                t in T.CLOSERS -> {
                    depth--
                    if (depth < 0) return false
                }
                depth > 0 -> {}
                t == T.EQ -> return true
                t == T.WHITE_SPACE -> {}
                t == T.DOUBLE_COLON || t == T.KEYWORD -> sawAnnotation = true
                t == T.IDENTIFIER || t == T.DOT || t == T.OPERATOR || t == T.BUILTIN_CONSTANT -> if (!sawAnnotation) return false
                else -> return false
            }
            i++
            if (i > 4000) return false
        }
    }
}
