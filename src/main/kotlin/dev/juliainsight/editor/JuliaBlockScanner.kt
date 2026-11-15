package dev.juliainsight.editor

import dev.juliainsight.lexer.JuliaLexer
import dev.juliainsight.lexer.JuliaTokenTypes as T

/**
 * Text-level helpers used by the typing assistants (indentation, auto `end`). They work on single lines with the
 * lexer, so they are fast and never need a committed PSI.
 */
object JuliaBlockScanner {

    data class LineInfo(
        /** keyword blocks opened minus closed on this line (generators and index `end` excluded) */
        val blockDelta: Int,
        /** open minus closed brackets */
        val bracketDelta: Int,
        /** first token is else / elseif / catch / finally */
        val startsWithMidKeyword: Boolean,
        /** first token is `end` */
        val startsWithEnd: Boolean,
        /** first token is a closing bracket */
        val startsWithCloser: Boolean,
        /** the line contains a block opener that is not closed on the same line */
        val opensBlock: Boolean,
    )

    fun analyze(line: CharSequence): LineInfo {
        val lexer = JuliaLexer()
        lexer.start(line, 0, line.length, 0)
        var blocks = 0
        var opened = 0
        var bracketDepth = 0
        var minBracketDepth = 0
        var first: com.intellij.psi.tree.IElementType? = null
        var prevKw: com.intellij.psi.tree.IElementType? = null
        var prevSignificant: com.intellij.psi.tree.IElementType? = null
        // bracket stack entries: true if '[' used for indexing
        val brackets = ArrayList<Boolean>()
        val sawExprInGroup = ArrayList<Boolean>()
        val generatorInGroup = ArrayList<Boolean>()
        while (true) {
            val t = lexer.tokenType ?: break
            if (t == T.WHITE_SPACE || t == T.NEWLINE || t == T.LINE_COMMENT || t == T.BLOCK_COMMENT) {
                lexer.advance()
                continue
            }
            if (first == null) first = t
            val inGroup = brackets.isNotEmpty()
            val inIndex = brackets.any { it }
            when {
                t in T.OPENERS -> {
                    val index = t == T.LBRACKET && prevSignificant != null && prevSignificant in T.EXPRESSION_END &&
                        lexer.tokenStart > 0 && line[lexer.tokenStart - 1].let { it != ' ' && it != '\t' }
                    brackets.add(index)
                    sawExprInGroup.add(false)
                    generatorInGroup.add(false)
                    bracketDepth++
                }
                t in T.CLOSERS -> {
                    if (brackets.isNotEmpty()) {
                        brackets.removeAt(brackets.size - 1)
                        sawExprInGroup.removeAt(sawExprInGroup.size - 1)
                        generatorInGroup.removeAt(generatorInGroup.size - 1)
                    }
                    bracketDepth--
                    if (bracketDepth < minBracketDepth) minBracketDepth = bracketDepth
                }
                t == T.COMMA || t == T.SEMICOLON -> if (inGroup) sawExprInGroup[sawExprInGroup.size - 1] = false
                inIndex && (t == T.END_KW || t == T.BEGIN_KW) -> {}
                t == T.FOR_KW && inGroup && sawExprInGroup.last() -> generatorInGroup[generatorInGroup.size - 1] = true
                t == T.IF_KW && inGroup && generatorInGroup.last() -> {}
                t == T.STRUCT_KW && prevKw == T.MUTABLE_KW -> {}
                t == T.TYPE_KW -> {}
                t in T.BLOCK_OPENERS -> {
                    blocks++
                    opened++
                }
                t == T.END_KW -> {
                    blocks--
                    if (opened > 0) opened--
                }
            }
            if (inGroup && t !in T.OPENERS && t != T.COMMA && t != T.SEMICOLON && brackets.isNotEmpty()) {
                sawExprInGroup[sawExprInGroup.size - 1] = true
            }
            if (t in T.KEYWORDS) prevKw = t
            prevSignificant = t
            lexer.advance()
        }
        return LineInfo(
            blockDelta = blocks,
            bracketDelta = bracketDepth,
            startsWithMidKeyword = first != null && first in T.MID_KEYWORDS,
            startsWithEnd = first == T.END_KW,
            startsWithCloser = first != null && first in T.CLOSERS,
            opensBlock = opened > 0,
        )
    }

    fun leadingWhitespace(line: CharSequence): String {
        var i = 0
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        return line.subSequence(0, i).toString()
    }

    fun isBlank(line: CharSequence): Boolean = line.all { it == ' ' || it == '\t' }

    /** Words that close (or continue) a block and therefore align with the line that opened it. */
    val DEDENT_WORDS = setOf("end", "else", "elseif", "catch", "finally")
}
