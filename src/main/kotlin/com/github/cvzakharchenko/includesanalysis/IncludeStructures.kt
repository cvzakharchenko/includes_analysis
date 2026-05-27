package com.github.cvzakharchenko.includesanalysis

import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.search.SearchScope
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

private val EMPTY_CHILDREN = emptyArray<Any>()

class IncludeTreeStructure(
    private val projectRef: Project,
    file: PsiFile,
    private val state: IncludeFilterState,
    private val direction: IncludeDirection,
    private val cache: IncludeGraphCache,
) : HierarchyTreeStructure(
    projectRef,
    IncludeNodeDescriptor(projectRef, null, file, true, showFullPath = state.showFullPath),
) {
    private val buildLock = Any()
    private val childrenByDescriptor = Collections.synchronizedMap(IdentityHashMap<IncludeNodeDescriptor, Array<Any>>())
    private val shownFilePaths = ConcurrentHashMap.newKeySet<String>()

    init {
        file.virtualFile?.path?.let { shownFilePaths.add(it) }
    }

    override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> {
        val includeDescriptor = descriptor as? IncludeNodeDescriptor ?: return EMPTY_CHILDREN
        synchronized(buildLock) {
            childrenByDescriptor[includeDescriptor]?.let { return it }

            val children = buildChildrenOnce(includeDescriptor)
            childrenByDescriptor[includeDescriptor] = children
            return children
        }
    }

    private fun buildChildrenOnce(descriptor: IncludeNodeDescriptor): Array<Any> {
        val file = descriptor.file
        val scope = state.scope
        val filter = state.filter
        val showOutOfScopeLeaves = state.showDirectOutOfScopeLeaves
        val showEagerChildCounts = state.showChildrenCount && !isUnboundedIncludeScope(scope)

        if (descriptor.pruneChildren || !canExpand(descriptor, scope, cache)) {
            descriptor.updateChildCount(if (state.showChildrenCount) childCountText(0, 0, filter) else null)
            return EMPTY_CHILDREN
        }

        val childDescriptors = cache.visibleChildFiles(file, direction, scope, filter, showOutOfScopeLeaves)
            .asSequence()
            .map { childFile ->
                val childPath = childFile.virtualFile?.path
                val pruneChildren = state.hideRepeatedIncludes && childPath != null && !shownFilePaths.add(childPath)
                val childDescriptor = IncludeNodeDescriptor(
                    projectRef,
                    descriptor,
                    childFile,
                    false,
                    pruneChildren = pruneChildren,
                    showFullPath = state.showFullPath,
                )
                childDescriptor.updateChildCount(
                    if (showEagerChildCounts) {
                        visibleDescendantCountText(childDescriptor, scope, filter, showOutOfScopeLeaves)
                    } else {
                        null
                    },
                )
                childDescriptor
            }
            .toList()

        descriptor.updateChildCount(
            if (state.showChildrenCount) {
                visibleDescendantCountText(descriptor, scope, filter, showOutOfScopeLeaves)
            } else {
                null
            },
        )
        return childDescriptors.toTypedArray()
    }

    private fun visibleDescendantCountText(
        descriptor: IncludeNodeDescriptor,
        scope: SearchScope,
        filter: IncludeHierarchyFilter,
        showOutOfScopeLeaves: Boolean,
    ): String {
        if (descriptor.pruneChildren || !canExpand(descriptor, scope, cache)) {
            return childCountText(0, 0, filter)
        }

        return cache.visibleDescendantCountText(
            descriptor.file,
            direction,
            scope,
            filter,
            showOutOfScopeLeaves,
            ancestorFilePaths(descriptor),
        )
    }
}

class FlatIncludeStructure(
    private val projectRef: Project,
    file: PsiFile,
    private val state: IncludeFilterState,
    private val direction: IncludeDirection,
    private val cache: IncludeGraphCache,
    private val autoloadFilesProvider: () -> List<PsiFile>?,
) : HierarchyTreeStructure(
    projectRef,
    IncludeNodeDescriptor(projectRef, null, file, true, showFullPath = state.showFullPath),
) {
    override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> {
        val includeDescriptor = descriptor as? IncludeNodeDescriptor ?: return EMPTY_CHILDREN
        if (includeDescriptor.parentDescriptor != null) {
            return EMPTY_CHILDREN
        }

        val scope = state.scope
        val filter = state.filter
        val showOutOfScopeLeaves = state.showDirectOutOfScopeLeaves
        val showRowCounts = state.showChildrenCount && !isUnboundedIncludeScope(scope)
        val files = autoloadFilesProvider()
            ?.filter { childFile -> cache.matchesFilter(childFile, filter) }
            ?: cache.flatFiles(includeDescriptor.file, direction, scope, filter, showOutOfScopeLeaves)
        val totalFiles = if (state.showChildrenCount && !filter.isEmpty) {
            autoloadFilesProvider()?.size
                ?: cache.flatFiles(
                    includeDescriptor.file,
                    direction,
                    scope,
                    IncludeHierarchyFilter.empty(filter.includePath),
                    showOutOfScopeLeaves,
                ).size
        } else {
            files.size
        }

        includeDescriptor.updateChildCount(
            if (state.showChildrenCount) {
                childCountText(files.size, totalFiles, filter)
            } else {
                null
            },
        )

        return files.asSequence()
            .sortedWith(PSI_FILE_COMPARATOR)
            .map { childFile ->
                val childCount = if (showRowCounts) {
                    cache.visibleDescendantCountText(childFile, direction, scope, filter, showOutOfScopeLeaves)
                } else {
                    null
                }
                IncludeNodeDescriptor(
                    projectRef,
                    includeDescriptor,
                    childFile,
                    false,
                    showFullPath = state.showFullPath,
                    childCount = childCount,
                )
            }
            .toList()
            .toTypedArray()
    }
}

private fun canExpand(
    descriptor: IncludeNodeDescriptor,
    scope: SearchScope,
    cache: IncludeGraphCache,
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
    descriptor: IncludeNodeDescriptor,
    file: PsiFile,
): Boolean {
    val path = file.virtualFile?.path ?: return false
    var parent = descriptor.parentDescriptor

    while (parent is IncludeNodeDescriptor) {
        if (parent.file.virtualFile?.path == path) {
            return true
        }
        parent = parent.parentDescriptor
    }

    return false
}

private fun ancestorFilePaths(descriptor: IncludeNodeDescriptor): Set<String> {
    val paths = linkedSetOf<String>()
    var parent = descriptor.parentDescriptor

    while (parent is IncludeNodeDescriptor) {
        parent.file.virtualFile?.path?.let { paths.add(it) }
        parent = parent.parentDescriptor
    }

    return paths
}