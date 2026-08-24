package com.assetsking.app

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdateCheckerTest {
    private val releaseJson = """
        {
          "tag_name": "v0.1.4",
          "name": "资产大王 v0.1.4",
          "body": "修复更新",
          "html_url": "https://github.com/fivespeedbuck/assets-king/releases/tag/v0.1.4",
          "assets": [{
            "name": "assets-king-v0.1.4.apk",
            "browser_download_url": "https://github.com/fivespeedbuck/assets-king/releases/download/v0.1.4/assets-king-v0.1.4.apk",
            "size": 12345,
            "digest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          }]
        }
    """.trimIndent()

    @Test
    fun `patch release is newer than installed version`() {
        assertTrue(UpdateChecker.isNewer("v0.1.4", "0.1.3"))
        assertFalse(UpdateChecker.isNewer("v0.1.4", "0.1.4"))
    }

    @Test
    fun `release parser keeps verified apk metadata`() {
        val release = UpdateChecker.parseRelease(releaseJson)

        assertEquals("v0.1.4", release.tag)
        assertEquals("assets-king-v0.1.4.apk", release.apkName)
        assertEquals(12345L, release.apkSize)
        assertEquals("A".repeat(64), release.sha256)
    }

    @Test
    fun `public manifest is used when github api fails`() {
        val visited = mutableListOf<String>()

        val result = UpdateChecker.fetchLatestWith { url ->
            visited += url
            if (url == UpdateChecker.LATEST_RELEASE_API) throw IOException("API unavailable")
            releaseJson
        }

        val success = assertIs<UpdateChecker.FetchResult.Success>(result)
        assertEquals(UpdateChecker.Source.PUBLIC_MANIFEST, success.source)
        assertEquals(
            listOf(UpdateChecker.LATEST_RELEASE_API, UpdateChecker.RELEASE_MANIFEST_URL),
            visited
        )
    }

    @Test
    fun `both update sources return a useful failure`() {
        val result = UpdateChecker.fetchLatestWith { "{}" }

        val failure = assertIs<UpdateChecker.FetchResult.Failure>(result)
        assertTrue(failure.message.contains("主地址"))
        assertTrue(failure.message.contains("备用地址"))
    }
}
