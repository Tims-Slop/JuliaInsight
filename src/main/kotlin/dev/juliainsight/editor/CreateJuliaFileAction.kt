package dev.juliainsight.editor

import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import dev.juliainsight.JuliaIcons

class CreateJuliaFileAction : CreateFileFromTemplateAction(), DumbAware {
    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder.setTitle("New Julia File")
            .addKind("File", JuliaIcons.FILE, "Julia File")
            .addKind("Module", AllIcons.Nodes.Package, "Julia Module")
    }

    override fun getActionName(directory: PsiDirectory?, newName: String, templateName: String?): String = "Create Julia File $newName"
}
