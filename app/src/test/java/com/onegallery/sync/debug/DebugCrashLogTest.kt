package com.onegallery.sync.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DebugCrashLogTest {
    @Test
    fun reportIncludesExceptionAndBreadcrumbs() {
        val report = DebugCrashLog.buildReport(
            versionName = "0.8.440",
            versionCode = 467,
            threadName = "main",
            error = IllegalStateException("grade quebrou"),
            breadcrumbs = listOf("scroll-start", "cell video clip.mp4"),
        )
        assertTrue(report.contains("0.8.440"))
        assertTrue(report.contains("467"))
        assertTrue(report.contains("main"))
        assertTrue(report.contains("grade quebrou"))
        assertTrue(report.contains("scroll-start"))
        assertTrue(report.contains("IllegalStateException"))
    }

    @Test
    fun writeReadClearRoundTrip() {
        val dir = File.createTempFile("crash", "dir").apply {
            delete()
            mkdirs()
        }
        DebugCrashLog.write(dir, "stack de teste")
        assertEquals("stack de teste", DebugCrashLog.read(dir))
        DebugCrashLog.clear(dir)
        assertNull(DebugCrashLog.read(dir))
        dir.deleteRecursively()
    }
}
