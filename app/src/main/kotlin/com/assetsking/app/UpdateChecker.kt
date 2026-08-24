package com.assetsking.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * 软件升级元数据：优先读取 GitHub Releases API；API 不可用时回退到仓库内的公开清单。
 * 清单只含公开发布信息，APK 内不保存 GitHub Token。
 */
object UpdateChecker {

    internal const val LATEST_RELEASE_API =
        "https://api.github.com/repos/fivespeedbuck/assets-king/releases/latest"
    internal const val RELEASE_MANIFEST_URL =
        "https://raw.githubusercontent.com/fivespeedbuck/assets-king/main/update_manifest.json"
    private const val RELEASES_URL =
        "https://github.com/fivespeedbuck/assets-king/releases/latest"

    enum class Source { GITHUB_API, PUBLIC_MANIFEST }

    data class Release(
        val tag: String,
        val name: String,
        val body: String,
        val htmlUrl: String,
        val apkUrl: String,
        val apkName: String,
        val apkSize: Long,
        val sha256: String
    )

    sealed interface FetchResult {
        data class Success(val release: Release, val source: Source) : FetchResult
        data class Failure(val message: String) : FetchResult
    }

    /** 需在 IO 线程调用。 */
    fun fetchLatest(): FetchResult = fetchLatestWith(::readJson)

    internal fun fetchLatestWith(reader: (String) -> String): FetchResult {
        val api = runCatching { parseRelease(reader(LATEST_RELEASE_API)) }
        api.getOrNull()?.let { return FetchResult.Success(it, Source.GITHUB_API) }

        val manifest = runCatching { parseRelease(reader(RELEASE_MANIFEST_URL)) }
        manifest.getOrNull()?.let { return FetchResult.Success(it, Source.PUBLIC_MANIFEST) }

        return FetchResult.Failure(
            "检查失败：主地址${failureLabel(api.exceptionOrNull())}，" +
                "备用地址${failureLabel(manifest.exceptionOrNull())}"
        )
    }

    internal fun parseRelease(json: String): Release {
        val obj = JSONObject(json)
        val assets = obj.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0))
            .asSequence()
            .mapNotNull { assets?.optJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?: throw UpdateMetadataException("没有可用 APK")

        val tag = obj.optString("tag_name").trim()
        val apkUrl = apk.optString("browser_download_url").trim()
        val apkName = apk.optString("name").trim()
        val apkSize = apk.optLong("size", 0L)
        val rawDigest = apk.optString("digest")
        val sha256 = rawDigest.substringAfter("sha256:", missingDelimiterValue = rawDigest).trim().uppercase()

        if (tag.isBlank()) throw UpdateMetadataException("版本号缺失")
        if (!apkUrl.startsWith("https://")) throw UpdateMetadataException("APK 地址无效")
        if (!apkName.endsWith(".apk", ignoreCase = true)) throw UpdateMetadataException("APK 文件名无效")
        if (apkSize <= 0L) throw UpdateMetadataException("APK 大小缺失")
        if (!sha256.matches(Regex("[0-9A-F]{64}"))) throw UpdateMetadataException("APK 校验值缺失")

        return Release(
            tag = tag,
            name = obj.optString("name").ifBlank { tag },
            body = obj.optString("body"),
            htmlUrl = obj.optString("html_url").ifBlank { RELEASES_URL },
            apkUrl = apkUrl,
            apkName = apkName,
            apkSize = apkSize,
            sha256 = sha256
        )
    }

    private fun readJson(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "assets-king/${BuildConfig.VERSION_NAME}")
            val code = connection.responseCode
            if (code !in 200..299) throw UpdateMetadataException("HTTP $code")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun failureLabel(error: Throwable?): String = when (error) {
        is SocketTimeoutException -> "连接超时"
        is UnknownHostException -> "无法解析"
        is UpdateMetadataException -> error.message ?: "数据无效"
        is IOException -> "网络不可用"
        else -> "数据无效"
    }

    /** 最新 tag 是否比当前版本新。兼容 v0.2.0 / 0.2.0，按数字段比较。 */
    fun isNewer(latestTag: String, current: String): Boolean {
        fun parts(value: String) = value.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val latest = parts(latestTag)
        val installed = parts(current)
        for (index in 0 until maxOf(latest.size, installed.size)) {
            val left = latest.getOrElse(index) { 0 }
            val right = installed.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }
}

internal class UpdateMetadataException(message: String) : IOException(message)
