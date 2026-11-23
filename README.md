# KubernetesWithSSH

[![Twitter Follow](https://img.shields.io/badge/follow-%40JBPlatform-1DA1F2?logo=twitter)](https://twitter.com/JBPlatform)
[![Developers Forum](https://img.shields.io/badge/JetBrains%20Platform-Join-blue)][jb:forum]

## Overview

**Kubernetes with SSH** is an IntelliJ Platform plugin enabling direct management and monitoring of Kubernetes pods on a remote server accessible via SSH.

## Features

- **Multiple SSH Profiles**: Configure and manage multiple remote SSH profiles under IDE **Settings -> Tools -> Kubernetes over SSH**. Add, duplicate, rename, delete, test connection per profile, and switch between active profiles directly from the Tool Window or Settings.
- **SSH Configuration**: Configure remote host, port, username, authentication (Password or Private Key file with optional passphrase), and remote `kubectl` and `docker` binary locations. Includes a built-in **Test SSH Connection** button.
- **Right-side Tool Window**: Access the **Kubernetes SSH** tool window anchored to the right side of the IDE with dedicated **Pods & Resources**, **Tail Logs**, **Docker Images**, and **Kubectl** tabs with instant profile switching.
- **Dedicated Tail Logs Viewer (Multi-Pod Streaming)**:
  - Separate non-blocking tab to continuously stream and tail live logs while working on other tasks across the IDE.
  - Concurrent multi-pod streaming support: tail multiple pods simultaneously in an aggregated stream with color-coded pod identifiers or in dedicated individual sub-tabs.
  - Interactive stream controls: Pause / Resume live streaming, restart, clear console buffer, and soft wrap toggle.
  - Configurable tail depth (`50`, `100`, `200`, `500`, `1000`, or `All`), Follow mode (`-f`), timestamp toggling (`--timestamps`), and previous terminated container log inspection (`-p`).
  - Container-level targeting for multi-container pods (`-c <container>`).
  - Real-time search and filter with instant match highlighting.
  - One-click clipboard export and line count metrics.
- **Resource Explorer (Pods, Services, Ingresses, ConfigMaps, Secrets, Deployments, StatefulSets, DaemonSets, Jobs, CronJobs, PersistentVolumeClaims, Events)**:
  - Toggle seamlessly across all major Kubernetes resource types within the main explorer tab:
  - **Tier 1 (High Impact)**:
    - **Secrets**: Inspect encoded and decoded Base64 contents with a dedicated viewer, key inspector, and YAML editor with dry-run verification.
    - **Ingresses**: View Class, Hosts, Address, Ports, Age, routing rules, describe, and edit YAML.
  - **Tier 2 (Diagnostics & Batch)**:
    - **Jobs**: Monitor completions, run duration, containers, images, conditions, view snapshot logs, tail logs in the Tail Logs tab, and describe.
    - **CronJobs**: Inspect execution schedule, suspend flag, active runs, last schedule time, and manually trigger jobs ("Run Now").
    - **Events**: Dedicated event inspector tracking Warning/Normal types, reasons, involved objects, error messages, occurrence counts, and sources.
  - **Tier 3 (Extended Infrastructure)**:
    - **StatefulSets**: Track replica readiness, quick-scale replicas, restart rollouts, and edit YAML manifests.
    - **DaemonSets**: Monitor desired/ready node allocations, up-to-date/available daemon pods, node selectors, and restart rollouts.
    - **PersistentVolumeClaims (PVCs)**: Inspect bound status, volume bindings, requested/actual storage capacity, access modes, and storage classes.
  - **Core Workloads & Config**:
    - **Pods**: View real-time pod metrics (Status, Ready ratio, Restarts, Age, Docker Image & ID, Node, Pod IP). Execute interactive terminal, view logs, describe, delete / force delete, and jump directly to Docker Images tab.
    - **Services**: View Name, Type (ClusterIP, NodePort, LoadBalancer, ExternalName), Cluster IP, External IP, Ports, Age, and Selectors.
    - **ConfigMaps**: View Name, Data Keys, Key Count, and Age with dedicated key-value and YAML inspector.
    - **Deployments**: Monitor replica readiness, up-to-date, available replicas, images, and container specs with interactive scaling and rollout control.
- **Custom Kubectl Console**: Dedicated tab allowing users to execute arbitrary custom `kubectl` commands over SSH. Features interactive command execution, quick snippet presets (`get nodes`, `get pods -A`, `cluster-info`, `top nodes`, `api-resources`, etc.), Up/Down arrow command history, live output search/filtering, execution timestamps, duration tracking, exit code status indicators, auto-namespace appending (`-n <namespace>`), output appending/clearing, and one-click clipboard copying.
- **Auto-Refresh**: Continuous 5-second background auto-refresh for both Pods and Docker Images with intelligent table row selection retention and non-blocking asynchronous updates.
- **Namespace Switching**: Discover and switch between all available namespaces in the remote cluster.
- **Pod Status Monitoring**: View real-time pod metrics including Name, Status (with color indicators for Running, Pending, CrashLoopBackOff, Terminating, Completed, etc.), Ready container count, Restart count, Age, Docker/Container Image, Image ID, Node, and Pod IP.
- **Docker Images Explorer**: Dedicated tab listing remote Docker images matching `docker image list` output (`REPOSITORY`, `TAG`, `IMAGE ID`, `CREATED`, `SIZE`), with support for searching, copying metadata, creating new tags, changing/updating tags, inspecting, and deleting (`docker rmi`).
- **Quick Jump from Pod to Docker**: One-click "Show in Docker Images" action in toolbar and context menu to instantly switch to the Docker Images tab with the pod's image/image ID automatically pre-filled in the search filter.
- **Edit Kubernetes Deployments**:
  - Full interactive YAML/JSON manifest editor dialog with live reload and clipboard copying.
  - Server-side dry-run validation (`kubectl apply -f - --dry-run=server`) to verify manifests before applying.
  - Quick scale replicas control with real-time scaling (`kubectl scale deployment`).
  - Container image updater to easily update container image tags (`kubectl set image deployment`).
  - Rollout management with instant restart rollout (`kubectl rollout restart`) and rollout status monitoring (`kubectl rollout status`).
  - Accessible directly from the toolbar, right-click context menu, and pod details inspector.
- **Pod Management & Actions**:
  - **Execute Pod Terminal**: Open an interactive terminal session inside any pod with container and shell selectors, PTY stream handling, ANSI color styling, direct keyboard navigation, and quick command bar execution.
  - Delete Pod with safety confirmation prompt.
  - Force Delete Pod option.
  - View Pod Logs directly inside an IDE dialog.
  - Describe Pod details with full cluster inspection.
  - Live search / filter across pod names, images, image IDs, statuses, nodes, and IPs.
  - Async non-blocking execution to keep IDE responsive.

## 🧪 Testing with the Example Kubernetes Application

A full, ready-to-deploy multi-tier demo application is included under [`examples/`](./examples/) to test all plugin functionality (namespacing, pods, multi-container terminals, logs, deployment editing/scaling, crash loops, cronjobs, and Docker integration).

To deploy the test suite:
- **Linux / macOS / WSL**: `./examples/deploy.sh`
- **Windows (PowerShell)**: `.\examples\deploy.ps1`
- **Local SSH Sandbox**: `cd examples/docker-ssh-sandbox && docker compose up -d`

See [examples/README.md](./examples/README.md) for the complete feature testing guide and architecture details.

## Plugin structure

A generated project contains the following content structure:

```
.
├── .run/                   Predefined Run/Debug Configurations
├── gradle
│   ├── wrapper/            Gradle Wrapper
│   ├── libs.versions.toml  Version catalog
├── src                     Plugin sources
│   └── main
│       ├── kotlin/         Kotlin production sources
│       └── resources/      Plugin resources
│           ├── META-INF/   Plugin configuration file and logo
│           └── messages/   Message bundles
├── .gitignore              Git ignoring rules
├── build.gradle.kts        Gradle build configuration
├── gradle.properties       Gradle configuration properties
├── gradlew                 *nix Gradle Wrapper script
├── gradlew.bat             Windows Gradle Wrapper script
├── README.md               This file
└── settings.gradle.kts     Gradle project settings
```

In addition to the configuration files, the most crucial part is the `src` directory, which contains our implementation
and the manifest for our plugin – [plugin.xml][file:plugin.xml].

> [!NOTE]
> To use Java in your plugin, create the `/src/main/java` directory.

The plugin logo is placed in `src/main/resources/META-INF/pluginIcon.svg`.
See [Plugin Logo][docs:logo] for more information and logo requirements.

## Build script

The [build.gradle.kts][file:build.gradle.kts] is the core of the project definition.
It applies three Gradle plugins:

| Plugin                            | Description                                                                      |
|-----------------------------------|----------------------------------------------------------------------------------|
| `org.jetbrains.kotlin.jvm`        | Adds Kotlin support                                                              |
| `org.jetbrains.changelog`         | Simplifies patching the [CHANGELOG.md][file:CHANGELOG.md] file                   |
| `org.jetbrains.intellij.platform` | The [IntelliJ Platform Gradle Plugin][docs:intellij-platform-gradle-plugin-docs] |

The `intellijPlatform` dependencies block selects the IDE to compile against:

```kotlin
intellijIdea("2026.2.4")
```

See [Target Versions][docs:target-version] for more information.

The `intellijPlatform` dependencies block also contains a dependency on the platform testing framework:

```kotlin
testFramework(TestFrameworkType.Platform)
```

See [Testing][docs:testing] for more information

## Plugin configuration file

The plugin configuration file is a [plugin.xml][file:plugin.xml] file located in the `src/main/resources/META-INF`
directory.
It provides general information about the plugin, its dependencies, extensions, and listeners.

You can read more about this file in the [Plugin Configuration File][docs:plugin.xml] section of our documentation.

### Plugin ID and name

Generated plugin ID and name may require adjustment.

These values are generated based on _Group ID_ and _Artifact ID_ provided in the IDE Plugin wizard.
It is recommended to review `<id>` and `<name>` elements in the plugin.xml file, and adjust them if needed.

Please note that Gradle properties `rootProject.name` and `project.group` don't need to match the `<id>` and `<name>`
elements.
There is no IntelliJ Platform-related reason they should as they serve different functions.

## Predefined Run/Debug configurations

Within the default project structure, there is a `.run` directory provided containing predefined *Run/Debug
configurations* that expose corresponding Gradle tasks:

| Configuration name  | Description                                                                                                                                                                           |
|---------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Run IDE with Plugin | Runs [`:runIde`][docs:intellij-platform-gradle-plugin-runIde] IntelliJ Platform Gradle Plugin task. Use the *Debug* icon for plugin debugging.                                        |
| Run Tests           | Runs [`:check`][gradle:lifecycle-tasks] Gradle task.                                                                                                                                  |
| Run Verifications   | Runs [`:verifyPlugin`][docs:intellij-platform-gradle-plugin-verifyPlugin] IntelliJ Platform Gradle Plugin task to check the plugin compatibility against the specified IntelliJ IDEs. |

> [!NOTE]
> You can find the logs from the running task in the `idea.log` tab.

## Publishing the plugin

> [!TIP]
> Make sure to follow all guidelines listed in [Publishing a Plugin][docs:publishing] to follow all recommended and
required steps.

Releasing a plugin to [JetBrains Marketplace](https://plugins.jetbrains.com) is a straightforward operation that uses
the `publishPlugin` Gradle task provided by
the [intellij-platform-gradle-plugin][docs:intellij-platform-gradle-plugin-docs].

You can also upload the plugin to the [JetBrains Plugin Repository](https://plugins.jetbrains.com/plugin/upload)
manually via UI.

## Useful links

- [IntelliJ Platform SDK Plugin SDK][docs]
- [IntelliJ Platform Gradle Plugin Documentation][docs:intellij-platform-gradle-plugin-docs]
- [IntelliJ Platform Explorer][jb:ipe]
- [JetBrains Marketplace Quality Guidelines][jb:quality-guidelines]
- [IntelliJ Platform UI Guidelines][jb:ui-guidelines]
- [JetBrains Marketplace Paid Plugins][jb:paid-plugins]
- [IntelliJ SDK Code Samples][gh:code-samples]

[docs]: https://plugins.jetbrains.com/docs/intellij
[docs:plugin.xml]: https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html?from=IJPluginReadmeFile
[docs:publishing]: https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html?from=IJPluginReadmeFile
[docs:intellij-platform-gradle-plugin-docs]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html?from=IJPluginReadmeFile
[docs:intellij-platform-gradle-plugin-runIde]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html?from=IJPluginReadmeFile#runIde
[docs:intellij-platform-gradle-plugin-verifyPlugin]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html?from=IJPluginReadmeFile#verifyPlugin
[docs:logo]: https://plugins.jetbrains.com/docs/intellij/plugin-icon-file.html?from=IJPluginReadmeFile
[docs:target-version]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html?from=IJPluginReadmeFile#target-versions
[docs:testing]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html?from=IJPluginReadmeFile#testing

[file:build.gradle.kts]: ./build.gradle.kts
[file:CHANGELOG.md]: ./CHANGELOG.md
[file:gradle.properties]: ./gradle.properties
[file:plugin.xml]: ./src/main/resources/META-INF/plugin.xml

[gh:code-samples]: https://github.com/JetBrains/intellij-sdk-code-samples

[gradle:lifecycle-tasks]: https://docs.gradle.org/current/userguide/java_plugin.html#lifecycle_tasks

[jb:github]: https://github.com/JetBrains/.github/blob/main/profile/README.md
[jb:forum]: https://platform.jetbrains.com/
[jb:quality-guidelines]: https://plugins.jetbrains.com/docs/marketplace/quality-guidelines.html
[jb:paid-plugins]: https://plugins.jetbrains.com/docs/marketplace/paid-plugins-marketplace.html
[jb:ipe]: https://jb.gg/ipe
[jb:ui-guidelines]: https://jetbrains.github.io/ui
