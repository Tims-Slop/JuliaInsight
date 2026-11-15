package dev.juliainsight.editor

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.editorActions.MultiCharQuoteHandler
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.lang.BracePair
import com.intellij.lang.CodeDocumentationAwareCommenter
import com.intellij.lang.Language
import com.intellij.lang.PairedBraceMatcher
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider
import com.intellij.psi.tree.IElementType
import dev.juliainsight.JuliaLanguage
import dev.juliainsight.lexer.JuliaChars
import dev.juliainsight.lexer.JuliaTokenTypes as T
import dev.juliainsight.settings.JuliaSettings

class JuliaCommenter : CodeDocumentationAwareCommenter {
    override fun getLineCommentPrefix(): String = "#"
    override fun getBlockCommentPrefix(): String = "#="
    override fun getBlockCommentSuffix(): String = "=#"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
    override fun getLineCommentTokenType(): IElementType = T.LINE_COMMENT
    override fun getBlockCommentTokenType(): IElementType = T.BLOCK_COMMENT
    override fun getDocumentationCommentTokenType(): IElementType? = null
    override fun getDocumentationCommentPrefix(): String? = null
    override fun getDocumentationCommentLinePrefix(): String? = null
    override fun getDocumentationCommentSuffix(): String? = null
    override fun isDocumentationComment(element: PsiComment?): Boolean = false
}

class JuliaBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = PAIRS
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean =
        contextType == null || contextType in T.WHITESPACES || contextType in T.COMMENTS || contextType in T.CLOSERS ||
            contextType == T.COMMA || contextType == T.SEMICOLON
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset

    companion object {
        private val PAIRS = arrayOf(
            BracePair(T.LPAR, T.RPAR, false),
            BracePair(T.LBRACKET, T.RBRACKET, false),
            BracePair(T.LBRACE, T.RBRACE, true),
        )
    }
}

/** Auto-closes `"`, `"""` and backticks, and steps over closing quotes. */
class JuliaQuoteHandler : MultiCharQuoteHandler {
    private val openers = setOf(T.STRING_START, T.CMD_START)
    private val closers = setOf(T.STRING_END, T.CMD_END)

    override fun isClosingQuote(iterator: HighlighterIterator, offset: Int): Boolean {
        val t = iterator.tokenType
        if (t !in closers) return false
        val start = iterator.start
        val end = iterator.end
        val text = iterator.document.charsSequence
        // only the quote characters themselves (not string-macro suffix flags)
        var qEnd = start
        while (qEnd < end && (text[qEnd] == '"' || text[qEnd] == '`')) qEnd++
        return offset in start until qEnd && (qEnd - start == 1 || offset == start)
    }

    override fun isOpeningQuote(iterator: HighlighterIterator, offset: Int): Boolean =
        iterator.tokenType in openers && offset == iterator.start

    override fun hasNonClosedLiteral(editor: Editor, iterator: HighlighterIterator, offset: Int): Boolean {
        val start = iterator.start
        val doc = editor.document
        val lineEnd = doc.getLineEndOffset(doc.getLineNumber(start))
        val text = doc.charsSequence
        val triple = iterator.end - iterator.start == 3
        var steps = 0
        try {
            iterator.advance()
            steps++
            while (!iterator.atEnd()) {
                val t = iterator.tokenType
                if (t in closers) return false
                if (!triple && t in T.STRING_INNER) {
                    for (i in iterator.start until minOf(iterator.end, text.length)) if (text[i] == '\n') return true
                }
                if (!triple && iterator.start > lineEnd) return true
                if (t !in T.STRING_PARTS && t != T.LPAR && t != T.RPAR && iterator.start > lineEnd) return true
                iterator.advance()
                steps++
            }
            return true
        } finally {
            while (steps-- > 0) iterator.retreat()
        }
    }

    override fun isInsideLiteral(iterator: HighlighterIterator): Boolean = iterator.tokenType in T.STRING_INNER

    override fun getClosingQuote(iterator: HighlighterIterator, offset: Int): CharSequence? {
        val start = iterator.start
        val len = iterator.end - start
        return if (iterator.tokenType == T.STRING_START && len == 3 && offset == start + 3) "\"\"\"" else null
    }
}

// ---- indentation ------------------------------------------------------------------------------------------------------

internal fun indentUnit(project: Project, document: Document): String {
    val psi = com.intellij.psi.PsiDocumentManager.getInstance(project).getPsiFile(document)
    val options = if (psi != null) CodeStyle.getIndentOptions(psi) else CodeStyle.getSettings(project).getIndentOptions(null)
    return if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE.coerceAtLeast(1))
}

internal fun lineText(document: Document, line: Int): CharSequence =
    document.charsSequence.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line))

/** Indentation of the line that opened the block a dedent word (`end`, `else`, …) on [line] belongs to. */
internal fun openerIndent(document: Document, line: Int): String? {
    var balance = 0
    var l = line - 1
    var scanned = 0
    while (l >= 0 && scanned < 5000) {
        val text = lineText(document, l)
        if (!JuliaBlockScanner.isBlank(text)) {
            val info = JuliaBlockScanner.analyze(text)
            balance += info.blockDelta
            if (balance > 0) return JuliaBlockScanner.leadingWhitespace(text)
        }
        l--
        scanned++
    }
    return null
}

/** Computes the indentation of a fresh line (used by Enter and by "auto-indent lines" without a formatter). */
class JuliaLineIndentProvider : LineIndentProvider {
    override fun isSuitableFor(language: Language?): Boolean = language == JuliaLanguage

