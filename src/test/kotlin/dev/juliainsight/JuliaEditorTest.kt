package dev.juliainsight

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.juliainsight.lexer.JuliaTokenTypes
import dev.juliainsight.parser.JuliaElementTypes
import dev.juliainsight.psi.JuliaDefinition
import dev.juliainsight.repl.JuliaStatementFinder
import dev.juliainsight.settings.JuliaSettings
import dev.juliainsight.settings.ReplaceMode

class JuliaEditorTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        JuliaSettings.getInstance().loadState(JuliaSettings.State())
    }

    private fun typing(before: String, typed: String, after: String) {
        myFixture.configureByText("a.jl", before)
        myFixture.type(typed)
        myFixture.checkResult(after)
    }

    // ---- math input, default mode: Space / Return convert the word before the cursor -----------------------------------

    fun testNothingChangesWhileTyping() = typing("x = <caret>", "\\theta", "x = \\theta<caret>")
    fun testSpaceConverts() = typing("x = <caret>", "\\theta ", "x = θ <caret>")
    fun testReturnConverts() = typing("x = <caret>", "\\theta\n", "x = θ\n<caret>")
    fun testWordWithSubscript() = typing("<caret>", "\\omega_0 = 2\\pi * 50\n", "ω₀ = 2π * 50\n<caret>")
    fun testConsecutiveNames() = typing("<caret>", "\\omega\\omega ", "ωω <caret>")
    fun testSymbolsInsideCall() = typing("<caret>", "f(v_0, \\alpha*\\beta) ", "f(v₀, α*β) <caret>")
    fun testSubscriptsOnSpace() = typing("<caret>", "v_0 + x_12 ", "v₀ + x₁₂ <caret>")
    fun testIdentifierContinuesNoSubscript() = typing("<caret>", "x_2d ", "x_2d <caret>")
    fun testLongNamesKeepUnderscoreOnSpace() = typing("<caret>", "step_1 ", "step_1 <caret>")
    fun testAccentOnSpace() = typing("<caret>", "x\\hat = 1", "x̂ = 1<caret>")
    fun testLeftDivisionSurvivesSpace() = typing("<caret>", "y = A\\u + 1", "y = A\\u + 1<caret>")
    fun testIdentifierThenGreek() = typing("<caret>", "K\\xi ", "Kξ <caret>")
    fun testStringUntouchedOnSpace() = typing("<caret>", "s = \"\\theta\" ", "s = \"\\theta\" <caret>")
    fun testCommentOnSpace() = typing("# angle <caret>", "\\theta ", "# angle θ <caret>")
    fun testRawLatexStringNeverReplaced() {
        JuliaSettings.getInstance().state.unicodeInStrings = true
        typing("<caret>", "s = L\"\\alpha\" ", "s = L\"\\alpha\" <caret>")
    }
    fun testTabCompletesLikeRepl() = typing("<caret>", "\\alph\t", "α<caret>")
    fun testDisabledReplacement() {
        JuliaSettings.getInstance().state.latexAutoReplace = false
        JuliaSettings.getInstance().state.subscriptDigits = false
        typing("<caret>", "\\theta v_0 ", "\\theta v_0 <caret>")
    }
    fun testSpaceWhileCompletionPopupIsOpen() {
        myFixture.configureByText("a.jl", "<caret>")
        myFixture.type("v_0")
        com.intellij.codeInsight.lookup.LookupManager.getInstance(project).showLookup(
            myFixture.editor,
            com.intellij.codeInsight.lookup.LookupElementBuilder.create("v_0_max"),
            com.intellij.codeInsight.lookup.LookupElementBuilder.create("v_0_min"),
        )
        myFixture.type(" = 1")
        myFixture.checkResult("v₀ = 1<caret>")
    }

    // ---- math input, optional "while typing" mode ---------------------------------------------------------------------

    private fun typingLive(before: String, typed: String, after: String) {
        JuliaSettings.getInstance().state.replaceMode = ReplaceMode.WHILE_TYPING
        typing(before, typed, after)
    }

    fun testLiveLatexEager() = typingLive("x = <caret>", "\\theta", "x = θ<caret>")
    fun testLiveLatexOnTerminator() = typingLive("for i <caret>", "\\in 1:3", "for i ∈ 1:3<caret>")
    fun testLiveSubscriptAfterLatex() = typingLive("<caret>", "\\omega_0 = 1", "ω₀ = 1<caret>")
    fun testLiveSubscriptDigits() = typingLive("<caret>", "v_0 + x_12", "v₀ + x₁₂<caret>")
    fun testLiveSubscriptRevertedWhenIdentifierContinues() = typingLive("<caret>", "x_2d", "x_2d<caret>")
    fun testLiveLongNamesKeepUnderscore() = typingLive("<caret>", "step_1", "step_1<caret>")
    fun testLiveLeftDivisionUntouched() = typingLive("<caret>", "y = A\\u + 1", "y = A\\u + 1<caret>")
    fun testLiveAccentAfterIdentifier() = typingLive("<caret>", "x\\hat = 1", "x̂ = 1<caret>")
    fun testLiveNumberTimesPi() = typingLive("<caret>", "2\\pi ", "2π <caret>")
    fun testLiveInsideStringUntouchedByDefault() = typingLive("s = \"<caret>\"", "\\theta", "s = \"\\theta<caret>\"")
    fun testLiveInsideComment() = typingLive("# angle <caret>", "\\theta", "# angle θ<caret>")
    fun testLiveBackspaceRestoresLatex() {
        typingLive("<caret>", "\\theta\b", "\\theta<caret>")
        myFixture.type(" ")
        myFixture.checkResult("\\theta <caret>") // the restored text is not replaced again
    }
    fun testLiveBackspaceRestoresSubscript() = typingLive("<caret>", "v_0\b", "v_0<caret>")
    fun testLiveEnterCompletesPendingName() = typingLive("a <caret>", "\\in\n", "a ∈\n<caret>")
    fun testLiveConsecutiveSymbols() = typingLive("<caret>", "\\omega\\omega", "ωω<caret>")
    fun testLiveSpaceCommitsAfterIdentifier() = typingLive("<caret>", "x\\alpha ", "xα <caret>")
    fun testLiveEnterCommitsAfterIdentifier() = typingLive("<caret>", "K\\xi\n", "Kξ\n<caret>")
    fun testLiveSingleLetterLeftDivisionSurvivesSpace() = typingLive("<caret>", "y = A\\u ", "y = A\\u <caret>")
    fun testLiveSubscriptWhileCompletionPopupIsOpen() {
        JuliaSettings.getInstance().state.replaceMode = ReplaceMode.WHILE_TYPING
        myFixture.configureByText("a.jl", "<caret>")
        myFixture.type("ω_")
        com.intellij.codeInsight.lookup.LookupManager.getInstance(project).showLookup(
            myFixture.editor,
            com.intellij.codeInsight.lookup.LookupElementBuilder.create("ω_max"),
            com.intellij.codeInsight.lookup.LookupElementBuilder.create("ω_min"),
        )
        assertNotNull("popup open", com.intellij.codeInsight.lookup.LookupManager.getActiveLookup(myFixture.editor))
        myFixture.type("0 = 1")
        myFixture.checkResult("ω₀ = 1<caret>")
    }

    fun testSettingsPageCanBeFoundForProject() {
        // the notification's "Settings…" link opens this page for the current project
        val groups = com.intellij.ide.actions.ShowSettingsUtilImpl.getConfigurableGroups(project, true).toList()
        assertNotNull(com.intellij.openapi.options.ex.ConfigurableVisitor.findByType(dev.juliainsight.settings.JuliaConfigurable::class.java, groups))
    }

    fun testLatexCompletion() {
        myFixture.configureByText("a.jl", "x = \\varphi<caret>")
        val items = myFixture.completeBasic()
        if (items == null) {
            myFixture.checkResult("x = φ<caret>") // single variant was inserted directly
        } else {
            assertTrue(items.any { it.lookupString == "\\varphi" })
        }
    }

    // ---- smart editing ------------------------------------------------------------------------------------------------

    fun testAutoEndAfterFunction() = typing("function f(x)<caret>", "\n", "function f(x)\n    <caret>\nend")
    fun testAutoEndNotDuplicated() = typing("function f(x)<caret>\nend", "\n", "function f(x)\n    <caret>\nend")
    fun testAutoEndForDoBlock() = typing("map(xs) do x<caret>", "\n", "map(xs) do x\n    <caret>\nend")
    fun testNoAutoEndForGenerator() = typing("y = [x for x in xs]<caret>", "\n", "y = [x for x in xs]\n<caret>")
    fun testNoAutoEndForOneLiner() = typing("if a; b; end<caret>", "\n", "if a; b; end\n<caret>")
    fun testIndentAfterElse() = typing("if a\n    b\nelse<caret>\nend", "\n", "if a\n    b\nelse\n    <caret>\nend")
    fun testDedentEnd() = typing("for i in 1:3\n    x = i\n    <caret>", "end", "for i in 1:3\n    x = i\nend<caret>")
    fun testDedentElse() = typing("if a\n    b\n    <caret>", "else", "if a\n    b\nelse<caret>")

    fun testCommentLine() {
        myFixture.configureByText("a.jl", "x = 1<caret>")
        myFixture.performEditorAction("CommentByLineComment")
        myFixture.checkResult("# x = 1")
    }

    fun testAutoCloseQuoteAndBracket() {
        typing("x = <caret>", "\"", "x = \"<caret>\"")
        typing("x = <caret>", "(", "x = (<caret>)")
    }

    // ---- structure ------------------------------------------------------------------------------------------------------

    fun testParserFindsDefinitions() {
        val file = myFixture.configureByText("a.jl", javaClass.getResource("/sample.jl")!!.readText())
        val defs = PsiTreeUtil.findChildrenOfType(file, JuliaDefinition::class.java).map { "${it.kind}:${it.name}" }
        for (expected in listOf(
            "MODULE:Kinematics", "STRUCT:Projectile", "STRUCT:Counter", "ABSTRACT_TYPE:AbstractBody", "PRIMITIVE_TYPE:Flag8",
            "CONST:g", "FUNCTION:range_of", "FUNCTION:show", "FUNCTION:apex", "FUNCTION:trajectory", "MACRO:twice",
            "FUNCTION:f", "FUNCTION:g2", "FUNCTION:∇f",
        )) assertTrue("missing $expected in $defs", expected in defs)
        assertFalse("assignments must not become definitions: $defs", defs.any { it.endsWith(":x̂") || it.endsWith(":v") })
        // every `end` belongs to a block; nothing is left dangling at file level
        assertNoStrayEnds(file)
    }

    fun testIndexEndIsNotBlockEnd() {
        val file = myFixture.configureByText("a.jl", "function f(a)\n    a[end] + a[begin:end-1][1]\nend\nx = 1")
        val fn = PsiTreeUtil.findChildOfType(file, JuliaDefinition::class.java)!!
        assertEquals("f", fn.name)
        assertTrue(fn.text.endsWith("end") && !fn.text.contains("x = 1"))
    }

    fun testGeneratorsHaveNoEnd() {
        val file = myFixture.configureByText("a.jl", "function f(xs)\n    s = sum(x^2 for x in xs if x > 0)\n    [i for i in 1:3]\nend\ny = 2")
        val fn = PsiTreeUtil.findChildOfType(file, JuliaDefinition::class.java)!!
        assertFalse(fn.text.contains("y = 2"))
        assertNoStrayEnds(file)
    }

    fun testFolding() {
        myFixture.configureByText("a.jl", "function f(x)\n    x + 1\nend\n\"\"\"\ndoc\n\"\"\"\ng(x) = 2")
        myFixture.doHighlighting()
        val regions = myFixture.editor.foldingModel.allFoldRegions.map { it.placeholderText }
        assertTrue(regions.toString(), regions.any { it.contains("end") })
        assertTrue(regions.toString(), regions.any { it.contains("doc") })
    }

    fun testStructureView() {
        val file = myFixture.configureByText("a.jl", "module M\nstruct P\n    x::Int\n    y\nend\nf(p::P) = p.x\nfunction g()\nend\nend")
        val model = dev.juliainsight.editor.JuliaStructureViewModel(file, myFixture.editor)
        val texts = ArrayList<String>()
        fun walk(e: com.intellij.ide.util.treeView.smartTree.TreeElement, depth: Int) {
            texts += "  ".repeat(depth) + e.presentation.presentableText
            e.children.forEach { walk(it, depth + 1) }
        }
        model.root.children.forEach { walk(it, 0) }
        assertEquals("M\n  P\n    x::Int\n    y\n  f(p::P)\n  g()", texts.joinToString("\n"))
    }

    fun testSendToReplStatementRange() {
        val text = "\"\"\"doc\"\"\"\nfunction f(x)\n    x + 1\nend\n\ny = f(\n  2)\nz = 3"
        val file = myFixture.configureByText("a.jl", text)
        val doc = myFixture.editor.document
        val r1 = JuliaStatementFinder.statementRange(file, doc, text.indexOf("x + 1"))!!
        assertEquals("\"\"\"doc\"\"\"\nfunction f(x)\n    x + 1\nend", doc.getText(r1))
        val r2 = JuliaStatementFinder.statementRange(file, doc, text.indexOf("y = f"))!!
        assertEquals("y = f(\n  2)", doc.getText(r2))
    }

    fun testHighlightingAnnotator() {
        myFixture.configureByText("a.jl", "function foo(a::Int)\n    bar(a)\nend")
        val infos = myFixture.doHighlighting()
        fun keyAt(text: String) = infos.firstOrNull { myFixture.editor.document.getText(com.intellij.openapi.util.TextRange(it.startOffset, it.endOffset)) == text }?.forcedTextAttributesKey?.externalName
        assertEquals("JULIA_FUNCTION_DECLARATION", keyAt("foo"))
        assertEquals("JULIA_TYPE", keyAt("Int"))
        assertEquals("JULIA_FUNCTION_CALL", keyAt("bar"))
        assertEquals("JULIA_PARAMETER", keyAt("a"))
    }

    companion object {
        fun assertNoStrayEnds(file: PsiElement) {
            var child = file.firstChild
            while (child != null) {
                if (child.elementType == JuliaTokenTypes.END_KW) fail("stray `end` at offset ${child.textOffset}")
                child = child.nextSibling
            }
            PsiTreeUtil.processElements(file) { e ->
                if (e.elementType in JuliaElementTypes.KEYWORD_BLOCKS && e.lastChild?.elementType != JuliaTokenTypes.END_KW) {
                    fail("unterminated ${e.elementType} at offset ${e.textOffset}: ${e.text.take(60)}")
                }
                true
            }
        }
    }
}
