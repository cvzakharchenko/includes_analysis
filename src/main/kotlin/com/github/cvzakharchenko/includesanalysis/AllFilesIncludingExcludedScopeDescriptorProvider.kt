package com.github.cvzakharchenko.includesanalysis

import com.intellij.ide.util.scopeChooser.ScopeDescriptor
import com.intellij.ide.util.scopeChooser.ScopeDescriptorProvider
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope

class AllFilesIncludingExcludedScopeDescriptorProvider : ScopeDescriptorProvider {
    override fun getScopeDescriptors(project: Project, dataContext: DataContext): Array<ScopeDescriptor> =
        arrayOf(ScopeDescriptor(AllFilesIncludingExcludedScope(project)))
}

private class AllFilesIncludingExcludedScope(project: Project) : GlobalSearchScope(project) {
    override fun getDisplayName(): String = DISPLAY_NAME

    override fun contains(file: VirtualFile): Boolean = true

    override fun isSearchInModuleContent(aModule: Module): Boolean = true

    override fun isSearchInLibraries(): Boolean = true

    override fun isForceSearchingInLibrarySources(): Boolean = true

    companion object {
        private const val DISPLAY_NAME = "All Files (Including Excluded)"
    }
}