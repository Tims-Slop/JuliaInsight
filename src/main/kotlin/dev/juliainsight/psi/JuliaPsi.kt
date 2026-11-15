package dev.juliainsight.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.icons.AllIcons
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.elementType
import dev.juliainsight.JuliaFileType
import dev.juliainsight.JuliaLanguage
import dev.juliainsight.lexer.JuliaLexer
import dev.juliainsight.lexer.JuliaTokenTypes
import dev.juliainsight.parser.JuliaElementTypes as E
import dev.juliainsight.parser.JuliaParser
import javax.swing.Icon

class JuliaFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, JuliaLanguage) {
    override fun getFileType(): FileType = JuliaFileType
    override fun toString(): String = "Julia File"
}

open class JuliaPsiElement(node: ASTNode) : ASTWrapperPsiElement(node)

/** A named definition: function, macro, struct, abstract/primitive type, module or const. */
class JuliaDefinition(node: ASTNode) : JuliaPsiElement(node) {

    val nameElement: PsiElement? get() = findChildByType(E.DEF_NAME)

    /** Full (possibly qualified) name, e.g. `Base.show`. */
    val qualifiedName: String? get() = nameElement?.text

    override fun getName(): String? {
        val n = nameElement ?: return null
        var last: PsiElement? = n.lastChild
        while (last != null && last.elementType != JuliaTokenTypes.IDENTIFIER && last.elementType != JuliaTokenTypes.OPERATOR &&
            last.elementType != JuliaTokenTypes.SYMBOL && last.elementType != JuliaTokenTypes.BUILTIN_CONSTANT
        ) last = last.prevSibling
        return (last?.text ?: n.text).removePrefix(":")
    }

    override fun getTextOffset(): Int = nameElement?.textOffset ?: super.getTextOffset()

    val kind: Kind
        get() = when (node.elementType) {
            E.FUNCTION_DEF, E.SHORT_FUNCTION_DEF -> Kind.FUNCTION
            E.MACRO_DEF -> Kind.MACRO
            E.STRUCT_DEF -> Kind.STRUCT
            E.ABSTRACT_DEF -> Kind.ABSTRACT_TYPE
            E.PRIMITIVE_DEF -> Kind.PRIMITIVE_TYPE
            E.MODULE_DEF -> Kind.MODULE
            else -> Kind.CONST
        }

    /** Human readable one-line signature, e.g. `kinetic(m, v)` or `Point{T} <: AbstractPoint`. */
    val signature: String
        get() {
            val name = qualifiedName ?: return if (kind == Kind.FUNCTION) "(anonymous)" else "?"
            val nameEl = nameElement!!
            val sb = StringBuilder(if (kind == Kind.MACRO) "@$name" else name)
            when (kind) {
                Kind.FUNCTION, Kind.MACRO -> {
                    var e = nameEl.nextSibling
                    while (e != null && e.elementType != E.PAREN_GROUP && e.elementType != E.BRACE_GROUP) {
                        if (e !is com.intellij.psi.PsiWhiteSpace) break
                        e = e.nextSibling
                    }
                    if (e?.elementType == E.BRACE_GROUP) {
                        sb.append(e.text)
                        e = e.nextSibling
                    }
                    if (e?.elementType == E.PAREN_GROUP) sb.append(e.text)
                }
                Kind.STRUCT, Kind.ABSTRACT_TYPE, Kind.PRIMITIVE_TYPE -> {
                    val header = headerLineText()
                    val idx = header.indexOf(name)
                    if (idx >= 0) return header.substring(idx).substringBefore('#').trim().removeSuffix("end").trim()
                }
                else -> {}
            }
            return sb.toString().replace(Regex("\\s+"), " ")
        }

    private fun headerLineText(): String {
        val t = text
        val nl = t.indexOf('\n')
        return if (nl < 0) t else t.substring(0, nl)
    }

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = signature.let { if (it.length > 120) it.take(117) + "…" else it }
        override fun getLocationString(): String? = null
        override fun getIcon(unused: Boolean): Icon = kind.icon
    }

    enum class Kind(val icon: Icon) {
        FUNCTION(AllIcons.Nodes.Function),
        MACRO(AllIcons.Nodes.Annotationtype),
        STRUCT(AllIcons.Nodes.Class),
        ABSTRACT_TYPE(AllIcons.Nodes.AbstractClass),
        PRIMITIVE_TYPE(AllIcons.Nodes.Type),
        MODULE(AllIcons.Nodes.Package),
        CONST(AllIcons.Nodes.Constant),
    }
}

class JuliaParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = JuliaLexer()
    override fun createParser(project: Project?): PsiParser = JuliaParser()
    override fun getFileNodeType(): IFileElementType = FILE
    override fun getWhitespaceTokens(): TokenSet = JuliaTokenTypes.WHITESPACES
    override fun getCommentTokens(): TokenSet = JuliaTokenTypes.COMMENTS
    override fun getStringLiteralElements(): TokenSet = JuliaTokenTypes.STRING_CONTENTS
    override fun createFile(viewProvider: FileViewProvider): PsiFile = JuliaFile(viewProvider)
    override fun createElement(node: ASTNode): PsiElement =
        if (node.elementType in E.DEFINITIONS) JuliaDefinition(node) else JuliaPsiElement(node)

    companion object {
        @JvmField val FILE = IFileElementType(JuliaLanguage)
    }
}
