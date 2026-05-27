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

    // Memoize the cumulative descendant counts for the life of this descriptor.
    // Descriptors are recreated by every refreshAllViews → IncludeTreeStructure rebuild,
    // so the cached counts track the latest state / cache snapshot. Autoload-driven
    // refreshes therefore pick up new descendants as the cache fills in.
    private var cachedTotal: Int = -1
    private var cachedFiltered: Int = -1

    override fun update(): Boolean {
        var changes = super.update()
        val file = psiElement as? PsiFile
        val newText = CompositeAppearance()
        if (file == null || !file.isValid) {
            newText.ending.addText("<invalid>")
        } else {
            newText.ending.addText(file.name)
            if (state.showChildrenCount) {
                newText.ending.addText(" (${descendantCountLabel(file)})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
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

    private fun descendantCountLabel(file: PsiFile): String {
        computeCounts(file)
        // No filter active → just the scope-bounded total. With a filter → "matched /
        // total" so the user sees how many descendants the filter is keeping versus
        // hiding. Descriptors are recreated on every refresh, so the memoized counts
        // stay consistent with the current state snapshot.
        return if (state.query.isEmpty()) cachedTotal.toString()
        else "$cachedFiltered / $cachedTotal"
    }

    private fun computeCounts(file: PsiFile) {
        if (cachedTotal >= 0) return
        try {
            val reachable = cache.scopeBoundedReachable(file, direction, state)
            cachedTotal = reachable.size
            cachedFiltered = if (state.query.isEmpty()) cachedTotal
            else reachable.count { state.matchesQuery(it) }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Throwable) {
            cachedTotal = 0
            cachedFiltered = 0
        }
    }
}
