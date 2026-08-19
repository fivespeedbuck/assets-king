package com.assetsking.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 软件升级检查（REQ 设置§13）：沿用碳水大王流程——检查 GitHub Release、展示版本说明，
 * 由用户确认后跳转下载安装 APK，不静默更新。仅做只读检查与跳转，不在 App 内下载/安装。
 */
object UpdateChecker {

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/fivespeedbuck/assets-king/releases/latest"

    data class Release(
        val tag: String,        // 如 v0.2.0
        val name: String,       // 版本标题
        val body: String,       // 版本说明（Markdown）
        val htmlUrl: String     // Release 页面（用户确认后在浏览器下载 APK）
    )

    /** 拉取最新 Release；无网络/无 Release/解析失败都返回 null。需在 IO 线程调用。 */
    fun fetchLatest(): Release? = runCatching {
        val conn = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "assets-king")
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val obj = JSONObject(text)
        Release(
            tag = obj.optString("tag_name"),
            name = obj.optString("name").ifBlank { obj.optString("tag_name") },
            body = obj.optString("body"),
            htmlUrl = obj.optString("html_url")
        )
    }.getOrNull()

    /** 最新 tag 是否比当前版本新。兼容 "v0.2.0" / "0.2.0" 写法，按数字段比较。 */
    fun isNewer(latestTag: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val a = parts(latestTag)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
