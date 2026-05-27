package com.github.cvzakharchenko.includesanalysis.hierarchy

import com.intellij.ide.hierarchy.HierarchyBrowser
import com.intellij.ide.hierarchy.HierarchyProvider
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.psi.PsiElement
import com.jetbrains.rider.cpp.features.includesHierarchy.CppIncludesHierarchyProvider
import com.jetbrains.rider.model.cppIncludeGraph
import com.jetbrains.rider.projectView.solution

class ScopedIncludeHierarchyProvider : HierarchyProvider {
    private val novaProvider = CppIncludesHierarchyProvider()

    override fun getTarget(dataContext: DataContext): PsiElement? =
        novaProvider.getTarget(dataContext)

    override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser {
        val project = target.project
        return ScopedIncludeHierarchyBrowser(project, target, project.solution.cppIncludeGraph)
    }

    override fun browserActivated(hierarchyBrowser: HierarchyBrowser) {
        val browser = hierarchyBrowser as ScopedIncludeHierarchyBrowser
        browser.changeView(browser.initialViewType)
    }
}