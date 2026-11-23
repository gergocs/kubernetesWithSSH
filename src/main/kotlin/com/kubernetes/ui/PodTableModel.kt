package com.kubernetes.ui

import com.kubernetes.model.ConfigMapInfo
import com.kubernetes.model.CronJobInfo
import com.kubernetes.model.DaemonSetInfo
import com.kubernetes.model.DeploymentInfo
import com.kubernetes.model.EventInfo
import com.kubernetes.model.IngressInfo
import com.kubernetes.model.JobInfo
import com.kubernetes.model.PersistentVolumeClaimInfo
import com.kubernetes.model.PodInfo
import com.kubernetes.model.ResourceType
import com.kubernetes.model.SecretInfo
import com.kubernetes.model.ServiceInfo
import com.kubernetes.model.StatefulSetInfo
import javax.swing.table.AbstractTableModel

class PodTableModel : AbstractTableModel() {

    private val podColumns = arrayOf("Name", "Status", "Ready", "Restarts", "Age", "Image", "Image ID", "Node", "IP")
    private val serviceColumns = arrayOf("Name", "Type", "Cluster IP", "External IP", "Ports", "Age", "Selector")
    private val ingressColumns = arrayOf("Name", "Class", "Hosts", "Address", "Ports", "Age")
    private val configMapColumns = arrayOf("Name", "Data Keys", "Count", "Age")
    private val secretColumns = arrayOf("Name", "Type", "Data Keys", "Count", "Age")
    private val deploymentColumns = arrayOf("Name", "Ready", "Up-to-date", "Available", "Age", "Images", "Containers")
    private val statefulSetColumns = arrayOf("Name", "Ready", "Age", "Containers", "Images")
    private val daemonSetColumns = arrayOf("Name", "Desired", "Current", "Ready", "Up-to-date", "Available", "Node Selector", "Age")
    private val jobColumns = arrayOf("Name", "Completions", "Duration", "Age", "Containers", "Images")
    private val cronJobColumns = arrayOf("Name", "Schedule", "Suspend", "Active", "Last Schedule", "Age", "Images")
    private val pvcColumns = arrayOf("Name", "Status", "Volume", "Capacity", "Access Modes", "StorageClass", "Age")
    private val eventColumns = arrayOf("Type", "Reason", "Object", "Message", "Count", "Age", "Source")

    private var currentResourceType: ResourceType = ResourceType.PODS

    private var allPods: List<PodInfo> = emptyList()
    private var displayedPods: List<PodInfo> = emptyList()

    private var allServices: List<ServiceInfo> = emptyList()
    private var displayedServices: List<ServiceInfo> = emptyList()

    private var allIngresses: List<IngressInfo> = emptyList()
    private var displayedIngresses: List<IngressInfo> = emptyList()

    private var allConfigMaps: List<ConfigMapInfo> = emptyList()
    private var displayedConfigMaps: List<ConfigMapInfo> = emptyList()

    private var allSecrets: List<SecretInfo> = emptyList()
    private var displayedSecrets: List<SecretInfo> = emptyList()

    private var allDeployments: List<DeploymentInfo> = emptyList()
    private var displayedDeployments: List<DeploymentInfo> = emptyList()

    private var allStatefulSets: List<StatefulSetInfo> = emptyList()
    private var displayedStatefulSets: List<StatefulSetInfo> = emptyList()

    private var allDaemonSets: List<DaemonSetInfo> = emptyList()
    private var displayedDaemonSets: List<DaemonSetInfo> = emptyList()

    private var allJobs: List<JobInfo> = emptyList()
    private var displayedJobs: List<JobInfo> = emptyList()

    private var allCronJobs: List<CronJobInfo> = emptyList()
    private var displayedCronJobs: List<CronJobInfo> = emptyList()

    private var allPVCs: List<PersistentVolumeClaimInfo> = emptyList()
    private var displayedPVCs: List<PersistentVolumeClaimInfo> = emptyList()

    private var allEvents: List<EventInfo> = emptyList()
    private var displayedEvents: List<EventInfo> = emptyList()

    private var filterQuery: String = ""

    fun getResourceType(): ResourceType = currentResourceType

    fun setResourceType(type: ResourceType) {
        if (this.currentResourceType != type) {
            this.currentResourceType = type
            applyFilter()
            fireTableStructureChanged()
        }
    }

