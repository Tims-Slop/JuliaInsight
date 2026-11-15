package dev.juliainsight

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.juliainsight.lexer.JuliaLexer
import dev.juliainsight.lexer.JuliaTokenTypes
import dev.juliainsight.parser.JuliaElementTypes
import java.io.File

/**
 * Runs the lexer and parser over Julia's own sources (base + stdlib), which are known to be valid Julia.
 * Enabled when the system property `julia.insight.test.julia` points to a julia executable.
 */
class JuliaCorpusTest : BasePlatformTestCase() {

    private fun corpus(): List<File> {
        val exe = System.getProperty("julia.insight.test.julia").orEmpty()
        if (exe.isBlank()) return emptyList()
        val share = File(exe).absoluteFile.parentFile.parentFile.resolve("share/julia")
        val roots = listOf(share.resolve("base")) + (share.resolve("stdlib").listFiles()?.flatMap { v ->
            v.listFiles()?.map { it.resolve("src") } ?: emptyList()
        } ?: emptyList())
        return roots.filter { it.isDirectory }.flatMap { r -> r.walkTopDown().filter { it.isFile && it.extension == "jl" }.toList() }
    }

    fun testLexerOnJuliaSources() {
        val files = corpus()
        if (files.isEmpty()) return
        val problems = ArrayList<String>()
        for (f in files) {
            val text = f.readText().replace("\r\n", "\n")
            val l = JuliaLexer()
            l.start(text)
            while (l.tokenType != null) {
                if (l.tokenType == JuliaTokenTypes.BAD_CHARACTER) {
                    val line = text.substring(0, l.tokenStart).count { it == '\n' } + 1
                    problems += "${f.name}:$line bad character <${text.substring(l.tokenStart, l.tokenEnd)}> in: " +
                        text.lines()[line - 1].trim().take(100)
                    break
                }
                l.advance()
            }
            try {
                JuliaLexerTest.assertRestartable(text, maxRestarts = 40)
            } catch (e: AssertionError) {
                problems += "${f.name}: ${e.message}"
            }
        }
        println("lexed ${files.size} files from Julia's sources")
        if (problems.isNotEmpty()) fail("${problems.size} problems:\n" + problems.take(40).joinToString("\n"))
    }

    fun testParserOnJuliaSources() {
        val files = corpus()
        if (files.isEmpty()) return
        val problems = ArrayList<String>()
        for (f in files) {
            val text = f.readText().replace("\r\n", "\n")
            val psi = PsiFileFactory.getInstance(project).createFileFromText(f.name, JuliaFileType, text)
            fun line(e: PsiElement) = text.substring(0, e.textRange.startOffset).count { it == '\n' } + 1
            var child = psi.firstChild
            while (child != null) {
                if (child.elementType == JuliaTokenTypes.END_KW) {
                    problems += "${f.name}:${line(child)} stray end"
                    break
                }
                child = child.nextSibling
            }
            val unterminated = PsiTreeUtil.collectElements(psi) {
                it.elementType in JuliaElementTypes.KEYWORD_BLOCKS && it.lastChild?.elementType != JuliaTokenTypes.END_KW
            }.firstOrNull()
            if (unterminated != null) problems += "${f.name}:${line(unterminated)} unterminated ${unterminated.elementType}: " +
                unterminated.text.lineSequence().first().take(80)
        }
        println("parsed ${files.size} files from Julia's sources")
        if (problems.isNotEmpty()) fail("${problems.size} files with problems:\n" + problems.take(40).joinToString("\n"))
    }
}
