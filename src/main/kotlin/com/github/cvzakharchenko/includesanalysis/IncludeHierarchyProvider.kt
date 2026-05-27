package com.github.cvzakharchenko.includesanalysis

import com.intellij.ide.hierarchy.HierarchyBrowser
import com.intellij.ide.hierarchy.HierarchyProvider
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.rider.cpp.features.includesHierarchy.CppIncludesHierarchyProvider

class IncludeHierarchyProvider : HierarchyProvider {
    private val novaProvider = CppIncludesHierarchyProvider()

    override fun getTarget(dataContext: DataContext): PsiElement? = novaProvider.getTarget(dataContext)

    override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser =
        IncludeHierarchyBrowser(target.project, target as PsiFile)

    override fun browserActivated(hierarchyBrowser: HierarchyBrowser) {
        val browser = hierarchyBrowser as IncludeHierarchyBrowser
        browser.changeView(browser.initialViewType())
    }
}
