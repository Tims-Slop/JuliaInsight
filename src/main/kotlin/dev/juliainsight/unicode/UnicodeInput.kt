package dev.juliainsight.unicode

import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.editorActions.BackspaceHandlerDelegate
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.codeInsight.lookup.CharFilter
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.actionSystem.EditorWriteActionHandler
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Ref
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ThreeState
import dev.juliainsight.JuliaLanguage
import dev.juliainsight.isJuliaFile
import dev.juliainsight.lexer.JuliaChars
import dev.juliainsight.lexer.JuliaTokenTypes as T
import dev.juliainsight.settings.JuliaSettings
import dev.juliainsight.settings.ReplaceMode
import java.awt.Component

// ---- shared helpers -------------------------------------------------------------------------------------------------

/** Context of the caret for auto-replacement, or `null` where replacement must never happen (raw/macro strings such as `L"…"`). */
internal fun inputContextAt(editor: Editor, offset: Int): InputContext? {
    if (offset <= 0 || editor !is EditorEx) return InputContext.CODE
    val it = editor.highlighter.createIterator(offset - 1)
    if (it.atEnd()) return InputContext.CODE
    val t = it.tokenType
    return when {
        t in T.COMMENTS -> InputContext.COMMENT
        t == T.STRING_END || t == T.CMD_END -> if (offset >= it.end) InputContext.CODE else InputContext.STRING
        t == T.STRING_START || t == T.CMD_START || t in T.STRING_INNER || t == T.INTERP_VAR -> {
            var steps = 0
            while (!it.atEnd() && it.tokenType != T.STRING_START && it.tokenType != T.CMD_START && steps < 10_000) {
                it.retreat(); steps++
            }
            if (it.atEnd()) return InputContext.STRING
            it.retreat()
            if (!it.atEnd() && it.tokenType == T.STRING_MACRO_PREFIX) null else InputContext.STRING
        }
        else -> InputContext.CODE
    }
}

internal fun JuliaSettings.State.unicodeOptions() = UnicodeOptions(
    latex = latexAutoReplace,
    eager = replaceMode == ReplaceMode.WHILE_TYPING,
    subscriptDigits = subscriptDigits,
    subscriptLetters = subscriptLetters,
    maxSubscriptBase = maxSubscriptBase,
)

internal fun JuliaSettings.State.allows(context: InputContext?): Boolean = when (context) {
    null -> false
    InputContext.CODE -> true
    InputContext.COMMENT -> unicodeInComments
    InputContext.STRING -> unicodeInStrings
}

/** Space / Return in the default mode: converts the word before [offset]. Returns the new caret offset, or -1 if nothing changed. */
internal fun applyCommit(editor: Editor, offset: Int, s: JuliaSettings.State): Int {
    if (!s.latexAutoReplace && !s.subscriptDigits && !s.subscriptLetters) return -1
    val edit = UnicodeInputEngine.commitWord(editor.document.charsSequence, offset, s.unicodeOptions()) { i ->
        inputContextAt(editor, i + 1)?.takeIf { s.allows(it) }
    } ?: return -1
    editor.document.replaceString(edit.start, edit.end, edit.text)
    val caret = edit.start + edit.text.length
    editor.caretModel.moveToOffset(caret)
    return caret
}

internal fun isJuliaEditor(editor: Editor): Boolean = FileDocumentManager.getInstance().getFile(editor.document).isJuliaFile()

/** Remembers the last automatic replacement in an editor so Backspace can undo it and a tentative subscript can be reverted. */
internal class UnicodeInputState {
    data class Record(
        val document: Document,
        val start: Int,
        val end: Int,
        val original: String,
        val stamp: Long,
        val isSubscript: Boolean,
        val hasLetter: Boolean,
    )

    private var record: Record? = null
    private var suppressedStart = -1
    private var suppressedStamp = -1L
    var revertOnDelete: Record? = null

    fun replacementEndingAt(document: Document, offset: Int): Record? =
        record?.takeIf { it.document == document && it.stamp == document.modificationStamp && it.end == offset }

    fun record(document: Document, start: Int, end: Int, original: String, isSubscript: Boolean, hasLetter: Boolean) {
        record = Record(document, start, end, original, document.modificationStamp, isSubscript, hasLetter)
    }

    fun clear() {
        record = null
    }

    fun suppress(document: Document, start: Int) {
        suppressedStart = start
        suppressedStamp = document.modificationStamp
    }

    fun isSuppressed(document: Document, start: Int): Boolean = start == suppressedStart && document.modificationStamp == suppressedStamp

