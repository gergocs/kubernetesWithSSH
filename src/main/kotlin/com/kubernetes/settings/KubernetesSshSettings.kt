package com.kubernetes.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "com.kubernetes.settings.KubernetesSshSettings",
    storages = [Storage("KubernetesSshSettings.xml")]
)
class KubernetesSshSettings : PersistentStateComponent<KubernetesSshSettingsState> {
    private var myState = KubernetesSshSettingsState()

    override fun getState(): KubernetesSshSettingsState {
        return myState
    }

    override fun loadState(state: KubernetesSshSettingsState) {
        myState = state
    }

    companion object {
        fun getInstance(project: Project): KubernetesSshSettings {
            return project.getService(KubernetesSshSettings::class.java)
        }
    }
}
