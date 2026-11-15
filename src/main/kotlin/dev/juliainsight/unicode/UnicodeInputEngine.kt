package dev.juliainsight.unicode

import dev.juliainsight.lexer.JuliaChars

/** Where the caret is, as far as auto-replacement is concerned. */
enum class InputContext { CODE, COMMENT, STRING }

data class UnicodeOptions(
    val latex: Boolean = true,
    val eager: Boolean = true,
    val subscriptDigits: Boolean = true,
    val subscriptLetters: Boolean = false,
    /** Maximum length (in characters) of the identifier before `_` for automatic subscripts; 0 = unlimited. */
    val maxSubscriptBase: Int = 2,
)

sealed interface UnicodeEdit {
    val start: Int
    val end: Int
    val text: String

    /** Replace `[start, end)` with [text] and swallow the typed character. [original] is what the user typed. */
    data class Consume(override val start: Int, override val end: Int, override val text: String, val original: String) : UnicodeEdit

    /** Replace `[start, end)` with [text], then let the typed character be inserted as usual. */
    data class Then(override val start: Int, override val end: Int, override val text: String) : UnicodeEdit
}

/**
 * The rules behind "type `\theta` and get `θ`" and "type `v_0` and get `v₀`". Pure functions over text so they
 * can be unit tested without an editor.
 */
object UnicodeInputEngine {

    enum class Preceding { NONE, IDENTIFIER, MATH_IDENTIFIER, NUMBER }

    /** Offset of the `\` that starts a LaTeX name ending at [offset], or -1. */
    fun latexStart(text: CharSequence, offset: Int): Int {
        var i = offset - 1
        val min = maxOf(0, offset - LatexSymbols.maxNameLength - 1)
        while (i >= min) {
            val c = text[i]
            if (c == '\\') return i
            if (!LatexSymbols.isNameChar(c)) return -1
            i--
        }
        return -1
    }

    /**
     * What directly precedes the backslash at [start]: an ASCII identifier (`A\b` → maybe left division!),
     * a "math" identifier ending in a non-ASCII character (`ω\omega`, `x₁\alpha` → clearly symbol input),
     * a number (`2\pi`) or nothing.
     */
    fun preceding(text: CharSequence, start: Int): Preceding {
        if (start == 0) return Preceding.NONE
        val p = text[start - 1]
        if (p == ')' || p == ']' || p == '}' || p == '\'') return Preceding.IDENTIFIER
        val cp = Character.codePointBefore(text, start)
        if (!JuliaChars.isIdChar(cp)) return Preceding.NONE
        var i = start
        while (i > 0) {
            val q = Character.codePointBefore(text, i)
            if (!JuliaChars.isIdChar(q)) break
            i -= Character.charCount(q)
        }
        return when {
            text[i] in '0'..'9' -> Preceding.NUMBER
            cp >= 0x80 -> Preceding.MATH_IDENTIFIER
            else -> Preceding.IDENTIFIER
        }
    }

    /**
     * Whether automatically replacing `name` → `value` is safe. In code `A\b` is left division, so while typing after an
     * ASCII identifier we only apply accents, sub/superscripts and primes (`x\hat`, `x\_1`, `x\prime`) and multi-letter
     * operators. An explicit commit (Space / Enter) replaces any complete name, except single-letter names after an
     * identifier (`A\u`, `A\o`, `B\L`), which are practically always left divisions.
     */
    fun autoReplaceAllowed(name: String, value: String, preceding: Preceding, context: InputContext, explicit: Boolean = false): Boolean {
        if (context != InputContext.CODE) return true
        val cp = value.codePointAt(0)
        val combining = SymbolKind.isCombining(cp)
        val continuation = SymbolKind.isIdentifierContinuation(value)
        if (explicit) return !(preceding == Preceding.IDENTIFIER && name.length <= 2)
        return when (preceding) {
            Preceding.NONE -> !combining
            Preceding.NUMBER -> !combining && !continuation
            Preceding.MATH_IDENTIFIER -> true
            Preceding.IDENTIFIER -> name.length >= 3 && (continuation || !JuliaChars.isIdChar(cp))
        }
    }

