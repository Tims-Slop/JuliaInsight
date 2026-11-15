package dev.juliainsight

import dev.juliainsight.lexer.JuliaLexer
import dev.juliainsight.lexer.JuliaTokenTypes
import junit.framework.TestCase

class JuliaLexerTest : TestCase() {

    private fun lex(text: String): List<Pair<String, String>> {
        val l = JuliaLexer()
        l.start(text)
        val out = ArrayList<Pair<String, String>>()
        while (l.tokenType != null) {
            val t = l.tokenType!!
            if (t != JuliaTokenTypes.WHITE_SPACE && t != JuliaTokenTypes.NEWLINE) {
                out += t.toString().removePrefix("Julia:") to text.substring(l.tokenStart, l.tokenEnd)
            }
            l.advance()
        }
        return out
    }

    private fun types(text: String) = lex(text).joinToString(" ") { it.first }

    private fun assertTokens(text: String, vararg expected: String) {
        assertEquals("tokens of <$text>", expected.joinToString(" "), lex(text).joinToString(" ") { "${it.first}(${it.second})" })
    }

    fun testTransposeVersusChar() {
        assertTokens("x'", "IDENTIFIER(x)", "'(')")
        assertTokens("x = 'a'", "IDENTIFIER(x)", "=(=)", "CHAR_LITERAL('a')")
        assertTokens("A[1:end]'", "IDENTIFIER(A)", "[([)", "NUMBER(1)", ":(:)", "end(end)", "](])", "'(')")
        assertTokens("f(x)''", "IDENTIFIER(f)", "((()", "IDENTIFIER(x)", ")())", "'(')", "'(')")
        assertTokens("'\\n' '\\u00e9' '\\''", "CHAR_LITERAL('\\n')", "CHAR_LITERAL('\\u00e9')", "CHAR_LITERAL('\\'')")
        assertTokens("print(io, ''')", "IDENTIFIER(print)", "((()", "IDENTIFIER(io)", ",(,)", "CHAR_LITERAL(''')", ")())")
        assertTokens("d.module.end", "IDENTIFIER(d)", ".(.)", "IDENTIFIER(module)", ".(.)", "IDENTIFIER(end)")
        assertTokens("c == 'θ'", "IDENTIFIER(c)", "OPERATOR(==)", "CHAR_LITERAL('θ')")
    }

    fun testSymbolsVersusRanges() {
        assertTokens("x = :sym", "IDENTIFIER(x)", "=(=)", "SYMBOL(:sym)")
        assertTokens("1:n", "NUMBER(1)", ":(:)", "IDENTIFIER(n)")
        assertTokens("a[i:j]", "IDENTIFIER(a)", "[([)", "IDENTIFIER(i)", ":(:)", "IDENTIFIER(j)", "](])")
        assertTokens("c ? a : b", "IDENTIFIER(c)", "OPERATOR(?)", "IDENTIFIER(a)", ":(:)", "IDENTIFIER(b)")
        assertTokens("x::Int", "IDENTIFIER(x)", "::(::)", "IDENTIFIER(Int)")
        assertTokens("Dict(:a => 1)", "IDENTIFIER(Dict)", "((()", "SYMBOL(:a)", "OPERATOR(=>)", "NUMBER(1)", ")())")
        assertTokens("x[:, 1]", "IDENTIFIER(x)", "[([)", ":(:)", ",(,)", "NUMBER(1)", "](])")
        assertTokens("Base.:+", "IDENTIFIER(Base)", ".(.)", ":(:)", "OPERATOR(+)")
        assertTokens(":end", "SYMBOL(:end)")
    }

    fun testStringsAndInterpolation() {
        assertTokens(
            "\"a \$b \$(c+1)\\n\"",
            "STRING_START(\")", "STRING_CONTENT(a )", "INTERP_VAR(\$b)", "STRING_CONTENT( )", "INTERP_DOLLAR(\$)", "((()",
            "IDENTIFIER(c)", "OPERATOR(+)", "NUMBER(1)", ")())", "STRING_ESCAPE(\\n)", "STRING_END(\")",
        )
        assertTokens("\"\$(f(\"x\"))\"",
            "STRING_START(\")", "INTERP_DOLLAR(\$)", "((()", "IDENTIFIER(f)", "((()", "STRING_START(\")", "STRING_CONTENT(x)",
            "STRING_END(\")", ")())", ")())", "STRING_END(\")")
        assertTokens("r\"\\d+\$\"i", "STRING_MACRO_PREFIX(r)", "STRING_START(\")", "STRING_CONTENT(\\d+\$)", "STRING_END(\"i)")
        assertTokens("L\"\\alpha\"", "STRING_MACRO_PREFIX(L)", "STRING_START(\")", "STRING_CONTENT(\\alpha)", "STRING_END(\")")
        assertTokens("raw\"a\\\\\"", "STRING_MACRO_PREFIX(raw)", "STRING_START(\")", "STRING_CONTENT(a\\\\)", "STRING_END(\")")
        assertTokens("\"\"\"doc \"q\" \"\"\"", "STRING_START(\"\"\")", "STRING_CONTENT(doc \"q\" )", "STRING_END(\"\"\")")
        assertTokens("\"bad \\q\"", "STRING_START(\")", "STRING_CONTENT(bad )", "STRING_INVALID_ESCAPE(\\q)", "STRING_END(\")")
        assertTokens("`ls \$dir`", "CMD_START(`)", "CMD_CONTENT(ls )", "INTERP_VAR(\$dir)", "CMD_END(`)")
        assertTokens("\"\"", "STRING_START(\")", "STRING_END(\")")
    }