    fun setPods(pods: List<PodInfo>) {
        this.currentResourceType = ResourceType.PODS
        this.allPods = pods
        applyFilter()
    }

    fun setServices(services: List<ServiceInfo>) {
        this.currentResourceType = ResourceType.SERVICES
        this.allServices = services
        applyFilter()
    }

    fun setIngresses(ingresses: List<IngressInfo>) {
        this.currentResourceType = ResourceType.INGRESSES
        this.allIngresses = ingresses
        applyFilter()
    }

    fun setConfigMaps(configMaps: List<ConfigMapInfo>) {
        this.currentResourceType = ResourceType.CONFIG_MAPS
        this.allConfigMaps = configMaps
        applyFilter()
    }

    fun setSecrets(secrets: List<SecretInfo>) {
        this.currentResourceType = ResourceType.SECRETS
        this.allSecrets = secrets
        applyFilter()
    }

    fun setDeployments(deployments: List<DeploymentInfo>) {
        this.currentResourceType = ResourceType.DEPLOYMENTS
        this.allDeployments = deployments
        applyFilter()
    }

    fun setStatefulSets(statefulSets: List<StatefulSetInfo>) {
        this.currentResourceType = ResourceType.STATEFUL_SETS
        this.allStatefulSets = statefulSets
        applyFilter()
    }

    fun setDaemonSets(daemonSets: List<DaemonSetInfo>) {
        this.currentResourceType = ResourceType.DAEMON_SETS
        this.allDaemonSets = daemonSets
        applyFilter()
    }

    fun setJobs(jobs: List<JobInfo>) {
        this.currentResourceType = ResourceType.JOBS
        this.allJobs = jobs
        applyFilter()
    }

    fun setCronJobs(cronJobs: List<CronJobInfo>) {
        this.currentResourceType = ResourceType.CRON_JOBS
        this.allCronJobs = cronJobs
        applyFilter()
    }

    fun setPersistentVolumeClaims(pvcs: List<PersistentVolumeClaimInfo>) {
        this.currentResourceType = ResourceType.PERSISTENT_VOLUME_CLAIMS
        this.allPVCs = pvcs
        applyFilter()
    }

    fun setEvents(events: List<EventInfo>) {
        this.currentResourceType = ResourceType.EVENTS
        this.allEvents = events
        applyFilter()
    }

    fun setFilter(query: String) {
        this.filterQuery = query.trim().lowercase()
        applyFilter()
    }

