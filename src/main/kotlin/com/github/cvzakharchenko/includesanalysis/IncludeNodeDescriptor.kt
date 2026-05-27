package com.github.cvzakharchenko.includesanalysis

import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.util.CompositeAppearance
import com.intellij.psi.PsiFile
import java.awt.Font

class IncludeNodeDescriptor(
    project: Project,
    parentDescriptor: HierarchyNodeDescriptor?,
    val file: PsiFile,
    isBase: Boolean,
    val pruneChildren: Boolean = false,
    private val showFullPath: Boolean = false,
    private var childCount: String? = null,
) : HierarchyNodeDescriptor(project, parentDescriptor, file, isBase) {
    override fun update(): Boolean {
        var changed = super.update()
        val previousText = highlightedText.text

        val text = CompositeAppearance()
        text.ending.addText(file.name, fileNameAttributes())
        childCount?.let { text.ending.addText(" ($it)", getPackageNameAttributes()) }
        if (showFullPath) {
            text.ending.addText(" (${displayPath(file)})", getPackageNameAttributes())
        }
        myHighlightedText = text
        myName = highlightedText.text
        installIcon(file, false)

        if (previousText != highlightedText.text) {
            changed = true
        }
        return changed
    }

    fun updateChildCount(count: String?) {
        if (childCount != count) {
            childCount = count
            update()
        }
    }

    private fun fileNameAttributes(): TextAttributes? =
        myColor?.let { TextAttributes(it, null, null, null, Font.PLAIN) }
}