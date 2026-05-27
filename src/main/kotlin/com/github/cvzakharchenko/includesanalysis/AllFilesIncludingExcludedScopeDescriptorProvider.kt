package com.github.cvzakharchenko.includesanalysis

import com.intellij.icons.AllIcons
import com.intellij.ide.util.scopeChooser.ScopeDescriptor
import com.intellij.ide.util.scopeChooser.ScopeDescriptorProvider
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import javax.swing.Icon

class AllFilesIncludingExcludedScopeDescriptorProvider : ScopeDescriptorProvider {
    override fun getScopeDescriptors(project: Project, dataContext: DataContext): Array<ScopeDescriptor> =
        arrayOf(AllFilesIncludingExcludedScopeDescriptor(AllFilesIncludingExcludedScope(project)))
}

internal interface UnboundedIncludeScope

internal fun isUnboundedIncludeScope(scope: SearchScope): Boolean =
    scope is UnboundedIncludeScope

private class AllFilesIncludingExcludedScopeDescriptor(scope: AllFilesIncludingExcludedScope) : ScopeDescriptor(scope) {
    override fun getIcon(): Icon = AllIcons.General.Warning
}

private class AllFilesIncludingExcludedScope(project: Project) : GlobalSearchScope(project), UnboundedIncludeScope {
    override fun getDisplayName(): String = DISPLAY_NAME

    override fun contains(file: VirtualFile): Boolean = true

    override fun isSearchInModuleContent(aModule: Module): Boolean = true

    override fun isSearchInLibraries(): Boolean = true

    override fun isForceSearchingInLibrarySources(): Boolean = true

    companion object {
        private const val DISPLAY_NAME = "All Files (Including Excluded)"
    }
}