package com.github.cvzakharchenko.includesanalysis

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

// Background BFS that mirrors IncludeGraphCache.scopeBoundedDfs but pre-populates
// IncludeGraphCache.directRelated for the whole reachable set. Each finished node
// bumps the processed counter; each newly seen child bumps the discovered counter.
// Progress callbacks from stale generations are dropped so an old run can't
// overwrite the UI after a restart.
class IncludeGraphAutoloader(
    private val cache: IncludeGraphCache,
    private val onProgress: (processed: Int, discovered: Int, done: Boolean) -> Unit,
) {
    private val log = Logger.getInstance(IncludeGraphAutoloader::class.java)
    private val generation = AtomicLong(0)

    @Volatile
    private var task: Future<*>? = null

    @Volatile
    private var indicator: ProgressIndicator? = null

    fun start(base: PsiFile, direction: IncludeDirection, state: IncludeFilterState) {
        cancel()
        val gen = generation.incrementAndGet()
        val showOutOfScopeLeaf = state.showFirstOutOfScopeLeaf
        // Snapshot the scope check; SearchScope.contains is read-action-safe.
        val inScope: (PsiFile) -> Boolean = { state.acceptsScope(it.virtualFile) }
        val ind = EmptyProgressIndicator()
        indicator = ind
        task = ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // runBlockingCancellable (used inside IncludeGraphCache.directRelated)
                // needs either a coroutine Job or a ProgressIndicator on the current
                // thread, otherwise it fails with "There is no ProgressIndicator or
                // Job in this thread". Plain executeOnPooledThread provides neither,
                // so we install one. As a bonus, cancelling the indicator propagates
                // cancellation into any in-flight RPC call.
                ProgressManager.getInstance().runProcess(
                    { run(gen, base, direction, showOutOfScopeLeaf, inScope) },
                    ind,
                )
            } catch (e: ProcessCanceledException) {
                // expected on cancel()
            }
        }
    }

    fun cancel() {
        generation.incrementAndGet()
        indicator?.cancel()
        indicator = null
        task = null
    }

    private fun run(
        gen: Long,
        base: PsiFile,
        direction: IncludeDirection,
        showOutOfScopeLeaf: Boolean,
        inScope: (PsiFile) -> Boolean,
    ) {
        try {
            val seen = LinkedHashSet<PsiFile>()
            val queue = ArrayDeque<PsiFile>()
            seen.add(base)
            queue.addLast(base)
            var processed = 0
            emit(gen, processed, seen.size, done = false)
            while (queue.isNotEmpty()) {
                if (gen != generation.get()) return
                val node = queue.removeFirst()
                val children = try {
                    cache.directRelated(node, direction)
                } catch (e: ProcessCanceledException) {
                    return
                } catch (e: Throwable) {
                    log.warn("autoloader directRelated failed", e)
                    emptyList()
                }
                for (child in children) {
                    if (!seen.add(child)) continue
                    if (inScope(child)) {
                        queue.addLast(child)
                    } else if (!showOutOfScopeLeaf) {
                        // Out-of-scope and we don't want it as a leaf either; the BFS
                        // wouldn't reach it through this branch in the structure, so
                        // undo the seen insert to keep the count honest.
                        seen.remove(child)
                    }
                }
                processed++
                emit(gen, processed, seen.size, done = false)
            }
            emit(gen, processed, seen.size, done = true)
        } catch (e: ProcessCanceledException) {
            // ignored — cancel path
        } catch (e: Throwable) {
            log.warn("autoloader failed", e)
        }
    }

    private fun emit(gen: Long, processed: Int, discovered: Int, done: Boolean) {
        if (gen != generation.get()) return
        onProgress(processed, discovered, done)
    }
}
