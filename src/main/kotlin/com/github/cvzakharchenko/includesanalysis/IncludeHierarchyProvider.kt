package com.github.cvzakharchenko.includesanalysis

import com.intellij.ide.hierarchy.HierarchyBrowser
import com.intellij.ide.hierarchy.HierarchyProvider
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.rider.cpp.fileType.psi.CppFile

class IncludeHierarchyProvider : HierarchyProvider {
    override fun getTarget(dataContext: DataContext): PsiElement? {
        val file = CommonDataKeys.PSI_FILE.getData(dataContext) ?: return null
        return if (file is CppFile) file else null
    }

    override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser =
        IncludeHierarchyBrowser(target.project, target as PsiFile)

    override fun browserActivated(hierarchyBrowser: HierarchyBrowser) {
        val browser = hierarchyBrowser as IncludeHierarchyBrowser
        browser.changeView(browser.initialViewType())
    }
}
