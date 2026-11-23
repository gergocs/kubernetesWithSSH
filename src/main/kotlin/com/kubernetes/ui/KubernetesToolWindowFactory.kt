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
            .setText("Pods & Resources")
            .setIcon(AllIcons.Nodes.Deploy)
            .setTooltipText(HtmlChunk.text("Kubernetes Resources (Pods, Services, ConfigMaps, Deployments)"))

        val tailLogsPanel = TailLogsPanel(project)
        Disposer.register(toolWindow.disposable, tailLogsPanel)
        val tailLogsTab = TabInfo(tailLogsPanel)
            .setText("Tail Logs")
            .setIcon(AllIcons.Debugger.Console)
            .setTooltipText(HtmlChunk.text("Live Log Tailing for Single & Multiple Pods"))

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
        tabs.addTab(tailLogsTab)
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

        podsPanel.onNavigateToTailLogs = { profile, namespace, pods, container, follow, tailLines, timestamps, previous ->
            tabs.select(tailLogsTab, true)
            tailLogsPanel.tailMultiplePods(profile, namespace, pods, container, follow, tailLines, timestamps, previous)
        }

        dockerImagesPanel.onNavigateToConsole = { command ->
            tabs.select(kubectlTab, true)
            kubectlPanel.executeCommand(command)
        }

        podsPanel.onProfileChanged = { profile ->
            dockerImagesPanel.syncProfile(profile)
            kubectlPanel.syncProfile(profile)
            tailLogsPanel.syncProfile(profile)
        }
        dockerImagesPanel.onProfileChanged = { profile ->
            podsPanel.syncProfile(profile)
            kubectlPanel.syncProfile(profile)
            tailLogsPanel.syncProfile(profile)
        }
        kubectlPanel.onProfileChanged = { profile ->
            podsPanel.syncProfile(profile)
            dockerImagesPanel.syncProfile(profile)
            tailLogsPanel.syncProfile(profile)
        }
        tailLogsPanel.onProfileChanged = { profile ->
            podsPanel.syncProfile(profile)
            dockerImagesPanel.syncProfile(profile)
            kubectlPanel.syncProfile(profile)
        }
    }
}
