package dev.juliainsight

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import javax.swing.Icon

object JuliaLanguage : Language("Julia") {
    private fun readResolve(): Any = JuliaLanguage
    override fun getDisplayName(): String = "Julia"
    override fun isCaseSensitive(): Boolean = true
}

object JuliaFileType : LanguageFileType(JuliaLanguage) {
    override fun getName(): String = "Julia"
    override fun getDescription(): String = "Julia source file"
    override fun getDefaultExtension(): String = "jl"
    override fun getIcon(): Icon = JuliaIcons.FILE
}

object JuliaIcons {
    @JvmField val FILE: Icon = IconLoader.getIcon("/icons/julia.svg", JuliaIcons::class.java)
    @JvmField val REPL: Icon = IconLoader.getIcon("/icons/juliaRepl.svg", JuliaIcons::class.java)
    @JvmField val PLOTS: Icon = IconLoader.getIcon("/icons/juliaPlots.svg", JuliaIcons::class.java)
    @JvmField val WORKSPACE: Icon = IconLoader.getIcon("/icons/juliaWorkspace.svg", JuliaIcons::class.java)
}

fun VirtualFile?.isJuliaFile(): Boolean = this != null && (fileType == JuliaFileType || extension.equals("jl", ignoreCase = true))
fun PsiFile?.isJuliaFile(): Boolean = this != null && language == JuliaLanguage
