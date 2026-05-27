package com.github.cvzakharchenko.includesanalysis

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.SearchScope
import com.jetbrains.rider.model.cppIncludeGraph
import com.jetbrains.rider.projectView.hasSolution
import com.jetbrains.rider.projectView.solution
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

internal val PSI_FILE_COMPARATOR = compareBy<PsiFile>(
    { it.name.lowercase() },
    { it.virtualFile?.path?.lowercase() ?: "" },
)

class IncludeGraphCache(private val project: com.intellij.openapi.project.Project) {
    private val log = Logger.getInstance(IncludeGraphCache::class.java)
    private val directCache = ConcurrentHashMap<DirectCacheKey, List<PsiFile>>()
    private val flatFileCache = Collections.synchronizedMap(mutableMapOf<FlatCacheKey, List<PsiFile>>())
    private val filteredFlatFileCache = Collections.synchronizedMap(mutableMapOf<FilteredFlatCacheKey, List<PsiFile>>())
    private val visibleChildFileCache = Collections.synchronizedMap(mutableMapOf<VisibleChildrenCacheKey, List<PsiFile>>())
    private val scopeBoundedReachablePathCache =
        Collections.synchronizedMap(mutableMapOf<ScopeBoundedReachableCacheKey, ReachablePathsEntry>())
    private val matchingSubtreeCache = Collections.synchronizedMap(mutableMapOf<MatchingSubtreeCacheKey, Boolean>())
    private val scopeContainsCache = Collections.synchronizedMap(mutableMapOf<ScopeFileCacheKey, Boolean>())
    private val filterMatchCache = ConcurrentHashMap<FilterMatchCacheKey, Boolean>()
    private val searchableTextCache = ConcurrentHashMap<SearchableTextCacheKey, String>()
    private val fileCache = Collections.synchronizedMap(mutableMapOf<String, PsiFile?>())

    fun clear() {
        directCache.clear()
        flatFileCache.clear()
        filteredFlatFileCache.clear()
        visibleChildFileCache.clear()
        scopeBoundedReachablePathCache.clear()
        matchingSubtreeCache.clear()
        scopeContainsCache.clear()
        filterMatchCache.clear()
        searchableTextCache.clear()
        synchronized(fileCache) {
            fileCache.clear()
        }
    }

    fun invalidateScopeBounded() {
        flatFileCache.clear()
        filteredFlatFileCache.clear()
        visibleChildFileCache.clear()
        scopeBoundedReachablePathCache.clear()
        matchingSubtreeCache.clear()
        scopeContainsCache.clear()
    }

    fun directRelated(file: PsiFile, direction: IncludeDirection): List<PsiFile> {
        val path = file.virtualFile?.path ?: return emptyList()
        return directCache.computeIfAbsent(DirectCacheKey(direction, path)) {
            loadRelatedPaths(path, direction).asSequence()
                .mapNotNull { childPath -> findFile(childPath) }
                .distinctBy { childFile -> childFile.virtualFile?.path }
                .sortedWith(PSI_FILE_COMPARATOR)
                .toList()
        }
    }

    fun visibleChildFiles(
        file: PsiFile,
        direction: IncludeDirection,
        scope: SearchScope,
        filter: IncludeHierarchyFilter,
        showOutOfScopeLeaves: Boolean,
    ): List<PsiFile> {
        val path = file.virtualFile?.path ?: return emptyList()
        val key = VisibleChildrenCacheKey(path, direction, scope, filter.text, filter.includePath, showOutOfScopeLeaves)
        synchronized(visibleChildFileCache) {
            visibleChildFileCache[key]?.let { return it }
        }

        val files = directRelated(file, direction).filter { childFile ->
            shouldShowChild(childFile, direction, scope, filter, showOutOfScopeLeaves)
        }
        synchronized(visibleChildFileCache) {
            visibleChildFileCache[key] = files
        }
        return files
    }

    fun visibleDescendantCountText(
        file: PsiFile,
        direction: IncludeDirection,
        scope: SearchScope,
        filter: IncludeHierarchyFilter,
        showOutOfScopeLeaves: Boolean,
        ancestorPaths: Set<String> = emptySet(),
    ): String {
        val reachablePaths = scopeBoundedReachablePaths(file, direction, scope, showOutOfScopeLeaves, ancestorPaths)
        val totalCount = reachablePaths.size
        val filteredCount = if (filter.isEmpty) {
            totalCount
        } else {
            reachablePaths.count { path -> matchesFilter(path, filter) }
        }
        return childCountText(filteredCount, totalCount, filter)
    }

