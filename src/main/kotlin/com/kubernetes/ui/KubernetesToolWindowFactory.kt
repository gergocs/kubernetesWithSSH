package com.kubernetes.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo

class KubernetesToolWindowFactory : ToolWindowFactory {

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val tabs = JBTabsFactory.createTabs(project, toolWindow.disposable)

        val podsPanel = KubernetesToolWindowPanel(project)
        Disposer.register(toolWindow.disposable, podsPanel)
        val podsTab = TabInfo(podsPanel)
            .setText("Pods")
            .setIcon(AllIcons.Nodes.Deploy)
            .setTooltipText(HtmlChunk.text("Kubernetes Pods Explorer"))

        val dockerImagesPanel = DockerImagesPanel(project)
        Disposer.register(toolWindow.disposable, dockerImagesPanel)
        val dockerImagesTab = TabInfo(dockerImagesPanel)
            .setText("Docker Images")
            .setIcon(AllIcons.Nodes.Artifact)
            .setTooltipText(HtmlChunk.text("Remote Docker Images"))

        val kubectlPanel = KubectlConsolePanel(project)
        Disposer.register(toolWindow.disposable, kubectlPanel)
        val kubectlTab = TabInfo(kubectlPanel)
            .setText("Kubectl")
            .setIcon(AllIcons.Nodes.Console)
            .setTooltipText(HtmlChunk.text("Kubectl Command Console"))

        tabs.addTab(podsTab)
        tabs.addTab(dockerImagesTab)
        tabs.addTab(kubectlTab)

        val content = ContentFactory.getInstance().createContent(tabs.component, "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)

        podsPanel.onNavigateToDockerImage = { imageQuery ->
            tabs.select(dockerImagesTab, true)
            dockerImagesPanel.filterByImage(imageQuery)
        }

        podsPanel.onNavigateToKubectl = { command ->
            tabs.select(kubectlTab, true)
            kubectlPanel.executeCommand(command)
        }

        podsPanel.onProfileChanged = { profile ->
            dockerImagesPanel.syncProfile(profile)
            kubectlPanel.syncProfile(profile)
        }
        dockerImagesPanel.onProfileChanged = { profile ->
            podsPanel.syncProfile(profile)
            kubectlPanel.syncProfile(profile)
        }
        kubectlPanel.onProfileChanged = { profile ->
            podsPanel.syncProfile(profile)
            dockerImagesPanel.syncProfile(profile)
        }
    }
}
