package dev.juliainsight.repl

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.elementType
import dev.juliainsight.isJuliaFile
import dev.juliainsight.parser.JuliaElementTypes as E
import dev.juliainsight.psi.JuliaDefinition
import dev.juliainsight.psi.JuliaFile

private fun AnActionEvent.juliaEditor(): Editor? {
    val editor = getData(CommonDataKeys.EDITOR) ?: return null
    val file = FileDocumentManager.getInstance().getFile(editor.document)
    return if (file.isJuliaFile()) editor else null
}

internal fun juliaString(s: String): String = "\"" + s.replace("\\", "/").replace("\"", "\\\"").replace("$", "\\$") + "\""

/** Finds the top-level statement (function, block, line …) around [offset], expanded to whole lines. */
object JuliaStatementFinder {
    fun statementRange(file: PsiFile, document: Document, offset: Int): TextRange? {
        var line = document.getLineNumber(offset)
        val text = document.charsSequence
        fun lineBlank(l: Int): Boolean {
            val s = text.subSequence(document.getLineStartOffset(l), document.getLineEndOffset(l)).toString().trim()
            return s.isEmpty() || (s.startsWith("#") && !s.startsWith("#="))
        }
        if (lineBlank(line)) return null
        val ls = document.getLineStartOffset(line)
        var first = ls
        while (first < text.length && (text[first] == ' ' || text[first] == '\t')) first++
        var el: PsiElement = file.findElementAt(first) ?: return null
        // climb to a direct child of the file or of a module body
        while (true) {
            val p = el.parent ?: break
            if (p is JuliaFile) break
            if (p is JuliaDefinition && p.kind == JuliaDefinition.Kind.MODULE) break
            el = p
        }
        if (el is JuliaDefinition && el.kind == JuliaDefinition.Kind.MODULE && el.textRange.startOffset < ls) {
            // caret on the `end` line of a module: send just that line
            return TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))
        }
        var start = el.textRange.startOffset
        var end = el.textRange.endOffset
        // include siblings sharing the first/last lines (e.g. `x = f(1)` is several PSI siblings)
        var changed = true
        while (changed) {
            changed = false
            val rs = document.getLineStartOffset(document.getLineNumber(start))
            val re = document.getLineEndOffset(document.getLineNumber(maxOf(start, end - 1)))
            var s = el.parent?.firstChild
            while (s != null) {
                val r = s.textRange
                if (s !is PsiWhiteSpace && r.endOffset > rs && r.startOffset < re && s.elementType != E.DEF_NAME) {
                    if (s is JuliaDefinition && s.kind == JuliaDefinition.Kind.MODULE && r.startOffset < rs) { s = s.nextSibling; continue }
                    if (r.startOffset < start) { start = r.startOffset; changed = true }
                    if (r.endOffset > end) { end = r.endOffset; changed = true }
                }
                s = s.nextSibling
            }
        }
        // include a docstring directly above a definition
        if (el is JuliaDefinition) {
            var p = el.prevSibling
            if (p is PsiWhiteSpace && p.text.count { it == '\n' } == 1) p = p.prevSibling
            if (p?.elementType == E.STRING_LITERAL) start = minOf(start, p!!.textRange.startOffset)
        }
        line = document.getLineNumber(start)
        return TextRange(document.getLineStartOffset(line), document.getLineEndOffset(document.getLineNumber(maxOf(start, end - 1))))
    }

    fun nextCodeLineOffset(document: Document, afterOffset: Int): Int {
        var line = document.getLineNumber(afterOffset) + 1
        val text = document.charsSequence
        while (line < document.lineCount) {
            val s = text.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString().trim()
            if (s.isNotEmpty() && !s.startsWith("#")) {
                var o = document.getLineStartOffset(line)
                while (o < text.length && (text[o] == ' ' || text[o] == '\t')) o++
                return o
            }
            line++
        }
        return document.getLineEndOffset(document.getLineNumber(afterOffset))
    }
}

/** Ctrl+Enter: run the selection, or the current statement/block, in the REPL and move to the next one. */
class JuliaSendToReplAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.juliaEditor() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.juliaEditor() ?: return
        val document = editor.document
        val sel = editor.selectionModel
        if (sel.hasSelection()) {
            JuliaReplService.getInstance(project).execute(sel.selectedText ?: return)
            return
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val file = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return
        val offset = editor.caretModel.offset
        val range = JuliaStatementFinder.statementRange(file, document, offset)
        if (range != null) {
            JuliaReplService.getInstance(project).execute(document.getText(range))
            flash(editor, range)
        }
        val next = JuliaStatementFinder.nextCodeLineOffset(document, range?.endOffset ?: offset)
        editor.caretModel.moveToOffset(next)
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
    }

    private fun flash(editor: Editor, range: TextRange) {
        val attrs = com.intellij.openapi.editor.markup.TextAttributes().apply {
            backgroundColor = com.intellij.ui.JBColor(0xE4F2E4, 0x2F3F2F)
        }
        val h = editor.markupModel.addRangeHighlighter(range.startOffset, range.endOffset,
            com.intellij.openapi.editor.markup.HighlighterLayer.SELECTION - 1, attrs,
            com.intellij.openapi.editor.markup.HighlighterTargetArea.LINES_IN_RANGE)
        com.intellij.util.concurrency.EdtExecutorService.getScheduledExecutorInstance().schedule({
            if (!editor.isDisposed) editor.markupModel.removeHighlighter(h)
        }, 350, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
}

/** Prefer "Send to REPL" over the editor's own Ctrl+Enter actions inside Julia files. */
class JuliaReplActionPromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
        val editor = CommonDataKeys.EDITOR.getData(context) ?: return emptyList()
        if (!FileDocumentManager.getInstance().getFile(editor.document).isJuliaFile()) return emptyList()
        return actions.filter { it is JuliaSendToReplAction || it is JuliaRunFileInReplAction }
    }
}

class JuliaRunFileInReplAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.VIRTUAL_FILE).isJuliaFile()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        JuliaReplService.getInstance(project).execute("include(${juliaString(file.path)})", activate = true)
    }
}

class JuliaCdToFileDirAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { it.isDirectory || it.isJuliaFile() } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val dir = if (file.isDirectory) file else file.parent ?: return
        JuliaReplService.getInstance(project).execute("cd(${juliaString(dir.path)}); pwd()")
    }
}

class JuliaStartReplAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repl = JuliaReplService.getInstance(project)
        if (!repl.isRunning) repl.start(focus = true)
        repl.showToolWindow(activate = true)
    }
}

class JuliaRestartReplAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        JuliaReplService.getInstance(e.project ?: return).restart()
    }
}

class JuliaInterruptReplAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { JuliaReplService.getInstance(it).isRunning } == true
    }
    override fun actionPerformed(e: AnActionEvent) {
        JuliaReplService.getInstance(e.project ?: return).interrupt()
    }
}

class JuliaRunTestsInReplAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        JuliaReplService.getInstance(e.project ?: return).execute("import Pkg; Pkg.test()", activate = true)
    }
}
