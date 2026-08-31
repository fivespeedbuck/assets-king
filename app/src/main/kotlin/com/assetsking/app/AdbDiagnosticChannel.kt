package com.assetsking.app

import com.assetsking.database.LedgerRepository
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AdbDiagnosticState(
    val starting: Boolean = false,
    val active: Boolean = false,
    val port: Int = 0,
    val token: String = "",
    val remainingSeconds: Int = 0,
    val error: String? = null
)

internal fun acceptsDiagnosticRequest(requestLine: String?, expectedToken: String): Boolean {
    if (requestLine == null || requestLine.length > 2_048) return false
    val parts = requestLine.split(' ')
    if (parts.size != 3 || parts[0] != "GET" || parts[2] !in setOf("HTTP/1.0", "HTTP/1.1")) return false
    return parts[1] == "/snapshot?token=$expectedToken"
}

/**
 * Release 包内默认关闭的临时诊断通道。它只监听设备回环地址，只能下载一次只读数据库快照；
 * 不提供 SQL、写入或文件浏览能力。
 */
class AdbDiagnosticChannel(
    private val repository: LedgerRepository
) {
    private data class Session(val id: String, val socket: ServerSocket)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val _state = MutableStateFlow(AdbDiagnosticState())
    val state: StateFlow<AdbDiagnosticState> = _state.asStateFlow()
    private var session: Session? = null

    suspend fun start() = withContext(Dispatchers.IO) {
        stop()
        _state.value = AdbDiagnosticState(starting = true)
        runCatching {
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
            }
            val current = Session(UUID.randomUUID().toString(), server)
            val token = ByteArray(24).also(SecureRandom()::nextBytes)
                .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            synchronized(lock) { session = current }
            _state.value = AdbDiagnosticState(
                active = true,
                port = server.localPort,
                token = token,
                remainingSeconds = SESSION_SECONDS
            )
            scope.launch { serve(current, token) }
            scope.launch { countDown(current) }
        }.onFailure { error ->
            _state.value = AdbDiagnosticState(error = error.message ?: "诊断通道开启失败")
        }
    }

    fun stop() {
        val closing = synchronized(lock) {
            val current = session
            session = null
            current
        }
        closing?.socket?.close()
        _state.value = AdbDiagnosticState()
    }

    private suspend fun serve(current: Session, token: String) {
        try {
            while (isCurrent(current)) {
                val client = current.socket.accept()
                if (serveClient(client, token)) {
                    closeCurrent(current)
                    return
                }
            }
        } catch (_: Exception) {
            // 手动关闭或倒计时结束会关闭 ServerSocket，从而退出阻塞中的 accept。
        }
    }

    private suspend fun serveClient(client: Socket, token: String): Boolean = client.use { socket ->
        socket.soTimeout = 5_000
        val requestLine = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII)).readLine()
        if (!acceptsDiagnosticRequest(requestLine, token)) {
            writeTextResponse(socket, "403 Forbidden", "诊断令牌或路径无效")
            return@use false
        }
        return@use runCatching {
            val snapshot = repository.createAdbDiagnosticSnapshot()
            try {
                val header = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: application/vnd.sqlite3\r\n")
                    append("Content-Disposition: attachment; filename=assets-king-diagnostic.db\r\n")
                    append("Content-Length: ${snapshot.length()}\r\n")
                    append("Cache-Control: no-store\r\n")
                    append("Connection: close\r\n\r\n")
                }.toByteArray(Charsets.US_ASCII)
                socket.getOutputStream().use { output ->
                    output.write(header)
                    snapshot.inputStream().use { it.copyTo(output) }
                    output.flush()
                }
            } finally {
                snapshot.delete()
            }
            true
        }.getOrElse { error ->
            writeTextResponse(socket, "500 Internal Server Error", error.message ?: "数据库快照生成失败")
            false
        }
    }

    private fun writeTextResponse(socket: Socket, status: String, message: String) {
        val body = message.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        socket.getOutputStream().use { output ->
            output.write(header)
            output.write(body)
            output.flush()
        }
    }

    private suspend fun countDown(current: Session) {
        repeat(SESSION_SECONDS) {
            delay(1_000)
            if (!isCurrent(current)) return
            val remaining = SESSION_SECONDS - it - 1
            _state.value = _state.value.copy(remainingSeconds = remaining)
        }
        closeCurrent(current)
    }

    private fun isCurrent(expected: Session): Boolean = synchronized(lock) { session?.id == expected.id }

    private fun closeCurrent(expected: Session) {
        val closing = synchronized(lock) {
            if (session?.id != expected.id) return
            session = null
            expected
        }
        closing.socket.close()
        _state.value = AdbDiagnosticState()
    }

    private companion object {
        const val SESSION_SECONDS = 10 * 60
    }
}
