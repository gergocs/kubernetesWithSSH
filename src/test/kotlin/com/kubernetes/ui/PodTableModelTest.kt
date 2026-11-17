package com.kubernetes.ui

import com.kubernetes.model.PodInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PodTableModelTest {

    @Test
    fun testTableModelOperationsAndFiltering() {
        val model = PodTableModel()

        val pods = listOf(
            PodInfo("auth-service-7f6d89", "default", "Running", "1/1", 0, "5m", "node1", "10.0.0.1", image = "auth-api:1.0.0", imageId = "8251a1f0ba5d"),
            PodInfo("payment-service-123a", "default", "CrashLoopBackOff", "0/1", 4, "12h", "node2", "10.0.0.2", image = "payment-gateway:2.3.1", imageId = "7f9a12b3c4d5"),
            PodInfo("notification-worker", "default", "Completed", "0/1", 0, "1d", "node1", "10.0.0.3", image = "worker-daemon:latest", imageId = "4c0fdaa8b634")
        )

        model.setPods(pods)
        assertEquals(3, model.rowCount)
        assertEquals(9, model.columnCount)
        assertEquals(3, model.getTotalCount())
        assertEquals(3, model.getDisplayedCount())

        // Test column values
        assertEquals("auth-service-7f6d89", model.getValueAt(0, 0))
        assertEquals("Running", model.getValueAt(0, 1))
        assertEquals("1/1", model.getValueAt(0, 2))
        assertEquals(0, model.getValueAt(0, 3))
        assertEquals("5m", model.getValueAt(0, 4))
        assertEquals("auth-api:1.0.0", model.getValueAt(0, 5))
        assertEquals("8251a1f0ba5d", model.getValueAt(0, 6))
        assertEquals("node1", model.getValueAt(0, 7))
        assertEquals("10.0.0.1", model.getValueAt(0, 8))

        // Filter by name
        model.setFilter("payment")
        assertEquals(1, model.rowCount)
        assertEquals("payment-service-123a", model.getPodAt(0)?.name)

        // Filter by image
        model.setFilter("payment-gateway")
        assertEquals(1, model.rowCount)
        assertEquals("payment-service-123a", model.getPodAt(0)?.name)

        // Filter by image ID
        model.setFilter("4c0fdaa8")
        assertEquals(1, model.rowCount)
        assertEquals("notification-worker", model.getPodAt(0)?.name)

        // Filter by status
        model.setFilter("running")
        assertEquals(1, model.rowCount)
        assertEquals("auth-service-7f6d89", model.getPodAt(0)?.name)

        // Filter by node
        model.setFilter("node1")
        assertEquals(2, model.rowCount)

        // Reset filter
        model.setFilter("")
        assertEquals(3, model.rowCount)
        assertNotNull(model.getPodAt(2))
        assertNull(model.getPodAt(10))
    }
}
