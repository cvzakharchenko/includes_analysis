package com.github.cvzakharchenko.includesanalysis

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.SearchScope

enum class IncludeDirection { INCLUDEES, INCLUDERS }

class IncludeFilterState(initialScope: SearchScope) {
    var scope: SearchScope = initialScope
    var filter: IncludeHierarchyFilter = IncludeHierarchyFilter.empty()
    var flat: Boolean = false
    var direction: IncludeDirection = IncludeDirection.INCLUDEES
    var showDirectOutOfScopeLeaves: Boolean = false
    var hideRepeatedIncludes: Boolean = false
    var filterByPath: Boolean = false
    var showFullPath: Boolean = false
    var showChildrenCount: Boolean = false
    var autoload: Boolean = false

    fun acceptsScope(file: VirtualFile?): Boolean {
        if (file == null) return false
        return ApplicationManager.getApplication().runReadAction<Boolean> {
            scope.contains(file)
        }
    }

    fun matchesFilter(file: PsiFile): Boolean {
        if (filter.isEmpty) return true
        val path = file.virtualFile?.path
        val target = when {
            filter.includePath && path != null -> "${file.name} $path"
            else -> file.name
        }
        return filter.matches(target.lowercase())
    }
}

class IncludeHierarchyFilter private constructor(
    val text: String,
    val includePath: Boolean,
    private val terms: List<String>,
) {
    val isEmpty: Boolean
        get() = terms.isEmpty()

    fun matches(searchableText: String): Boolean {
        if (isEmpty) return true
        return terms.all { searchableText.contains(it) }
    }

    override fun equals(other: Any?): Boolean =
        other is IncludeHierarchyFilter && text == other.text && includePath == other.includePath

    override fun hashCode(): Int = 31 * text.hashCode() + includePath.hashCode()

    companion object {
        private val EMPTY_BY_NAME = IncludeHierarchyFilter("", false, emptyList())
        private val EMPTY_BY_PATH = IncludeHierarchyFilter("", true, emptyList())

        fun empty(includePath: Boolean = false): IncludeHierarchyFilter =
            if (includePath) EMPTY_BY_PATH else EMPTY_BY_NAME

        fun from(text: String, includePath: Boolean = false): IncludeHierarchyFilter {
            val normalized = text.trim().lowercase()
            if (normalized.isEmpty()) {
                return empty(includePath)
            }

            return IncludeHierarchyFilter(
                normalized,
                includePath,
                normalized.split(Regex("\\s+")).filter { it.isNotEmpty() },
            )
        }
    }
}

internal fun childCountText(
    filteredCount: Int,
    totalCount: Int,
    filter: IncludeHierarchyFilter,
    truncated: Boolean = false,
): String {
    val totalText = if (truncated) "$totalCount+" else totalCount.toString()
    return if (filter.isEmpty) totalText else "$filteredCount / $totalText"
}

internal fun displayPath(file: PsiFile): String {
    val virtualFile = file.virtualFile ?: return file.name
    val basePath = file.project.basePath?.replace('\\', '/') ?: return virtualFile.path
    val normalizedPath = virtualFile.path.replace('\\', '/')
    val root = basePath.trimEnd('/')
    return when {
        normalizedPath == root -> file.name
        normalizedPath.startsWith("$root/") -> normalizedPath.removePrefix("$root/")
        else -> virtualFile.path
    }
}