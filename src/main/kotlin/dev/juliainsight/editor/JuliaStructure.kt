package dev.juliainsight.editor

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.folding.CustomFoldingBuilder
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.elementType
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import dev.juliainsight.JuliaLanguage
import dev.juliainsight.lexer.JuliaTokenTypes as T
import dev.juliainsight.parser.JuliaElementTypes as E
import dev.juliainsight.psi.JuliaDefinition
import dev.juliainsight.psi.JuliaFile
import javax.swing.Icon

// ---- folding ----------------------------------------------------------------------------------------------------------

class JuliaFoldingBuilder : CustomFoldingBuilder(), DumbAware {

    override fun buildLanguageFoldRegions(descriptors: MutableList<FoldingDescriptor>, root: PsiElement, document: Document, quick: Boolean) {
        collect(root.node, document, descriptors)
    }

    private fun collect(node: ASTNode, document: Document, out: MutableList<FoldingDescriptor>) {
        val type = node.elementType
        val range = node.textRange
        val multiline = range.length > 0 && document.getLineNumber(range.startOffset) != document.getLineNumber(range.endOffset)
        if (multiline) {
            when {
                type in E.KEYWORD_BLOCKS && node.lastChildNode?.elementType == T.END_KW -> {
                    val firstLineEnd = document.getLineEndOffset(document.getLineNumber(range.startOffset))
                    var foldStart = firstLineEnd
                    // don't fold away a trailing comment on the header line
                    val header = document.charsSequence.subSequence(range.startOffset, firstLineEnd).toString()
                    val hash = header.indexOf(" #")
                    if (hash > 0) foldStart = range.startOffset + hash
                    while (foldStart > range.startOffset && document.charsSequence[foldStart - 1].isWhitespace()) foldStart--
                    if (foldStart < range.endOffset - 3) {
                        out += FoldingDescriptor(node, TextRange(foldStart, range.endOffset), null, " … end")
                    }
                }
                type in E.GROUPS && type != E.INTERPOLATION && range.length > 2 -> {
                    out += FoldingDescriptor(node, TextRange(range.startOffset + 1, range.endOffset - 1), null, "…")
                }
                type == E.STRING_LITERAL -> {
                    val text = node.text
                    val quote = if (text.contains("\"\"\"")) "\"\"\"" else "\""
                    val firstContent = text.substringAfter(quote).lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && it != quote } ?: ""
                    val prefix = text.substringBefore(quote)
                    out += FoldingDescriptor(node, range, null, "$prefix$quote${firstContent.take(60)} …$quote")
                }
                type == T.BLOCK_COMMENT -> out += FoldingDescriptor(node, range, null, "#= … =#")
            }
        }
        if (type == T.LINE_COMMENT) {
            collectCommentRun(node, document, out)
        }
        var child = node.firstChildNode
        while (child != null) {
            collect(child, document, out)
            child = child.treeNext
        }
    }

    /** Folds runs of 3+ consecutive line comments (e.g. license headers) starting at [first]. */
    private fun collectCommentRun(first: ASTNode, document: Document, out: MutableList<FoldingDescriptor>) {
        val line = document.getLineNumber(first.startOffset)
        val lineStart = document.getLineStartOffset(line)
        if (document.charsSequence.subSequence(lineStart, first.startOffset).isNotBlank()) return // trailing comment
        var p = first.treePrev
        while (p != null && (p.elementType == T.NEWLINE || p.elementType == T.WHITE_SPACE)) p = p.treePrev
        if (p != null && p.elementType == T.LINE_COMMENT && document.getLineNumber(p.startOffset) == line - 1) return // not the first
        var last = first
        var count = 1
        var n = first.treeNext
        while (n != null) {
            if (n.elementType == T.NEWLINE || (n.elementType == T.WHITE_SPACE && !n.text.contains('\n'))) {
                n = n.treeNext
                continue
            }
            if (n.elementType == T.LINE_COMMENT && document.getLineNumber(n.startOffset) == document.getLineNumber(last.startOffset) + 1) {
                last = n
                count++
                n = n.treeNext
                continue
            }
            break
        }
        if (count >= 3 && !isCustomRegionComment(first)) {
            val firstText = first.text.trimEnd()
            out += FoldingDescriptor(first, TextRange(first.startOffset, last.textRange.endOffset), null, "$firstText …")
        }
    }

    private fun isCustomRegionComment(node: ASTNode): Boolean {
        val t = node.text.removePrefix("#").trim()
        return t.startsWith("region") || t.startsWith("endregion")
    }

    override fun getLanguagePlaceholderText(node: ASTNode, range: TextRange): String = "…"
    override fun isRegionCollapsedByDefault(node: ASTNode): Boolean = false
}

// ---- structure view -------------------------------------------------------------------------------------------------

class JuliaStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile !is JuliaFile) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel = JuliaStructureViewModel(psiFile, editor)
            override fun isRootNodeShown(): Boolean = false
        }
    }
}