    private fun applyFilter() {
        val q = filterQuery
        when (currentResourceType) {
            ResourceType.PODS -> {
                displayedPods = if (q.isBlank()) {
                    allPods
                } else {
                    allPods.filter { pod ->
                        pod.name.lowercase().contains(q) ||
                        pod.status.lowercase().contains(q) ||
                        pod.image.lowercase().contains(q) ||
                        pod.imageId.lowercase().contains(q) ||
                        pod.node.lowercase().contains(q) ||
                        pod.ip.lowercase().contains(q)
                    }
                }
            }
            ResourceType.SERVICES -> {
                displayedServices = if (q.isBlank()) {
                    allServices
                } else {
                    allServices.filter { svc ->
                        svc.name.lowercase().contains(q) ||
                        svc.type.lowercase().contains(q) ||
                        svc.clusterIP.lowercase().contains(q) ||
                        svc.externalIP.lowercase().contains(q) ||
                        svc.ports.lowercase().contains(q) ||
                        svc.selector.lowercase().contains(q)
                    }
                }
            }
            ResourceType.INGRESSES -> {
                displayedIngresses = if (q.isBlank()) {
                    allIngresses
                } else {
                    allIngresses.filter { ing ->
                        ing.name.lowercase().contains(q) ||
                        ing.className.lowercase().contains(q) ||
                        ing.hosts.lowercase().contains(q) ||
                        ing.address.lowercase().contains(q) ||
                        ing.ports.lowercase().contains(q)
                    }
                }
            }
            ResourceType.CONFIG_MAPS -> {
                displayedConfigMaps = if (q.isBlank()) {
                    allConfigMaps
                } else {
                    allConfigMaps.filter { cm ->
                        cm.name.lowercase().contains(q) ||
                        cm.keys.any { it.lowercase().contains(q) } ||
                        cm.data.values.any { it.lowercase().contains(q) }
                    }
                }
            }
            ResourceType.SECRETS -> {
                displayedSecrets = if (q.isBlank()) {
                    allSecrets
                } else {
                    allSecrets.filter { sec ->
                        sec.name.lowercase().contains(q) ||
                        sec.type.lowercase().contains(q) ||
                        sec.keys.any { it.lowercase().contains(q) }
                    }
                }
            }
            ResourceType.DEPLOYMENTS -> {
                displayedDeployments = if (q.isBlank()) {
                    allDeployments
                } else {
                    allDeployments.filter { dep ->
                        dep.name.lowercase().contains(q) ||
                        dep.images.any { it.lowercase().contains(q) } ||
                        dep.containers.any { it.lowercase().contains(q) }
                    }
                }
            }
            ResourceType.STATEFUL_SETS -> {
                displayedStatefulSets = if (q.isBlank()) {
                    allStatefulSets
                } else {
                    allStatefulSets.filter { sts ->
                        sts.name.lowercase().contains(q) ||
                        sts.images.any { it.lowercase().contains(q) } ||
                        sts.containers.any { it.lowercase().contains(q) }
                    }
                }
            }
            ResourceType.DAEMON_SETS -> {
                displayedDaemonSets = if (q.isBlank()) {
                    allDaemonSets
                } else {
                    allDaemonSets.filter { ds ->
                        ds.name.lowercase().contains(q) ||
                        ds.nodeSelector.lowercase().contains(q) ||
                        ds.images.any { it.lowercase().contains(q) } ||
                        ds.containers.any { it.lowercase().contains(q) }
                    }
                }
            }
            ResourceType.JOBS -> {
                displayedJobs = if (q.isBlank()) {
                    allJobs
                } else {
                    allJobs.filter { j ->
                        j.name.lowercase().contains(q) ||
                        j.condition.lowercase().contains(q) ||
                        j.images.any { it.lowercase().contains(q) } ||
                        j.containers.any { it.lowercase().contains(q) }
                    }
                }
            }
            ResourceType.CRON_JOBS -> {
                displayedCronJobs = if (q.isBlank()) {
                    allCronJobs
                } else {
                    allCronJobs.filter { cj ->
                        cj.name.lowercase().contains(q) ||
                        cj.schedule.lowercase().contains(q) ||
                        cj.images.any { it.lowercase().contains(q) } ||
                        cj.containers.any { it.lowercase().contains(q) }
                    }
                }
            }
            ResourceType.PERSISTENT_VOLUME_CLAIMS -> {
                displayedPVCs = if (q.isBlank()) {
                    allPVCs
                } else {
                    allPVCs.filter { pvc ->
                        pvc.name.lowercase().contains(q) ||
                        pvc.status.lowercase().contains(q) ||
                        pvc.volume.lowercase().contains(q) ||
                        pvc.storageClass.lowercase().contains(q) ||
                        pvc.accessModes.lowercase().contains(q)
                    }
                }
            }
            ResourceType.EVENTS -> {
                displayedEvents = if (q.isBlank()) {
                    allEvents
                } else {
                    allEvents.filter { ev ->
                        ev.name.lowercase().contains(q) ||
                        ev.type.lowercase().contains(q) ||
                        ev.reason.lowercase().contains(q) ||
                        ev.involvedObject.lowercase().contains(q) ||
                        ev.message.lowercase().contains(q) ||
                        ev.source.lowercase().contains(q)
                    }
                }
            }
        }
        fireTableDataChanged()
    }

    fun getPodAt(row: Int): PodInfo? = displayedPods.getOrNull(row)
    fun getServiceAt(row: Int): ServiceInfo? = displayedServices.getOrNull(row)
    fun getIngressAt(row: Int): IngressInfo? = displayedIngresses.getOrNull(row)
    fun getConfigMapAt(row: Int): ConfigMapInfo? = displayedConfigMaps.getOrNull(row)
    fun getSecretAt(row: Int): SecretInfo? = displayedSecrets.getOrNull(row)
    fun getDeploymentAt(row: Int): DeploymentInfo? = displayedDeployments.getOrNull(row)
    fun getStatefulSetAt(row: Int): StatefulSetInfo? = displayedStatefulSets.getOrNull(row)
    fun getDaemonSetAt(row: Int): DaemonSetInfo? = displayedDaemonSets.getOrNull(row)
    fun getJobAt(row: Int): JobInfo? = displayedJobs.getOrNull(row)
    fun getCronJobAt(row: Int): CronJobInfo? = displayedCronJobs.getOrNull(row)
    fun getPersistentVolumeClaimAt(row: Int): PersistentVolumeClaimInfo? = displayedPVCs.getOrNull(row)
    fun getEventAt(row: Int): EventInfo? = displayedEvents.getOrNull(row)