    fun flatFiles(
        file: PsiFile,
        direction: IncludeDirection,
        scope: SearchScope,
        filter: IncludeHierarchyFilter,
        showOutOfScopeLeaves: Boolean,
    ): List<PsiFile> {
        if (filter.isEmpty) {
            return flatFiles(file, direction, scope, showOutOfScopeLeaves)
        }

        val path = file.virtualFile?.path ?: return emptyList()
        val key = FilteredFlatCacheKey(path, direction, scope, filter.text, filter.includePath, showOutOfScopeLeaves)
        synchronized(filteredFlatFileCache) {
            filteredFlatFileCache[key]?.let { return it }
        }

        val files = flatFiles(file, direction, scope, showOutOfScopeLeaves)
            .filter { childFile -> matchesFilter(childFile, filter) }
        synchronized(filteredFlatFileCache) {
            filteredFlatFileCache[key] = files
        }
        return files
    }

    private fun flatFiles(
        file: PsiFile,
        direction: IncludeDirection,
        scope: SearchScope,
        showOutOfScopeLeaves: Boolean,
    ): List<PsiFile> {
        val path = file.virtualFile?.path ?: return emptyList()
        val key = FlatCacheKey(path, direction, scope, showOutOfScopeLeaves)
        synchronized(flatFileCache) {
            flatFileCache[key]?.let { return it }
        }

        val files = scopeBoundedReachablePaths(file, direction, scope, showOutOfScopeLeaves)
            .asSequence()
            .mapNotNull { childPath -> findFile(childPath) }
            .sortedWith(PSI_FILE_COMPARATOR)
            .toList()
        synchronized(flatFileCache) {
            flatFileCache[key] = files
        }
        return files
    }

    fun contains(scope: SearchScope, file: PsiFile): Boolean {
        val virtualFile = file.virtualFile ?: return false
        return contains(scope, virtualFile.path, virtualFile)
    }

    fun autoloadHierarchy(
        rootFile: PsiFile,
        direction: IncludeDirection,
        scope: SearchScope,
        showOutOfScopeLeaves: Boolean,
        shouldCancel: () -> Boolean,
        onProgress: (AutoloadProgress) -> Unit,
        onDiscoveredFile: (PsiFile, Boolean) -> Unit,
    ): AutoloadProgress {
        val rootPath = rootFile.virtualFile?.path ?: return AutoloadProgress(0, 0)
        val discoveredPaths = hashSetOf(rootPath)
        val queue = ArrayDeque<PsiFile>()
        var processed = 0
        var discovered = 1

        queue.add(rootFile)
        onProgress(AutoloadProgress(processed, discovered))

        while (queue.isNotEmpty()) {
            ProgressManager.checkCanceled()
            if (shouldCancel()) break

            val currentFile = queue.removeFirst()
            for (childFile in directRelated(currentFile, direction)) {
                if (shouldCancel()) break

                val virtualFile = childFile.virtualFile ?: continue
                val childPath = virtualFile.path
                if (!discoveredPaths.add(childPath)) continue

                val inScope = contains(scope, childPath, virtualFile)
                if (inScope) {
                    discovered++
                    onDiscoveredFile(childFile, true)
                    queue.addLast(childFile)
                } else if (showOutOfScopeLeaves) {
                    discovered++
                    onDiscoveredFile(childFile, false)
                }
            }

            processed++
            onProgress(AutoloadProgress(processed, discovered))
        }

        return AutoloadProgress(processed, discovered)
    }

    fun matchesFilter(file: PsiFile, filter: IncludeHierarchyFilter): Boolean {
        if (filter.isEmpty) return true
        val path = file.virtualFile?.path ?: return filter.matches(file.name.lowercase())
        return matchesFilter(path, filter)
    }

    private fun matchesFilter(path: String, filter: IncludeHierarchyFilter): Boolean {
        if (filter.isEmpty) return true
        return filterMatchCache.computeIfAbsent(FilterMatchCacheKey(path, filter.text, filter.includePath)) {
            filter.matches(searchableText(path, filter.includePath))
        }
    }

