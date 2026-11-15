package dev.juliainsight.lexer

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType
import dev.juliainsight.lexer.JuliaTokenTypes as T
import java.util.concurrent.ConcurrentHashMap

/**
 * Hand-written lexer for Julia.
 *
 * The lexer is restartable: everything it needs to continue lexing (nested strings, `$( )` interpolations and
 * whether the previous token ends an expression, which decides between `'` = transpose / char literal and
 * `:` = range / symbol) is encoded in an interned integer state. State 0 means "top level code after whitespace",
 * which is where the editor highlighter may restart incremental lexing.
 */
class JuliaLexer : LexerBase() {

    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var pos = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null
    private var tokenState = 0

    private var stack: ModeStack? = null
    private var exprEnd = false

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.bufferEnd = endOffset
        this.pos = startOffset
        val st = LexerStates.get(initialState)
        stack = st.stack
        exprEnd = st.exprEnd
        advance()
    }

    override fun getState(): Int = tokenState
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = bufferEnd

    override fun advance() {
        tokenState = LexerStates.id(stack, exprEnd)
        tokenStart = pos
        if (pos >= bufferEnd) {
            tokenType = null
            tokenEnd = pos
            return
        }
        var type = lexNext()
        if (pos <= tokenStart) { // safety net: never emit empty tokens
            pos = tokenStart + 1
            type = T.BAD_CHARACTER
        }
        if (pos > bufferEnd) pos = bufferEnd
        tokenType = type
        tokenEnd = pos
    }

    // ---------------------------------------------------------------------------------------------------------------

    private fun lexNext(): IElementType = when (val top = stack?.top) {
        is StrMode -> lexStringPart(top)
        PendingRaw -> {
            pop()
            val c = buffer[pos]
            if (c == '"' || c == '`') lexStringOpen(raw = true) else lexCode()
        }
        else -> lexCode()
    }

    private fun ch(i: Int): Char = if (i < bufferEnd) buffer[i] else '\u0000'
    private fun cp(i: Int): Int = if (i < bufferEnd) Character.codePointAt(buffer, i) else 0

    private fun push(m: Mode) {
        stack = ModeStack(m, stack)
    }

    private fun pop() {
        stack = stack?.rest
    }

    private fun replaceTop(m: Mode) {
        stack = ModeStack(m, stack?.rest)
    }

    private fun lexCode(): IElementType {
        val c = buffer[pos]
        when (c) {
            '\n' -> { pos++; exprEnd = false; return T.NEWLINE }
            '\r' -> { pos++; if (ch(pos) == '\n') pos++; exprEnd = false; return T.NEWLINE }
            ' ', '\t', '\u000C', '﻿' -> {
                pos++
                while (pos < bufferEnd) {
                    val d = buffer[pos]
                    if (d == ' ' || d == '\t' || d == '\u000C' || d == '﻿') pos++ else break
                }
                exprEnd = false
                return T.WHITE_SPACE
            }
            '#' -> return lexComment()
            '"', '`' -> return lexStringOpen(raw = false)
            '\'' -> {
                if (exprEnd) {
                    pos++
                    return T.TRANSPOSE
                }
                return lexChar()
            }
            in '0'..'9' -> return lexNumber()
            '.' -> return lexDot()
            '@' -> return lexMacro()
            ':' -> return lexColon()
            '(' -> {
                pos++
                val t = stack?.top
                if (t is InterpMode) replaceTop(InterpMode(t.depth + 1))
                exprEnd = false
                return T.LPAR
            }
            ')' -> {
                pos++
                val t = stack?.top
                if (t is InterpMode) {
                    if (t.depth <= 1) pop() else replaceTop(InterpMode(t.depth - 1))
                }
                exprEnd = true
                return T.RPAR
            }
            '[' -> { pos++; exprEnd = false; return T.LBRACKET }
            ']' -> { pos++; exprEnd = true; return T.RBRACKET }
            '{' -> { pos++; exprEnd = false; return T.LBRACE }
            '}' -> { pos++; exprEnd = true; return T.RBRACE }
            ',' -> { pos++; exprEnd = false; return T.COMMA }
            ';' -> { pos++; exprEnd = false; return T.SEMICOLON }
            '$' -> { pos++; exprEnd = false; return T.DOLLAR }
        }
        val code = cp(pos)
        if (code >= 0x80 && Character.getType(code) == Character.SPACE_SEPARATOR.toInt()) { // e.g. U+00A0
            pos += Character.charCount(code)
            exprEnd = false
            return T.WHITE_SPACE
        }
        if (JuliaChars.isIdStart(code)) return lexIdentifier()
        if (JuliaChars.isAsciiOperatorChar(code) || JuliaChars.isUnicodeOperator(code)) return lexOperator()
        pos += Character.charCount(code)
        exprEnd = false
        return T.BAD_CHARACTER
    }

    private fun lexComment(): IElementType {
        pos++ // '#'
        exprEnd = false
        if (ch(pos) == '=') {
            pos++
            var depth = 1
            while (pos < bufferEnd && depth > 0) {
                val c = buffer[pos]
                if (c == '#' && ch(pos + 1) == '=') {
                    depth++; pos += 2
                } else if (c == '=' && ch(pos + 1) == '#') {
                    depth--; pos += 2
                } else {
                    pos++
                }
            }
            return T.BLOCK_COMMENT
        }
        while (pos < bufferEnd) {
            val c = buffer[pos]
            if (c == '\n' || c == '\r') break
            pos++
        }
        return T.LINE_COMMENT
    }

    // ---- strings --------------------------------------------------------------------------------------------------

    private fun lexStringOpen(raw: Boolean): IElementType {
        val q = buffer[pos]
        val cmd = q == '`'
        val triple = ch(pos + 1) == q && ch(pos + 2) == q
        pos += if (triple) 3 else 1
        push(StrMode(triple, raw, cmd))
        exprEnd = false
        return if (cmd) T.CMD_START else T.STRING_START
    }

    private fun isClosingAt(i: Int, m: StrMode, q: Char): Boolean {
        if (buffer[i] != q) return false
        return !m.triple || (ch(i + 1) == q && ch(i + 2) == q)
    }

    private fun lexStringPart(m: StrMode): IElementType {
        val q = if (m.cmd) '`' else '"'
        val c = buffer[pos]
        if (isClosingAt(pos, m, q)) {
            pos += if (m.triple) 3 else 1
            pop()
            if (m.raw) { // string macro suffix flags, e.g. r"..."i
                while (pos < bufferEnd) {
                    val code = cp(pos)
                    if (code != '!'.code && JuliaChars.isIdChar(code)) pos += Character.charCount(code) else break
                }
            }
            exprEnd = true
            return if (m.cmd) T.CMD_END else T.STRING_END
        }
        if (c == '\\' && !m.raw) return lexEscape(m)
        if (c == '$' && !m.raw) {
            if (ch(pos + 1) == '(') {
                pos++
                push(InterpMode(0))
                exprEnd = false
                return T.INTERP_DOLLAR
            }
            if (JuliaChars.isIdStart(cp(pos + 1))) {
                pos++
                consumeIdentifier()
                return T.INTERP_VAR
            }
        }
        val start = pos
        while (pos < bufferEnd) {
            val d = buffer[pos]
            if (d == q && isClosingAt(pos, m, q)) break
            if (d == '\\') {
                if (!m.raw) break
                pos += if (ch(pos + 1) == q || ch(pos + 1) == '\\') 2 else 1
                continue
            }
            if (d == '$' && !m.raw && (ch(pos + 1) == '(' || JuliaChars.isIdStart(cp(pos + 1)))) break
            pos++
        }
        if (pos == start) pos++
        return if (m.cmd) T.CMD_CONTENT else T.STRING_CONTENT
    }

    private fun lexEscape(m: StrMode): IElementType {
        pos++ // backslash
        if (pos >= bufferEnd) return T.STRING_INVALID_ESCAPE
        val c = buffer[pos]
        if (m.cmd) {
            pos += Character.charCount(cp(pos))
            return T.STRING_ESCAPE
        }
        when (c) {
            'n', 't', 'r', 'a', 'b', 'f', 'v', 'e', '\\', '"', '$', '\'' -> { pos++; return T.STRING_ESCAPE }
            '\n' -> { pos++; return T.STRING_ESCAPE }
            '\r' -> { pos++; if (ch(pos) == '\n') pos++; return T.STRING_ESCAPE }
            'x' -> return hexEscape(2)
            'u' -> return hexEscape(4)
            'U' -> return hexEscape(8)
            in '0'..'7' -> {
                var n = 0
                while (n < 3 && ch(pos) in '0'..'7') { pos++; n++ }
                return T.STRING_ESCAPE
            }
        }
        pos += Character.charCount(cp(pos))
        return T.STRING_INVALID_ESCAPE
    }

    private fun hexEscape(max: Int): IElementType {
        pos++ // x / u / U
        var n = 0
        while (n < max && JuliaChars.isHexDigit(ch(pos))) { pos++; n++ }
        return if (n > 0) T.STRING_ESCAPE else T.STRING_INVALID_ESCAPE
    }

    private fun lexChar(): IElementType {
        val start = pos
        if (ch(pos + 1) == '\'' && ch(pos + 2) == '\'') { // the character literal for a single quote
            pos += 3
            exprEnd = true
            return T.CHAR_LITERAL
        }
        var p = pos + 1
        if (ch(p) == '\\') {
            p++
            if (p < bufferEnd) {
                val e = buffer[p]
                fun hex(max: Int) {
                    p++
                    var n = 0
                    while (n < max && JuliaChars.isHexDigit(ch(p))) { p++; n++ }
                }
                when {
                    e == 'u' -> hex(4)
                    e == 'U' -> hex(8)
                    e == 'x' -> hex(2)
                    e in '0'..'7' -> {
                        var n = 0
                        while (n < 3 && ch(p) in '0'..'7') { p++; n++ }
                    }
                    else -> p += Character.charCount(cp(p))
                }
            }
        } else if (p < bufferEnd && buffer[p] != '\'' && buffer[p] != '\n' && buffer[p] != '\r') {
            p += Character.charCount(cp(p))
        }
        if (p < bufferEnd && buffer[p] == '\'' && p > start + 1) {
            pos = p + 1
            exprEnd = true
            return T.CHAR_LITERAL
        }
        pos = start + 1
        exprEnd = false
        return T.BAD_CHARACTER
    }

    // ---- numbers, dots, macros, symbols ---------------------------------------------------------------------------

    private fun digits(pred: (Char) -> Boolean) {
        while (pos < bufferEnd && (pred(buffer[pos]) || (buffer[pos] == '_' && pred(ch(pos + 1))))) pos++
    }

    private fun isDec(c: Char) = c in '0'..'9'

    private fun lexNumber(): IElementType {
        exprEnd = true
        if (buffer[pos] == '0') {
            when (ch(pos + 1)) {
                'x', 'X' -> if (JuliaChars.isHexDigit(ch(pos + 2)) || ch(pos + 2) == '.') {
                    pos += 2
                    digits(JuliaChars::isHexDigit)
                    if (ch(pos) == '.' && JuliaChars.isHexDigit(ch(pos + 1))) { pos++; digits(JuliaChars::isHexDigit) }
                    if (ch(pos) == 'p' || ch(pos) == 'P') exponent()
                    return T.NUMBER
                }
                'b', 'B' -> if (ch(pos + 2) == '0' || ch(pos + 2) == '1') {
                    pos += 2; digits { it == '0' || it == '1' }; return T.NUMBER
                }
                'o', 'O' -> if (ch(pos + 2) in '0'..'7') {
                    pos += 2; digits { it in '0'..'7' }; return T.NUMBER
                }
            }
        }
        digits(::isDec)
        if (ch(pos) == '.') {
            val n = ch(pos + 1)
            when {
                isDec(n) -> { pos++; digits(::isDec) }
                n == '.' -> {}
                (n == 'e' || n == 'E' || n == 'f') && (isDec(ch(pos + 2)) || ((ch(pos + 2) == '+' || ch(pos + 2) == '-') && isDec(ch(pos + 3)))) -> pos++
                pos + 1 >= bufferEnd -> pos++
                JuliaChars.isIdStart(cp(pos + 1)) -> {}
                JuliaChars.isAsciiOperatorChar(cp(pos + 1)) || JuliaChars.isUnicodeOperator(cp(pos + 1)) -> {}
                n == '(' || n == '\'' -> {}
                else -> pos++
            }
        }
        val e = ch(pos)
        if (e == 'e' || e == 'E' || e == 'f') exponent()
        return T.NUMBER
    }

    private fun exponent() {
        val save = pos
        pos++
        if (ch(pos) == '+' || ch(pos) == '-') pos++
        if (isDec(ch(pos))) digits(::isDec) else pos = save
    }

    private fun lexDot(): IElementType {
        if (isDec(ch(pos + 1)) && !exprEnd) {
            pos++
            digits(::isDec)
            val e = ch(pos)
            if (e == 'e' || e == 'E' || e == 'f') exponent()
            exprEnd = true
            return T.NUMBER
        }
        exprEnd = false
        if (ch(pos + 1) == '.') {
            pos += if (ch(pos + 2) == '.') 3 else 2
            return T.OPERATOR
        }
        if (pos + 1 < bufferEnd) {
            val n = cp(pos + 1)
            if (n != ':'.code && n != '$'.code && n != '?'.code &&
                (JuliaChars.isAsciiOperatorChar(n) || JuliaChars.isUnicodeOperator(n))
            ) {
                pos++
                lexOperator()
                return T.OPERATOR
            }
        }
        pos++
        return T.DOT
    }

    private fun lexMacro(): IElementType {
        pos++ // @
        exprEnd = false
        if (ch(pos) == '.') {
            pos++
            return T.MACRO
        }
        if (JuliaChars.isIdStart(cp(pos))) {
            consumeIdentifier()
            while (ch(pos) == '.' && JuliaChars.isIdStart(cp(pos + 1))) {
                pos++
                consumeIdentifier()
            }
            return T.MACRO
        }
        return T.OPERATOR
    }

    private fun lexColon(): IElementType {
        if (ch(pos + 1) == ':') {
            pos += 2
            exprEnd = false
            return T.DOUBLE_COLON
        }
        if (!exprEnd && JuliaChars.isIdStart(cp(pos + 1))) {
            pos++
            consumeIdentifier()
            exprEnd = true
            return T.SYMBOL
        }
        pos++
        exprEnd = false
        return T.COLON
    }

    // ---- identifiers & keywords -----------------------------------------------------------------------------------

    /** Consumes an identifier starting at [pos] (the first character is assumed to be a valid start). */
    private fun consumeIdentifier() {
        pos += Character.charCount(cp(pos))
        while (pos < bufferEnd) {
            val code = cp(pos)
            if (!JuliaChars.isIdChar(code)) break
            if (code == '!'.code && ch(pos + 1) == '=') break
            pos += Character.charCount(code)
        }
    }

    private fun lexIdentifier(): IElementType {
        val start = pos
        consumeIdentifier()
        val len = pos - start
        if (len == 3 && ch(pos) == '"' && regionIs(start, "var")) {
            pos++
            while (pos < bufferEnd && buffer[pos] != '"' && buffer[pos] != '\n') {
                if (buffer[pos] == '\\') pos++
                pos++
            }
            if (ch(pos) == '"') pos++
            if (pos > bufferEnd) pos = bufferEnd
            exprEnd = true
            return T.IDENTIFIER
        }
        val text = buffer.subSequence(start, pos).toString()
        // a keyword right after a field-access dot is a field name: `def.module`, `x.end`
        val afterFieldDot = start > 0 && buffer[start - 1] == '.' && (start < 2 || buffer[start - 2] != '.')
        val kw = if (afterFieldDot) null else keywordType(text, start)
        if (kw != null) {
            exprEnd = kw === T.END_KW || kw === T.BOOL
            return kw
        }
        if (ch(pos) == '"' || ch(pos) == '`') {
            push(PendingRaw)
            exprEnd = false
            return T.STRING_MACRO_PREFIX
        }
        exprEnd = true
        return if (text in BUILTIN_CONSTANTS) T.BUILTIN_CONSTANT else T.IDENTIFIER
    }

    private fun regionIs(start: Int, s: String): Boolean {
        if (start + s.length > bufferEnd) return false
        for (i in s.indices) if (buffer[start + i] != s[i]) return false
        return true
    }

    private fun followedByWord(word: String): Boolean {
        var p = pos
        while (p < bufferEnd && (buffer[p] == ' ' || buffer[p] == '\t')) p++
        if (p == pos || !regionIs(p, word)) return false
        val after = p + word.length
        return after >= bufferEnd || !JuliaChars.isIdChar(cp(after))
    }

    private fun precedingWord(start: Int): String? {
        var p = start - 1
        while (p >= 0 && (buffer[p] == ' ' || buffer[p] == '\t')) p--
        if (p == start - 1) return null
        val end = p + 1
        while (p >= 0 && (buffer[p].isLetter() || buffer[p] == '_')) p--
        return if (end > p + 1) buffer.subSequence(p + 1, end).toString() else null
    }

    private fun lineStartBefore(start: Int): Int {
        var p = start
        while (p > 0 && buffer[p - 1] != '\n' && buffer[p - 1] != '\r') p--
        return p
    }

    private fun atLineStart(start: Int): Boolean {
        var p = start - 1
        while (p >= 0 && (buffer[p] == ' ' || buffer[p] == '\t')) p--
        return p < 0 || buffer[p] == '\n' || buffer[p] == '\r' || buffer[p] == ';'
    }

    private fun keywordType(text: String, start: Int): IElementType? = when (text) {
        "function" -> T.FUNCTION_KW
        "macro" -> T.MACRO_KW
        "module", "baremodule" -> T.MODULE_KW
        "struct" -> T.STRUCT_KW
        "mutable" -> if (followedByWord("struct")) T.MUTABLE_KW else null
        "abstract" -> if (followedByWord("type")) T.ABSTRACT_KW else null
        "primitive" -> if (followedByWord("type")) T.PRIMITIVE_KW else null
        "type" -> precedingWord(start).let { if (it == "abstract" || it == "primitive") T.TYPE_KW else null }
        "if" -> T.IF_KW
        "elseif" -> T.ELSEIF_KW
        "else" -> T.ELSE_KW
        "for" -> T.FOR_KW
        "while" -> T.WHILE_KW
        "begin" -> T.BEGIN_KW
        "let" -> T.LET_KW
        "quote" -> T.QUOTE_KW
        "try" -> T.TRY_KW
        "catch" -> T.CATCH_KW
        "finally" -> T.FINALLY_KW
        "do" -> T.DO_KW
        "end" -> T.END_KW
        "const" -> T.CONST_KW
        "return", "break", "continue", "using", "import", "export", "global", "local", "where", "in", "isa" -> T.KEYWORD
        "public" -> if (atLineStart(start) && followedByIdentifier()) T.KEYWORD else null
        "as" -> {
            val ls = lineStartBefore(start)
            var p = ls
            while (p < start && (buffer[p] == ' ' || buffer[p] == '\t')) p++
            if (regionIs(p, "import ") || regionIs(p, "using ")) T.KEYWORD else null
        }
        "outer" -> if (precedingWord(start) == "for") T.KEYWORD else null
        "true", "false" -> T.BOOL
        else -> null
    }

    private fun followedByIdentifier(): Boolean {
        var p = pos
        while (p < bufferEnd && (buffer[p] == ' ' || buffer[p] == '\t')) p++
        return p > pos && p < bufferEnd && (JuliaChars.isIdStart(cp(p)) || buffer[p] == '@')
    }

    // ---- operators ------------------------------------------------------------------------------------------------

    private fun lexOperator(): IElementType {
        val start = pos
        val c = buffer[pos]
        if (c.code < 128) {
            for (op in ASCII_OPERATORS) {
                if (regionIs(pos, op)) {
                    pos += op.length
                    break
                }
            }
            if (pos == start) pos++
        } else {
            pos += Character.charCount(cp(pos))
        }
        while (pos < bufferEnd) {
            val code = cp(pos)
            if (JuliaChars.isOperatorSuffix(code)) pos += Character.charCount(code) else break
        }
        exprEnd = false
        val len = pos - start
        if (len == 1 && c == '=') return T.EQ
        if (len == 2 && c == '-' && buffer[start + 1] == '>') return T.ARROW
        return T.OPERATOR
    }

    companion object {
        private val ASCII_OPERATORS = listOf(
            "<-->", ">>>=", "===", "!==", ">>>", "<<=", ">>=", "//=", "-->", "<--",
            "->", "=>", "==", "!=", "<=", ">=", "&&", "||", "<:", ">:", "+=", "-=", "*=", "/=", "\\=", "^=", "%=",
            "|=", "&=", "<<", ">>", "//", "|>", "<|",
        ).sortedByDescending { it.length }

        val BUILTIN_CONSTANTS: Set<String> = hashSetOf(
            "nothing", "missing", "Inf", "Inf16", "Inf32", "Inf64", "NaN", "NaN16", "NaN32", "NaN64",
            "π", "ℯ", "pi", "im", "undef", "C_NULL", "stdin", "stdout", "stderr", "devnull", "ARGS", "ENV",
            "VERSION", "PROGRAM_FILE", "DEPOT_PATH", "LOAD_PATH", "ENDIAN_BOM",
        )
    }
}

