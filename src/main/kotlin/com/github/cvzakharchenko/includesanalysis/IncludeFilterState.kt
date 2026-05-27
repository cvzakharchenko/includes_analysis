package com.github.cvzakharchenko.includesanalysis

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.SearchScope

enum class IncludeDirection { INCLUDEES, INCLUDERS }

class IncludeFilterState {
    var scope: SearchScope? = null
    var query: String = ""
    var flat: Boolean = false
    var direction: IncludeDirection = IncludeDirection.INCLUDEES
    var showFirstOutOfScopeLeaf: Boolean = false
    var skipDuplicateSubtree: Boolean = false
    var filterByPath: Boolean = false
    var showFullPath: Boolean = false
    var autoload: Boolean = false

    fun acceptsScope(file: VirtualFile?): Boolean {
        if (file == null) return false
        val s = scope ?: return true
        return s.contains(file)
    }

    fun matchesQuery(file: PsiFile): Boolean {
        if (query.isEmpty()) return true
        val target = if (filterByPath) displayPath(file) else file.name
        return target.contains(query, ignoreCase = true)
    }
}

fun displayPath(file: PsiFile): String {
    val vf = file.virtualFile ?: return file.name
    val base = file.project.basePath ?: return vf.path
    val path = vf.path
    return if (path.startsWith(base)) path.removePrefix(base).removePrefix("/").removePrefix("\\") else path
}
