package com.github.cvzakharchenko.includesanalysis

import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.util.CompositeAppearance
import com.intellij.psi.PsiFile
import com.intellij.ui.SimpleTextAttributes

class IncludeNodeDescriptor(
    project: Project,
    parentDescriptor: HierarchyNodeDescriptor?,
    psiFile: PsiFile,
    isBase: Boolean,
    private val state: IncludeFilterState,
) : HierarchyNodeDescriptor(project, parentDescriptor, psiFile, isBase) {

    override fun update(): Boolean {
        var changes = super.update()
        val file = psiElement as? PsiFile
        val newText = CompositeAppearance()
        if (file == null || !file.isValid) {
            newText.ending.addText("<invalid>")
        } else {
            newText.ending.addText(file.name)
            if (state.showFullPath) {
                newText.ending.addText(" (${displayPath(file)})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        if (myHighlightedText != newText) {
            myHighlightedText = newText
            changes = true
        }
        icon = file?.getIcon(0)
        return changes
    }
}
