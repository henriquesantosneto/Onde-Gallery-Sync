package com.onegallery.sync.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SyncProcessMarkerTest {

    @Test
    fun visibleSyncDeathBecomesTheCrashReport() {
        val dir = tempDir()
        SyncProcessMarker.note(dir, foreground = true, step = "Miniaturas dos vídeos locais…")
        val report = SyncProcessMarker.promoteUncleanExit(dir, "0.8.480", 507)
        assertTrue(report!!.contains("fechou a tela sozinho"))
        assertTrue(report.contains("Miniaturas dos vídeos locais"))
        assertTrue(report.contains("0.8.480"))
        assertEquals(report, DebugCrashLog.read(dir))
        assertNull(SyncProcessMarker.promoteUncleanExit(dir, "0.8.480", 507))
        dir.deleteRecursively()
    }

    @Test
    fun leavingTheScreenDropsAVisibleDeathReport() {
        val dir = tempDir()
        SyncProcessMarker.note(dir, foreground = true, step = "Comparando itens locais com a nuvem…")
        SyncProcessMarker.setForeground(dir, foreground = false)
        assertNull(SyncProcessMarker.promoteUncleanExit(dir, "0.8.483", 510))
        assertNull(DebugCrashLog.read(dir))
        dir.deleteRecursively()
    }

    @Test
    fun backgroundExitDoesNotBlockTheNextOpen() {
        val dir = tempDir()
        SyncProcessMarker.note(dir, foreground = false, step = "Sincronizando com OneDrive…")
        assertNull(SyncProcessMarker.promoteUncleanExit(dir, "0.8.480", 507))
        assertNull(DebugCrashLog.read(dir))
        dir.deleteRecursively()
    }

    @Test
    fun aRealJavaCrashIsKept() {
        val dir = tempDir()
        DebugCrashLog.write(dir, "exceção já gravada")
        SyncProcessMarker.note(dir, foreground = true, step = "Indexando biblioteca do aparelho…")
        assertNull(SyncProcessMarker.promoteUncleanExit(dir, "0.8.480", 507))
        assertEquals("exceção já gravada", DebugCrashLog.read(dir))
        dir.deleteRecursively()
    }

    private fun tempDir(): File = File.createTempFile("sync-marker", "dir").apply {
        delete()
        mkdirs()
    }
}
