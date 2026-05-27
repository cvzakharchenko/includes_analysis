package com.github.cvzakharchenko.includesanalysis

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile
import com.intellij.psi.search.SearchScope
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

class IncludeGraphAutoloader(
    private val cache: IncludeGraphCache,
    private val onDiscoveredFile: (PsiFile) -> Unit,
    private val onRefreshNeeded: (done: Boolean) -> Unit,
) {
    private val log = Logger.getInstance(IncludeGraphAutoloader::class.java)
    private val generation = AtomicLong(0)

    @Volatile
    private var task: Future<*>? = null

    @Volatile
    private var indicator: ProgressIndicator? = null

    fun start(
        base: PsiFile,
        direction: IncludeDirection,
        scope: SearchScope,
        showOutOfScopeLeaves: Boolean,
    ) {
        cancel()
        val gen = generation.incrementAndGet()
        val ind = EmptyProgressIndicator()
        indicator = ind
        task = ApplicationManager.getApplication().executeOnPooledThread {
            try {
                ProgressManager.getInstance().runProcess(
                    { run(gen, base, direction, scope, showOutOfScopeLeaves) },
                    ind,
                )
            } catch (e: ProcessCanceledException) {
                // Expected on cancellation.
            }
        }
    }

    fun cancel() {
        generation.incrementAndGet()
        indicator?.cancel()
        indicator = null
        task?.cancel(true)
        task = null
    }

    private fun run(
        gen: Long,
        base: PsiFile,
        direction: IncludeDirection,
        scope: SearchScope,
        showOutOfScopeLeaves: Boolean,
    ) {
        try {
            cache.autoloadHierarchy(
                base,
                direction,
                scope,
                showOutOfScopeLeaves,
                shouldCancel = {
                    gen != generation.get() || Thread.currentThread().isInterrupted
                },
                onProgress = {
                    if (gen == generation.get()) {
                        onRefreshNeeded(false)
                    }
                },
                onDiscoveredFile = { file, _ ->
                    if (gen == generation.get()) {
                        onDiscoveredFile(file)
                    }
                },
            )
            if (gen == generation.get()) {
                onRefreshNeeded(true)
            }
        } catch (e: ProcessCanceledException) {
            // Expected on cancellation.
        } catch (e: Throwable) {
            log.warn("Include hierarchy autoload failed", e)
        }
    }
}