    fun getResourceNameAt(row: Int): String? {
        return when (currentResourceType) {
            ResourceType.PODS -> getPodAt(row)?.name
            ResourceType.SERVICES -> getServiceAt(row)?.name
            ResourceType.INGRESSES -> getIngressAt(row)?.name
            ResourceType.CONFIG_MAPS -> getConfigMapAt(row)?.name
            ResourceType.SECRETS -> getSecretAt(row)?.name
            ResourceType.DEPLOYMENTS -> getDeploymentAt(row)?.name
            ResourceType.STATEFUL_SETS -> getStatefulSetAt(row)?.name
            ResourceType.DAEMON_SETS -> getDaemonSetAt(row)?.name
            ResourceType.JOBS -> getJobAt(row)?.name
            ResourceType.CRON_JOBS -> getCronJobAt(row)?.name
            ResourceType.PERSISTENT_VOLUME_CLAIMS -> getPersistentVolumeClaimAt(row)?.name
            ResourceType.EVENTS -> getEventAt(row)?.name
        }
    }

    fun getResourceNamespaceAt(row: Int): String? {
        return when (currentResourceType) {
            ResourceType.PODS -> getPodAt(row)?.namespace
            ResourceType.SERVICES -> getServiceAt(row)?.namespace
            ResourceType.INGRESSES -> getIngressAt(row)?.namespace
            ResourceType.CONFIG_MAPS -> getConfigMapAt(row)?.namespace
            ResourceType.SECRETS -> getSecretAt(row)?.namespace
            ResourceType.DEPLOYMENTS -> getDeploymentAt(row)?.namespace
            ResourceType.STATEFUL_SETS -> getStatefulSetAt(row)?.namespace
            ResourceType.DAEMON_SETS -> getDaemonSetAt(row)?.namespace
            ResourceType.JOBS -> getJobAt(row)?.namespace
            ResourceType.CRON_JOBS -> getCronJobAt(row)?.namespace
            ResourceType.PERSISTENT_VOLUME_CLAIMS -> getPersistentVolumeClaimAt(row)?.namespace
            ResourceType.EVENTS -> getEventAt(row)?.namespace
        }
    }

    fun getDisplayedCount(): Int = when (currentResourceType) {
        ResourceType.PODS -> displayedPods.size
        ResourceType.SERVICES -> displayedServices.size
        ResourceType.INGRESSES -> displayedIngresses.size
        ResourceType.CONFIG_MAPS -> displayedConfigMaps.size
        ResourceType.SECRETS -> displayedSecrets.size
        ResourceType.DEPLOYMENTS -> displayedDeployments.size
        ResourceType.STATEFUL_SETS -> displayedStatefulSets.size
        ResourceType.DAEMON_SETS -> displayedDaemonSets.size
        ResourceType.JOBS -> displayedJobs.size
        ResourceType.CRON_JOBS -> displayedCronJobs.size
        ResourceType.PERSISTENT_VOLUME_CLAIMS -> displayedPVCs.size
        ResourceType.EVENTS -> displayedEvents.size
    }

    fun getTotalCount(): Int = when (currentResourceType) {
        ResourceType.PODS -> allPods.size
        ResourceType.SERVICES -> allServices.size
        ResourceType.INGRESSES -> allIngresses.size
        ResourceType.CONFIG_MAPS -> allConfigMaps.size
        ResourceType.SECRETS -> allSecrets.size
        ResourceType.DEPLOYMENTS -> allDeployments.size
        ResourceType.STATEFUL_SETS -> allStatefulSets.size
        ResourceType.DAEMON_SETS -> allDaemonSets.size
        ResourceType.JOBS -> allJobs.size
        ResourceType.CRON_JOBS -> allCronJobs.size
        ResourceType.PERSISTENT_VOLUME_CLAIMS -> allPVCs.size
        ResourceType.EVENTS -> allEvents.size
    }

