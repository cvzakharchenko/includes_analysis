package com.github.cvzakharchenko.includesanalysis.hierarchy

import com.intellij.icons.AllIcons
import com.intellij.ide.hierarchy.HierarchyBrowserBaseEx
import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.util.treeView.AlphaComparator
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.ide.util.scopeChooser.ScopeChooserCombo
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.DefaultCustomComponentAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ide.util.PropertiesComponent
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.PopupHandler
import com.intellij.ui.SearchTextField
import com.jetbrains.rider.cpp.fileType.psi.CppFile
import com.jetbrains.rider.model.CppIncludeGraph
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode

class ScopedIncludeHierarchyBrowser(
    private val projectRef: Project,
    file: PsiElement,
    private val includeGraph: CppIncludeGraph,
) : HierarchyBrowserBaseEx(projectRef, file) {
    private val properties = PropertiesComponent.getInstance(projectRef)
    private var selectedScope: SearchScope = GlobalSearchScope.projectScope(projectRef)
    private var selectedScopeName: String? = properties.getValue(SCOPE_NAME_PROPERTY)
    private val includeesCache = IncludeHierarchyCache(projectRef, includeGraph.getIncludees)
    private val includersCache = IncludeHierarchyCache(projectRef, includeGraph.getIncluders)
    private var flatMode = properties.getBoolean(FLAT_MODE_PROPERTY, false)
    private var showIncluders = properties.getBoolean(SHOW_INCLUDERS_PROPERTY, false)
    private var showOutOfScopeLeaves = properties.getBoolean(SHOW_OUT_OF_SCOPE_LEAVES_PROPERTY, false)
    private var hideRepeatedIncludes = properties.getBoolean(HIDE_REPEATED_INCLUDES_PROPERTY, false)
    private var filterByFilePath = properties.getBoolean(FILTER_BY_FILE_PATH_PROPERTY, false)
    private var showFullFilePath = properties.getBoolean(SHOW_FULL_FILE_PATH_PROPERTY, false)
    private var showChildCounts = properties.getBoolean(SHOW_CHILD_COUNTS_PROPERTY, false)
    private var autoloadHierarchy = properties.getBoolean(AUTOLOAD_HIERARCHY_PROPERTY, false)
    private var filter = IncludeHierarchyFilter.empty(filterByFilePath)
    private val autoloadFilesLock = Any()
    private val autoloadRefreshQueued = AtomicBoolean(false)
    private var autoloadFuture: Future<*>? = null
    private var autoloadGeneration = 0
    private val autoloadFilesByPath = LinkedHashMap<String, CppFile>()
    private val filterRefreshTimer = Timer(FILTER_REFRESH_DELAY_MS) {
        refreshFilterResults()
    }.apply {
        isRepeats = false
    }
    private val autoloadRefreshTimer = Timer(AUTOLOAD_REFRESH_DELAY_MS) {
        refreshAutoloadResults()
    }.apply {
        isRepeats = false
    }
    private val filterField: SearchTextField by lazy { createFilterField() }

    val initialViewType: String
        get() = viewTypeForCurrentState()

    override fun getElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement? =
        (descriptor as? ScopedIncludeHierarchyNodeDescriptor)?.file

    override fun getPrevOccurenceActionNameImpl(): String = "Previous File"

    override fun getNextOccurenceActionNameImpl(): String = "Next File"

    override fun getActionPlace(): String = "TypeHierarchyViewToolbar"

    override fun isApplicableElement(element: PsiElement): Boolean = element is CppFile

    override fun getComparator(): Comparator<NodeDescriptor<*>> = AlphaComparator.getInstance()

    override fun createHierarchyTreeStructure(typeName: String, element: PsiElement): HierarchyTreeStructure? {
        val file = element as? CppFile ?: return null
        val cache = cacheFor(typeName)
        return if (isFlatView(typeName)) {
            FlatIncludeHierarchyTreeStructure(
                projectRef,
                file,
                cache,
                { selectedScope },
                { filter },
                { showOutOfScopeLeaves },
                { showFullFilePath },
                { showChildCounts },
                { autoloadFilesSnapshot() },
            )
        } else {
            ScopedIncludeHierarchyTreeStructure(
                projectRef,
                file,
                cache,
                { selectedScope },
                { filter },
                { showOutOfScopeLeaves },
                { hideRepeatedIncludes },
                { showFullFilePath },
                { showChildCounts },
            )
        }
    }

    override fun createLegendPanel(): javax.swing.JPanel? = null

    override fun doRefresh(currentBuilderOnly: Boolean) {
        cancelAutoload()
        includeesCache.clear()
        includersCache.clear()
        refreshTree(currentBuilderOnly)
        startAutoloadIfEnabled()
    }

    private fun rebuildWithoutClearingCaches(currentBuilderOnly: Boolean) {
        refreshTree(currentBuilderOnly)
    }

    private fun refreshTree(currentBuilderOnly: Boolean) {
        if (currentViewType == null || !hasCurrentTreeRoot()) {
            ApplicationManager.getApplication().invokeLater {
                if (!isDisposed && currentViewType != null && hasCurrentTreeRoot()) {
                    refreshTree(currentBuilderOnly)
                }
            }
            return
        }

        ApplicationManager.getApplication().runReadAction {
            if (hasCurrentTreeRoot()) {
                super.doRefresh(currentBuilderOnly)
            }
        }
    }

    private fun hasCurrentTreeRoot(): Boolean {
        val tree = runCatching { currentTree }.getOrNull() ?: return false
        return tree.model?.root != null
    }

    override fun createTrees(trees: MutableMap<in String, in JTree>) {
        val popupGroup = DefaultActionGroup().apply {
            add(FilterByFileNameAction())
        } as ActionGroup

        val includeesTree = createIncludeTree(popupGroup)
        trees[INCLUDEES_TREE_VIEW] = includeesTree

        val includeesFlatTree = createIncludeTree(popupGroup)
        trees[INCLUDEES_FLAT_VIEW] = includeesFlatTree

        val includersTree = createIncludeTree(popupGroup)
        trees[INCLUDERS_TREE_VIEW] = includersTree

        val includersFlatTree = createIncludeTree(popupGroup)
        trees[INCLUDERS_FLAT_VIEW] = includersFlatTree
    }

    private fun createIncludeTree(popupGroup: ActionGroup): JTree {
        val tree = createTree(true)
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                selectPopupRow(event)
            }

            override fun mouseReleased(event: MouseEvent) {
                selectPopupRow(event)
            }
        })
        PopupHandler.installPopupMenu(tree, popupGroup, "TypeHierarchyViewPopup")
        return tree
    }

    private fun selectPopupRow(event: MouseEvent) {
        if (!event.isPopupTrigger && !SwingUtilities.isRightMouseButton(event)) {
            return
        }

        val tree = event.source as? JTree ?: return
        val path = tree.getPathForLocation(event.x, event.y) ?: return
        tree.selectionPath = path
    }

    override fun getPresentableNameMap(): MutableMap<String, Supplier<String>> =
        hashMapOf(
            INCLUDEES_TREE_VIEW to Supplier { "Includes" },
            INCLUDEES_FLAT_VIEW to Supplier { "Includes Flat" },
            INCLUDERS_TREE_VIEW to Supplier { "Includers" },
            INCLUDERS_FLAT_VIEW to Supplier { "Includers Flat" },
        )

    override fun prependActions(actionGroup: DefaultActionGroup) {
        actionGroup.add(DirectionModeAction())
        actionGroup.add(FlatModeAction())
        actionGroup.add(OptionsGroup())
        actionGroup.add(DefaultCustomComponentAction { filterField })
        actionGroup.add(DefaultCustomComponentAction { createScopeChooser() })
        super.prependActions(actionGroup)
    }

    private fun createScopeChooser(): ScopeChooserCombo {
        val chooser = ScopeChooserCombo(projectRef, false, true, selectedScopeName ?: DEFAULT_SCOPE_NAME)
        shrinkScopeChooser(chooser)
        updateSelectedScope(chooser, refresh = false)
        chooser.childComponent.addActionListener {
            updateSelectedScope(chooser, refresh = true)
        }
        Disposer.register(this, chooser)
        return chooser
    }

    private fun updateSelectedScope(chooser: ScopeChooserCombo, refresh: Boolean) {
        val scope = chooser.selectedScope ?: GlobalSearchScope.projectScope(projectRef)
        val scopeName = scope.displayName
        val changed = scope != selectedScope || scopeName != selectedScopeName
        selectedScope = scope
        selectedScopeName = scopeName
        properties.setValue(SCOPE_NAME_PROPERTY, scopeName)

        if (refresh && changed) {
            rebuildWithoutClearingCaches(false)
            restartAutoload()
        }
    }

    private fun shrinkScopeChooser(chooser: ScopeChooserCombo) {
        val height = chooser.preferredSize.height
        val size = Dimension(SCOPE_CHOOSER_WIDTH, height)
        chooser.preferredSize = size
        chooser.minimumSize = size
        chooser.maximumSize = size
    }

    private fun createFilterField(): SearchTextField {
        val field = SearchTextField(false)
        field.textEditor.emptyText.text = "Filter"
        field.textEditor.columns = 18
        field.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                val nextFilter = IncludeHierarchyFilter.from(field.text, filterByFilePath)
                if (nextFilter != filter) {
                    filter = nextFilter
                    filterRefreshTimer.restart()
                }
            }
        })
        return field
    }

    private fun cacheFor(typeName: String): IncludeHierarchyCache =
        if (isIncludersView(typeName)) {
            includersCache
        } else {
            includeesCache
        }

    private fun viewTypeForCurrentState(): String =
        when {
            showIncluders && flatMode -> INCLUDERS_FLAT_VIEW
            showIncluders -> INCLUDERS_TREE_VIEW
            flatMode -> INCLUDEES_FLAT_VIEW
            else -> INCLUDEES_TREE_VIEW
        }

    private fun switchToCurrentView() {
        val typeName = viewTypeForCurrentState()
        val needsRefresh = hasViewModel(typeName)
        changeView(typeName, false)
        if (needsRefresh) {
            rebuildWithoutClearingCaches(true)
        }
    }

    private fun selectedDescriptorFile(): CppFile? {
        val tree = runCatching { currentTree }.getOrNull() ?: return null
        val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return (node.userObject as? ScopedIncludeHierarchyNodeDescriptor)?.file
    }

    private fun replaceFilterText(text: String) {
        val editor = filterField.textEditor
        if (editor.text == text) {
            val nextFilter = IncludeHierarchyFilter.from(text, filterByFilePath)
            if (nextFilter != filter) {
                filter = nextFilter
                filterRefreshTimer.restart()
            }
        } else {
            editor.text = text
        }

        editor.caretPosition = editor.document.length
        editor.requestFocusInWindow()
    }

    fun startAutoloadIfEnabled() {
        if (autoloadHierarchy) {
            restartAutoload()
        }
    }

    private fun restartAutoload() {
        cancelAutoload()
        if (!autoloadHierarchy) {
            clearAutoloadFiles()
            return
        }

        val baseFile = getHierarchyBase() as? CppFile
        if (baseFile == null) {
            clearAutoloadFiles()
            queueAutoloadRefresh()
            return
        }

        clearAutoloadFiles()
        val generation = autoloadGeneration
        val cache = cacheFor(viewTypeForCurrentState())
        val scope = selectedScope
        val showLeaves = showOutOfScopeLeaves
        queueAutoloadRefresh(generation)
        autoloadFuture = ApplicationManager.getApplication().executeOnPooledThread {
            val finalProgress = runCatching {
                cache.autoloadHierarchy(
                    baseFile,
                    scope,
                    showLeaves,
                    shouldCancel = {
                        isDisposed || generation != autoloadGeneration || Thread.currentThread().isInterrupted
                    },
                    onProgress = { _ ->
                        if (generation == autoloadGeneration) {
                            queueAutoloadRefresh(generation)
                        }
                    },
                    onDiscoveredFile = { discoveredFile, inScope ->
                        if (generation == autoloadGeneration && inScope) {
                            rememberAutoloadFile(discoveredFile)
                        }
                    },
                )
            }.getOrNull()

            if (finalProgress != null && generation == autoloadGeneration) {
                queueAutoloadRefresh(generation)
            }
        }
    }

    private fun cancelAutoload() {
        autoloadGeneration++
        autoloadFuture?.cancel(true)
        autoloadFuture = null
        autoloadRefreshTimer.stop()
    }

    private fun clearAutoloadFiles() {
        synchronized(autoloadFilesLock) {
            autoloadFilesByPath.clear()
        }
    }

    private fun rememberAutoloadFile(file: CppFile) {
        val path = file.virtualFile?.path ?: return
        synchronized(autoloadFilesLock) {
            autoloadFilesByPath[path] = file
        }
    }

    private fun autoloadFilesSnapshot(): List<CppFile>? {
        if (!autoloadHierarchy) {
            return null
        }

        return synchronized(autoloadFilesLock) {
            autoloadFilesByPath.values.toList()
        }
    }

    private fun queueAutoloadRefresh(generation: Int = autoloadGeneration) {
        if (generation != autoloadGeneration) {
            return
        }

        if (!autoloadRefreshQueued.compareAndSet(false, true)) {
            return
        }

        SwingUtilities.invokeLater {
            autoloadRefreshQueued.set(false)
            if (isDisposed) {
                return@invokeLater
            }

            if (autoloadHierarchy) {
                autoloadRefreshTimer.restart()
            }
        }
    }

    private fun refreshAutoloadResults() {
        if (!autoloadHierarchy || isDisposed) {
            return
        }

        rebuildWithoutClearingCaches(true)
    }

    private fun hasViewModel(typeName: String): Boolean =
        runCatching {
            getTreeModel(typeName)
            true
        }.getOrDefault(false)

    private fun isFlatView(typeName: String): Boolean =
        typeName == INCLUDEES_FLAT_VIEW || typeName == INCLUDERS_FLAT_VIEW

    private fun isIncludersView(typeName: String): Boolean =
        typeName == INCLUDERS_TREE_VIEW || typeName == INCLUDERS_FLAT_VIEW

    private inner class DirectionModeAction : DumbAwareToggleAction("Show Includers") {
        init {
            templatePresentation.description = "Show files that include the current file"
            templatePresentation.icon = AllIcons.Hierarchy.Supertypes
        }

        override fun isSelected(event: AnActionEvent): Boolean = showIncluders

        override fun setSelected(event: AnActionEvent, state: Boolean) {
            if (showIncluders != state) {
                showIncluders = state
                properties.setValue(SHOW_INCLUDERS_PROPERTY, showIncluders, false)
                switchToCurrentView()
                restartAutoload()
            }
        }
    }

    private fun refreshFilterResults() {
        val editor = filterField.textEditor
        val shouldRestoreFocus = editor.isFocusOwner || filterField.isFocusOwner
        val selectionStart = editor.selectionStart
        val selectionEnd = editor.selectionEnd

        rebuildWithoutClearingCaches(true)

        if (shouldRestoreFocus) {
            SwingUtilities.invokeLater {
                if (!editor.isDisplayable) {
                    return@invokeLater
                }

                val textLength = editor.text.length
                editor.requestFocusInWindow()
                editor.select(
                    selectionStart.coerceIn(0, textLength),
                    selectionEnd.coerceIn(0, textLength),
                )
            }
        }
    }

    private inner class FlatModeAction : DumbAwareToggleAction("Flat List") {
        init {
            templatePresentation.description = "Show unique files in a flat list"
            templatePresentation.icon = AllIcons.Actions.ListFiles
        }

        override fun isSelected(event: AnActionEvent): Boolean = flatMode

        override fun setSelected(event: AnActionEvent, state: Boolean) {
            if (flatMode != state) {
                flatMode = state
                properties.setValue(FLAT_MODE_PROPERTY, flatMode, false)
                switchToCurrentView()
            }
        }
    }

    private inner class OptionsGroup : DefaultActionGroup("Options", true) {
        init {
            templatePresentation.description = "Include hierarchy options"
            templatePresentation.icon = AllIcons.General.GearPlain
            add(AutoloadHierarchyAction())
            add(ShowOutOfScopeLeavesAction())
            add(HideRepeatedIncludesAction())
            add(FilterByFilePathAction())
            add(ShowFullFilePathAction())
            add(ShowChildCountsAction())
        }
    }

    private inner class ShowOutOfScopeLeavesAction : DumbAwareToggleAction("Show Direct Out-of-Scope Leaves") {
        override fun isSelected(event: AnActionEvent): Boolean = showOutOfScopeLeaves

        override fun setSelected(event: AnActionEvent, state: Boolean) {
            if (showOutOfScopeLeaves != state) {
                showOutOfScopeLeaves = state
                properties.setValue(SHOW_OUT_OF_SCOPE_LEAVES_PROPERTY, showOutOfScopeLeaves, false)
                rebuildWithoutClearingCaches(false)
                restartAutoload()
            }
        }
    }

    private inner class HideRepeatedIncludesAction : DumbAwareToggleAction("Hide Repeated Includes") {
        override fun isSelected(event: AnActionEvent): Boolean = hideRepeatedIncludes

        override fun setSelected(event: AnActionEvent, state: Boolean) {
            if (hideRepeatedIncludes != state) {
                hideRepeatedIncludes = state
                properties.setValue(HIDE_REPEATED_INCLUDES_PROPERTY, hideRepeatedIncludes, false)
                rebuildWithoutClearingCaches(false)
            }
        }
    }

    private inner class FilterByFilePathAction : DumbAwareToggleAction("Filter by File Path") {
        override fun isSelected(event: AnActionEvent): Boolean = filterByFilePath

        override fun setSelected(event: AnActionEvent, state: Boolean) {
            if (filterByFilePath != state) {
                filterByFilePath = state
                properties.setValue(FILTER_BY_FILE_PATH_PROPERTY, filterByFilePath, false)
                filter = IncludeHierarchyFilter.from(filterField.text, filterByFilePath)
                rebuildWithoutClearingCaches(false)
            }
        }
    }

    private inner class ShowFullFilePathAction : DumbAwareToggleAction("Show Full File Path") {
        override fun isSelected(event: AnActionEvent): Boolean = showFullFilePath

        override fun setSelected(event: AnActionEvent, state: Boolean) {
            if (showFullFilePath != state) {
                showFullFilePath = state
                properties.setValue(SHOW_FULL_FILE_PATH_PROPERTY, showFullFilePath, false)
                rebuildWithoutClearingCaches(false)
            }
        }
    }

    private inner class ShowChildCountsAction : DumbAwareToggleAction("Show Unique Descendant Counts") {
        override fun isSelected(event: AnActionEvent): Boolean = showChildCounts

        override fun setSelected(event: AnActionEvent, state: Boolean) {
            if (showChildCounts != state) {
                showChildCounts = state
                properties.setValue(SHOW_CHILD_COUNTS_PROPERTY, showChildCounts, false)
                rebuildWithoutClearingCaches(false)
            }
        }
    }

    private inner class FilterByFileNameAction : DumbAwareAction("Filter by File Name") {
        init {
            templatePresentation.description = "Replace the filter text with the selected file name"
        }

        override fun actionPerformed(event: AnActionEvent) {
            val file = selectedDescriptorFile() ?: return
            replaceFilterText(file.name)
        }

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = selectedDescriptorFile() != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class AutoloadHierarchyAction : DumbAwareToggleAction("Autoload Hierarchy") {
        override fun isSelected(event: AnActionEvent): Boolean = autoloadHierarchy

        override fun setSelected(event: AnActionEvent, state: Boolean) {
            if (autoloadHierarchy != state) {
                autoloadHierarchy = state
                properties.setValue(AUTOLOAD_HIERARCHY_PROPERTY, autoloadHierarchy, false)
                restartAutoload()
            }
        }
    }

    override fun dispose() {
        cancelAutoload()
        filterRefreshTimer.stop()
        autoloadRefreshTimer.stop()
        super.dispose()
    }

    companion object {
        const val INCLUDEES_TREE_VIEW = "Includees..."
        const val INCLUDEES_FLAT_VIEW = "IncludeesFlat..."
        const val INCLUDERS_TREE_VIEW = "Includers..."
        const val INCLUDERS_FLAT_VIEW = "IncludersFlat..."
        private const val FILTER_REFRESH_DELAY_MS = 200
        private const val PROPERTY_PREFIX = "includes.analysis.hierarchy."
        private const val DEFAULT_SCOPE_NAME = "Project Files"
        private const val SCOPE_NAME_PROPERTY = PROPERTY_PREFIX + "scope.name"
        private const val SHOW_INCLUDERS_PROPERTY = PROPERTY_PREFIX + "show.includers"
        private const val FLAT_MODE_PROPERTY = PROPERTY_PREFIX + "flat.mode"
        private const val SHOW_OUT_OF_SCOPE_LEAVES_PROPERTY = PROPERTY_PREFIX + "show.out.of.scope.leaves"
        private const val HIDE_REPEATED_INCLUDES_PROPERTY = PROPERTY_PREFIX + "hide.repeated.includes"
        private const val FILTER_BY_FILE_PATH_PROPERTY = PROPERTY_PREFIX + "filter.by.file.path"
        private const val SHOW_FULL_FILE_PATH_PROPERTY = PROPERTY_PREFIX + "show.full.file.path"
        private const val SHOW_CHILD_COUNTS_PROPERTY = PROPERTY_PREFIX + "show.child.counts"
        private const val AUTOLOAD_HIERARCHY_PROPERTY = PROPERTY_PREFIX + "autoload.hierarchy"
        private const val AUTOLOAD_REFRESH_DELAY_MS = 500
        private const val SCOPE_CHOOSER_WIDTH = 160
    }
}