package com.github.cvzakharchenko.includesanalysis.hierarchy

import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.util.CompositeAppearance
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.SearchScope
import com.jetbrains.rd.framework.impl.RdCall
import com.jetbrains.rider.cpp.fileType.psi.CppFile
import java.awt.Font
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
    private val hideRepeatedIncludesProvider: () -> Boolean,
    private val showFullFilePathProvider: () -> Boolean,
    private val showChildCountsProvider: () -> Boolean,
) : HierarchyTreeStructure(
    projectRef,
    ScopedIncludeHierarchyNodeDescriptor(projectRef, null, file, true, showFullFilePath = showFullFilePathProvider()),
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
        val hideRepeatedIncludes = hideRepeatedIncludesProvider()
        val showChildCounts = showChildCountsProvider()

        if (descriptor.pruneChildren || !canExpand(descriptor, scope, cache)) {
            descriptor.updateChildCount(if (showChildCounts) childCountText(0) else null)
            return EMPTY_CHILDREN
        }

        val visibleChildren = cache.visibleChildFiles(file, scope, filter, showOutOfScopeLeaves)
        val childDescriptors = visibleChildren.asSequence()
            .map { childFile ->
                val childPath = childFile.virtualFile?.path
                val pruneChildren = hideRepeatedIncludes && childPath != null && !shownFilePaths.add(childPath)
                val childDescriptor = ScopedIncludeHierarchyNodeDescriptor(
                    projectRef,
                    descriptor,
                    childFile,
                    false,
                    pruneChildren = pruneChildren,
                    showFullFilePath = showFullFilePathProvider(),
                )
                childDescriptor.updateChildCount(
                    if (showChildCounts) {
                        visibleDescendantCountText(childDescriptor, scope, filter, showOutOfScopeLeaves)
                    } else {
                        null
                    },
                )
                childDescriptor
            }
            .toList()

        descriptor.updateChildCount(
            if (showChildCounts) {
                visibleDescendantCountText(descriptor, scope, filter, showOutOfScopeLeaves)
            } else {
                null
            },
        )
        return childDescriptors.toTypedArray()
    }

    private fun visibleDescendantCountText(
        descriptor: ScopedIncludeHierarchyNodeDescriptor,
        scope: SearchScope,
        filter: IncludeHierarchyFilter,
        showOutOfScopeLeaves: Boolean,
    ): String =
        childCountText(visibleDescendantPaths(descriptor, scope, filter, showOutOfScopeLeaves).size)

    private fun visibleDescendantPaths(
        descriptor: ScopedIncludeHierarchyNodeDescriptor,
        scope: SearchScope,
        filter: IncludeHierarchyFilter,
        showOutOfScopeLeaves: Boolean,
    ): Set<String> {
        if (descriptor.pruneChildren || !canExpand(descriptor, scope, cache)) {
            return emptySet()
        }

        return cache.visibleDescendantPaths(
            descriptor.file,
            scope,
            filter,
            showOutOfScopeLeaves,
            ancestorFilePaths(descriptor),
        )
    }

}