    override fun getRowCount(): Int = getDisplayedCount()

    override fun getColumnCount(): Int = when (currentResourceType) {
        ResourceType.PODS -> podColumns.size
        ResourceType.SERVICES -> serviceColumns.size
        ResourceType.INGRESSES -> ingressColumns.size
        ResourceType.CONFIG_MAPS -> configMapColumns.size
        ResourceType.SECRETS -> secretColumns.size
        ResourceType.DEPLOYMENTS -> deploymentColumns.size
        ResourceType.STATEFUL_SETS -> statefulSetColumns.size
        ResourceType.DAEMON_SETS -> daemonSetColumns.size
        ResourceType.JOBS -> jobColumns.size
        ResourceType.CRON_JOBS -> cronJobColumns.size
        ResourceType.PERSISTENT_VOLUME_CLAIMS -> pvcColumns.size
        ResourceType.EVENTS -> eventColumns.size
    }

    override fun getColumnName(column: Int): String = when (currentResourceType) {
        ResourceType.PODS -> podColumns.getOrElse(column) { "" }
        ResourceType.SERVICES -> serviceColumns.getOrElse(column) { "" }
        ResourceType.INGRESSES -> ingressColumns.getOrElse(column) { "" }
        ResourceType.CONFIG_MAPS -> configMapColumns.getOrElse(column) { "" }
        ResourceType.SECRETS -> secretColumns.getOrElse(column) { "" }
        ResourceType.DEPLOYMENTS -> deploymentColumns.getOrElse(column) { "" }
        ResourceType.STATEFUL_SETS -> statefulSetColumns.getOrElse(column) { "" }
        ResourceType.DAEMON_SETS -> daemonSetColumns.getOrElse(column) { "" }
        ResourceType.JOBS -> jobColumns.getOrElse(column) { "" }
        ResourceType.CRON_JOBS -> cronJobColumns.getOrElse(column) { "" }
        ResourceType.PERSISTENT_VOLUME_CLAIMS -> pvcColumns.getOrElse(column) { "" }
        ResourceType.EVENTS -> eventColumns.getOrElse(column) { "" }
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        return when (currentResourceType) {
            ResourceType.PODS -> {
                val pod = displayedPods.getOrNull(rowIndex) ?: return ""
                when (columnIndex) {
                    0 -> pod.name
                    1 -> pod.status
                    2 -> pod.ready
                    3 -> pod.restarts
                    4 -> pod.age
                    5 -> pod.image
                    6 -> pod.imageId
                    7 -> pod.node
                    8 -> pod.ip
                    else -> ""
                }
            }
            ResourceType.SERVICES -> {
                val svc = displayedServices.getOrNull(rowIndex) ?: return ""
                when (columnIndex) {
                    0 -> svc.name
                    1 -> svc.type
                    2 -> svc.clusterIP
                    3 -> svc.externalIP
                    4 -> svc.ports
                    5 -> svc.age
                    6 -> svc.selector
                    else -> ""
                }
            }
            ResourceType.INGRESSES -> {
                val ing = displayedIngresses.getOrNull(rowIndex) ?: return ""
                when (columnIndex) {
                    0 -> ing.name
                    1 -> ing.className.ifBlank { "<none>" }
                    2 -> ing.hosts
                    3 -> ing.address
                    4 -> ing.ports
                    5 -> ing.age
                    else -> ""
                }
            }
            ResourceType.CONFIG_MAPS -> {
                val cm = displayedConfigMaps.getOrNull(rowIndex) ?: return ""
                when (columnIndex) {
                    0 -> cm.name
                    1 -> if (cm.keys.isNotEmpty()) cm.keys.joinToString(", ") else "${cm.dataCount} items"
                    2 -> cm.dataCount
                    3 -> cm.age
                    else -> ""
                }
            }
            ResourceType.SECRETS -> {
                val sec = displayedSecrets.getOrNull(rowIndex) ?: return ""
                when (columnIndex) {
                    0 -> sec.name
                    1 -> sec.type
                    2 -> if (sec.keys.isNotEmpty()) sec.keys.joinToString(", ") else "${sec.dataCount} items"
                    3 -> sec.dataCount
                    4 -> sec.age
                    else -> ""
                }
            }
            ResourceType.DEPLOYMENTS -> {
                val dep = displayedDeployments.getOrNull(rowIndex) ?: return ""
                when (columnIndex) {
                    0 -> dep.name
                    1 -> "${dep.readyReplicas}/${dep.replicas}"
                    2 -> dep.updatedReplicas
                    3 -> dep.availableReplicas
                    4 -> dep.age
                    5 -> dep.images.joinToString(", ")
                    6 -> dep.containers.joinToString(", ")
                    else -> ""
                }
            }
            ResourceType.STATEFUL_SETS -> {
                val sts = displayedStatefulSets.getOrNull(rowIndex) ?: return ""
                when (columnIndex) {
                    0 -> sts.name
                    1 -> "${sts.readyReplicas}/${sts.replicas}"
                    2 -> sts.age
                    3 -> sts.containers.joinToString(", ")
                    4 -> sts.images.joinToString(", ")
                    else -> ""
                }
            }
            ResourceType.DAEMON_SETS -> {
                val ds = displayedDaemonSets.getOrNull(rowIndex) ?: return ""
                when (columnIndex) {
                    0 -> ds.name
                    1 -> ds.desired
                    2 -> ds.current
                    3 -> ds.ready
                    4 -> ds.upToDate
                    5 -> ds.available
                    6 -> ds.nodeSelector
                    7 -> ds.age
                    else -> ""
                }
            }
            ResourceType.JOBS -> {
                val job = displayedJobs.getOrNull(rowIndex) ?: return ""
                when (columnIndex) {
                    0 -> job.name
                    1 -> job.completions
                    2 -> job.duration.ifBlank { "<running/none>" }
                    3 -> job.age
                    4 -> job.containers.joinToString(", ")
                    5 -> job.images.joinToString(", ")
                    else -> ""
                }
            }
            ResourceType.CRON_JOBS -> {
                val cj = displayedCronJobs.getOrNull(rowIndex) ?: return ""
                when (columnIndex) {
                    0 -> cj.name
                    1 -> cj.schedule
                    2 -> if (cj.suspend) "True" else "False"
                    3 -> cj.active
                    4 -> cj.lastSchedule
                    5 -> cj.age
                    6 -> cj.images.joinToString(", ")
                    else -> ""
                }
            }
            ResourceType.PERSISTENT_VOLUME_CLAIMS -> {
                val pvc = displayedPVCs.getOrNull(rowIndex) ?: return ""
                when (columnIndex) {
                    0 -> pvc.name
                    1 -> pvc.status
                    2 -> pvc.volume.ifBlank { "<none>" }
                    3 -> pvc.capacity.ifBlank { "<none>" }
                    4 -> pvc.accessModes.ifBlank { "<none>" }
                    5 -> pvc.storageClass.ifBlank { "<none>" }
                    6 -> pvc.age
                    else -> ""
                }
            }
            ResourceType.EVENTS -> {
                val ev = displayedEvents.getOrNull(rowIndex) ?: return ""
                when (columnIndex) {
                    0 -> ev.type
                    1 -> ev.reason
                    2 -> ev.involvedObject
                    3 -> ev.message
                    4 -> ev.count
                    5 -> ev.age
                    6 -> ev.source.ifBlank { "<none>" }
                    else -> ""
                }
            }
        }
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    override fun getColumnClass(columnIndex: Int): Class<*> {
        return when (currentResourceType) {
            ResourceType.PODS -> if (columnIndex == 3) java.lang.Integer::class.java else String::class.java
            ResourceType.CONFIG_MAPS -> if (columnIndex == 2) java.lang.Integer::class.java else String::class.java
            ResourceType.SECRETS -> if (columnIndex == 3) java.lang.Integer::class.java else String::class.java
            ResourceType.DEPLOYMENTS -> if (columnIndex in 2..3) java.lang.Integer::class.java else String::class.java
            ResourceType.DAEMON_SETS -> if (columnIndex in 1..5) java.lang.Integer::class.java else String::class.java
            ResourceType.CRON_JOBS -> if (columnIndex == 3) java.lang.Integer::class.java else String::class.java
            ResourceType.EVENTS -> if (columnIndex == 4) java.lang.Integer::class.java else String::class.java
            ResourceType.SERVICES,
            ResourceType.INGRESSES,
            ResourceType.STATEFUL_SETS,
            ResourceType.JOBS,
            ResourceType.PERSISTENT_VOLUME_CLAIMS -> String::class.java
        }
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
}