    private fun scopeBoundedReachablePaths(
        file: PsiFile,
        direction: IncludeDirection,
        scope: SearchScope,
        showOutOfScopeLeaves: Boolean,
        ancestorPaths: Set<String> = emptySet(),
    ): Set<String> {
        val path = file.virtualFile?.path ?: return emptySet()
        val visitingPaths = LinkedHashSet(ancestorPaths)
        if (!visitingPaths.add(path)) {
            return emptySet()
        }

        return scopeBoundedReachableEntry(file, direction, scope, showOutOfScopeLeaves, visitingPaths).paths
    }

    private fun scopeBoundedReachableEntry(
        file: PsiFile,
        direction: IncludeDirection,
        scope: SearchScope,
        showOutOfScopeLeaves: Boolean,
        visitingPaths: MutableSet<String>,
    ): ReachablePathsEntry {
        val path = file.virtualFile?.path ?: return ReachablePathsEntry.EMPTY
        val key = ScopeBoundedReachableCacheKey(path, direction, scope, showOutOfScopeLeaves)
        synchronized(scopeBoundedReachablePathCache) {
            scopeBoundedReachablePathCache[key]
                ?.takeUnless { cachedEntry -> cachedEntry.intersects(visitingPaths) }
                ?.let { return it }
        }

        val entry = buildScopeBoundedReachableEntry(file, direction, scope, showOutOfScopeLeaves, visitingPaths)
        if (entry.complete) {
            synchronized(scopeBoundedReachablePathCache) {
                scopeBoundedReachablePathCache[key] = entry
            }
        }
        return entry
    }

    private fun buildScopeBoundedReachableEntry(
        file: PsiFile,
        direction: IncludeDirection,
        scope: SearchScope,
        showOutOfScopeLeaves: Boolean,
        visitingPaths: MutableSet<String>,
    ): ReachablePathsEntry {
        ProgressManager.checkCanceled()
        var complete = true
        val reachablePaths = linkedSetOf<String>()

        for (childFile in directRelated(file, direction)) {
            val virtualFile = childFile.virtualFile ?: continue
            val childPath = virtualFile.path
            val inScope = contains(scope, childPath, virtualFile)
            if (!inScope) {
                if (showOutOfScopeLeaves) {
                    reachablePaths.add(childPath)
                }
                continue
            }

            if (!reachablePaths.add(childPath)) {
                continue
            }

            if (!visitingPaths.add(childPath)) {
                complete = false
                continue
            }

            val childEntry = scopeBoundedReachableEntry(
                childFile,
                direction,
                scope,
                showOutOfScopeLeaves,
                visitingPaths,
            )
            complete = complete && childEntry.complete
            reachablePaths.addAll(childEntry.paths)
            visitingPaths.remove(childPath)
        }

        return ReachablePathsEntry(reachablePaths, complete)
    }

    private fun shouldShowChild(
        childFile: PsiFile,
        direction: IncludeDirection,
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

        return inScope && hasMatchingSubtree(childFile, direction, scope, filter, showOutOfScopeLeaves)
    }

    private fun hasMatchingSubtree(
        file: PsiFile,
        direction: IncludeDirection,
        scope: SearchScope,
        filter: IncludeHierarchyFilter,
        showOutOfScopeLeaves: Boolean,
    ): Boolean {
        if (filter.isEmpty) return true

        val path = file.virtualFile?.path ?: return false
        val key = MatchingSubtreeCacheKey(path, direction, scope, filter.text, filter.includePath, showOutOfScopeLeaves)
        synchronized(matchingSubtreeCache) {
            matchingSubtreeCache[key]?.let { return it }
        }

        val result = buildHasMatchingSubtree(file, direction, scope, filter, showOutOfScopeLeaves, hashSetOf(path))
        synchronized(matchingSubtreeCache) {
            matchingSubtreeCache[key] = result
        }
        return result
    }

    private fun buildHasMatchingSubtree(
        file: PsiFile,
        direction: IncludeDirection,
        scope: SearchScope,
        filter: IncludeHierarchyFilter,
        showOutOfScopeLeaves: Boolean,
        visitedPaths: MutableSet<String>,
    ): Boolean {
        ProgressManager.checkCanceled()
        if (matchesFilter(file, filter)) {
            return true
        }

        for (childFile in directRelated(file, direction)) {
            val virtualFile = childFile.virtualFile ?: continue
            val path = virtualFile.path
            if (!visitedPaths.add(path)) {
                continue
            }

            val inScope = contains(scope, path, virtualFile)
            if (!inScope) {
                if (showOutOfScopeLeaves && matchesFilter(childFile, filter)) {
                    return true
                }
                continue
            }

            if (buildHasMatchingSubtree(childFile, direction, scope, filter, showOutOfScopeLeaves, visitedPaths)) {
                return true
            }
        }

        return false
    }