class FlatIncludeHierarchyTreeStructure(
    private val projectRef: Project,
    file: CppFile,
    private val cache: IncludeHierarchyCache,
    private val scopeProvider: () -> SearchScope,
    private val filterProvider: () -> IncludeHierarchyFilter,
    private val showOutOfScopeLeavesProvider: () -> Boolean,
    private val showFullFilePathProvider: () -> Boolean,
    private val showChildCountsProvider: () -> Boolean,
    private val autoloadFilesProvider: () -> List<CppFile>?,
) : HierarchyTreeStructure(
    projectRef,
    ScopedIncludeHierarchyNodeDescriptor(projectRef, null, file, true, showFullFilePath = showFullFilePathProvider()),
) {
    override fun buildChildren(nodeDescriptor: HierarchyNodeDescriptor): Array<Any> {
        val descriptor = nodeDescriptor as? ScopedIncludeHierarchyNodeDescriptor ?: return EMPTY_CHILDREN
        if (descriptor.parentDescriptor != null) {
            return EMPTY_CHILDREN
        }

        val scope = scopeProvider()
        val filter = filterProvider()
        val files = autoloadFilesProvider()
            ?.filter { childFile -> cache.matchesFilter(childFile, filter) }
            ?: cache.flatFiles(descriptor.file, scope, filter)
        val showChildCounts = showChildCountsProvider()
        val showOutOfScopeLeaves = showOutOfScopeLeavesProvider()

        descriptor.updateChildCount(if (showChildCounts) childCountText(files.size) else null)

        return files.asSequence()
            .sortedWith(CPP_FILE_COMPARATOR)
            .map { childFile ->
                val childCount = if (showChildCounts) {
                    cache.visibleDescendantCountText(childFile, scope, filter, showOutOfScopeLeaves)
                } else {
                    null
                }
                ScopedIncludeHierarchyNodeDescriptor(
                    projectRef,
                    descriptor,
                    childFile,
                    false,
                    showFullFilePath = showFullFilePathProvider(),
                    childCount = childCount,
                )
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
    private val visibleDescendantPathCache = Collections.synchronizedMap(mutableMapOf<VisibleChildrenCacheKey, DescendantPathsEntry>())
    private val matchingSubtreeCache = Collections.synchronizedMap(mutableMapOf<MatchingSubtreeCacheKey, Boolean>())
    private val scopeContainsCache = Collections.synchronizedMap(mutableMapOf<ScopeFileCacheKey, Boolean>())
    private val filterMatchCache = ConcurrentHashMap<FilterMatchCacheKey, Boolean>()
    private val searchableTextCache = ConcurrentHashMap<SearchableTextCacheKey, String>()
    private val fileCache = Collections.synchronizedMap(mutableMapOf<String, CppFile?>())

    fun clear() {
        childPathCache.clear()
        childFileCache.clear()
        flatFileCache.clear()
        filteredFlatFileCache.clear()
        visibleChildFileCache.clear()
        visibleDescendantPathCache.clear()
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
        val key = VisibleChildrenCacheKey(path, scope, filter.text, filter.includePath, showOutOfScopeLeaves)
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

    fun visibleDescendantCount(
        file: CppFile,
        scope: SearchScope,
        filter: IncludeHierarchyFilter,
        showOutOfScopeLeaves: Boolean,
        ancestorPaths: Set<String> = emptySet(),
    ): Int =
        visibleDescendantPaths(file, scope, filter, showOutOfScopeLeaves, ancestorPaths).size

    fun visibleDescendantCountText(
        file: CppFile,
        scope: SearchScope,
        filter: IncludeHierarchyFilter,
        showOutOfScopeLeaves: Boolean,
        ancestorPaths: Set<String> = emptySet(),
    ): String =
        childCountText(visibleDescendantPaths(file, scope, filter, showOutOfScopeLeaves, ancestorPaths).size)

    fun visibleDescendantPaths(
        file: CppFile,
        scope: SearchScope,
        filter: IncludeHierarchyFilter,
        showOutOfScopeLeaves: Boolean,
        ancestorPaths: Set<String> = emptySet(),
    ): Set<String> {
        val path = file.virtualFile?.path ?: return emptySet()
        val visitingPaths = LinkedHashSet(ancestorPaths)
        if (!visitingPaths.add(path)) {
            return emptySet()
        }

        return visibleDescendantPathsEntry(file, scope, filter, showOutOfScopeLeaves, visitingPaths).paths
    }

    fun flatFiles(file: CppFile, scope: SearchScope, filter: IncludeHierarchyFilter): List<CppFile> {
        if (filter.isEmpty) {
            return flatFiles(file, scope)
        }

        val path = file.virtualFile?.path ?: return emptyList()
        val key = FilteredFlatCacheKey(path, scope, filter.text, filter.includePath)
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

    fun autoloadHierarchy(
        rootFile: CppFile,
        scope: SearchScope,
        showOutOfScopeLeaves: Boolean,
        shouldCancel: () -> Boolean,
        onProgress: (AutoloadProgress) -> Unit,
        onDiscoveredFile: (CppFile, Boolean) -> Unit = { _, _ -> },
    ): AutoloadProgress {
        val rootPath = rootFile.virtualFile?.path ?: return AutoloadProgress(0, 0)
        val discoveredPaths = hashSetOf(rootPath)
        val queue = ArrayDeque<CppFile>()
        var processed = 0
        var discovered = 1

        queue.add(rootFile)
        onProgress(AutoloadProgress(processed, discovered))

        while (queue.isNotEmpty()) {
            ProgressManager.checkCanceled()
            if (shouldCancel()) {
                break
            }

            val currentFile = queue.removeFirst()
            for (childFile in childFiles(currentFile)) {
                if (shouldCancel()) {
                    break
                }

                val virtualFile = childFile.virtualFile ?: continue
                val childPath = virtualFile.path
                if (!discoveredPaths.add(childPath)) {
                    continue
                }

                val inScope = contains(scope, childPath, virtualFile)
                if (inScope) {
                    discovered++
                    onDiscoveredFile(childFile, true)
                    queue.addLast(childFile)
                } else if (showOutOfScopeLeaves) {
                    discovered++
                    onDiscoveredFile(childFile, false)
                    processed++
                }
            }

            processed++
            onProgress(AutoloadProgress(processed, discovered))
        }

        return AutoloadProgress(processed, discovered)
    }

    fun hasMatchingSubtree(file: CppFile, scope: SearchScope, filter: IncludeHierarchyFilter): Boolean {
        if (filter.isEmpty) {
            return true
        }

        val path = file.virtualFile?.path ?: return false
        val key = MatchingSubtreeCacheKey(path, scope, filter.text, filter.includePath)
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

    fun matchesFilter(file: CppFile, filter: IncludeHierarchyFilter): Boolean {
        if (filter.isEmpty) {
            return true
        }

        val path = file.virtualFile?.path ?: file.name
        return filterMatchCache.computeIfAbsent(FilterMatchCacheKey(path, filter.text, filter.includePath)) {
            filter.matches(searchableText(file, path, filter.includePath))
        }
    }

    private fun searchableText(file: CppFile, cacheKey: String, includePath: Boolean): String =
        searchableTextCache.computeIfAbsent(SearchableTextCacheKey(cacheKey, includePath)) {
            val virtualFile = file.virtualFile
            buildString {
                append(file.name)
                if (includePath && virtualFile != null) {
                    append(' ')
                    append(virtualFile.path)
                }
            }.lowercase()
        }

    private fun loadChildPaths(path: String): List<String> =
        try {
            val progressManager = ProgressManager.getInstance()
            if (progressManager.hasProgressIndicator()) {
                loadChildPathsCancellable(path)
            } else {
                progressManager.runProcess(
                    Computable { loadChildPathsCancellable(path) },
                    EmptyProgressIndicator(),
                )
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: RuntimeException) {
            emptyList()
        }

    private fun loadChildPathsCancellable(path: String): List<String> =
        runBlockingCancellable {
            getChildrenCall.startSuspending(path)
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

    private fun visibleDescendantPathsEntry(
        file: CppFile,
        scope: SearchScope,
        filter: IncludeHierarchyFilter,
        showOutOfScopeLeaves: Boolean,
        visitingPaths: MutableSet<String>,
    ): DescendantPathsEntry {
        val path = file.virtualFile?.path ?: return DescendantPathsEntry.EMPTY
        val key = VisibleChildrenCacheKey(path, scope, filter.text, filter.includePath, showOutOfScopeLeaves)
        synchronized(visibleDescendantPathCache) {
            visibleDescendantPathCache[key]
                ?.takeUnless { cachedEntry -> cachedEntry.intersects(visitingPaths) }
                ?.let { return it }
        }

        val entry = buildVisibleDescendantPathsEntry(file, scope, filter, showOutOfScopeLeaves, visitingPaths)
        if (!entry.contextDependent) {
            synchronized(visibleDescendantPathCache) {
                visibleDescendantPathCache[key] = entry
            }
        }
        return entry
    }

    private fun buildVisibleDescendantPathsEntry(
        file: CppFile,
        scope: SearchScope,
        filter: IncludeHierarchyFilter,
        showOutOfScopeLeaves: Boolean,
        visitingPaths: MutableSet<String>,
    ): DescendantPathsEntry {
        ProgressManager.checkCanceled()
        var contextDependent = false
        val descendantPaths = linkedSetOf<String>()
        val guardPaths = linkedSetOf<String>()

        for (childFile in visibleChildFiles(file, scope, filter, showOutOfScopeLeaves)) {
            val virtualFile = childFile.virtualFile ?: continue
            val childPath = virtualFile.path
            guardPaths.add(childPath)
            if (filter.isEmpty || matchesFilter(childFile, filter)) {
                descendantPaths.add(childPath)
            }

            if (!contains(scope, childPath, virtualFile)) {
                continue
            }

            if (!visitingPaths.add(childPath)) {
                contextDependent = true
                continue
            }

            val childEntry = visibleDescendantPathsEntry(
                childFile,
                scope,
                filter,
                showOutOfScopeLeaves,
                visitingPaths,
            )
            contextDependent = contextDependent || childEntry.contextDependent
            descendantPaths.addAll(childEntry.paths)
            guardPaths.addAll(childEntry.guardPaths)
            visitingPaths.remove(childPath)
        }

        return DescendantPathsEntry(descendantPaths, guardPaths, contextDependent)
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
        val file = virtualFile?.let {
            ApplicationManager.getApplication().runReadAction<CppFile?> {
                PsiManager.getInstance(projectRef).findFile(it) as? CppFile
            }
        }
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
        val includePath: Boolean,
    )

    private data class VisibleChildrenCacheKey(
        val rootPath: String,
        val scope: SearchScope,
        val filter: String,
        val includePath: Boolean,
        val showOutOfScopeLeaves: Boolean,
    )

    private data class MatchingSubtreeCacheKey(
        val rootPath: String,
        val scope: SearchScope,
        val filter: String,
        val includePath: Boolean,
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

    private data class DescendantPathsEntry(
        val paths: Set<String>,
        val guardPaths: Set<String>,
        val contextDependent: Boolean,
    ) {
        fun intersects(otherPaths: Set<String>): Boolean =
            otherPaths.any { guardPaths.contains(it) }

        companion object {
            val EMPTY = DescendantPathsEntry(emptySet(), emptySet(), false)
        }
    }
}

data class AutoloadProgress(
    val processed: Int,
    val discovered: Int,
)

class IncludeHierarchyFilter private constructor(
    val text: String,
    val includePath: Boolean,
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

class ScopedIncludeHierarchyNodeDescriptor(
    project: Project,
    parentDescriptor: HierarchyNodeDescriptor?,
    val file: CppFile,
    isBase: Boolean,
    val pruneChildren: Boolean = false,
    private val showFullFilePath: Boolean = false,
    private var childCount: String? = null,
) : HierarchyNodeDescriptor(project, parentDescriptor, file, isBase) {
    override fun update(): Boolean {
        var changed = super.update()
        val previousText = highlightedText.text

        val virtualFile = file.virtualFile
        val text = CompositeAppearance()
        text.ending.addText(file.name, fileNameAttributes())
        childCount?.let { text.ending.addText(" ($it)", getPackageNameAttributes()) }
        if (showFullFilePath && virtualFile != null) {
            text.ending.addText(" (${displayPath(virtualFile.path)})", getPackageNameAttributes())
        }
        myHighlightedText = text
        myName = highlightedText.text
        installIcon(file, false)

        if (previousText != highlightedText.text) {
            changed = true
        }
        return changed
    }

    fun updateChildCount(count: String?) {
        if (childCount != count) {
            childCount = count
            update()
        }
    }

    private fun fileNameAttributes(): TextAttributes? =
        myColor?.let { TextAttributes(it, null, null, null, Font.PLAIN) }

    private fun displayPath(path: String): String {
        val basePath = myProject.basePath?.replace('\\', '/') ?: return path
        val normalizedPath = path.replace('\\', '/')
        val root = basePath.trimEnd('/')
        return when {
            normalizedPath == root -> file.name
            normalizedPath.startsWith("$root/") -> normalizedPath.removePrefix("$root/")
            else -> path
        }
    }
}

private fun childCountText(count: Int): String = count.toString()

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

private fun ancestorFilePaths(descriptor: ScopedIncludeHierarchyNodeDescriptor): Set<String> {
    val paths = linkedSetOf<String>()
    var parent = descriptor.parentDescriptor

    while (parent is ScopedIncludeHierarchyNodeDescriptor) {
        parent.file.virtualFile?.path?.let { paths.add(it) }
        parent = parent.parentDescriptor
    }

    return paths
}