package dev.juliainsight.highlight

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import dev.juliainsight.lexer.JuliaTokenTypes as T
import dev.juliainsight.parser.JuliaElementTypes as E
import dev.juliainsight.psi.JuliaDefinition

/**
 * Lightweight "semantic" highlighting that works without the language server:
 * declarations, calls, types, module names, parameters and docstrings.
 */
class JuliaHighlightingAnnotator : Annotator, DumbAware {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element.elementType) {
            T.IDENTIFIER -> annotateIdentifier(element, holder)
            E.STRING_LITERAL -> if (isDocstring(element)) mark(holder, element, JuliaColors.DOCSTRING)
        }
    }

    private fun mark(holder: AnnotationHolder, element: PsiElement, key: TextAttributesKey) {
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(element).textAttributes(key).create()
    }

    private fun annotateIdentifier(e: PsiElement, holder: AnnotationHolder) {
        val parent = e.parent
        if (parent.elementType == E.DEF_NAME) {
            val def = parent.parent as? JuliaDefinition ?: return
            val isLastName = generateSequence(e.nextSibling) { it.nextSibling }.none {
                it.elementType == T.IDENTIFIER || it.elementType == T.SYMBOL || it.elementType == T.OPERATOR
            }
            if (!isLastName) {
                mark(holder, e, JuliaColors.MODULE_NAME)
                return
            }
            val key = when (def.kind) {
                JuliaDefinition.Kind.FUNCTION, JuliaDefinition.Kind.MACRO -> JuliaColors.FUNCTION_DECLARATION
                JuliaDefinition.Kind.STRUCT, JuliaDefinition.Kind.ABSTRACT_TYPE, JuliaDefinition.Kind.PRIMITIVE_TYPE -> JuliaColors.TYPE
                JuliaDefinition.Kind.MODULE -> JuliaColors.MODULE_NAME
                JuliaDefinition.Kind.CONST -> null
            } ?: return
            mark(holder, e, key)
            return
        }

        val text = e.text
        val next = PsiTreeUtil.nextLeaf(e)
        val adjacentNext = next != null && next.textRange.startOffset == e.textRange.endOffset
        if (adjacentNext) {
            val nt = next.elementType
            if (nt == T.LBRACE) {
                mark(holder, e, JuliaColors.TYPE)
                return
            }
            val isCall = nt == T.LPAR || (nt == T.DOT && PsiTreeUtil.nextLeaf(next!!)?.elementType == T.LPAR)
            if (isCall) {
                mark(holder, e, if (text.firstOrNull()?.isUpperCase() == true) JuliaColors.TYPE else JuliaColors.FUNCTION_CALL)
                return
            }
        }

        if (isTypePosition(e)) {
            mark(holder, e, JuliaColors.TYPE)
            return
        }
        if (parent.elementType == E.BRACE_GROUP && text.firstOrNull()?.isUpperCase() == true) {
            mark(holder, e, JuliaColors.TYPE)
            return
        }
        if (isParameter(e)) {
            mark(holder, e, JuliaColors.PARAMETER)
            return
        }
        if (isImportedModule(e)) {
            mark(holder, e, JuliaColors.MODULE_NAME)
            return
        }
        if (text in BUILTIN_TYPES) mark(holder, e, JuliaColors.TYPE)
    }

    private fun prevSignificantLeaf(e: PsiElement): PsiElement? {
        var p = PsiTreeUtil.prevLeaf(e)
        while (p != null && (p is PsiWhiteSpace || p is PsiComment)) p = PsiTreeUtil.prevLeaf(p)
        return p
    }

    /** After `::`, `<:`, `>:`, `where`, including qualified chains like `::Base.Vector`. */
    private fun isTypePosition(e: PsiElement): Boolean {
        var start = e
        while (true) {
            val prev = PsiTreeUtil.prevLeaf(start) ?: break
            if (prev.elementType != T.DOT) break
            val before = PsiTreeUtil.prevLeaf(prev) ?: break
            if (before.elementType != T.IDENTIFIER) break
            start = before
        }
        val p = prevSignificantLeaf(start) ?: return false
        return when (p.elementType) {
            T.DOUBLE_COLON -> true
            T.OPERATOR -> p.text == "<:" || p.text == ">:"
            T.KEYWORD -> p.text == "where"
            else -> false
        }
    }

    private fun isParameter(e: PsiElement): Boolean {
        val group = e.parent ?: return false
        if (group.elementType != E.PAREN_GROUP) return false
        val def = group.parent as? JuliaDefinition ?: return false
        if (def.kind != JuliaDefinition.Kind.FUNCTION && def.kind != JuliaDefinition.Kind.MACRO) return false
        // the signature group is the first paren group after the name (possibly after `{T}`)
        var s = def.nameElement?.nextSibling ?: return false
        while (s is PsiWhiteSpace || s.elementType == E.BRACE_GROUP) s = s.nextSibling ?: return false
        if (s != group) return false
        val p = prevSignificantLeaf(e) ?: return false
        return (p.elementType == T.LPAR && p.parent == group) || p.elementType == T.COMMA || p.elementType == T.SEMICOLON ||
            (p.elementType == T.OPERATOR && p.text == "..." )
    }

    private fun isImportedModule(e: PsiElement): Boolean {
        val p = prevSignificantLeaf(e) ?: return false
        if (p.elementType == T.DOT) return false
        // walk back to the start of the statement; it must start with using/import and contain no ':' before e
        var q: PsiElement? = p
        while (q != null) {
            when (q.elementType) {
                T.KEYWORD -> return q.text == "using" || q.text == "import"
                T.COLON, T.NEWLINE, T.SEMICOLON -> return false
                T.IDENTIFIER, T.COMMA, T.DOT, T.WHITE_SPACE -> {}
                else -> if (q !is PsiWhiteSpace) return false
            }
            if (q is PsiWhiteSpace && q.textContains('\n')) return false
            q = PsiTreeUtil.prevLeaf(q)
        }
        return false
    }

    private fun isDocstring(s: PsiElement): Boolean {
        val first = s.firstChild ?: return false
        if (first.elementType != T.STRING_START) return false
        val prev = prevSignificantLeaf(s)
        if (prev != null) {
            if (prev.elementType == T.MACRO && prev.text == "@doc") return true
            // must start a statement
            var w = PsiTreeUtil.prevLeaf(s)
            var sawNewline = false
            while (w != null && (w is PsiWhiteSpace || w is PsiComment)) {
                if (w.textContains('\n')) sawNewline = true
                w = PsiTreeUtil.prevLeaf(w)
            }
            if (!sawNewline && w?.elementType != T.SEMICOLON) return false
        }
        // followed by exactly one line break and then the documented object
        val ws = s.nextSibling
        if (ws !is PsiWhiteSpace) return false
        val nl = ws.text.count { it == '\n' }
        if (nl != 1) return false
        val target = ws.nextSibling ?: return false
        return target is JuliaDefinition || target.elementType in DOC_TARGETS
    }

    companion object {
        private val DOC_TARGETS = setOf(T.IDENTIFIER, T.MACRO, T.CONST_KW, T.KEYWORD, T.FUNCTION_KW, T.STRUCT_KW, T.MUTABLE_KW)

        val BUILTIN_TYPES: Set<String> = hashSetOf(
            "Any", "Nothing", "Missing", "Bool", "Int", "Int8", "Int16", "Int32", "Int64", "Int128",
            "UInt", "UInt8", "UInt16", "UInt32", "UInt64", "UInt128", "Float16", "Float32", "Float64",
            "BigInt", "BigFloat", "Complex", "ComplexF16", "ComplexF32", "ComplexF64", "Rational", "Irrational",
            "Real", "Integer", "Number", "AbstractFloat", "AbstractIrrational", "Signed", "Unsigned",
            "AbstractString", "String", "SubString", "Char", "AbstractChar", "Symbol", "Regex", "RegexMatch",
            "Vector", "Matrix", "Array", "AbstractVector", "AbstractMatrix", "AbstractArray", "VecOrMat",
            "DenseArray", "DenseVector", "DenseMatrix", "SubArray", "BitArray", "BitVector", "BitMatrix",
            "Tuple", "NTuple", "NamedTuple", "Dict", "IdDict", "AbstractDict", "Set", "AbstractSet", "Pair",
            "Function", "Type", "DataType", "Union", "UnionAll", "Module", "Expr", "QuoteNode", "LineNumberNode",
            "IO", "IOBuffer", "IOStream", "Ref", "Ptr", "Cvoid", "Cint", "Cuint", "Clong", "Cdouble", "Cfloat",
            "Csize_t", "Cstring", "Cchar", "UnitRange", "StepRange", "StepRangeLen", "LinRange", "AbstractRange",
            "OrdinalRange", "CartesianIndex", "CartesianIndices", "LinearIndices", "Colon", "Val", "Some", "Enum",
            "Exception", "ErrorException", "ArgumentError", "BoundsError", "DomainError", "DimensionMismatch",
            "KeyError", "MethodError", "OverflowError", "InexactError", "UndefVarError", "StackOverflowError",
            "OutOfMemoryError", "InterruptException", "AssertionError", "TypeError", "LoadError", "SystemError",
            "Task", "Channel", "Condition", "ReentrantLock", "Threads", "Base", "Core", "Main", "Iterators",
        )
    }
}
