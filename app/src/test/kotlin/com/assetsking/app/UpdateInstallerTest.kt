package com.assetsking.app

import kotlin.test.Test
import kotlin.test.assertFailsWith

class UpdateInstallerTest {
    @Test
    fun `verified apk accepts matching size and sha256`() {
        UpdateInstaller.verifyDownload(
            actualSize = 123L,
            actualSha256 = "A".repeat(64),
            expectedSize = 123L,
            expectedSha256 = "a".repeat(64)
        )
    }

    @Test
    fun `verified apk rejects size mismatch`() {
        assertFailsWith<UpdateDownloadException> {
            UpdateInstaller.verifyDownload(122L, "A".repeat(64), 123L, "A".repeat(64))
        }
    }

    @Test
    fun `verified apk rejects hash mismatch`() {
        assertFailsWith<UpdateDownloadException> {
            UpdateInstaller.verifyDownload(123L, "B".repeat(64), 123L, "A".repeat(64))
        }
    }
}
