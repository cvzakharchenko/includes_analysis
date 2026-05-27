package com.github.cvzakharchenko.includesanalysis

import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
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
    private val cache: IncludeGraphCache,
    private val direction: IncludeDirection,
) : HierarchyNodeDescriptor(project, parentDescriptor, psiFile, isBase) {

    // Memoize the cumulative descendant count for the life of this descriptor.
    // Descriptors are recreated by every refreshAllViews → IncludeTreeStructure rebuild,
    // so the cached count tracks the latest state / cache snapshot. Autoload-driven
    // refreshes therefore pick up new descendants as the cache fills in.
    private var cachedCount: Int = -1

    override fun update(): Boolean {
        var changes = super.update()
        val file = psiElement as? PsiFile
        val newText = CompositeAppearance()
        if (file == null || !file.isValid) {
            newText.ending.addText("<invalid>")
        } else {
            newText.ending.addText(file.name)
            if (state.showChildrenCount) {
                newText.ending.addText(" (${descendantCount(file)})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
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

    private fun descendantCount(file: PsiFile): Int {
        if (cachedCount >= 0) return cachedCount
        val computed = try {
            cache.scopeBoundedReachable(file, direction, state).size
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Throwable) {
            0
        }
        cachedCount = computed
        return computed
    }
}