class JuliaStructureViewModel(file: PsiFile, editor: Editor?) :
    StructureViewModelBase(file, editor, JuliaStructureElement(file)), StructureViewModel.ElementInfoProvider {
    init {
        withSuitableClasses(JuliaDefinition::class.java)
        withSorters(Sorter.ALPHA_SORTER)
    }

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement?): Boolean = false
    override fun isAlwaysLeaf(element: StructureViewTreeElement?): Boolean {
        val v = element?.value
        return v is JuliaDefinition && v.kind != JuliaDefinition.Kind.MODULE && v.kind != JuliaDefinition.Kind.STRUCT
    }
}

class JuliaStructureElement(private val element: PsiElement) : StructureViewTreeElement, SortableTreeElement {
    override fun getValue(): Any = element
    override fun navigate(requestFocus: Boolean) {
        (element as? Navigatable)?.navigate(requestFocus)
    }
    override fun canNavigate(): Boolean = (element as? Navigatable)?.canNavigate() == true
    override fun canNavigateToSource(): Boolean = (element as? Navigatable)?.canNavigateToSource() == true
    override fun getAlphaSortKey(): String = (element as? JuliaDefinition)?.name ?: ""

    override fun getPresentation(): ItemPresentation = when (element) {
        is JuliaDefinition -> element.presentation
        is JuliaFile -> element.presentation ?: object : ItemPresentation {
            override fun getPresentableText() = element.name
            override fun getIcon(unused: Boolean): Icon? = element.getIcon(0)
        }
        else -> fieldPresentation(element)
    }

    override fun getChildren(): Array<TreeElement> {
        val result = ArrayList<TreeElement>()
        when {
            element is JuliaFile -> collectDefinitions(element, result)
            element is JuliaDefinition && element.kind == JuliaDefinition.Kind.MODULE -> collectDefinitions(element, result, skipName = true)
            element is JuliaDefinition && element.kind == JuliaDefinition.Kind.STRUCT -> collectFields(element, result)
        }
        return result.toTypedArray()
    }

    private fun collectDefinitions(parent: PsiElement, out: MutableList<TreeElement>, skipName: Boolean = false) {
        var child = parent.firstChild
        while (child != null) {
            if (child is JuliaDefinition) {
                out += JuliaStructureElement(child)
            } else if (!(skipName && child.elementType == E.DEF_NAME) && child.firstChild != null && child.elementType !in E.GROUPS &&
                child.elementType != E.STRING_LITERAL
            ) {
                collectDefinitions(child, out) // e.g. definitions inside `@static if … end` or `begin … end`
            }
            child = child.nextSibling
        }
    }

    private fun collectFields(struct: JuliaDefinition, out: MutableList<TreeElement>) {
        var child = struct.firstChild
        var atLineStart = false
        while (child != null) {
            when {
                child is PsiWhiteSpace -> if (child.textContains('\n')) atLineStart = true
                child.elementType == T.IDENTIFIER && atLineStart -> {
                    out += JuliaStructureElement(child)
                    atLineStart = false
                }
                child.elementType == T.LINE_COMMENT -> {}
                else -> atLineStart = false
            }
            child = child.nextSibling
        }
    }

    private fun fieldPresentation(e: PsiElement): ItemPresentation {
        // `name::Type` up to the end of the line (without default value / comment)
        val sb = StringBuilder(e.text)
        var n = e.nextSibling
        while (n != null && !(n is PsiWhiteSpace && n.textContains('\n'))) {
            if (n.elementType == T.EQ || n.elementType == T.LINE_COMMENT) break
            sb.append(n.text)
            n = n.nextSibling
        }
        val text = sb.toString().trim().replace(Regex("\\s+"), " ")
        return object : ItemPresentation {
            override fun getPresentableText() = text
            override fun getIcon(unused: Boolean): Icon = AllIcons.Nodes.Field
        }
    }
}

// ---- breadcrumbs ------------------------------------------------------------------------------------------------------

class JuliaBreadcrumbsProvider : BreadcrumbsProvider {
    override fun getLanguages(): Array<Language> = arrayOf(JuliaLanguage)
    override fun acceptElement(element: PsiElement): Boolean = element is JuliaDefinition && element.kind != JuliaDefinition.Kind.CONST
    override fun getElementInfo(element: PsiElement): String {
        val d = element as JuliaDefinition
        return when (d.kind) {
            JuliaDefinition.Kind.MODULE -> "module ${d.name}"
            JuliaDefinition.Kind.MACRO -> "@${d.name}"
            JuliaDefinition.Kind.STRUCT -> "struct ${d.name}"
            else -> d.name ?: "function"
        }
    }
    override fun getElementIcon(element: PsiElement): Icon? = (element as? JuliaDefinition)?.kind?.icon
    override fun getElementTooltip(element: PsiElement): String? = (element as? JuliaDefinition)?.signature
}

internal val PsiElement.isJuliaBlock: Boolean get() = elementType in E.KEYWORD_BLOCKS
