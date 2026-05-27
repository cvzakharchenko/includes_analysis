package com.github.cvzakharchenko.includesanalysis

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.SearchScope

enum class IncludeDirection { INCLUDEES, INCLUDERS }

class IncludeFilterState {
    var scope: SearchScope? = null
    var query: String = ""
    var flat: Boolean = false
    var direction: IncludeDirection = IncludeDirection.INCLUDEES
    var showFirstOutOfScopeLeaf: Boolean = false
    var skipDuplicateSubtree: Boolean = false

    fun acceptsScope(file: VirtualFile?): Boolean {
        if (file == null) return false
        val s = scope ?: return true
        return s.contains(file)
    }

    fun matchesQuery(name: String): Boolean =
        query.isEmpty() || name.contains(query, ignoreCase = true)
}
