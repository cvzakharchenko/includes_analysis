package com.github.cvzakharchenko.includesanalysis

import com.intellij.icons.AllIcons
import com.intellij.ide.hierarchy.HierarchyBrowserBaseEx
import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.util.scopeChooser.ScopeChooserCombo
import com.intellij.ide.util.treeView.AlphaComparator
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.PopupHandler
import com.intellij.ui.SearchTextField
import com.intellij.util.Alarm
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Comparator
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode

class IncludeHierarchyBrowser(
    project: Project,
    private val baseFile: PsiFile,
) : HierarchyBrowserBaseEx(project, baseFile) {
    private val settings = IncludeHierarchySettings.getInstance(project)
    private val state = IncludeFilterState(GlobalSearchScope.projectScope(project)).also {
        it.direction = settings.direction
        it.flat = settings.flat
        it.showDirectOutOfScopeLeaves = settings.showDirectOutOfScopeLeaves
        it.hideRepeatedIncludes = settings.hideRepeatedIncludes
        it.filterByPath = settings.filterByPath
        it.showFullPath = settings.showFullPath
        it.showChildrenCount = settings.showChildrenCount
        it.autoload = settings.autoload
        it.filter = IncludeHierarchyFilter.empty(settings.filterByPath)
    }
    private val cache = IncludeGraphCache(project)
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val autoloadRefreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val autoloadFilesLock = Any()
    private val autoloadFilesByPath = LinkedHashMap<String, PsiFile>()
    private var autoloadRefreshPending = false
    private var filterField: SearchTextField? = null
    private var skipCacheClear = false
    private val autoloader = IncludeGraphAutoloader(
        cache,
        onDiscoveredFile = { file -> rememberAutoloadFile(file) },
        onRefreshNeeded = { done -> SwingUtilities.invokeLater { onAutoloadProgress(done) } },
    )

    init {
        Disposer.register(this) { autoloader.cancel() }
        ApplicationManager.getApplication().invokeLater {
            if (!isDisposed && state.autoload) {
                restartAutoload()
            }
        }
    }

    companion object {
        const val TYPE_INCLUDEES_TREE: String = "What This File Includes"
        const val TYPE_INCLUDEES_FLAT: String = "What This File Includes (Flat)"
        const val TYPE_INCLUDERS_TREE: String = "What Includes This File"
        const val TYPE_INCLUDERS_FLAT: String = "What Includes This File (Flat)"
        private const val DEFAULT_SCOPE_NAME = "Project Files"
        private const val FILTER_REFRESH_DELAY_MS = 200
        private const val AUTOLOAD_REFRESH_DELAY_MS = 500
        private const val SCOPE_CHOOSER_WIDTH = 128
    }

    fun initialViewType(): String = typeNameFor(state)

    override fun isApplicableElement(element: PsiElement): Boolean = element is PsiFile

    override fun getElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement? =
        (descriptor as? IncludeNodeDescriptor)?.file

    override fun createTrees(trees: MutableMap<in String, in JTree>) {
        val popupGroup = DefaultActionGroup().apply {
            add(FilterByFileNameAction())
        } as ActionGroup
        trees[TYPE_INCLUDEES_TREE] = createIncludeTree(popupGroup)
        trees[TYPE_INCLUDEES_FLAT] = createIncludeTree(popupGroup)
        trees[TYPE_INCLUDERS_TREE] = createIncludeTree(popupGroup)
        trees[TYPE_INCLUDERS_FLAT] = createIncludeTree(popupGroup)
    }

    private fun createIncludeTree(popupGroup: ActionGroup): JTree {
        val tree = createTree(true)
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) = selectPopupRow(event)
            override fun mouseReleased(event: MouseEvent) = selectPopupRow(event)
        })
        PopupHandler.installPopupMenu(tree, popupGroup, "IncludeHierarchyViewPopup")
        return tree
    }

    private fun selectPopupRow(event: MouseEvent) {
        if (!event.isPopupTrigger && !SwingUtilities.isRightMouseButton(event)) return
        val tree = event.source as? JTree ?: return
        val path = tree.getPathForLocation(event.x, event.y) ?: return
        tree.selectionPath = path
    }

    private fun selectedDescriptorFile(): PsiFile? {
        val tree = runCatching { currentTree }.getOrNull() ?: return null
        val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return (node.userObject as? IncludeNodeDescriptor)?.file
    }

    override fun createHierarchyTreeStructure(typeName: String, psiElement: PsiElement): HierarchyTreeStructure? {
        val file = psiElement as? PsiFile ?: return null
        return when (typeName) {
            TYPE_INCLUDEES_TREE -> IncludeTreeStructure(myProject, file, state, IncludeDirection.INCLUDEES, cache)
            TYPE_INCLUDEES_FLAT -> FlatIncludeStructure(
                myProject,
                file,
                state,
                IncludeDirection.INCLUDEES,
                cache,
                { autoloadFilesSnapshot() },
            )
            TYPE_INCLUDERS_TREE -> IncludeTreeStructure(myProject, file, state, IncludeDirection.INCLUDERS, cache)
            TYPE_INCLUDERS_FLAT -> FlatIncludeStructure(
                myProject,
                file,
                state,
                IncludeDirection.INCLUDERS,
                cache,
                { autoloadFilesSnapshot() },
            )
            else -> null
        }
    }

    override fun doRefresh(currentBuilderOnly: Boolean) {
        val cacheCleared = !skipCacheClear
        if (cacheCleared) {
            autoloader.cancel()
            clearAutoloadFiles()
            cache.clear()
        }
        super.doRefresh(currentBuilderOnly)
        if (cacheCleared) {
            restartAutoload()
        }
    }

    override fun getComparator(): Comparator<NodeDescriptor<*>> = AlphaComparator.getInstance()

    override fun getActionPlace(): String = ActionPlaces.UNKNOWN

    override fun getPrevOccurenceActionNameImpl(): String = "Previous Include"

    override fun getNextOccurenceActionNameImpl(): String = "Next Include"

    override fun createLegendPanel(): javax.swing.JPanel? = null

    override fun appendActions(actionGroup: DefaultActionGroup, helpID: String?) {
        actionGroup.add(DirectionToggleAction())
        actionGroup.add(FlatToggleAction())
        actionGroup.add(OptionsDropdownGroup())
        actionGroup.add(Separator())
        actionGroup.add(FilterFieldAction())
        actionGroup.add(ScopeChooserAction())
        actionGroup.add(Separator())
        super.appendActions(actionGroup, helpID)
    }

    private fun typeNameFor(state: IncludeFilterState): String = when (state.direction) {
        IncludeDirection.INCLUDEES -> if (state.flat) TYPE_INCLUDEES_FLAT else TYPE_INCLUDEES_TREE
        IncludeDirection.INCLUDERS -> if (state.flat) TYPE_INCLUDERS_FLAT else TYPE_INCLUDERS_TREE
    }

    private fun scheduleRefresh() {
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest({ refreshAllViews() }, FILTER_REFRESH_DELAY_MS)
    }

    private fun refreshAllViews() {
        if (!hasCurrentTreeRoot()) {
            ApplicationManager.getApplication().invokeLater {
                if (!isDisposed && hasCurrentTreeRoot()) {
                    refreshAllViews()
                }
            }
            return
        }

        val field = filterField
        val editor = field?.textEditor
        val hadFocus = editor?.hasFocus() == true
        val selectionStart = editor?.selectionStart ?: 0
        val selectionEnd = editor?.selectionEnd ?: 0
        skipCacheClear = true
        try {
            ApplicationManager.getApplication().runReadAction {
                doRefresh(false)
            }
        } finally {
            skipCacheClear = false
        }

        if (hadFocus) {
            SwingUtilities.invokeLater {
                if (!editor.isDisplayable) return@invokeLater
                val textLength = editor.text.length
                editor.requestFocusInWindow()
                editor.select(
                    selectionStart.coerceIn(0, textLength),
                    selectionEnd.coerceIn(0, textLength),
                )
            }
        }
    }

    private fun hasCurrentTreeRoot(): Boolean {
        val tree = runCatching { currentTree }.getOrNull() ?: return false
        return tree.model?.root != null
    }

    private inner class ScopeChooserAction : AnAction(), CustomComponentAction {
        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            val preselect = settings.scopeName ?: DEFAULT_SCOPE_NAME
            val combo = ScopeChooserCombo(myProject, false, true, preselect)
            Disposer.register(this@IncludeHierarchyBrowser, combo)
            val height = combo.preferredSize.height
            val size = Dimension(SCOPE_CHOOSER_WIDTH, height)
            combo.preferredSize = size
            combo.minimumSize = size
            combo.maximumSize = size

            updateSelectedScope(combo, refresh = true)
            combo.childComponent.addActionListener {
                updateSelectedScope(combo, refresh = true)
            }
            return combo
        }

        override fun actionPerformed(e: AnActionEvent) = Unit

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = true
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private fun updateSelectedScope(combo: ScopeChooserCombo, refresh: Boolean) {
        val scope = combo.selectedScope ?: GlobalSearchScope.projectScope(myProject)
        val scopeName = scope.displayName
        val changed = scope != state.scope || scopeName != settings.scopeName
        state.scope = scope
        settings.scopeName = scopeName

        if (refresh && changed) {
            cache.invalidateScopeBounded()
            refreshAllViews()
            restartAutoload()
        }
    }

    private inner class DirectionToggleAction : ToggleAction(
        "Show Includers",
        "Toggle between what this file includes and what files include this one",
        AllIcons.Hierarchy.Supertypes,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = state.direction == IncludeDirection.INCLUDERS

        override fun setSelected(e: AnActionEvent, value: Boolean) {
            val direction = if (value) IncludeDirection.INCLUDERS else IncludeDirection.INCLUDEES
            if (state.direction == direction) return
            state.direction = direction
            settings.direction = direction
            changeView(typeNameFor(state))
            refreshAllViews()
            restartAutoload()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class FlatToggleAction : ToggleAction(
        "Flat List",
        "Show a flat list of unique reachable includes",
        AllIcons.Actions.ListFiles,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = state.flat

        override fun setSelected(e: AnActionEvent, value: Boolean) {
            if (state.flat == value) return
            state.flat = value
            settings.flat = value
            changeView(typeNameFor(state))
            refreshAllViews()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class FilterFieldAction : AnAction(), CustomComponentAction {
        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            val field = SearchTextField(false)
            field.textEditor.emptyText.text = "Filter"
            field.preferredSize = Dimension(200, field.preferredSize.height)
            field.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    val nextFilter = IncludeHierarchyFilter.from(field.text, state.filterByPath)
                    if (nextFilter != state.filter) {
                        state.filter = nextFilter
                        scheduleRefresh()
                    }
                }
            })
            filterField = field
            return field
        }

        override fun actionPerformed(e: AnActionEvent) = Unit

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = true
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class OptionsDropdownGroup : DefaultActionGroup("Options", true) {
        init {
            templatePresentation.icon = AllIcons.General.GearPlain
            templatePresentation.description = "Display options"
            add(ShowOutOfScopeLeafToggleAction())
            add(HideRepeatedIncludesToggleAction())
            add(FilterByPathToggleAction())
            add(ShowFullPathToggleAction())
            add(ShowChildrenCountToggleAction())
            add(AutoloadToggleAction())
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class ShowOutOfScopeLeafToggleAction : ToggleAction(
        "Show Direct Out-of-Scope Leaves",
        "Show direct includes outside the selected scope as leaves",
        null,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = state.showDirectOutOfScopeLeaves

        override fun setSelected(e: AnActionEvent, value: Boolean) {
            if (state.showDirectOutOfScopeLeaves == value) return
            state.showDirectOutOfScopeLeaves = value
            settings.showDirectOutOfScopeLeaves = value
            cache.invalidateScopeBounded()
            refreshAllViews()
            restartAutoload()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class HideRepeatedIncludesToggleAction : ToggleAction(
        "Hide Repeated Includes",
        "Expand each file only at its first occurrence and show repeats as leaves",
        null,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = state.hideRepeatedIncludes

        override fun setSelected(e: AnActionEvent, value: Boolean) {
            if (state.hideRepeatedIncludes == value) return
            state.hideRepeatedIncludes = value
            settings.hideRepeatedIncludes = value
            refreshAllViews()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class FilterByPathToggleAction : ToggleAction(
        "Filter by File Path",
        "Match the filter against the full file path instead of only the file name",
        null,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = state.filterByPath

        override fun setSelected(e: AnActionEvent, value: Boolean) {
            if (state.filterByPath == value) return
            state.filterByPath = value
            settings.filterByPath = value
            state.filter = IncludeHierarchyFilter.from(filterField?.text ?: "", state.filterByPath)
            refreshAllViews()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class ShowFullPathToggleAction : ToggleAction(
        "Show Full File Path",
        "Display the project-relative or absolute path in parentheses",
        null,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = state.showFullPath

        override fun setSelected(e: AnActionEvent, value: Boolean) {
            if (state.showFullPath == value) return
            state.showFullPath = value
            settings.showFullPath = value
            refreshAllViews()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class ShowChildrenCountToggleAction : ToggleAction(
        "Show Descendant Counts",
        "Display unique descendant counts, or matching descendants over total while filtered",
        null,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = state.showChildrenCount

        override fun setSelected(e: AnActionEvent, value: Boolean) {
            if (state.showChildrenCount == value) return
            state.showChildrenCount = value
            settings.showChildrenCount = value
            refreshAllViews()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class AutoloadToggleAction : ToggleAction(
        "Autoload Hierarchy",
        "Walk the include graph in the background and populate the hierarchy",
        null,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = state.autoload

        override fun setSelected(e: AnActionEvent, value: Boolean) {
            if (state.autoload == value) return
            state.autoload = value
            settings.autoload = value
            restartAutoload()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class FilterByFileNameAction : AnAction("Filter by File Name") {
        override fun actionPerformed(e: AnActionEvent) {
            val file = selectedDescriptorFile() ?: return
            replaceFilterText(file.name)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = selectedDescriptorFile() != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private fun replaceFilterText(text: String) {
        val field = filterField ?: return
        val editor = field.textEditor
        if (editor.text == text) {
            val nextFilter = IncludeHierarchyFilter.from(text, state.filterByPath)
            if (nextFilter != state.filter) {
                state.filter = nextFilter
                scheduleRefresh()
            }
        } else {
            editor.text = text
        }

        editor.caretPosition = editor.document.length
        editor.requestFocusInWindow()
    }

    fun startAutoloadIfEnabled() {
        if (state.autoload) {
            restartAutoload()
        }
    }

    private fun restartAutoload() {
        autoloader.cancel()
        autoloadRefreshAlarm.cancelAllRequests()
        autoloadRefreshPending = false
        clearAutoloadFiles()

        if (!state.autoload) {
            return
        }

        val base = getHierarchyBase() as? PsiFile ?: baseFile
        autoloader.start(base, state.direction, state.scope, state.showDirectOutOfScopeLeaves)
        queueAutoloadRefresh()
    }

    private fun rememberAutoloadFile(file: PsiFile) {
        val path = file.virtualFile?.path ?: return
        synchronized(autoloadFilesLock) {
            autoloadFilesByPath[path] = file
        }
    }

    private fun clearAutoloadFiles() {
        synchronized(autoloadFilesLock) {
            autoloadFilesByPath.clear()
        }
    }

    private fun autoloadFilesSnapshot(): List<PsiFile>? {
        if (!state.autoload) {
            return null
        }

        return synchronized(autoloadFilesLock) {
            autoloadFilesByPath.values.toList()
        }
    }

    private fun queueAutoloadRefresh() {
        if (isDisposed || !state.autoload) {
            return
        }

        if (autoloadRefreshPending) {
            return
        }

        autoloadRefreshPending = true
        autoloadRefreshAlarm.addRequest({
            autoloadRefreshPending = false
            if (!isDisposed && state.autoload) {
                refreshAllViews()
            }
        }, AUTOLOAD_REFRESH_DELAY_MS)
    }

    private fun onAutoloadProgress(done: Boolean) {
        if (isDisposed || !state.autoload) {
            return
        }

        if (done) {
            autoloadRefreshAlarm.cancelAllRequests()
            autoloadRefreshPending = false
            refreshAllViews()
        } else {
            queueAutoloadRefresh()
        }
    }

    override fun dispose() {
        autoloader.cancel()
        refreshAlarm.cancelAllRequests()
        autoloadRefreshAlarm.cancelAllRequests()
        super.dispose()
    }
}