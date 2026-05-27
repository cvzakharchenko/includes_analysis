package com.github.cvzakharchenko.includesanalysis.hierarchy

import com.intellij.ide.hierarchy.actions.BrowseHierarchyActionBase
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.psi.PsiDocumentManager
import com.jetbrains.rider.cpp.configurations.CppAvailabilityService
import com.jetbrains.rider.cpp.fileType.psi.CppFile

class ScopedIncludeHierarchyAction : AnAction() {
    private val provider = ScopedIncludeHierarchyProvider()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        if (!CppAvailabilityService.isCppAvailable()) {
            event.presentation.isEnabledAndVisible = false
            return
        }

        val isCppFile = CommonDataKeys.PSI_FILE.getData(event.dataContext) is CppFile
        if (event.isFromContextMenu) {
            event.presentation.isVisible = isCppFile
        } else {
            event.presentation.isVisible = true
        }
        event.presentation.isEnabled = event.project != null && isCppFile
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val target = provider.getTarget(event.dataContext) ?: return
        BrowseHierarchyActionBase.createAndAddToPanel(project, provider, target)
    }
}