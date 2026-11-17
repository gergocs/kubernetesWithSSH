package com.kubernetes.ui

import com.kubernetes.model.DockerImageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DockerImageTableModelTest {

    @Test
    fun testTableModelDataAndColumns() {
        val model = DockerImageTableModel()

        val images = listOf(
            DockerImageInfo("ghcr.io/maintainerr/maintainerr", "latest", "8251a1f0ba5d", "5 weeks ago", "742MB"),
            DockerImageInfo("nginx", "1.25-alpine", "4c0fdaa8b634", "2 weeks ago", "42.6MB"),
            DockerImageInfo("redis", "7.0-alpine", "123456789abc", "3 months ago", "35.1MB")
        )

        model.setImages(images)
        assertEquals(3, model.rowCount)
        assertEquals(5, model.columnCount)
        assertEquals(3, model.getTotalCount())
        assertEquals(3, model.getDisplayedCount())

        assertEquals("REPOSITORY", model.getColumnName(0))
        assertEquals("TAG", model.getColumnName(1))
        assertEquals("IMAGE ID", model.getColumnName(2))
        assertEquals("CREATED", model.getColumnName(3))
        assertEquals("SIZE", model.getColumnName(4))

        assertEquals("ghcr.io/maintainerr/maintainerr", model.getValueAt(0, 0))
        assertEquals("latest", model.getValueAt(0, 1))
        assertEquals("8251a1f0ba5d", model.getValueAt(0, 2))
        assertEquals("5 weeks ago", model.getValueAt(0, 3))
        assertEquals("742MB", model.getValueAt(0, 4))

        // Filter by repository
        model.setFilter("maintainerr")
        assertEquals(1, model.rowCount)
        assertEquals("ghcr.io/maintainerr/maintainerr", model.getImageAt(0)?.repository)

        // Filter by image ID
        model.setFilter("4c0fdaa8")
        assertEquals(1, model.rowCount)
        assertEquals("nginx", model.getImageAt(0)?.repository)

        // Filter by tag
        model.setFilter("alpine")
        assertEquals(2, model.rowCount)

        // Filter by repository:tag combined
        model.setFilter("nginx:1.25-alpine")
        assertEquals(1, model.rowCount)
        assertEquals("nginx", model.getImageAt(0)?.repository)

        // Filter by short image ID
        model.setFilter("8251a1f0ba5d")
        assertEquals(1, model.rowCount)
        assertEquals("ghcr.io/maintainerr/maintainerr", model.getImageAt(0)?.repository)

        // Clear filter
        model.setFilter("")
        assertEquals(3, model.rowCount)

        assertNull(model.getImageAt(99))
    }
}