// ---- lexer state ------------------------------------------------------------------------------------------------------

internal sealed class Mode
internal data class StrMode(val triple: Boolean, val raw: Boolean, val cmd: Boolean) : Mode()
internal data class InterpMode(val depth: Int) : Mode()
internal data object PendingRaw : Mode()

internal data class ModeStack(val top: Mode, val rest: ModeStack?)

internal data class LexerStateKey(val stack: ModeStack?, val exprEnd: Boolean)

/** Global registry interning lexer states into stable integers (0 = initial). */
internal object LexerStates {
    private val ids = ConcurrentHashMap<LexerStateKey, Int>()
    private val keys = ArrayList<LexerStateKey>()

    private val INITIAL = LexerStateKey(null, false)
    private val INITIAL_EXPR_END = LexerStateKey(null, true)

    init {
        register(INITIAL)
        register(INITIAL_EXPR_END)
    }

    @Synchronized
    private fun register(key: LexerStateKey): Int {
        ids[key]?.let { return it }
        val id = keys.size
        keys.add(key)
        ids[key] = id
        return id
    }

    fun id(stack: ModeStack?, exprEnd: Boolean): Int {
        if (stack == null) return if (exprEnd) 1 else 0
        val key = LexerStateKey(stack, exprEnd)
        return ids[key] ?: register(key)
    }

    @Synchronized
    fun get(id: Int): LexerStateKey = keys.getOrNull(id) ?: INITIAL
}