    companion object {
        private val KEY = Key.create<UnicodeInputState>("julia.insight.unicode.state")
        fun of(editor: Editor): UnicodeInputState = editor.getUserData(KEY) ?: UnicodeInputState().also { editor.putUserData(KEY, it) }
    }
}

// ---- typing -----------------------------------------------------------------------------------------------------------

/** `\theta` → `θ`, `v_0` → `v₀` while typing. */
class JuliaUnicodeTypedHandler : TypedHandlerDelegate() {

    override fun beforeCharTyped(c: Char, project: Project, editor: Editor, file: PsiFile, fileType: FileType): Result {
        if (file.language != JuliaLanguage) return Result.CONTINUE
        val s = JuliaSettings.getInstance().state
        if (!s.latexAutoReplace && !s.subscriptDigits && !s.subscriptLetters) return Result.CONTINUE
        if (editor.caretModel.caretCount != 1 || editor.selectionModel.hasSelection()) return Result.CONTINUE
        if (LookupManager.getActiveLookup(editor) != null) return Result.CONTINUE
        if (s.replaceMode == ReplaceMode.ON_SPACE_OR_ENTER) {
            if (c == ' ') applyCommit(editor, editor.caretModel.offset, s)
            return Result.CONTINUE
        }
        val document = editor.document
        val offset = editor.caretModel.offset
        val context = inputContextAt(editor, offset)
        if (!s.allows(context)) return Result.CONTINUE
        val state = UnicodeInputState.of(editor)

        // A tentative subscript is undone when the identifier goes on: `x_2d` stays `x_2d`.
        val pending = state.replacementEndingAt(document, offset)?.takeIf { it.isSubscript }
        if (pending != null && JuliaChars.isIdChar(c.code)) {
            val continuesDigits = c in '0'..'9' && !pending.hasLetter && s.subscriptDigits
            if (!continuesDigits) {
                document.replaceString(pending.start, pending.end, pending.original)
                editor.caretModel.moveToOffset(pending.start + pending.original.length)
                state.clear()
                return Result.CONTINUE
            }
        }

        val text = document.charsSequence
        if (s.latexAutoReplace) {
            val edit = UnicodeInputEngine.onLatexTyped(text, offset, c, context!!, eager = true)
            if (edit != null && !state.isSuppressed(document, edit.start)) {
                document.replaceString(edit.start, edit.end, edit.text)
                editor.caretModel.moveToOffset(edit.start + edit.text.length)
                if (edit is UnicodeEdit.Consume) {
                    state.record(document, edit.start, edit.start + edit.text.length, edit.original, isSubscript = false, hasLetter = false)
                    return Result.STOP
                }
                state.clear()
                return Result.CONTINUE
            }
        }

        if (s.subscriptDigits || s.subscriptLetters) {
            val edit = UnicodeInputEngine.onSubscriptTyped(text, offset, c, s.unicodeOptions())
            if (edit != null) {
                document.replaceString(edit.start, edit.end, edit.text)
                val end = edit.start + edit.text.length
                editor.caretModel.moveToOffset(end)
                if (pending != null && pending.end == edit.start) {
                    state.record(document, pending.start, end, pending.original + edit.original, true, pending.hasLetter || c.isLetter())
                } else {
                    state.record(document, edit.start, end, edit.original, true, c.isLetter())
                }
                return Result.STOP
            }
        }
        if (pending != null) state.clear()
        return Result.CONTINUE
    }
}

/** Backspace right after an automatic replacement restores what was typed (`θ` → `\theta`, `v₀` → `v_0`). */
class JuliaUnicodeBackspaceHandler : BackspaceHandlerDelegate() {
    override fun beforeCharDeleted(c: Char, file: PsiFile, editor: Editor) {
        if (file.language != JuliaLanguage) return
        val state = UnicodeInputState.of(editor)
        state.revertOnDelete = if (editor.caretModel.caretCount == 1 && !editor.selectionModel.hasSelection())
            state.replacementEndingAt(editor.document, editor.caretModel.offset) else null
    }

    override fun charDeleted(c: Char, file: PsiFile, editor: Editor): Boolean {
        if (file.language != JuliaLanguage) return false
        val state = UnicodeInputState.of(editor)
        val rec = state.revertOnDelete ?: return false
        state.revertOnDelete = null
        val caret = editor.caretModel.offset
        if (caret < rec.start) return false
        editor.document.replaceString(rec.start, caret, rec.original)
        editor.caretModel.moveToOffset(rec.start + rec.original.length)
        state.clear()
        if (!rec.isSubscript) state.suppress(editor.document, rec.start)
        return true
    }
}

