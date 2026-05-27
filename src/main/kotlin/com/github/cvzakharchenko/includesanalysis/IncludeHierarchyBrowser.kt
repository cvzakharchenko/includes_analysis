package com.github.cvzakharchenko.includesanalysis

import com.intellij.icons.AllIcons
import com.intellij.ide.hierarchy.HierarchyBrowserBaseEx
import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.util.scopeChooser.ScopeChooserCombo
import com.intellij.ide.util.treeView.AlphaComparator
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.util.Alarm
import java.awt.Dimension
import java.util.Comparator
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

class IncludeHierarchyBrowser(
    project: Project,
    baseFile: PsiFile,
) : HierarchyBrowserBaseEx(project, baseFile) {

    private val settings = IncludeHierarchySettings.getInstance()
    private val state = IncludeFilterState().also {
        it.direction = settings.direction
        it.flat = settings.flat
        it.showFirstOutOfScopeLeaf = settings.showFirstOutOfScopeLeaf
        it.skipDuplicateSubtree = settings.skipDuplicateSubtree
    }
    private val cache = IncludeGraphCache(project)
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var filterField: SearchTextField? = null
    private var skipCacheClear = false

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
        trees[TYPE_INCLUDEES_TREE] = createTree(false)
        trees[TYPE_INCLUDEES_FLAT] = createTree(false)
        trees[TYPE_INCLUDERS_TREE] = createTree(false)
        trees[TYPE_INCLUDERS_FLAT] = createTree(false)
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
        if (!skipCacheClear) cache.clear()
        super.doRefresh(currentBuilderOnly)
    }

    override fun getComparator(): Comparator<NodeDescriptor<*>>? = AlphaComparator.INSTANCE

    override fun getActionPlace(): String = ActionPlaces.UNKNOWN

    override fun getPrevOccurenceActionNameImpl(): String = "Previous Include"
    override fun getNextOccurenceActionNameImpl(): String = "Next Include"

    override fun createLegendPanel(): javax.swing.JPanel? = null

    override fun appendActions(actionGroup: DefaultActionGroup, helpID: String?) {
        actionGroup.add(ScopeChooserAction())
        actionGroup.add(Separator())
        actionGroup.add(DirectionToggleAction())
        actionGroup.add(FlatToggleAction())
        actionGroup.add(OptionsDropdownGroup())
        actionGroup.add(Separator())
        actionGroup.add(FilterFieldAction())
        actionGroup.add(Separator())
        super.appendActions(actionGroup, helpID)
    }

    private fun typeNameFor(state: IncludeFilterState): String = when (state.direction) {
        IncludeDirection.INCLUDEES -> if (state.flat) TYPE_INCLUDEES_FLAT else TYPE_INCLUDEES_TREE
        IncludeDirection.INCLUDERS -> if (state.flat) TYPE_INCLUDERS_FLAT else TYPE_INCLUDERS_TREE
    }

    private fun scheduleRefresh() {
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest({ refreshCurrentView() }, 200)
    }

    // doRefresh actually rebuilds the structure (changeView with the same type is a no-op).
    // Skip the cache clear that the Refresh toolbar button performs and restore filter focus
    // so typing in the filter field doesn't lose focus per keystroke.
    private fun refreshCurrentView() {
        val field = filterField
        val hadFocus = field != null && field.textEditor.hasFocus()
        val caret = field?.textEditor?.caretPosition ?: 0
        skipCacheClear = true
        try {
            doRefresh(true)
        } finally {
            skipCacheClear = false
        }
        if (hadFocus) {
            SwingUtilities.invokeLater {
                field.textEditor.requestFocusInWindow()
                val docLen = field.textEditor.document.length
                field.textEditor.caretPosition = caret.coerceAtMost(docLen)
            }
        }
    }

    private inner class ScopeChooserAction : AnAction(), CustomComponentAction {
        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            val preselect = settings.scopeName ?: "Project Files"
            val combo = ScopeChooserCombo(myProject, false, true, preselect)
            Disposer.register(this@IncludeHierarchyBrowser, combo)
            state.scope = combo.selectedScope
            settings.scopeName = combo.selectedScope?.displayName ?: preselect
            combo.childComponent.addActionListener {
                state.scope = combo.selectedScope
                settings.scopeName = combo.selectedScope?.displayName
                cache.invalidateScopeBounded()
                scheduleRefresh()
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
            scheduleRefresh()
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
            scheduleRefresh()
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }
}
