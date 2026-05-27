package com.github.cvzakharchenko.includesanalysis

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "ProjectIncludeHierarchySettings",
    storages = [Storage("projectIncludeHierarchy.xml")],
)
@Service(Service.Level.PROJECT)
class IncludeHierarchySettings : PersistentStateComponent<IncludeHierarchySettings> {
    var direction: IncludeDirection = IncludeDirection.INCLUDEES
    var flat: Boolean = false
    var showDirectOutOfScopeLeaves: Boolean = false
    var hideRepeatedIncludes: Boolean = false
    var filterByPath: Boolean = false
    var showFullPath: Boolean = false
    var showChildrenCount: Boolean = false
    var autoload: Boolean = false
    var scopeName: String? = null

    override fun getState(): IncludeHierarchySettings = this

    override fun loadState(state: IncludeHierarchySettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): IncludeHierarchySettings = project.service()
    }
}