/** Enter completes a pending `\name` first (`x \in` + Enter → `x ∈`). */
class JuliaLatexEnterHandler : EnterHandlerDelegateAdapter() {
    override fun preprocessEnter(
        file: PsiFile, editor: Editor, caretOffset: Ref<Int>, caretAdvance: Ref<Int>, dataContext: DataContext,
        originalHandler: EditorActionHandler?,
    ): EnterHandlerDelegate.Result {
        if (file.language != JuliaLanguage) return EnterHandlerDelegate.Result.Continue
        val s = JuliaSettings.getInstance().state
        if (editor.caretModel.caretCount != 1) return EnterHandlerDelegate.Result.Continue
        if (s.replaceMode == ReplaceMode.ON_SPACE_OR_ENTER) {
            val caret = applyCommit(editor, caretOffset.get(), s)
            if (caret >= 0) {
                caretOffset.set(caret)
                PsiDocumentManager.getInstance(file.project).commitDocument(editor.document)
            }
            return EnterHandlerDelegate.Result.Continue
        }
        if (!s.latexAutoReplace) return EnterHandlerDelegate.Result.Continue
        val offset = caretOffset.get()
        val context = inputContextAt(editor, offset)
        if (!s.allows(context)) return EnterHandlerDelegate.Result.Continue
        val document = editor.document
        val state = UnicodeInputState.of(editor)
        val edit = UnicodeInputEngine.completeBefore(document.charsSequence, offset, context!!) ?: return EnterHandlerDelegate.Result.Continue
        if (state.isSuppressed(document, edit.start)) return EnterHandlerDelegate.Result.Continue
        document.replaceString(edit.start, edit.end, edit.text)
        val newOffset = edit.start + edit.text.length
        editor.caretModel.moveToOffset(newOffset)
        caretOffset.set(newOffset)
        PsiDocumentManager.getInstance(file.project).commitDocument(document)
        return EnterHandlerDelegate.Result.Continue
    }
}

/** Tab completes `\name` like the Julia REPL does. */
class JuliaLatexTabHandler(private val original: EditorActionHandler) : EditorWriteActionHandler() {
    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        original.isEnabled(editor, caret, dataContext)

    override fun executeWriteAction(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        val project = editor.project
        if (project != null && editor.caretModel.caretCount == 1 && !editor.selectionModel.hasSelection() && isJuliaEditor(editor)) {
            val document = editor.document
            when (val r = UnicodeInputEngine.onTab(document.charsSequence, editor.caretModel.offset)) {
                is UnicodeInputEngine.TabResult.Replace -> {
                    document.replaceString(r.start, r.end, r.text)
                    editor.caretModel.moveToOffset(r.start + r.text.length)
                    return
                }
                UnicodeInputEngine.TabResult.ShowCompletion -> {
                    ApplicationManager.getApplication().invokeLater {
                        if (!editor.isDisposed) CodeCompletionHandlerBase(CompletionType.BASIC).invokeCompletion(project, editor)
                    }
                    return
                }
                UnicodeInputEngine.TabResult.Nothing -> {}
            }
        }
        original.execute(editor, caret, dataContext)
    }
}

// ---- completion -------------------------------------------------------------------------------------------------------

internal fun displaySymbol(value: String): String =
    if (value.isNotEmpty() && SymbolKind.isCombining(value.codePointAt(0))) "◌$value" else value

/** Completion of `\…` names showing the resulting glyph (Ctrl+Space after a backslash). */
class JuliaLatexCompletionContributor : CompletionContributor(), DumbAware {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val text = parameters.editor.document.charsSequence
        val offset = parameters.offset
        val start = UnicodeInputEngine.latexStart(text, offset)
        if (start < 0) return
        val prefix = text.subSequence(start, offset).toString()
        val rs = result.withPrefixMatcher(PlainPrefixMatcher(prefix, true))
        for ((name, value) in LatexSymbols.withPrefix(prefix)) {
            rs.addElement(
                LookupElementBuilder.create(name)
                    .withTypeText(displaySymbol(value), true)
                    .withInsertHandler { ctx, _ ->
                        ctx.document.replaceString(ctx.startOffset, ctx.tailOffset, value)
                        ctx.editor.caretModel.moveToOffset(ctx.startOffset + value.length)
                    },
            )
        }
        rs.stopHere()
    }
}

