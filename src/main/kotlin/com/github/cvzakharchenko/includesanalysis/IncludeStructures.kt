package com.github.cvzakharchenko.includesanalysis

import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

class IncludeTreeStructure(
    project: Project,
    baseFile: PsiFile,
    private val state: IncludeFilterState,
    private val direction: IncludeDirection,
    private val cache: IncludeGraphCache,
) : HierarchyTreeStructure(project, IncludeNodeDescriptor(project, null, baseFile, true, state, cache, direction)) {

    // Per-structure memos. A new IncludeTreeStructure is created on every doRefresh,
    // so these are pinned to one (scope, query, option) configuration and live for
    // the duration of one rebuild. They turn repeated subtrees into O(1) lookups
    // instead of repeated filter / walk passes.
    private val visibleChildrenCache = HashMap<PsiFile, List<PsiFile>>()
    private val passesQueryCache = HashMap<PsiFile, Boolean>()
    private val scopeCache = HashMap<VirtualFile, Boolean>()

    // Tracks the first descriptor that expanded a given file. With skipDuplicateSubtree on,
    // any later descriptor for the same file renders as a leaf — the subtree lives at
    // exactly one site in the tree.
    private val firstExpander = HashMap<PsiFile, HierarchyNodeDescriptor>()

    override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> {
        val file = descriptor.psiElement as? PsiFile ?: return emptyArray()
        val isBase = descriptor.parentDescriptor == null
        if (!isBase && !acceptsScope(file)) return emptyArray()

        if (!isBase && state.skipDuplicateSubtree) {
            val existing = firstExpander.putIfAbsent(file, descriptor)
            if (existing != null && existing !== descriptor) return emptyArray()
        }

        return visibleChildren(file)
            .map { IncludeNodeDescriptor(myProject, descriptor, it, false, state, cache, direction) }
            .toTypedArray()
    }

    private fun acceptsScope(file: PsiFile): Boolean {
        val vf = file.virtualFile ?: return false
        return scopeCache.getOrPut(vf) { state.acceptsScope(vf) }
    }

    private fun visibleChildren(file: PsiFile): List<PsiFile> {
        visibleChildrenCache[file]?.let { return it }
        val candidates = cache.directRelated(file, direction).filter { child ->
            acceptsScope(child) || state.showFirstOutOfScopeLeaf
        }
        val result = if (state.query.isEmpty()) candidates else candidates.filter { passesQuery(it) }
        visibleChildrenCache[file] = result
        return result
    }

    private fun passesQuery(file: PsiFile): Boolean {
        passesQueryCache[file]?.let { return it }
        val result = when {
            state.matchesQuery(file) -> true
            // Out-of-scope leaves have no descendants in our tree; only their name matters.
            !acceptsScope(file) -> false
            else -> cache.scopeBoundedReachable(file, direction, state)
                .any { state.matchesQuery(it) }
        }
        passesQueryCache[file] = result
        return result
    }
}

class FlatIncludeStructure(
    project: Project,
    baseFile: PsiFile,
    private val state: IncludeFilterState,
    private val direction: IncludeDirection,
    private val cache: IncludeGraphCache,
) : HierarchyTreeStructure(project, IncludeNodeDescriptor(project, null, baseFile, true, state, cache, direction)) {

    override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> {
        if (descriptor.parentDescriptor != null) return emptyArray()
        val base = descriptor.psiElement as? PsiFile ?: return emptyArray()

        return cache.scopeBoundedReachable(base, direction, state)
            .asSequence()
            .filter { state.matchesQuery(it) }
            .sortedBy { it.name.lowercase() }
            .map { IncludeNodeDescriptor(myProject, descriptor, it, false, state, cache, direction) }
            .toList()
            .toTypedArray()
    }
}