    fun testCommentsAndNumbers() {
        assertTokens("#= a #= b =# c =# x", "BLOCK_COMMENT(#= a #= b =# c =#)", "IDENTIFIER(x)")
        assertTokens("x # c", "IDENTIFIER(x)", "LINE_COMMENT(# c)")
        assertTokens("1.5e-3 0x1F 1_000 0b101 2x .5 1f0 3.", "NUMBER(1.5e-3)", "NUMBER(0x1F)", "NUMBER(1_000)", "NUMBER(0b101)",
            "NUMBER(2)", "IDENTIFIER(x)", "NUMBER(.5)", "NUMBER(1f0)", "NUMBER(3.)")
        assertTokens("1:2:10", "NUMBER(1)", ":(:)", "NUMBER(2)", ":(:)", "NUMBER(10)")
        assertTokens("x .+ 1.0", "IDENTIFIER(x)", "OPERATOR(.+)", "NUMBER(1.0)")
        assertTokens("a.b", "IDENTIFIER(a)", ".(.)", "IDENTIFIER(b)")
        assertTokens("f.(x)", "IDENTIFIER(f)", ".(.)", "((()", "IDENTIFIER(x)", ")())")
        assertTokens("x...", "IDENTIFIER(x)", "OPERATOR(...)")
    }

    fun testUnicodeAndIdentifiers() {
        assertTokens("θ₀ = ∑(x′)", "IDENTIFIER(θ₀)", "=(=)", "IDENTIFIER(∑)", "((()", "IDENTIFIER(x′)", ")())")
        assertTokens("x ∈ y ≤ z", "IDENTIFIER(x)", "OPERATOR(∈)", "IDENTIFIER(y)", "OPERATOR(≤)", "IDENTIFIER(z)")
        assertTokens("√2", "OPERATOR(√)", "NUMBER(2)")
        assertTokens("x̂ = ∂f", "IDENTIFIER(x̂)", "=(=)", "IDENTIFIER(∂f)")
        assertTokens("push!(a) a!=b", "IDENTIFIER(push!)", "((()", "IDENTIFIER(a)", ")())", "IDENTIFIER(a)", "OPERATOR(!=)", "IDENTIFIER(b)")
        assertTokens("var\"my var\"", "IDENTIFIER(var\"my var\")")
        assertTokens("2π", "NUMBER(2)", "BUILTIN_CONSTANT(π)")
        assertTokens("@time f(x)", "MACRO(@time)", "IDENTIFIER(f)", "((()", "IDENTIFIER(x)", ")())")
        assertTokens("Base.@kwdef @. x", "IDENTIFIER(Base)", ".(.)", "MACRO(@kwdef)", "MACRO(@.)", "IDENTIFIER(x)")
        assertTokens("x->x^2", "IDENTIFIER(x)", "->(->)", "IDENTIFIER(x)", "OPERATOR(^)", "NUMBER(2)")
    }

    fun testContextualKeywords() {
        assertEquals("mutable struct IDENTIFIER end", types("mutable struct X end"))
        assertEquals("abstract type IDENTIFIER end", types("abstract type T end"))
        assertEquals("primitive type IDENTIFIER NUMBER end", types("primitive type P 8 end"))
        assertEquals("IDENTIFIER = NUMBER", types("type = 1"))
        assertEquals("IDENTIFIER", types("mutable"))
        assertEquals("KEYWORD IDENTIFIER KEYWORD IDENTIFIER", types("import Foo as Bar"))
        assertEquals("IDENTIFIER =", types("as ="))
        assertEquals("BOOL BOOL BUILTIN_CONSTANT", types("true false nothing"))
    }

    fun testNoEmptyTokensAndFullCoverage() {
        val samples = listOf("'", "\"", "\"\"\"", "#=", "\"\$(", "0x", "1e", "@", "'\\", ":", "`", "r\"", "\$", "a'b'c", "x.'")
        for (s in samples) {
            val l = JuliaLexer()
            l.start(s)
            var pos = 0
            while (l.tokenType != null) {
                assertEquals("contiguous tokens for <$s>", pos, l.tokenStart)
                assertTrue("non-empty token for <$s>", l.tokenEnd > l.tokenStart)
                pos = l.tokenEnd
                l.advance()
            }
            assertEquals("whole input covered for <$s>", s.length, pos)
        }
    }

    fun testRestartability() {
        val text = javaClass.getResource("/sample.jl")!!.readText()
        assertRestartable(text)
    }

    companion object {
        /** Incremental highlighting restarts the lexer at tokens whose state is 0; the result must equal a full lex. */
        fun assertRestartable(text: String, maxRestarts: Int = 200) {
            data class Tok(val type: Any?, val start: Int, val end: Int, val state: Int)
            val full = ArrayList<Tok>()
            val l = JuliaLexer()
            l.start(text, 0, text.length, 0)
            while (l.tokenType != null) {
                full += Tok(l.tokenType, l.tokenStart, l.tokenEnd, l.state)
                l.advance()
            }
            val restartPoints = full.indices.filter { full[it].state == 0 }
            val step = maxOf(1, restartPoints.size / maxRestarts)
            for (k in restartPoints.indices step step) {
                val i = restartPoints[k]
                val r = JuliaLexer()
                r.start(text, full[i].start, text.length, 0)
                var j = i
                while (r.tokenType != null && j < full.size && j < i + 400) {
                    val f = full[j]
                    if (r.tokenType != f.type || r.tokenStart != f.start || r.tokenEnd != f.end) {
                        val line = text.substring(0, f.start).count { it == '\n' } + 1
                        fail("restart at ${full[i].start} diverges at line $line: ${r.tokenType}@${r.tokenStart} vs ${f.type}@${f.start}")
                    }
                    r.advance()
                    j++
                }
            }
        }
    }
}
