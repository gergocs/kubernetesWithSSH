package com.kubernetes.ui

import com.kubernetes.model.DockerImageInfo
import javax.swing.table.AbstractTableModel

class DockerImageTableModel : AbstractTableModel() {

    private val columns = arrayOf("REPOSITORY", "TAG", "IMAGE ID", "CREATED", "SIZE")
    private var allImages: List<DockerImageInfo> = emptyList()
    private var displayedImages: List<DockerImageInfo> = emptyList()
    private var filterQuery: String = ""

    fun setImages(images: List<DockerImageInfo>) {
        this.allImages = images
        applyFilter()
    }

    fun setFilter(query: String) {
        this.filterQuery = query.trim().lowercase()
        applyFilter()
    }

    private fun applyFilter() {
        displayedImages = if (filterQuery.isEmpty()) {
            allImages
        } else {
            allImages.filter { img ->
                val fullRepoTag = "${img.repository}:${img.tag}".lowercase()
                fullRepoTag.contains(filterQuery) ||
                img.repository.lowercase().contains(filterQuery) ||
                img.tag.lowercase().contains(filterQuery) ||
                img.imageId.lowercase().contains(filterQuery) ||
                img.created.lowercase().contains(filterQuery) ||
                img.size.lowercase().contains(filterQuery)
            }
        }
        fireTableDataChanged()
    }

    fun getImageAt(row: Int): DockerImageInfo? {
        if (row in displayedImages.indices) {
            return displayedImages[row]
        }
        return null
    }

    fun getTotalCount(): Int = allImages.size
    fun getDisplayedCount(): Int = displayedImages.size

    override fun getRowCount(): Int = displayedImages.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val img = displayedImages[rowIndex]
        return when (columnIndex) {
            0 -> img.repository
            1 -> img.tag
            2 -> img.imageId
            3 -> img.created
            4 -> img.size
            else -> ""
        }
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
}
