package com.wboard.ime.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {

    @Test
    fun parsesVersionJsonAndComparesVersionCode() {
        val json = """
            {
              "version": "1.0.2",
              "versionCode": 3,
              "apkUrl": "https://github.com/dwicao/WBoard/releases/download/v1.0.2/WBoard-v1.0.2-release.apk",
              "sizeBytes": 135000,
              "sha256": "abc123",
              "releasedAt": "2026-11-27",
              "notes": ["Fix height", "Add clipboard"]
            }
        """.trimIndent()

        val info = Updater.parseManifest(json)
        assertEquals("1.0.2", info.version)
        assertEquals(3L, info.versionCode)
        assertEquals(135000L, info.sizeBytes)
        assertEquals("abc123", info.sha256)
        assertEquals(listOf("Fix height", "Add clipboard"), info.notes)
        assertTrue(info.isNewerThan(2L))
        assertFalse(info.isNewerThan(3L))
        assertTrue(Updater.isSafeApkUrl(info.apkUrl))
        assertFalse(Updater.isSafeApkUrl("https://evil.example.com/WBoard.apk"))
    }
}