    /** Called before [c] is inserted at [offset]. Space commits a complete name; other characters follow the safety rules. */
    fun onLatexTyped(text: CharSequence, offset: Int, c: Char, context: InputContext, eager: Boolean): UnicodeEdit? {
        val start = latexStart(text, offset)
        if (start < 0) return null
        val seq = text.subSequence(start, offset).toString()
        val preceding = preceding(text, start)
        if (LatexSymbols.isNameChar(c)) {
            val ext = seq + c
            if (LatexSymbols.isPrefixOfAny(ext)) {
                if (eager) {
                    val v = LatexSymbols[ext]
                    if (v != null && !LatexSymbols.hasLongerMatch(ext) && autoReplaceAllowed(ext, v, preceding, context)) {
                        return UnicodeEdit.Consume(start, offset, v, ext)
                    }
                }
                return null
            }
        }
        val v = LatexSymbols[seq] ?: return null
        if (!autoReplaceAllowed(seq, v, preceding, context, explicit = c == ' ' || c == '\t')) return null
        return UnicodeEdit.Then(start, offset, v)
    }

    /** A complete LaTeX name right before [offset]; Enter commits it like Space does. */
    fun completeBefore(text: CharSequence, offset: Int, context: InputContext): UnicodeEdit? {
        val start = latexStart(text, offset)
        if (start < 0) return null
        val seq = text.subSequence(start, offset).toString()
        val v = LatexSymbols[seq] ?: return null
        if (!autoReplaceAllowed(seq, v, preceding(text, start), context, explicit = true)) return null
        return UnicodeEdit.Then(start, offset, v)
    }

    /** Explicit completion (Tab): exact match, unique completion, or extension to the common prefix. */
    sealed interface TabResult {
        data class Replace(val start: Int, val end: Int, val text: String) : TabResult
        data object ShowCompletion : TabResult
        data object Nothing : TabResult
    }

    fun onTab(text: CharSequence, offset: Int): TabResult {
        val start = latexStart(text, offset)
        if (start < 0) return TabResult.Nothing
        val seq = text.subSequence(start, offset).toString()
        if (seq.length < 2) return TabResult.Nothing
        LatexSymbols[seq]?.let { return TabResult.Replace(start, offset, it) }
        val matches = LatexSymbols.withPrefix(seq)
        if (matches.isEmpty()) return TabResult.Nothing
        if (matches.size == 1) return TabResult.Replace(start, offset, matches.values.first())
        val common = LatexSymbols.commonPrefix(seq)
        if (common.length > seq.length) return TabResult.Replace(start, offset, common)
        return TabResult.ShowCompletion
    }

    /** Automatic subscripts: `v_0` → `v₀`, `x_12` → `x₁₂` (and optionally `x_i` → `xᵢ`). */
    fun onSubscriptTyped(text: CharSequence, offset: Int, c: Char, options: UnicodeOptions): UnicodeEdit.Consume? {
        val isDigit = c in '0'..'9'
        val isLetter = options.subscriptLetters && Subscripts.isLetterWithSubscript(c)
        if (isDigit && !options.subscriptDigits) return null
        if (!isDigit && !isLetter) return null
        val sub = Subscripts.of(c) ?: return null
        if (offset >= 2 && text[offset - 1] == '_') {
            val baseEnd = offset - 1
            var baseStart = baseEnd
            while (baseStart > 0) {
                val q = Character.codePointBefore(text, baseStart)
                if (!JuliaChars.isIdChar(q)) break
                baseStart -= Character.charCount(q)
            }
            if (baseStart >= baseEnd) return null
            if (!JuliaChars.isIdStart(Character.codePointAt(text, baseStart))) return null // a number like 1_000
            if (baseStart > 0 && (text[baseStart - 1] == '@' || text[baseStart - 1] == '\\')) return null
            val baseLen = Character.codePointCount(text, baseStart, baseEnd)
            if (options.maxSubscriptBase > 0 && baseLen > options.maxSubscriptBase) return null
            return UnicodeEdit.Consume(offset - 1, offset, sub.toString(), "_$c")
        }
        if (isDigit && offset >= 2 && Subscripts.isSubscriptDigit(text[offset - 1])) {
            var i = offset - 1
            while (i >= 0 && Subscripts.isSubscriptDigit(text[i])) i--
            if (i >= 0 && JuliaChars.isIdChar(text[i].code) && !Subscripts.isSubscriptDigit(text[i])) {
                return UnicodeEdit.Consume(offset, offset, sub.toString(), c.toString())
            }
        }
        return null
    }

