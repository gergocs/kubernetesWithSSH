package com.kubernetes.ui

import com.kubernetes.model.PodInfo
import javax.swing.table.AbstractTableModel

class PodTableModel : AbstractTableModel() {

    private val columns = arrayOf("Name", "Status", "Ready", "Restarts", "Age", "Image", "Image ID", "Node", "IP")
    private var allPods: List<PodInfo> = emptyList()
    private var displayedPods: List<PodInfo> = emptyList()
    private var filterQuery: String = ""

    fun setPods(pods: List<PodInfo>) {
        this.allPods = pods
        applyFilter()
    }

    fun setFilter(query: String) {
        this.filterQuery = query.trim().lowercase()
        applyFilter()
    }

    private fun applyFilter() {
        displayedPods = if (filterQuery.isBlank()) {
            allPods
        } else {
            allPods.filter { pod ->
                pod.name.lowercase().contains(filterQuery) ||
                pod.status.lowercase().contains(filterQuery) ||
                pod.image.lowercase().contains(filterQuery) ||
                pod.imageId.lowercase().contains(filterQuery) ||
                pod.node.lowercase().contains(filterQuery) ||
                pod.ip.lowercase().contains(filterQuery)
            }
        }
        fireTableDataChanged()
    }

    fun getPodAt(row: Int): PodInfo? {
        if (row in displayedPods.indices) {
            return displayedPods[row]
        }
        return null
    }

    fun getDisplayedCount(): Int = displayedPods.size
    fun getTotalCount(): Int = allPods.size

    override fun getRowCount(): Int = displayedPods.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val pod = displayedPods[rowIndex]
        return when (columnIndex) {
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

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    override fun getColumnClass(columnIndex: Int): Class<*> {
        return when (columnIndex) {
            3 -> java.lang.Integer::class.java
            else -> String::class.java
        }
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
}
