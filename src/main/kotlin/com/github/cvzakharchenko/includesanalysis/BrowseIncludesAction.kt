package com.github.cvzakharchenko.includesanalysis

import com.intellij.ide.hierarchy.actions.BrowseHierarchyActionBase
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiFile
import com.jetbrains.rider.cpp.configurations.CppAvailabilityService
import com.jetbrains.rider.cpp.fileType.psi.CppFile

class BrowseIncludesAction : AnAction(), DumbAware {

    private val provider = IncludeHierarchyProvider()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = provider.getTarget(e.dataContext) ?: return
        BrowseHierarchyActionBase.createAndAddToPanel(project, provider, target)
    }

    override fun update(e: AnActionEvent) {
        if (!CppAvailabilityService.isCppAvailable()) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val file = e.getData(CommonDataKeys.PSI_FILE)
        val eligible = file != null && isEligible(file)
        if (e.isFromContextMenu) {
            e.presentation.isVisible = eligible
        } else {
            e.presentation.isVisible = true
        }
        e.presentation.isEnabled = eligible
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun isEligible(file: PsiFile): Boolean = file is CppFile
}
