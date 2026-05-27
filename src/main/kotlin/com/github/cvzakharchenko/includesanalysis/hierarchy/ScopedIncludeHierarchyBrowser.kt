package com.github.cvzakharchenko.includesanalysis.hierarchy

import com.intellij.icons.AllIcons
import com.intellij.ide.hierarchy.HierarchyBrowserBaseEx
import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.util.treeView.AlphaComparator
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.ide.util.scopeChooser.ScopeChooserCombo
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.DefaultCustomComponentAction
import com.intellij.openapi.application.ApplicationManager
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
import java.util.function.Supplier
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent

class ScopedIncludeHierarchyBrowser(
    private val projectRef: Project,
    file: PsiElement,
    private val includeGraph: CppIncludeGraph,
) : HierarchyBrowserBaseEx(projectRef, file) {
    private val properties = PropertiesComponent.getInstance(projectRef)
    private var selectedScope: SearchScope = GlobalSearchScope.projectScope(projectRef)
    private val includingCache = IncludeHierarchyCache(projectRef, includeGraph.getIncluders)
    private val includedByCache = IncludeHierarchyCache(projectRef, includeGraph.getIncludees)
    private var flatMode = properties.getBoolean(FLAT_MODE_PROPERTY, false)
    private var showIncludedBy = properties.getBoolean(SHOW_INCLUDED_BY_PROPERTY, false)
    private var showOutOfScopeLeaves = properties.getBoolean(SHOW_OUT_OF_SCOPE_LEAVES_PROPERTY, true)
    private var expandRepeatedIncludes = properties.getBoolean(EXPAND_REPEATED_INCLUDES_PROPERTY, false)
    private var filter = IncludeHierarchyFilter.empty()
    private val filterRefreshTimer = Timer(FILTER_REFRESH_DELAY_MS) {
        refreshFilterResults()
    }.apply {
        isRepeats = false
    }
    private val filterField: SearchTextField by lazy { createFilterField() }

    val initialViewType: String
        get() = if (showIncludedBy) INCLUDED_BY_VIEW else INCLUDING_VIEW

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
        return if (flatMode) {
            FlatIncludeHierarchyTreeStructure(projectRef, file, cache, { selectedScope }) { filter }
        } else {
            ScopedIncludeHierarchyTreeStructure(
                projectRef,
                file,
                cache,
                { selectedScope },
                { filter },
                { showOutOfScopeLeaves },
                { expandRepeatedIncludes },
            )
        }
    }

    override fun createLegendPanel(): JPanel? = null

    override fun doRefresh(currentBuilderOnly: Boolean) {
        includingCache.clear()
        includedByCache.clear()
        refreshTree(currentBuilderOnly)
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
        val popupGroup = DefaultActionGroup() as ActionGroup

        val includingTree = createTree(true)
        PopupHandler.installPopupMenu(includingTree, popupGroup, "TypeHierarchyViewPopup")
        trees[INCLUDING_VIEW] = includingTree

        val includedByTree = createTree(true)
        PopupHandler.installPopupMenu(includedByTree, popupGroup, "TypeHierarchyViewPopup")
        trees[INCLUDED_BY_VIEW] = includedByTree
    }

    override fun getPresentableNameMap(): MutableMap<String, Supplier<String>> =
        hashMapOf(
            INCLUDING_VIEW to Supplier { "Including" },
            INCLUDED_BY_VIEW to Supplier { "Included By" },
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
        val chooser = ScopeChooserCombo()
        chooser.init(projectRef, true, true, selectedScope, null)
        chooser.addActionListener {
            val scope = chooser.selectedScope ?: GlobalSearchScope.projectScope(projectRef)
            if (scope != selectedScope) {
                selectedScope = scope
                rebuildWithoutClearingCaches(false)
            }
        }
        Disposer.register(this, chooser)
        return chooser
    }

    private fun createFilterField(): SearchTextField {
        val field = SearchTextField(false)
        field.textEditor.emptyText.text = "Filter"
        field.textEditor.columns = 18
        field.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                val nextFilter = IncludeHierarchyFilter.from(field.text)
                if (nextFilter != filter) {
                    filter = nextFilter
                    filterRefreshTimer.restart()
                }
            }
        })
        return field
    }

    private fun cacheFor(typeName: String): IncludeHierarchyCache =
        if (typeName == INCLUDED_BY_VIEW) {
            includedByCache
        } else {
            includingCache
        }

    private inner class DirectionModeAction : DumbAwareToggleAction("Included By") {
        init {
            templatePresentation.description = "Show files that include the current file"
            templatePresentation.icon = AllIcons.General.Tree
        }

        override fun isSelected(event: AnActionEvent): Boolean = showIncludedBy

        override fun setSelected(event: AnActionEvent, state: Boolean) {
            if (showIncludedBy != state) {
                showIncludedBy = state
                properties.setValue(SHOW_INCLUDED_BY_PROPERTY, showIncludedBy, false)
                changeView(if (showIncludedBy) INCLUDED_BY_VIEW else INCLUDING_VIEW)
                rebuildWithoutClearingCaches(true)
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
                rebuildWithoutClearingCaches(false)
            }
        }
    }

    private inner class OptionsGroup : DefaultActionGroup("Options", true) {
        init {
            templatePresentation.description = "Include hierarchy options"
            templatePresentation.icon = AllIcons.General.GearPlain
            add(ShowOutOfScopeLeavesAction())
            add(ExpandRepeatedIncludesAction())
        }
    }

    private inner class ShowOutOfScopeLeavesAction : DumbAwareToggleAction("Show Out-of-Scope Leaves") {
        override fun isSelected(event: AnActionEvent): Boolean = showOutOfScopeLeaves

        override fun setSelected(event: AnActionEvent, state: Boolean) {
            if (showOutOfScopeLeaves != state) {
                showOutOfScopeLeaves = state
                properties.setValue(SHOW_OUT_OF_SCOPE_LEAVES_PROPERTY, showOutOfScopeLeaves, true)
                rebuildWithoutClearingCaches(false)
            }
        }
    }

    private inner class ExpandRepeatedIncludesAction : DumbAwareToggleAction("Expand Repeated Includes") {
        override fun isSelected(event: AnActionEvent): Boolean = expandRepeatedIncludes

        override fun setSelected(event: AnActionEvent, state: Boolean) {
            if (expandRepeatedIncludes != state) {
                expandRepeatedIncludes = state
                properties.setValue(EXPAND_REPEATED_INCLUDES_PROPERTY, expandRepeatedIncludes, false)
                rebuildWithoutClearingCaches(false)
            }
        }
    }

    override fun dispose() {
        filterRefreshTimer.stop()
        super.dispose()
    }

    companion object {
        const val INCLUDING_VIEW = "Including..."
        const val INCLUDED_BY_VIEW = "IncludedBy..."
        private const val FILTER_REFRESH_DELAY_MS = 200
        private const val PROPERTY_PREFIX = "includes.analysis.hierarchy."
        private const val SHOW_INCLUDED_BY_PROPERTY = PROPERTY_PREFIX + "show.included.by"
        private const val FLAT_MODE_PROPERTY = PROPERTY_PREFIX + "flat.mode"
        private const val SHOW_OUT_OF_SCOPE_LEAVES_PROPERTY = PROPERTY_PREFIX + "show.out.of.scope.leaves"
        private const val EXPAND_REPEATED_INCLUDES_PROPERTY = PROPERTY_PREFIX + "expand.repeated.includes"
    }
}