/** Keeps `^`, `_`, digits, `(`… in the lookup prefix while completing a `\name`. */
class JuliaLatexCharFilter : CharFilter() {
    override fun acceptChar(c: Char, prefixLength: Int, lookup: Lookup): Result? {
        if (lookup.psiFile?.language != JuliaLanguage) return null
        // The completion popup is usually open while typing `v_`: if this keystroke makes (or undoes) a subscript,
        // close the popup so the typed-character handler gets to convert it.
        val s = JuliaSettings.getInstance().state
        if (s.replaceMode == ReplaceMode.ON_SPACE_OR_ENTER) {
            if (c == ' ') {
                val editor = lookup.editor
                val wouldConvert = UnicodeInputEngine.commitWord(editor.document.charsSequence, editor.caretModel.offset, s.unicodeOptions()) { i ->
                    inputContextAt(editor, i + 1)?.takeIf { s.allows(it) }
                } != null
                if (wouldConvert) return Result.HIDE_LOOKUP
            }
        } else if (s.subscriptDigits || s.subscriptLetters) {
            val editor = lookup.editor
            val offset = editor.caretModel.offset
            val text = editor.document.charsSequence
            val pending = UnicodeInputState.of(editor).replacementEndingAt(editor.document, offset)?.takeIf { it.isSubscript }
            val converts = UnicodeInputEngine.onSubscriptTyped(text, offset, c, s.unicodeOptions()) != null
            if ((converts || (pending != null && JuliaChars.isIdChar(c.code))) && s.allows(inputContextAt(editor, offset))) {
                return Result.HIDE_LOOKUP
            }
        }
        val item = lookup.currentItem ?: return null
        if (!item.lookupString.startsWith("\\")) return null
        return if (LatexSymbols.isNameChar(c)) Result.ADD_TO_PREFIX else null
    }
}

/** No identifier auto-popup while a `\name` is being typed: the automatic replacement handles it. */
class JuliaLatexCompletionConfidence : CompletionConfidence() {
    override fun shouldSkipAutopopup(editor: Editor, contextElement: PsiElement, psiFile: PsiFile, offset: Int): ThreeState =
        if (UnicodeInputEngine.latexStart(editor.document.charsSequence, offset) >= 0) ThreeState.YES else ThreeState.UNSURE
}

// ---- actions ------------------------------------------------------------------------------------------------------------

class ConvertLatexToUnicodeAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null && isJuliaEditor(editor)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val document = editor.document
        val sel = editor.selectionModel
        val start = if (sel.hasSelection()) sel.selectionStart else 0
        val end = if (sel.hasSelection()) sel.selectionEnd else document.textLength
        val original = document.charsSequence.subSequence(start, end)
        val converted = UnicodeInputEngine.convertAll(original)
        if (converted == original.toString()) return
        WriteCommandAction.runWriteCommandAction(project, "Convert LaTeX to Unicode", null, {
            document.replaceString(start, end, converted)
        })
    }
}

// ---- status bar: shows the LaTeX name of the symbol at the caret --------------------------------------------------------

class JuliaUnicodeWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = JuliaUnicodeWidget.ID
    override fun getDisplayName(): String = "Julia Unicode Symbol"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = JuliaUnicodeWidget(project)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class JuliaUnicodeWidget(project: Project) : EditorBasedWidget(project), StatusBarWidget.TextPresentation {
    companion object {
        const val ID = "JuliaInsightUnicodeSymbol"
    }

    private var current: Pair<String, String>? = null

    override fun ID(): String = ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        super.install(statusBar)
        EditorFactory.getInstance().eventMulticaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (isOurEditor(event.editor)) refresh()
            }
        }, this)
        myConnection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = refresh()
        })
    }

    private fun refresh() {
        current = computeSymbol()
        statusBar?.updateWidget(ID)
    }

    private fun computeSymbol(): Pair<String, String>? {
        val editor = getEditor() ?: return null
        if (!isJuliaEditor(editor)) return null
        val text = editor.document.charsSequence
        val offset = editor.caretModel.offset
        fun at(i: Int): Pair<String, String>? {
            if (i < 0 || i >= text.length) return null
            val cp = Character.codePointAt(text, i)
            if (cp < 128) return null
            val s = String(Character.toChars(cp))
            val name = LatexSymbols.latexFor(s) ?: return null
            return s to name
        }
        if (offset > 0) {
            val before = Character.codePointBefore(text, offset)
            at(offset - Character.charCount(before))?.let { return it }
        }
        return at(offset)
    }

    override fun getText(): String = current?.let { (sym, name) -> "${displaySymbol(sym)}  $name" } ?: ""
    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT
    override fun getTooltipText(): String? = current?.let { (sym, name) ->
        val cp = sym.codePointAt(0)
        "U+%04X %s — type %s".format(cp, Character.getName(cp) ?: "", name)
    }
}
