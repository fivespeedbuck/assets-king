package com.assetsking.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.ServiceCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.assetsking.app.AssetsKingApplication
import com.assetsking.app.GUARDIAN_SENTENCE
import com.assetsking.app.MainActivity
import com.assetsking.app.R
import com.assetsking.database.RawNotificationEntity
import com.assetsking.usecase.NotificationParser
import com.assetsking.usecase.WechatNotificationEvidence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 通知读取服务。
 *
 * 保活策略：App 在前台打开时（MainActivity.onResume）提升为前台服务，降低普通后台
 * 回收概率。OriginOS 的最近任务 single-cleaner 仍会无回调地杀掉前台服务，因此 iQOO
 * 还需要在最近任务中锁定本应用；应用侧负责重开后的强制重绑和短信补扫，不能把 OEM
 * 强杀伪装成应用内可自行恢复。
 * 原先在 onListenerConnected 里直接 startForeground 会抛
 * ForegroundServiceStartNotAllowedException（后台不允许起前台服务），
 * 异常连带把 onListenerConnected 打挂 —— 现在改为从前台 Activity 起，异常只吞不崩。
 */
class AssetsNotificationListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recoveryJob: Job? = null
    @Volatile private var connectionGeneration = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 后台起 FGS 会抛异常（Android 12+），吞掉即可：前台起的那次已经保住进程
        runCatching { startKeepAliveNotification() }
        return START_STICKY
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        KEEP_ALIVE_NOTIF_ID,
        Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun startKeepAliveNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_KEEP_ALIVE, "后台运行", NotificationManager.IMPORTANCE_LOW).apply {
                description = "静默保持自动记账监听"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        // Android 渠道的声音配置创建后不可由 App 可靠改写；换新渠道完成静默迁移，
        // 再移除曾继承系统提示音的旧渠道，避免设置页残留两个“后台运行”。
        nm.deleteNotificationChannel(LEGACY_CHANNEL_KEEP_ALIVE)
        val notif = Notification.Builder(this, CHANNEL_KEEP_ALIVE)
            .setContentTitle("资产大王")
            .setContentText(GUARDIAN_SENTENCE)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, KEEP_ALIVE_NOTIF_ID, notif, type)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connectionGeneration += 1
        val generation = connectionGeneration
        recoveryJob?.cancel()
        isConnected = true
        val app = application as? AssetsKingApplication
        if (app == null) {
            runtimeStatus = VaultRuntimeStatus.ERROR
            return
        }
        val connectionStartedAt = System.currentTimeMillis()
        // Keep the previous completed connection as the rescan boundary. It is
        // advanced only after the recovery window has been processed successfully.
        val lastHealthyAt = app.repository.lastListenerHealthyAtValue()
        recoveryJob = serviceScope.launch {
            runtimeStatus = VaultRuntimeStatus.RECOVERING
            // 当前通知栏证据必须先真正落库并解析完成，才能把本次恢复判为成功。
            val activeScanComplete = runCatching {
                var allCompleted = true
                activeNotifications.orEmpty()
                    .mapNotNull(::rawNotificationCandidate)
                    .forEach { notification ->
                        if (!ingestNotification(app, notification)) allCompleted = false
                    }
                allCompleted
            }.getOrDefault(false)

            if (!hasSmsRecoveryPermissions(this@AssetsNotificationListenerService)) {
                // 缺完整短信权限时仍处理当前通知栏，但不能推进断线补收边界。
                resolveVaultRecoveryStatus(
                    generation,
                    connectionGeneration,
                    connected = isConnected,
                    completed = activeScanComplete
                )?.let { runtimeStatus = it }
                return@launch
            }

            // 短信补扫：掉线期间已从通知栏消失的银行短信，从收件箱补回（需 READ_SMS）。
            val recoveryComplete = activeScanComplete && runCatching {
                val scan = SmsRescan.rescan(this@AssetsNotificationListenerService, app.repository, lastHealthyAt)
                if (!scan.completed) return@runCatching false
                app.processPending.invoke()
                // 重连补扫产生的待确认：防抖 Job 已随旧进程死亡，直接补评估发通知
                PendingNotifier.ensureNotified(this@AssetsNotificationListenerService)
                true
            }.getOrDefault(false)

            // 旧连接的协程不得覆盖新连接状态，也不得在已经断开后推进健康边界。
            resolveVaultRecoveryStatus(
                generation,
                connectionGeneration,
                connected = isConnected,
                completed = recoveryComplete
            )?.let { status ->
                if (status == VaultRuntimeStatus.IDLE) {
                    app.repository.markListenerHealthy(connectionStartedAt)
                }
                runtimeStatus = status
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connectionGeneration += 1
        recoveryJob?.cancel()
        recoveryJob = null
        isConnected = false
        runtimeStatus = VaultRuntimeStatus.IDLE
        // 系统在 app 更新/重启后会断开绑定且不主动重连，这里主动要一次
        scheduleRebind(applicationContext, delayMillis = REBIND_SETTLE_MS)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = application as? AssetsKingApplication ?: return
        val notification = rawNotificationCandidate(sbn) ?: return
        val statusRevision = captureRuntimeStatusRevision()
        serviceScope.launch {
            if (!ingestNotification(app, notification)) {
                // 服务不能崩，但失败必须进入用户可见状态，不能继续显示“金库正常”。
                reportIngestionFailure(statusRevision)
            }
        }
    }

    private fun rawNotificationCandidate(sbn: StatusBarNotification): RawNotificationEntity? {
        // 自己的通知、常驻通知（音乐/下载）、分组摘要（内容重复）一律不看
        if (sbn.packageName == packageName) return null
        val flags = sbn.notification.flags
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return null
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return null

        val app = application as? AssetsKingApplication ?: return null
        val repository = app.repository

        val appLabel = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrNull()

        // 先登记来源（不放行的也记）——设置页据此列出开关，漏配的银行一键就能打开
        repository.recordNotificationSource(sbn.packageName, appLabel)
        if (!repository.isWhitelisted(sbn.packageName)) return null
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val content = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        ).distinct().joinToString("\n").ifBlank { sbn.notification.tickerText?.toString().orEmpty() }
        if (content.isBlank() && title.isNullOrBlank()) return null

        // 白名单挡不住微信——它必须放行（要收支付通知），但聊天消息远多于付款。
        // 聊天消息不进流水：真机取证——微信支付推 VPushChannel_1，聊天走
        // message_channel_new_id 且 category=msg。聊天内容带"75元一斤"会被当成
        // 金额冲进待确认箱（用户实报），按通道拦掉，不能靠文本判断。
        // category=msg 只能拦聊天 App：vivo 短信 App 发银行动账短信也是 MessagingStyle
        // + category=msg（真机取证 08-14：招行麻辣烫 24.98 元被此过滤漏探），不能一刀切。
        val parsedAmountCents = NotificationParser.parse(content, title).amountCents
        if (!shouldKeepMessageLikeNotification(
                packageName = sbn.packageName,
                channelId = sbn.notification.channelId,
                category = sbn.notification.category,
                title = title,
                content = content,
                parsedAmountCents = parsedAmountCents
            )
        ) return null

        // 普通无金额消息仍丢弃；微信官方退款/提现可能只给“到账”而不带金额，
        // 必须保留证据，后续唯一匹配或让用户补齐，不能静默漏单。
        if (parsedAmountCents == null && !WechatNotificationEvidence.shouldKeepAmountless(
                WechatNotificationEvidence.Raw(
                    id = "${sbn.key}:${sbn.postTime}",
                    packageName = sbn.packageName,
                    title = title,
                    content = content,
                    postedAt = sbn.postTime
                )
            )
        ) return null

        return RawNotificationEntity(
            id = "${sbn.key}:${sbn.postTime}",
            packageName = sbn.packageName,
            sourceLabel = appLabel,
            title = title,
            content = content,
            postedAt = sbn.postTime,
            receivedAt = System.currentTimeMillis()
        )
    }

    private suspend fun ingestNotification(
        app: AssetsKingApplication,
        notification: RawNotificationEntity
    ): Boolean {
        val saved = runCatching { app.repository.saveRawNotification(notification) }.isSuccess
        if (!saved) return false

        // 即使解析失败，已落库的 NEW 证据也必须进入提醒链，不能只在用户打开主页后才可见。
        PendingNotifier.scheduleDebounced(this@AssetsNotificationListenerService, serviceScope)
        return runCatching { app.processPending.invoke() }.isSuccess
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 部分系统先回调再撤前台通知；延迟重发，避免服务仍在但常驻提示消失。
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { startKeepAliveNotification() }
        }, TASK_REMOVED_REPOST_MS)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        connectionGeneration += 1
        recoveryJob?.cancel()
        recoveryJob = null
        isConnected = false
        runtimeStatus = VaultRuntimeStatus.IDLE
        serviceScope.cancel()
        // 正常销毁也不能只把 UI 置为断开后就放任漏收；与主动恢复共用同一可合并重绑入口。
        scheduleRebind(applicationContext, delayMillis = REBIND_SETTLE_MS)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_KEEP_ALIVE = "assets_king_foreground_silent_v2"
        private const val LEGACY_CHANNEL_KEEP_ALIVE = "assets_king_foreground"
        private const val KEEP_ALIVE_NOTIF_ID = 1
        private const val REBIND_SETTLE_MS = 300L
        private const val TASK_REMOVED_REPOST_MS = 750L
        private val rebindHandler = Handler(Looper.getMainLooper())
        private val rebindRequestGeneration = AtomicLong(0L)

        // StateFlow 而非普通 Boolean：绑定是异步完成的，UI 得能在完成那一刻自己更新，
        // 否则刚点完「重新绑定」还会继续显示「已断开」直到下次进前台
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected

        /** 监听服务内部运行态；补收或实时落库失败必须对首页可见。 */
        private val _runtimeStatus = MutableStateFlow(VaultRuntimeStatus.IDLE)
        val runtimeStatusFlow: StateFlow<VaultRuntimeStatus> = _runtimeStatus
        private val runtimeStatusLock = Any()
        @Volatile private var runtimeStatusRevision = 0L

        var runtimeStatus: VaultRuntimeStatus
            get() = _runtimeStatus.value
            private set(value) {
                synchronized(runtimeStatusLock) {
                    runtimeStatusRevision += 1
                    _runtimeStatus.value = value
                }
            }

        var isConnected: Boolean
            get() = _connected.value
            private set(value) { _connected.value = value }

        fun componentName(context: Context) =
            ComponentName(context, AssetsNotificationListenerService::class.java)

        /**
         * 已授权但未连接时把绑定要回来。
         * 装了新 APK 后系统保留授权却不重新 bind，服务会静默收不到任何东西 ——
         * 以前只能靠「设置里关掉再打开」恢复，这行代替了那个手工步骤。
         */
        fun rebindIfNeeded(context: Context) {
            if (isConnected) return
            scheduleRebind(context)
        }

        /** 用户主动恢复：即使系统仍声称已连接，也重新建立一次绑定并触发补收。 */
        fun recoverNow(context: Context) {
            val component = componentName(context)
            if (Build.VERSION.SDK_INT < 34) {
                scheduleRebind(context)
                return
            }
            runCatching { requestUnbind(component) }
            scheduleRebind(context, delayMillis = REBIND_SETTLE_MS)
        }

        /**
         * 断开回调、销毁、前台恢复可能在同一瞬间同时要求重绑。只保留最后一次请求，
         * 避免 requestUnbind 尚未落稳就被较早的 requestRebind 抢回去。
         */
        fun scheduleRebind(context: Context, delayMillis: Long = 0L) {
            val appContext = context.applicationContext
            val generation = rebindRequestGeneration.incrementAndGet()
            rebindHandler.postDelayed({
                val listenerStillEnabled = NotificationManagerCompat
                    .getEnabledListenerPackages(appContext)
                    .contains(appContext.packageName)
                if (generation == rebindRequestGeneration.get() && listenerStillEnabled) {
                    runCatching { requestRebind(componentName(appContext)) }
                }
            }, delayMillis.coerceAtLeast(0L))
        }

        fun captureRuntimeStatusRevision(): Long = synchronized(runtimeStatusLock) {
            runtimeStatusRevision
        }

        /** 旧任务的迟到失败不能覆盖后来已经恢复健康的新状态。 */
        fun reportIngestionFailure(startRevision: Long) {
            synchronized(runtimeStatusLock) {
                resolveIngestionFailureStatus(startRevision, runtimeStatusRevision)?.let { status ->
                    runtimeStatusRevision += 1
                    _runtimeStatus.value = status
                }
            }
        }

        /** App 在前台时提升为前台服务，降低普通后台回收概率。 */
        fun startKeepAlive(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, AssetsNotificationListenerService::class.java)
                )
            }
        }
    }
}

enum class VaultRuntimeStatus { IDLE, RECOVERING, ERROR }

internal fun resolveVaultRecoveryStatus(
    generation: Long,
    currentGeneration: Long,
    connected: Boolean,
    completed: Boolean
): VaultRuntimeStatus? = when {
    generation != currentGeneration || !connected -> null
    completed -> VaultRuntimeStatus.IDLE
    else -> VaultRuntimeStatus.ERROR
}

internal fun resolveIngestionFailureStatus(
    startRevision: Long,
    currentRevision: Long
): VaultRuntimeStatus? = if (startRevision == currentRevision) VaultRuntimeStatus.ERROR else null