    private fun contains(scope: SearchScope, path: String, virtualFile: VirtualFile): Boolean {
        val key = ScopeFileCacheKey(scope, path)
        synchronized(scopeContainsCache) {
            scopeContainsCache[key]?.let { return it }
        }

        val result = ApplicationManager.getApplication().runReadAction<Boolean> {
            scope.contains(virtualFile)
        }
        synchronized(scopeContainsCache) {
            scopeContainsCache[key] = result
        }
        return result
    }

    private fun searchableText(path: String, includePath: Boolean): String =
        searchableTextCache.computeIfAbsent(SearchableTextCacheKey(path, includePath)) {
            val fileName = path.substringAfterLast('/').substringAfterLast('\\')
            buildString {
                append(fileName)
                if (includePath) {
                    append(' ')
                    append(path)
                }
            }.lowercase()
        }

    private fun loadRelatedPaths(path: String, direction: IncludeDirection): List<String> =
        try {
            if (!project.hasSolution) {
                emptyList()
            } else {
                val progressManager = ProgressManager.getInstance()
                if (progressManager.hasProgressIndicator()) {
                    loadRelatedPathsCancellable(path, direction)
                } else {
                    progressManager.runProcess(
                        Computable { loadRelatedPathsCancellable(path, direction) },
                        EmptyProgressIndicator(),
                    )
                }
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Throwable) {
            log.warn("CppIncludeGraph $direction failed for $path", e)
            emptyList()
        }

    private fun loadRelatedPathsCancellable(path: String, direction: IncludeDirection): List<String> {
        val graph = project.solution.cppIncludeGraph
        val call = when (direction) {
            IncludeDirection.INCLUDEES -> graph.getIncludees
            IncludeDirection.INCLUDERS -> graph.getIncluders
        }
        return runBlockingCancellable {
            call.startSuspending(path)
        }
    }

    private fun findFile(path: String): PsiFile? {
        synchronized(fileCache) {
            if (fileCache.containsKey(path)) {
                return fileCache[path]
            }
        }

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(path)
        val file = virtualFile?.let {
            ApplicationManager.getApplication().runReadAction<PsiFile?> {
                PsiManager.getInstance(project).findFile(it)
            }
        }
        synchronized(fileCache) {
            fileCache[path] = file
        }
        return file
    }

    private data class DirectCacheKey(
        val direction: IncludeDirection,
        val path: String,
    )

    private data class FlatCacheKey(
        val rootPath: String,
        val direction: IncludeDirection,
        val scope: SearchScope,
        val showOutOfScopeLeaves: Boolean,
    )

    private data class FilteredFlatCacheKey(
        val rootPath: String,
        val direction: IncludeDirection,
        val scope: SearchScope,
        val filter: String,
        val includePath: Boolean,
        val showOutOfScopeLeaves: Boolean,
    )

    private data class VisibleChildrenCacheKey(
        val rootPath: String,
        val direction: IncludeDirection,
        val scope: SearchScope,
        val filter: String,
        val includePath: Boolean,
        val showOutOfScopeLeaves: Boolean,
    )

    private data class ScopeBoundedReachableCacheKey(
        val rootPath: String,
        val direction: IncludeDirection,
        val scope: SearchScope,
        val showOutOfScopeLeaves: Boolean,
    )

    private data class MatchingSubtreeCacheKey(
        val rootPath: String,
        val direction: IncludeDirection,
        val scope: SearchScope,
        val filter: String,
        val includePath: Boolean,
        val showOutOfScopeLeaves: Boolean,
    )

    private data class ScopeFileCacheKey(
        val scope: SearchScope,
        val path: String,
    )

    private data class FilterMatchCacheKey(
        val path: String,
        val filter: String,
        val includePath: Boolean,
    )

    private data class SearchableTextCacheKey(
        val path: String,
        val includePath: Boolean,
    )

    private data class ReachablePathsEntry(
        val paths: Set<String>,
        val complete: Boolean,
    ) {
        fun intersects(otherPaths: Set<String>): Boolean =
            otherPaths.any { paths.contains(it) }

        companion object {
            val EMPTY = ReachablePathsEntry(emptySet(), true)
        }
    }
}

data class AutoloadProgress(
    val processed: Int,
    val discovered: Int,
)