    /**
     * Space / Return in the default mode: converts the word right before [offset] (back to the previous whitespace),
     * e.g. `\omega_0` → `ω₀`, `f(v_0)` → `f(v₀)`, `\alpha*\beta` → `α*β`. [contextAt] gives the context of a position,
     * or null where nothing may be converted (strings when disabled, raw strings like `L"…"`).
     */
    fun commitWord(text: CharSequence, offset: Int, options: UnicodeOptions, contextAt: (Int) -> InputContext?): UnicodeEdit.Then? {
        var start = offset
        while (start > 0 && offset - start < 512) {
            val c = text[start - 1]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') break
            start--
        }
        if (start == offset) return null
        val sb = StringBuilder()
        var changed = false
        var i = start
        while (i < offset) {
            val c = text[i]
            if (c == '\\' && options.latex) {
                var j = i + 1
                while (j < offset && j - i <= LatexSymbols.maxNameLength && LatexSymbols.isNameChar(text[j])) j++
                var k = j
                var replaced = false
                while (k > i + 1) {
                    val name = text.subSequence(i, k).toString()
                    val value = LatexSymbols[name]
                    if (value != null) {
                        val ctx = contextAt(i)
                        if (ctx != null && autoReplaceAllowed(name, value, preceding(sb, sb.length), ctx, explicit = true)) {
                            sb.append(value)
                            replaced = true
                        }
                        break
                    }
                    k--
                }
                if (replaced) {
                    i = k
                    changed = true
                    continue
                }
            }
            if (c == '_' && (options.subscriptDigits || options.subscriptLetters)) {
                var j = i + 1
                while (j < offset && text[j] in '0'..'9') j++
                val digits = j - i - 1
                val letter = digits == 0 && options.subscriptLetters && j < offset && Subscripts.isLetterWithSubscript(text[j])
                if (letter) j++
                val endsIdentifier = j >= offset || !JuliaChars.isIdChar(Character.codePointAt(text, j))
                if (((digits > 0 && options.subscriptDigits) || letter) && endsIdentifier && subscriptBaseOk(sb, options) && contextAt(i) != null) {
                    for (d in i + 1 until j) sb.append(Subscripts.of(text[d]))
                    i = j
                    changed = true
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return if (changed) UnicodeEdit.Then(start, offset, sb.toString()) else null
    }

    /** The identifier at the end of [sb] may get a subscript: it exists, is not a number and is short enough. */
    private fun subscriptBaseOk(sb: CharSequence, options: UnicodeOptions): Boolean {
        var baseStart = sb.length
        while (baseStart > 0) {
            val q = Character.codePointBefore(sb, baseStart)
            if (!JuliaChars.isIdChar(q)) break
            baseStart -= Character.charCount(q)
        }
        if (baseStart >= sb.length) return false
        if (!JuliaChars.isIdStart(Character.codePointAt(sb, baseStart))) return false
        if (baseStart > 0 && (sb[baseStart - 1] == '@' || sb[baseStart - 1] == '\\')) return false
        val len = Character.codePointCount(sb, baseStart, sb.length)
        return options.maxSubscriptBase <= 0 || len <= options.maxSubscriptBase
    }

    /** Replaces every known `\name` in [text] (longest match wins). Used by the "Convert LaTeX to Unicode" action. */
    fun convertAll(text: CharSequence): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\') {
                var j = i + 1
                while (j < text.length && j - i <= LatexSymbols.maxNameLength && LatexSymbols.isNameChar(text[j])) j++
                var k = j
                var replaced = false
                while (k > i + 1) {
                    val v = LatexSymbols[text.subSequence(i, k).toString()]
                    if (v != null) {
                        sb.append(v)
                        i = k
                        replaced = true
                        break
                    }
                    k--
                }
                if (replaced) continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
