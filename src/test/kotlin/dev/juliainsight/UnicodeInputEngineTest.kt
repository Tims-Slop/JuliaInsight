package dev.juliainsight

import dev.juliainsight.unicode.InputContext
import dev.juliainsight.unicode.LatexSymbols
import dev.juliainsight.unicode.UnicodeEdit
import dev.juliainsight.unicode.UnicodeInputEngine
import dev.juliainsight.unicode.UnicodeOptions
import junit.framework.TestCase

/** Simulates typing character by character through the pure engine (no editor). */
class UnicodeInputEngineTest : TestCase() {

    private fun type(input: String, context: InputContext = InputContext.CODE, options: UnicodeOptions = UnicodeOptions(), initial: String = ""): String {
        val sb = StringBuilder(initial)
        for (c in input) {
            val offset = sb.length
            val latex = UnicodeInputEngine.onLatexTyped(sb, offset, c, context, options.eager)
            if (latex != null) {
                sb.replace(latex.start, latex.end, latex.text)
                if (latex is UnicodeEdit.Consume) continue
                sb.append(c)
                continue
            }
            val sub = UnicodeInputEngine.onSubscriptTyped(sb, sb.length, c, options)
            if (sub != null) {
                sb.replace(sub.start, sub.end, sub.text)
                continue
            }
            sb.append(c)
        }
        return sb.toString()
    }

    /** Default mode: only Space / Return convert, the whole word before the cursor at once. */
    private fun typeCommit(input: String, options: UnicodeOptions = UnicodeOptions(eager = false)): String {
        val sb = StringBuilder()
        for (c in input) {
            if (c == ' ' || c == '\n') {
                // positions inside "…" count as string context (not converted)
                UnicodeInputEngine.commitWord(sb, sb.length, options) { i ->
                    if (sb.substring(0, i).count { it == '"' } % 2 == 1) null else InputContext.CODE
                }?.let { sb.replace(it.start, it.end, it.text) }
            }
            sb.append(c)
        }
        return sb.toString()
    }

    fun testCommitMode() {
        assertEquals("\\theta", typeCommit("\\theta"))
        assertEquals("θ ", typeCommit("\\theta "))
        assertEquals("ω₀ = 2π * 50\n", typeCommit("\\omega_0 = 2\\pi * 50\n"))
        assertEquals("f(v₀) ", typeCommit("f(v_0) "))
        assertEquals("α*β ", typeCommit("\\alpha*\\beta "))
        assertEquals("ωω ", typeCommit("\\omega\\omega "))
        assertEquals("x̂ ", typeCommit("x\\hat "))
        assertEquals("y = A\\u ", typeCommit("y = A\\u "))
        assertEquals("Kξ ", typeCommit("K\\xi "))
        assertEquals("x_2d step_1 1_000 x₁₂ ", typeCommit("x_2d step_1 1_000 x_12 "))
        assertEquals("print(\"\\theta\",α) ", typeCommit("print(\"\\theta\",\\alpha) "))
    }

    fun testTableLoaded() {
        assertTrue(LatexSymbols.size > 3000)
        assertEquals("θ", LatexSymbols["\\theta"])
        assertEquals("\\theta", LatexSymbols.latexFor("θ"))
        assertEquals("ω", LatexSymbols["\\omega"])
    }

    fun testEagerGreekLetters() {
        assertEquals("θ", type("\\theta"))
        assertEquals("x = α + β", type("x = \\alpha + \\beta"))
        assertEquals("ω₀", type("\\omega_0"))
    }

    fun testReplacementOnTerminatorWhenAmbiguous() {
        // \in is a prefix of \infty, \int, … so it is replaced by the next character
        assertEquals("for i ∈ 1:3", type("for i \\in 1:3"))
        assertEquals("a ≤ b", type("a \\le b"))
        assertEquals("π*r", type("\\pi*r"))
        assertEquals("∞", type("\\infty"))
    }

    fun testSubscripts() {
        assertEquals("v₀ = 1", type("v_0 = 1"))
        assertEquals("x₁₂", type("x_12"))
        assertEquals("step_1", type("step_1"))  // long names keep their underscore
        assertEquals("1_000", type("1_000"))     // digit separators in numbers
        assertEquals("x_i", type("x_i"))         // letters are opt-in
        assertEquals("xᵢ", type("x_i", options = UnicodeOptions(subscriptLetters = true)))
        assertEquals("step₁", type("step_1", options = UnicodeOptions(maxSubscriptBase = 0)))
    }

    fun testConsecutiveSymbols() {
        // after a symbol that was just produced, the next name is replaced right away
        assertEquals("ωω", type("\\omega\\omega"))
        assertEquals("αβ = 1", type("\\alpha\\beta = 1"))
        assertEquals("x₁α", type("x_1\\alpha"))
    }

    fun testSpaceCommitsCompleteNames() {
        // Space (and Enter, see the editor test) always replaces a complete name and is then inserted ...
        assertEquals("Kξ + 1", type("K\\xi + 1"))
        assertEquals("xα ", type("x\\alpha "))
        // ... except single-letter names after an identifier: `A\u` is left division
        assertEquals("y = A\\u ", type("y = A\\u "))
    }

    fun testLeftDivisionIsNotMangled() {
        // while typing (no explicit Space/Enter), `A\u`, `A\o` and `K\xi` stay left divisions, not ŭ/ø/ξ
        assertEquals("y = A\\o\n", type("y = A\\o\n"))
        assertEquals("K\\xi+1", type("K\\xi+1"))
        assertEquals("y = A\\u*2", type("y = A\\u*2"))
        assertEquals("A \\ b", type("A \\ b"))
        // ... but accents, sub- and superscripts after an identifier are wanted
        assertEquals("x̂", type("x\\hat ").trimEnd())
        assertEquals("x₁", type("x\\_1"))
        assertEquals("r²", type("r\\^2"))
        assertEquals("2π ", type("2\\pi "))
    }

    fun testCommentsAllowEverything() {
        assertEquals("# A ø ", type("# A \\o ", context = InputContext.COMMENT))
    }

    fun testTab() {
        assertEquals(UnicodeInputEngine.TabResult.Replace(0, 5, "α"), UnicodeInputEngine.onTab("\\alph", 5))
        assertEquals(UnicodeInputEngine.TabResult.Replace(0, 3, "∈"), UnicodeInputEngine.onTab("\\in", 3))
        assertEquals(UnicodeInputEngine.TabResult.Nothing, UnicodeInputEngine.onTab("abc", 3))
    }

    fun testConvertAll() {
        assertEquals("θ = ∑(x) ∈ ℝ", UnicodeInputEngine.convertAll("\\theta = \\sum(x) \\in \\bbR"))
        assertEquals("\\zzz q", UnicodeInputEngine.convertAll("\\zzz q"))
    }
}
