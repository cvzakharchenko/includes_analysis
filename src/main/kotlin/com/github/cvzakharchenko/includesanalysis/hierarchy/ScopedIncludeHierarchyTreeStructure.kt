package com.github.cvzakharchenko.includesanalysis.hierarchy

import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.util.CompositeAppearance
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.SearchScope
import com.jetbrains.rd.framework.impl.RdCall
import com.jetbrains.rider.cpp.fileType.psi.CppFile
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

private val EMPTY_CHILDREN = emptyArray<Any>()
private val CPP_FILE_COMPARATOR = compareBy<CppFile>(
    { it.name.lowercase() },
    { it.virtualFile?.path?.lowercase() ?: "" },
)

class ScopedIncludeHierarchyTreeStructure(
    private val projectRef: Project,
    file: CppFile,
    private val cache: IncludeHierarchyCache,
    private val scopeProvider: () -> SearchScope,
    private val filterProvider: () -> IncludeHierarchyFilter,
    private val showOutOfScopeLeavesProvider: () -> Boolean,
    private val expandRepeatedIncludesProvider: () -> Boolean,
) : HierarchyTreeStructure(
    projectRef,
    ScopedIncludeHierarchyNodeDescriptor(projectRef, null, file, true),
) {
    private val buildLock = Any()
    private val childrenByDescriptor = Collections.synchronizedMap(IdentityHashMap<ScopedIncludeHierarchyNodeDescriptor, Array<Any>>())
    private val shownFilePaths = ConcurrentHashMap.newKeySet<String>()

    init {
        file.virtualFile?.path?.let { shownFilePaths.add(it) }
    }

    override fun buildChildren(nodeDescriptor: HierarchyNodeDescriptor): Array<Any> {
        val descriptor = nodeDescriptor as? ScopedIncludeHierarchyNodeDescriptor ?: return EMPTY_CHILDREN
        synchronized(buildLock) {
            childrenByDescriptor[descriptor]?.let { return it }

            val children = buildChildrenOnce(descriptor)
            childrenByDescriptor[descriptor] = children
            return children
        }
    }

    private fun buildChildrenOnce(descriptor: ScopedIncludeHierarchyNodeDescriptor): Array<Any> {
        val file = descriptor.file
        val scope = scopeProvider()
        val filter = filterProvider()
        val showOutOfScopeLeaves = showOutOfScopeLeavesProvider()
        val expandRepeatedIncludes = expandRepeatedIncludesProvider()

        if (descriptor.pruneChildren || !canExpand(descriptor, scope, cache)) {
            return EMPTY_CHILDREN
        }

        return cache.visibleChildFiles(file, scope, filter, showOutOfScopeLeaves).asSequence()
            .map { childFile ->
                val childPath = childFile.virtualFile?.path
                val pruneChildren = !expandRepeatedIncludes && childPath != null && !shownFilePaths.add(childPath)
                ScopedIncludeHierarchyNodeDescriptor(projectRef, descriptor, childFile, false, pruneChildren)
            }
            .toList()
            .toTypedArray()
    }
}

class FlatIncludeHierarchyTreeStructure(
    private val projectRef: Project,
    file: CppFile,
    private val cache: IncludeHierarchyCache,
    private val scopeProvider: () -> SearchScope,
    private val filterProvider: () -> IncludeHierarchyFilter,
) : HierarchyTreeStructure(
    projectRef,
    ScopedIncludeHierarchyNodeDescriptor(projectRef, null, file, true),
) {
    override fun buildChildren(nodeDescriptor: HierarchyNodeDescriptor): Array<Any> {
        val descriptor = nodeDescriptor as? ScopedIncludeHierarchyNodeDescriptor ?: return EMPTY_CHILDREN
        if (descriptor.parentDescriptor != null) {
            return EMPTY_CHILDREN
        }

        return cache.flatFiles(descriptor.file, scopeProvider(), filterProvider()).asSequence()
            .map { childFile ->
                ScopedIncludeHierarchyNodeDescriptor(projectRef, descriptor, childFile, false)
            }
            .toList()
            .toTypedArray()
    }
}