    override fun getLineIndent(project: Project, editor: Editor, language: Language?, offset: Int): String? {
        val document = editor.document
        val line = document.getLineNumber(offset)
        val unit = indentUnit(project, document)
        val rest = document.charsSequence.subSequence(offset, document.getLineEndOffset(line)).toString().trimStart()
        val firstWord = rest.takeWhile { JuliaChars.isIdChar(it.code) }
        if (firstWord in JuliaBlockScanner.DEDENT_WORDS) {
            return openerIndent(document, line) ?: ""
        }
        // previous non-blank line
        var p = line - 1
        while (p >= 0 && JuliaBlockScanner.isBlank(lineText(document, p))) p--
        if (p < 0) return ""
        var prevText = lineText(document, p)
        var info = JuliaBlockScanner.analyze(prevText)
        // if the previous line closes brackets opened earlier, use the line where the statement started
        if (info.bracketDelta < 0) {
            var balance = info.bracketDelta
            var q = p - 1
            while (q >= 0 && balance < 0 && p - q < 500) {
                val t = lineText(document, q)
                balance += JuliaBlockScanner.analyze(t).bracketDelta
                if (balance >= 0) {
                    prevText = t
                    info = JuliaBlockScanner.analyze(t).copy(bracketDelta = 0)
                    break
                }
                q--
            }
        }
        var indent = JuliaBlockScanner.leadingWhitespace(prevText)
        val opens = info.opensBlock || info.startsWithMidKeyword || info.bracketDelta > 0
        if (opens) indent += unit
        if (rest.startsWith(")") || rest.startsWith("]") || rest.startsWith("}")) {
            indent = indent.removeSuffix(unit)
        }
        return indent
    }
}

/** Re-aligns `end`, `else`, `elseif`, `catch` and `finally` with their block when the word is typed. */
class JuliaDedentTypedHandler : TypedHandlerDelegate() {
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file.language != JuliaLanguage || !c.isLetter()) return Result.CONTINUE
        val document = editor.document
        val caret = editor.caretModel.offset
        val line = document.getLineNumber(caret)
        val lineStart = document.getLineStartOffset(line)
        val before = document.charsSequence.subSequence(lineStart, caret).toString()
        val word = before.trimStart()
        if (word !in JuliaBlockScanner.DEDENT_WORDS) return Result.CONTINUE
        val lineEnd = document.getLineEndOffset(line)
        if (caret < lineEnd && JuliaChars.isIdChar(document.charsSequence[caret].code)) return Result.CONTINUE
        if (isInStringOrComment(editor, caret - 1)) return Result.CONTINUE
        val target = openerIndent(document, line) ?: return Result.CONTINUE
        val current = JuliaBlockScanner.leadingWhitespace(before)
        if (current != target) {
            document.replaceString(lineStart, lineStart + current.length, target)
            editor.caretModel.moveToOffset(lineStart + target.length + word.length)
        }
        return Result.CONTINUE
    }
}

internal fun isInStringOrComment(editor: Editor, offset: Int): Boolean {
    if (offset < 0 || editor !is EditorEx) return false
    val it = editor.highlighter.createIterator(offset)
    if (it.atEnd()) return false
    val t = it.tokenType
    return t in T.COMMENTS || t in T.STRING_INNER || t == T.STRING_START && offset > it.start
}

/** After Enter on a line that opens a block, inserts the matching `end` (unless the block is already closed). */
class JuliaAutoEndEnterHandler : EnterHandlerDelegateAdapter() {
    override fun postProcessEnter(file: PsiFile, editor: Editor, dataContext: DataContext): EnterHandlerDelegate.Result {
        if (file.language != JuliaLanguage || !JuliaSettings.getInstance().state.autoInsertEnd) return EnterHandlerDelegate.Result.Continue
        val document = editor.document
        val caret = editor.caretModel.offset
        val line = document.getLineNumber(caret)
        if (line == 0) return EnterHandlerDelegate.Result.Continue
        val current = lineText(document, line)
        if (!JuliaBlockScanner.isBlank(current)) return EnterHandlerDelegate.Result.Continue // Enter in the middle of a line
        val prevLine = lineText(document, line - 1)
        if (isInStringOrComment(editor, document.getLineEndOffset(line - 1) - 1)) return EnterHandlerDelegate.Result.Continue
        val info = JuliaBlockScanner.analyze(prevLine)
        if (!info.opensBlock || info.blockDelta <= 0) return EnterHandlerDelegate.Result.Continue
        val openerIndent = JuliaBlockScanner.leadingWhitespace(prevLine)
        // is the block already closed? look at the next non-blank line
        var n = line + 1
        while (n < document.lineCount && JuliaBlockScanner.isBlank(lineText(document, n))) n++
        if (n < document.lineCount) {
            val next = lineText(document, n)
            val nextIndent = JuliaBlockScanner.leadingWhitespace(next)
            if (nextIndent.length > openerIndent.length) return EnterHandlerDelegate.Result.Continue
            val word = next.trimStart().takeWhile { JuliaChars.isIdChar(it.code) }.toString()
            if (nextIndent.length == openerIndent.length && word in JuliaBlockScanner.DEDENT_WORDS) {
                return EnterHandlerDelegate.Result.Continue
            }
        }
        val insertAt = document.getLineEndOffset(line)
        document.insertString(insertAt, "\n" + openerIndent + "end")
        return EnterHandlerDelegate.Result.Continue
    }
}
