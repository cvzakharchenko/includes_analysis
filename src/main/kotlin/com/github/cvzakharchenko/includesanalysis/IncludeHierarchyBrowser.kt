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
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
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

    private val settings = IncludeHierarchySettings.getInstance()
    private val state = IncludeFilterState().also {
        it.direction = settings.direction
        it.flat = settings.flat
        it.showFirstOutOfScopeLeaf = settings.showFirstOutOfScopeLeaf
        it.skipDuplicateSubtree = settings.skipDuplicateSubtree
        it.filterByPath = settings.filterByPath
        it.showFullPath = settings.showFullPath
        it.showChildrenCount = settings.showChildrenCount
        it.autoload = settings.autoload
        // Seed with a safe scope before the toolbar paints. Without this, the first
        // buildChildren can race the ScopeChooserCombo render: state.scope is null,
        // acceptsScope returns true for everything, and out-of-scope files leak in.
        it.scope = GlobalSearchScope.projectScope(project)
    }
    private val cache = IncludeGraphCache(project)
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val loaderRefreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var loaderRefreshPending = false
    private var filterField: SearchTextField? = null
    private var skipCacheClear = false
    private val autoloader = IncludeGraphAutoloader(cache) { _, _, done ->
        SwingUtilities.invokeLater { onLoaderProgress(done) }
    }

    init {
        Disposer.register(this) { autoloader.cancel() }
        // The toolbar / view hasn't been constructed yet at this point; defer the
        // initial autoload until after the framework has wired up the view so the
        // loader-driven refreshAllViews has a tree root to update.
        ApplicationManager.getApplication().invokeLater {
            if (state.autoload) restartAutoload()
        }
    }

    companion object {
        const val TYPE_INCLUDEES_TREE: String = "What This File Includes"
        const val TYPE_INCLUDEES_FLAT: String = "What This File Includes (Flat)"
        const val TYPE_INCLUDERS_TREE: String = "What Includes This File"
        const val TYPE_INCLUDERS_FLAT: String = "What Includes This File (Flat)"
    }

    fun initialViewType(): String = typeNameFor(state)

    override fun isApplicableElement(element: PsiElement): Boolean = element is PsiFile

    override fun getElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement? =
        descriptor.psiElement

    override fun createTrees(trees: MutableMap<in String, in JTree>) {
        val popupGroup = DefaultActionGroup().apply {
            add(FilterByFileNameAction())
        } as ActionGroup
        trees[TYPE_INCLUDEES_TREE] = createIncludeTree(popupGroup)
        trees[TYPE_INCLUDEES_FLAT] = createIncludeTree(popupGroup)
        trees[TYPE_INCLUDERS_TREE] = createIncludeTree(popupGroup)
        trees[TYPE_INCLUDERS_FLAT] = createIncludeTree(popupGroup)
    }

    // Right-click doesn't auto-select the row, so without this MouseAdapter the popup
    // would fire against whatever was selected before, not the row the user clicked on.
    // PopupHandler.installPopupMenu attaches the popup on top of the default tree menu,
    // and our handler wins because it was installed last.
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
        return (node.userObject as? IncludeNodeDescriptor)?.psiElement as? PsiFile
    }

    override fun createHierarchyTreeStructure(typeName: String, psiElement: PsiElement): HierarchyTreeStructure? {
        val file = psiElement as? PsiFile ?: return null
        return when (typeName) {
            TYPE_INCLUDEES_TREE -> IncludeTreeStructure(myProject, file, state, IncludeDirection.INCLUDEES, cache)
            TYPE_INCLUDEES_FLAT -> FlatIncludeStructure(myProject, file, state, IncludeDirection.INCLUDEES, cache)
            TYPE_INCLUDERS_TREE -> IncludeTreeStructure(myProject, file, state, IncludeDirection.INCLUDERS, cache)
            TYPE_INCLUDERS_FLAT -> FlatIncludeStructure(myProject, file, state, IncludeDirection.INCLUDERS, cache)
            else -> null
        }
    }

    override fun doRefresh(currentBuilderOnly: Boolean) {
        val cacheCleared = !skipCacheClear
        if (cacheCleared) cache.clear()
        super.doRefresh(currentBuilderOnly)
        // A user-initiated Refresh blew the cache; the autoloader needs to refill it.
        // Internal refreshes (skipCacheClear=true) leave the cache and the loader alone.
        if (cacheCleared) restartAutoload()
    }

    override fun getComparator(): Comparator<NodeDescriptor<*>>? = AlphaComparator.INSTANCE

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
        refreshAlarm.addRequest({ refreshAllViews() }, 200)
    }

    // Rebuilds every created view (currentBuilderOnly=false) so that filter / option /
    // scope changes are visible immediately when the user switches view — otherwise the
    // non-current view keeps stale cached descriptors until something else dirties it.
    // Skips the cache clear that the Refresh toolbar button performs, and restores filter
    // focus so typing in the filter field doesn't lose focus per keystroke.
    private fun refreshAllViews() {
        // After changeView the new current tree's model loads its root asynchronously;
        // doRefresh calls TreeBuilderUtil.storePaths(root, ...) which NPEs on a null root.
        // Defer until the root is in place.
        if (!hasCurrentTreeRoot()) {
            ApplicationManager.getApplication().invokeLater {
                if (hasCurrentTreeRoot()) refreshAllViews()
            }
            return
        }
        val field = filterField
        val hadFocus = field != null && field.textEditor.hasFocus()
        val caret = field?.textEditor?.caretPosition ?: 0
        skipCacheClear = true
        try {
            // HierarchyBrowserBaseEx.doRefresh → isValidBase → getUncommittedDocuments
            // asserts read access. Combo/listener-triggered refreshes come in on the EDT
            // without a read lock, so wrap.
            ApplicationManager.getApplication().runReadAction { doRefresh(false) }
        } finally {
            skipCacheClear = false
        }
        if (hadFocus) {
            SwingUtilities.invokeLater {
                field!!.textEditor.requestFocusInWindow()
                val docLen = field.textEditor.document.length
                field.textEditor.caretPosition = caret.coerceAtMost(docLen)
            }
        }
    }

    private fun hasCurrentTreeRoot(): Boolean {
        val tree = runCatching { currentTree }.getOrNull() ?: return false
        return tree.model?.root != null
    }

    private inner class ScopeChooserAction : AnAction(), CustomComponentAction {
        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            val preselect = settings.scopeName ?: "Project Files"
            val combo = ScopeChooserCombo(myProject, false, true, preselect)
            Disposer.register(this@IncludeHierarchyBrowser, combo)
            // Default ScopeChooserCombo width is ~250px and dominates the toolbar;
            // clamp to a much narrower footprint. Long scope names still tooltip-up
            // and the dropdown itself sizes to its content when opened.
            val h = combo.preferredSize.height
            combo.preferredSize = Dimension(120, h)
            combo.maximumSize = Dimension(120, h)
            val resolved = combo.selectedScope
            if (resolved != null) {
                state.scope = resolved
                settings.scopeName = resolved.displayName
                // The tree may have already rendered against the projectScope default
                // we seeded; refresh so the real (possibly persisted, possibly named)
                // scope is applied.
                cache.invalidateScopeBounded()
                scheduleRefresh()
            }
            combo.childComponent.addActionListener {
                state.scope = combo.selectedScope
                settings.scopeName = combo.selectedScope?.displayName
                cache.invalidateScopeBounded()
                refreshAllViews()
                restartAutoload()
            }
            return combo
        }

        override fun actionPerformed(e: AnActionEvent) = Unit
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = true
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class DirectionToggleAction : ToggleAction(
        "Show Includers",
        "Toggle between what this file includes and what files include this one",
        AllIcons.Hierarchy.Supertypes,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = state.direction == IncludeDirection.INCLUDERS
        override fun setSelected(e: AnActionEvent, value: Boolean) {
            state.direction = if (value) IncludeDirection.INCLUDERS else IncludeDirection.INCLUDEES
            settings.direction = state.direction
            changeView(typeNameFor(state))
            refreshAllViews()
            restartAutoload()
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class FlatToggleAction : ToggleAction(
        "Flat List",
        "Show flat list of unique reachable includes",
        AllIcons.Actions.ListFiles,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = state.flat
        override fun setSelected(e: AnActionEvent, value: Boolean) {
            state.flat = value
            settings.flat = value
            changeView(typeNameFor(state))
            refreshAllViews()
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class FilterFieldAction : AnAction(), CustomComponentAction {
        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            val field = SearchTextField(false)
            field.textEditor.emptyText.text = "Filter"
            field.preferredSize = Dimension(200, field.preferredSize.height)
            field.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    state.query = field.text.trim()
                    scheduleRefresh()
                }
            })
            filterField = field
            return field
        }

        override fun actionPerformed(e: AnActionEvent) = Unit
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = true
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class OptionsDropdownGroup : DefaultActionGroup("Options", true) {
        init {
            templatePresentation.icon = AllIcons.General.GearPlain
            templatePresentation.description = "Display options"
            add(ShowOutOfScopeLeafToggleAction())
            add(SkipDuplicateSubtreeToggleAction())
            add(FilterByPathToggleAction())
            add(ShowFullPathToggleAction())
            add(ShowChildrenCountToggleAction())
            add(AutoloadToggleAction())
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class ShowOutOfScopeLeafToggleAction : ToggleAction(
        "Show First Out-of-Scope Include",
        "Show includes that fall outside the selected scope as leaves, without expanding them",
        null,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = state.showFirstOutOfScopeLeaf
        override fun setSelected(e: AnActionEvent, value: Boolean) {
            state.showFirstOutOfScopeLeaf = value
            settings.showFirstOutOfScopeLeaf = value
            cache.invalidateScopeBounded()
            refreshAllViews()
            restartAutoload()
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class SkipDuplicateSubtreeToggleAction : ToggleAction(
        "Skip Duplicate Subtrees",
        "Expand each file's includes only at its first occurrence; show it as a leaf elsewhere",
        null,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = state.skipDuplicateSubtree
        override fun setSelected(e: AnActionEvent, value: Boolean) {
            state.skipDuplicateSubtree = value
            settings.skipDuplicateSubtree = value
            refreshAllViews()
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class FilterByPathToggleAction : ToggleAction(
        "Filter by File Path",
        "Match the filter against the file's project-relative path; when off, match against the file name only",
        null,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = state.filterByPath
        override fun setSelected(e: AnActionEvent, value: Boolean) {
            state.filterByPath = value
            settings.filterByPath = value
            refreshAllViews()
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class ShowFullPathToggleAction : ToggleAction(
        "Show Full File Path",
        "Display each file's project-relative path instead of just its name",
        null,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = state.showFullPath
        override fun setSelected(e: AnActionEvent, value: Boolean) {
            state.showFullPath = value
            settings.showFullPath = value
            refreshAllViews()
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class ShowChildrenCountToggleAction : ToggleAction(
        "Show Descendant Count",
        "Display the cumulative number of reachable descendants in parentheses after each file name",
        null,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = state.showChildrenCount
        override fun setSelected(e: AnActionEvent, value: Boolean) {
            state.showChildrenCount = value
            settings.showChildrenCount = value
            refreshAllViews()
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class AutoloadToggleAction : ToggleAction(
        "Autoload Full Hierarchy",
        "Walk the include graph in the background and populate the whole hierarchy without waiting for expansions",
        null,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = state.autoload
        override fun setSelected(e: AnActionEvent, value: Boolean) {
            state.autoload = value
            settings.autoload = value
            restartAutoload()
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    // Right-click → "Filter by File Name" on a tree row replaces the filter text with
    // that file's name. The popup is installed by createIncludeTree, and the selected
    // row is resolved at action time so we don't need a custom DataProvider.
    private inner class FilterByFileNameAction : AnAction("Filter by File Name") {
        override fun actionPerformed(e: AnActionEvent) {
            val file = selectedDescriptorFile() ?: return
            filterField?.text = file.name
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = selectedDescriptorFile() != null
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private fun restartAutoload() {
        autoloader.cancel()
        loaderRefreshAlarm.cancelAllRequests()
        loaderRefreshPending = false
        if (state.autoload) {
            autoloader.start(baseFile, state.direction, state)
        }
    }

    private fun onLoaderProgress(done: Boolean) {
        if (done) {
            // One final refresh so the last batch lands; cancel any pending throttled
            // refresh since we're about to do one synchronously anyway.
            loaderRefreshAlarm.cancelAllRequests()
            loaderRefreshPending = false
            refreshAllViews()
        } else if (!loaderRefreshPending) {
            // Coalesce mid-load progress bursts into one refresh per ~500ms so the
            // tree visibly fills in without thrashing the builder.
            loaderRefreshPending = true
            loaderRefreshAlarm.addRequest({
                loaderRefreshPending = false
                refreshAllViews()
            }, 500)
        }
    }
}