class IncludeHierarchyCache(
    private val projectRef: Project,
    private val getChildrenCall: RdCall<String, List<String>>,
) {
    private val childPathCache = ConcurrentHashMap<String, List<String>>()
    private val childFileCache = ConcurrentHashMap<String, List<CppFile>>()
    private val flatFileCache = Collections.synchronizedMap(mutableMapOf<FlatCacheKey, List<CppFile>>())
    private val filteredFlatFileCache = Collections.synchronizedMap(mutableMapOf<FilteredFlatCacheKey, List<CppFile>>())
    private val visibleChildFileCache = Collections.synchronizedMap(mutableMapOf<VisibleChildrenCacheKey, List<CppFile>>())
    private val matchingSubtreeCache = Collections.synchronizedMap(mutableMapOf<MatchingSubtreeCacheKey, Boolean>())
    private val scopeContainsCache = Collections.synchronizedMap(mutableMapOf<ScopeFileCacheKey, Boolean>())
    private val filterMatchCache = ConcurrentHashMap<FilterMatchCacheKey, Boolean>()
    private val searchableTextCache = ConcurrentHashMap<String, String>()
    private val fileCache = Collections.synchronizedMap(mutableMapOf<String, CppFile?>())

    fun clear() {
        childPathCache.clear()
        childFileCache.clear()
        flatFileCache.clear()
        filteredFlatFileCache.clear()
        visibleChildFileCache.clear()
        matchingSubtreeCache.clear()
        scopeContainsCache.clear()
        filterMatchCache.clear()
        searchableTextCache.clear()
        synchronized(fileCache) {
            fileCache.clear()
        }
    }

    fun childFiles(file: CppFile): List<CppFile> {
        val path = file.virtualFile?.path ?: return emptyList()
        return childFileCache.computeIfAbsent(path) {
            childPaths(it).asSequence()
                .mapNotNull { childPath -> findCppFile(childPath) }
                .distinctBy { childFile -> childFile.virtualFile?.path }
                .sortedWith(CPP_FILE_COMPARATOR)
                .toList()
        }
    }

    fun visibleChildFiles(
        file: CppFile,
        scope: SearchScope,
        filter: IncludeHierarchyFilter,
        showOutOfScopeLeaves: Boolean,
    ): List<CppFile> {
        val path = file.virtualFile?.path ?: return emptyList()
        val key = VisibleChildrenCacheKey(path, scope, filter.text, showOutOfScopeLeaves)
        synchronized(visibleChildFileCache) {
            visibleChildFileCache[key]?.let { return it }
        }

        val files = childFiles(file).filter { childFile ->
            shouldShowChild(childFile, scope, filter, showOutOfScopeLeaves)
        }
        synchronized(visibleChildFileCache) {
            visibleChildFileCache[key] = files
        }
        return files
    }

    fun flatFiles(file: CppFile, scope: SearchScope, filter: IncludeHierarchyFilter): List<CppFile> {
        if (filter.isEmpty) {
            return flatFiles(file, scope)
        }

        val path = file.virtualFile?.path ?: return emptyList()
        val key = FilteredFlatCacheKey(path, scope, filter.text)
        synchronized(filteredFlatFileCache) {
            filteredFlatFileCache[key]?.let { return it }
        }

        val files = flatFiles(file, scope).filter { childFile -> matchesFilter(childFile, filter) }
        synchronized(filteredFlatFileCache) {
            filteredFlatFileCache[key] = files
        }
        return files
    }

    private fun flatFiles(file: CppFile, scope: SearchScope): List<CppFile> {
        val path = file.virtualFile?.path ?: return emptyList()
        val key = FlatCacheKey(path, scope)
        synchronized(flatFileCache) {
            flatFileCache[key]?.let { return it }
        }

        val files = buildFlatFiles(file, scope)
        synchronized(flatFileCache) {
            flatFileCache[key] = files
        }
        return files
    }

    fun contains(scope: SearchScope, file: CppFile): Boolean {
        val virtualFile = file.virtualFile ?: return false
        return contains(scope, virtualFile.path, virtualFile)
    }

    fun hasMatchingSubtree(file: CppFile, scope: SearchScope, filter: IncludeHierarchyFilter): Boolean {
        if (filter.isEmpty) {
            return true
        }

        val path = file.virtualFile?.path ?: return false
        val key = MatchingSubtreeCacheKey(path, scope, filter.text)
        synchronized(matchingSubtreeCache) {
            matchingSubtreeCache[key]?.let { return it }
        }

        val result = buildHasMatchingSubtree(file, scope, filter, hashSetOf(path))
        synchronized(matchingSubtreeCache) {
            matchingSubtreeCache[key] = result
        }
        return result
    }

    private fun childPaths(path: String): List<String> =
        childPathCache.computeIfAbsent(path) { currentPath ->
            loadChildPaths(currentPath)
        }

    private fun shouldShowChild(
        childFile: CppFile,
        scope: SearchScope,
        filter: IncludeHierarchyFilter,
        showOutOfScopeLeaves: Boolean,
    ): Boolean {
        val virtualFile = childFile.virtualFile ?: return false

        if (filter.isEmpty && showOutOfScopeLeaves) {
            return true
        }

        val inScope = contains(scope, virtualFile.path, virtualFile)
        if (filter.isEmpty) {
            return inScope
        }

        if ((showOutOfScopeLeaves || inScope) && matchesFilter(childFile, filter)) {
            return true
        }

        return inScope && hasMatchingSubtree(childFile, scope, filter)
    }

    private fun contains(scope: SearchScope, path: String, virtualFile: com.intellij.openapi.vfs.VirtualFile): Boolean {
        val key = ScopeFileCacheKey(scope, path)
        synchronized(scopeContainsCache) {
            scopeContainsCache[key]?.let { return it }
        }

        val result = scope.contains(virtualFile)
        synchronized(scopeContainsCache) {
            scopeContainsCache[key] = result
        }
        return result
    }

    private fun matchesFilter(file: CppFile, filter: IncludeHierarchyFilter): Boolean {
        if (filter.isEmpty) {
            return true
        }

        val path = file.virtualFile?.path ?: file.name
        return filterMatchCache.computeIfAbsent(FilterMatchCacheKey(path, filter.text)) {
            filter.matches(searchableText(file, path))
        }
    }

    private fun searchableText(file: CppFile, cacheKey: String): String =
        searchableTextCache.computeIfAbsent(cacheKey) {
            val virtualFile = file.virtualFile
            buildString {
                append(file.name)
                if (virtualFile != null) {
                    append(' ')
                    append(virtualFile.path)
                }
            }.lowercase()
        }

    private fun loadChildPaths(path: String): List<String> =
        try {
            runBlockingCancellable {
                getChildrenCall.startSuspending(path)
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: RuntimeException) {
            emptyList()
        }

    private fun buildFlatFiles(file: CppFile, scope: SearchScope): List<CppFile> {
        val rootPath = file.virtualFile?.path ?: return emptyList()
        val visitedFilePaths = hashSetOf(rootPath)
        val resultByPath = LinkedHashMap<String, CppFile>()
        val stack = ArrayDeque<CppFile>()
        stack.add(file)

        while (stack.isNotEmpty()) {
            ProgressManager.checkCanceled()
            val currentFile = stack.removeFirst()

            for (childFile in childFiles(currentFile)) {
                val virtualFile = childFile.virtualFile ?: continue
                val resolvedPath = virtualFile.path
                if (!visitedFilePaths.add(resolvedPath)) {
                    continue
                }

                if (!contains(scope, resolvedPath, virtualFile)) {
                    continue
                }

                resultByPath[resolvedPath] = childFile
                stack.addLast(childFile)
            }
        }

        return resultByPath.values.sortedWith(CPP_FILE_COMPARATOR)
    }

    private fun buildHasMatchingSubtree(
        file: CppFile,
        scope: SearchScope,
        filter: IncludeHierarchyFilter,
        visitedPaths: MutableSet<String>,
    ): Boolean {
        ProgressManager.checkCanceled()
        if (matchesFilter(file, filter)) {
            return true
        }

        for (childFile in childFiles(file)) {
            val virtualFile = childFile.virtualFile ?: continue
            val path = virtualFile.path
            if (!visitedPaths.add(path) || !contains(scope, path, virtualFile)) {
                continue
            }

            if (buildHasMatchingSubtree(childFile, scope, filter, visitedPaths)) {
                return true
            }
        }

        return false
    }

    fun findCppFile(path: String): CppFile? {
        synchronized(fileCache) {
            if (fileCache.containsKey(path)) {
                return fileCache[path]
            }
        }

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(path)
        val file = virtualFile?.let { PsiManager.getInstance(projectRef).findFile(it) } as? CppFile
        synchronized(fileCache) {
            fileCache[path] = file
        }
        return file
    }

    private data class FlatCacheKey(
        val rootPath: String,
        val scope: SearchScope,
    )

    private data class FilteredFlatCacheKey(
        val rootPath: String,
        val scope: SearchScope,
        val filter: String,
    )

    private data class VisibleChildrenCacheKey(
        val rootPath: String,
        val scope: SearchScope,
        val filter: String,
        val showOutOfScopeLeaves: Boolean,
    )

    private data class MatchingSubtreeCacheKey(
        val rootPath: String,
        val scope: SearchScope,
        val filter: String,
    )

    private data class ScopeFileCacheKey(
        val scope: SearchScope,
        val path: String,
    )

    private data class FilterMatchCacheKey(
        val path: String,
        val filter: String,
    )
}

class IncludeHierarchyFilter private constructor(
    val text: String,
    private val terms: List<String>,
) {
    val isEmpty: Boolean
        get() = terms.isEmpty()

    fun matches(searchableText: String): Boolean {
        if (isEmpty) {
            return true
        }

        return terms.all { searchableText.contains(it) }
    }

    override fun equals(other: Any?): Boolean =
        other is IncludeHierarchyFilter && text == other.text

    override fun hashCode(): Int = text.hashCode()

    companion object {
        private val EMPTY = IncludeHierarchyFilter("", emptyList())

        fun empty(): IncludeHierarchyFilter = EMPTY

        fun from(text: String): IncludeHierarchyFilter {
            val normalized = text.trim().lowercase()
            if (normalized.isEmpty()) {
                return EMPTY
            }

            return IncludeHierarchyFilter(
                normalized,
                normalized.split(Regex("\\s+")).filter { it.isNotEmpty() },
            )
        }
    }
}

class ScopedIncludeHierarchyNodeDescriptor(
    project: Project,
    parentDescriptor: HierarchyNodeDescriptor?,
    val file: CppFile,
    isBase: Boolean,
    val pruneChildren: Boolean = false,
) : HierarchyNodeDescriptor(project, parentDescriptor, file, isBase) {
    override fun update(): Boolean {
        val previousText = highlightedText.text

        val virtualFile = file.virtualFile
        val text = if (virtualFile == null) file.name else "${file.name} (${virtualFile.parent?.path ?: virtualFile.path})"
        myHighlightedText = CompositeAppearance.single(text)
        installIcon(file, false)

        return previousText != highlightedText.text
    }
}

private fun canExpand(
    descriptor: ScopedIncludeHierarchyNodeDescriptor,
    scope: SearchScope,
    cache: IncludeHierarchyCache,
): Boolean {
    if (descriptor.parentDescriptor == null) {
        return true
    }

    if (!cache.contains(scope, descriptor.file)) {
        return false
    }

    return !hasAncestorFile(descriptor, descriptor.file)
}

private fun hasAncestorFile(
    descriptor: ScopedIncludeHierarchyNodeDescriptor,
    file: PsiFile,
): Boolean {
    val path = file.virtualFile?.path ?: return false
    var parent = descriptor.parentDescriptor

    while (parent is ScopedIncludeHierarchyNodeDescriptor) {
        if (parent.file.virtualFile?.path == path) {
            return true
        }
        parent = parent.parentDescriptor
    }

    return false
}