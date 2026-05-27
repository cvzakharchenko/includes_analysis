package com.github.cvzakharchenko.includesanalysis

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.psi.PsiFile

// Registered in plugin.xml under HierarchyViewPopupMenu, which is the standard popup
// group HierarchyBrowserBase.createTree installs on every hierarchy tree. Gating on
// IncludeHierarchyBrowser.BROWSER_KEY hides this action in the Call/Type/Method
// hierarchy views, so it only appears in ours.
class FilterByFileNameAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val browser = e.getData(IncludeHierarchyBrowser.BROWSER_KEY) ?: return
        val file = e.getData(CommonDataKeys.PSI_ELEMENT) as? PsiFile ?: return
        browser.setFilter(file.name)
    }

    override fun update(e: AnActionEvent) {
        val browser = e.getData(IncludeHierarchyBrowser.BROWSER_KEY)
        val element = e.getData(CommonDataKeys.PSI_ELEMENT)
        e.presentation.isEnabledAndVisible = browser != null && element is PsiFile
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}
