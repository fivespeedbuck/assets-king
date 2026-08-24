package com.assetsking.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** 在 App 内下载并校验 APK，最后只把已验证文件交给 Android 系统安装器。 */
object UpdateInstaller {
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val BUFFER_SIZE = 256 * 1024

    data class DownloadedApk(
        val file: File,
        val bytes: Long,
        val sha256: String
    )

    suspend fun download(
        context: Context,
        release: UpdateChecker.Release,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): DownloadedApk = withContext(Dispatchers.IO) {
        if (!release.apkUrl.startsWith("https://")) {
            throw UpdateDownloadException("安装包下载地址无效")
        }

        val destination = destinationFile(context, release.apkName)
        val partial = File(destination.parentFile, "${destination.name}.part")
        destination.parentFile?.mkdirs()
        partial.delete()

        val connection = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", APK_MIME)
            connection.setRequestProperty("User-Agent", "assets-king/${BuildConfig.VERSION_NAME}")
            val code = connection.responseCode
            if (code !in 200..299) throw UpdateDownloadException("下载失败：服务器返回 $code")

            var downloaded = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                partial.outputStream().buffered(BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, release.apkSize)
                    }
                }
            }

            val actualSha256 = digest.digest().joinToString("") { "%02X".format(it.toInt() and 0xFF) }
            verifyDownload(downloaded, actualSha256, release.apkSize, release.sha256)
            Files.move(
                partial.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
            onProgress(downloaded, release.apkSize)
            DownloadedApk(destination, downloaded, actualSha256)
        } catch (error: Throwable) {
            partial.delete()
            throw when (error) {
                is UpdateDownloadException -> error
                is SocketTimeoutException -> UpdateDownloadException("下载超时，请重试", error)
                is UnknownHostException -> UpdateDownloadException("无法连接下载地址", error)
                is IOException -> UpdateDownloadException("下载中断，请检查网络后重试", error)
                else -> error
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun verifyDownload(
        actualSize: Long,
        actualSha256: String,
        expectedSize: Long,
        expectedSha256: String
    ) {
        if (actualSize != expectedSize) {
            throw UpdateDownloadException("下载大小不一致，请重新下载")
        }
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            throw UpdateDownloadException("下载校验失败，安装包可能不完整")
        }
    }

    fun canRequestPackageInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )

    fun launchSystemInstaller(context: Context, apk: File) {
        if (!apk.isFile) throw UpdateDownloadException("安装包不存在，请重新下载")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.update.provider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun destinationFile(context: Context, requestedName: String): File {
        val safeName = requestedName.substringAfterLast('/').substringAfterLast('\\')
            .takeIf { it.endsWith(".apk", ignoreCase = true) }
            ?: "assets-king-update.apk"
        val externalDownloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val directory = if (externalDownloads != null) {
            File(externalDownloads, "updates")
        } else {
            File(context.filesDir, "updates")
        }
        return File(directory, safeName)
    }
}

class UpdateDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)
