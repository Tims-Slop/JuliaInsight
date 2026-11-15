package dev.juliainsight.lexer

/**
 * Character classification following Julia's own rules, re-implemented from Julia's src/flisp/julia_extensions.c
 * (Julia is MIT licensed, see THIRD_PARTY_NOTICES.md). All functions take Unicode code points.
 */
object JuliaChars {

    fun isIdStart(c: Int): Boolean {
        if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c == '_'.code) return true
        if (c < 0xA1 || c > 0x10FFFF) return false
        return isCatIdStart(c, Character.getType(c))
    }

    fun isIdChar(c: Int): Boolean {
        if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c == '_'.code ||
            c in '0'.code..'9'.code || c == '!'.code
        ) return true
        if (c < 0xA1 || c > 0x10FFFF) return false
        val cat = Character.getType(c)
        if (isCatIdStart(c, cat)) return true
        return when (cat.toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.DECIMAL_DIGIT_NUMBER,
            Character.CONNECTOR_PUNCTUATION, Character.MODIFIER_SYMBOL, Character.ENCLOSING_MARK,
            Character.OTHER_NUMBER -> true
            else -> c in 0x2032..0x2037 || c == 0x2057
        }
    }

    private fun isCatIdStart(wc: Int, cat: Int): Boolean {
        when (cat.toByte()) {
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
            Character.MODIFIER_LETTER, Character.OTHER_LETTER, Character.LETTER_NUMBER,
            Character.CURRENCY_SYMBOL -> return true
            Character.OTHER_SYMBOL -> if (!(wc in 0x2190..0x21FF) && wc != 0xfffc && wc != 0xfffd && wc != 0x233f && wc != 0x00a6) return true
        }
        // math symbol (category Sm) whitelist
        if (wc in 0x2140..0x2a1c) {
            if (wc in 0x2140..0x2144 || wc == 0x223f || wc == 0x22be || wc == 0x22bf || wc == 0x22a4 || wc == 0x22a5) return true
            if (wc in 0x2200..0x2233 &&
                (wc == 0x2202 || wc == 0x2205 || wc == 0x2206 || wc == 0x2207 || wc == 0x220e || wc == 0x220f ||
                    wc == 0x2210 || wc == 0x2211 || wc == 0x221e || wc == 0x221f || wc >= 0x222b)
            ) return true
            if (wc in 0x22c0..0x22c3 || wc in 0x25F8..0x25ff) return true
            if (wc >= 0x266f && (wc == 0x266f || wc == 0x27d8 || wc == 0x27d9 || wc in 0x27c0..0x27c1 ||
                    wc in 0x29b0..0x29b4 || wc in 0x2a00..0x2a06 || wc in 0x2a09..0x2a16 || wc == 0x2a1b || wc == 0x2a1c)
            ) return true
        }
        if (wc >= 0x1d6c1 && (wc == 0x1d6c1 || wc == 0x1d6db || wc == 0x1d6fb || wc == 0x1d715 || wc == 0x1d735 ||
                wc == 0x1d74f || wc == 0x1d76f || wc == 0x1d789 || wc == 0x1d7a9 || wc == 0x1d7c3)
        ) return true
        if (wc in 0x207a..0x207e || wc in 0x208a..0x208e) return true // super- and subscript +-=()
        if (wc in 0x2220..0x2222 || wc in 0x299b..0x29af) return true // angle symbols
        if (wc == 0x2118 || wc == 0x212E || wc in 0x309B..0x309C) return true // Other_ID_Start
        if (wc in 0x1D7CE..0x1D7E1) return true // bold / double-struck digits
        return false
    }

    /** Non-ASCII characters that Julia parses as operators. */
    fun isUnicodeOperator(c: Int): Boolean {
        if (c < 0xA1) return false
        if (isIdChar(c)) return false
        return when (Character.getType(c).toByte()) {
            Character.MATH_SYMBOL, Character.OTHER_SYMBOL, Character.DASH_PUNCTUATION -> true
            else -> c == 0x00B7 /* · */ || c == 0x2026 /* … */ || c == 0x205D /* ⁝ */ || c == 0x00AC
        }
    }

    /** Characters that may follow an operator as a suffix (`+₁`, `⊕′`, combining marks). */
    fun isOperatorSuffix(c: Int): Boolean {
        if (c < 0xA1) return false
        if (c in 0x2032..0x2037 || c == 0x2057) return true
        if (c in 0x2070..0x209C || c in 0x1D62..0x1D6A || c == 0xB2 || c == 0xB3 || c == 0xB9) return true
        return when (Character.getType(c).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
            else -> false
        }
    }

    fun isAsciiOperatorChar(c: Int): Boolean = when (c.toChar()) {
        '+', '-', '*', '/', '\\', '^', '%', '&', '|', '<', '>', '=', '!', '~', '?', ':', '$', '÷' -> true
        else -> false
    }

    fun isHexDigit(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
}
