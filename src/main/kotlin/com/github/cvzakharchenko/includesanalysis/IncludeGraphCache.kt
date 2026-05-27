package com.github.cvzakharchenko.includesanalysis

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.jetbrains.rd.framework.impl.RpcTimeouts
import com.jetbrains.rider.model.cppIncludeGraph
import com.jetbrains.rider.projectView.hasSolution
import com.jetbrains.rider.projectView.solution
import java.util.concurrent.ConcurrentHashMap

class IncludeGraphCache(private val project: Project) {

    private val log = Logger.getInstance(IncludeGraphCache::class.java)

    private data class Key(val direction: IncludeDirection, val path: String)

    private val direct = ConcurrentHashMap<Key, List<PsiFile>>()

    // Scope-aware: in-scope descendants reachable through in-scope paths (plus the
    // immediate out-of-scope ring when showFirstOutOfScopeLeaf is on). Must be cleared
    // whenever scope or the leaf option changes, via invalidateScopeBounded().
    private val scopeBounded = ConcurrentHashMap<Key, Set<PsiFile>>()

    fun directRelated(file: PsiFile, direction: IncludeDirection): List<PsiFile> {
        val path = file.virtualFile?.path ?: return emptyList()
        val key = Key(direction, path)
        direct[key]?.let { return it }
        if (!project.hasSolution) return emptyList()

        val childPaths: List<String> = try {
            val graph = project.solution.cppIncludeGraph
            val call = when (direction) {
                IncludeDirection.INCLUDEES -> graph.getIncludees
                IncludeDirection.INCLUDERS -> graph.getIncluders
            }
            call.sync(path, RpcTimeouts.default)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Throwable) {
            log.warn("CppIncludeGraph $direction failed for $path", e)
            emptyList()
        }

        val vfs = LocalFileSystem.getInstance()
        val psiManager = PsiManager.getInstance(project)
        val resolved = ReadAction.compute<List<PsiFile>, RuntimeException> {
            val seen = LinkedHashSet<PsiFile>()
            for (childPath in childPaths) {
                val vf = vfs.findFileByPath(childPath) ?: continue
                val psi = psiManager.findFile(vf) ?: continue
                seen.add(psi)
            }
            seen.toList()
        }
        direct[key] = resolved
        return resolved
    }

    fun scopeBoundedReachable(
        start: PsiFile,
        direction: IncludeDirection,
        state: IncludeFilterState,
    ): Set<PsiFile> {
        if (start.virtualFile?.path == null) return emptySet()
        val result = scopeBoundedDfs(start, direction, state, HashSet()).reachable
        if (start !in result) return result
        val copy = LinkedHashSet(result)
        copy.remove(start)
        return copy
    }

    private data class ScopedResult(val reachable: Set<PsiFile>, val complete: Boolean)

    private fun scopeBoundedDfs(
        file: PsiFile,
        direction: IncludeDirection,
        state: IncludeFilterState,
        ancestors: MutableSet<PsiFile>,
    ): ScopedResult {
        val path = file.virtualFile?.path ?: return ScopedResult(emptySet(), true)
        val key = Key(direction, path)
        scopeBounded[key]?.let { return ScopedResult(it, true) }
        if (!ancestors.add(file)) return ScopedResult(emptySet(), false)
        try {
            val reachable = LinkedHashSet<PsiFile>()
            var complete = true
            for (child in directRelated(file, direction)) {
                if (child in reachable) continue
                val childInScope = state.acceptsScope(child.virtualFile)
                if (childInScope) {
                    reachable.add(child)
                    val sub = scopeBoundedDfs(child, direction, state, ancestors)
                    reachable.addAll(sub.reachable)
                    if (!sub.complete) complete = false
                } else if (state.showFirstOutOfScopeLeaf) {
                    reachable.add(child)
                }
            }
            if (complete) scopeBounded[key] = reachable
            return ScopedResult(reachable, complete)
        } finally {
            ancestors.remove(file)
        }
    }

    fun invalidateScopeBounded() {
        scopeBounded.clear()
    }

    fun clear() {
        direct.clear()
        scopeBounded.clear()
    }
}
