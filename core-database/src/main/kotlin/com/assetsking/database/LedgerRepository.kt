package com.assetsking.database

import android.content.SharedPreferences
import androidx.room.withTransaction
import com.assetsking.ledger.BalanceCheckpoint
import com.assetsking.ledger.BalanceMath
import com.assetsking.ledger.ContentFingerprint
import com.assetsking.ledger.DefaultCategories
import com.assetsking.ledger.InstallmentMatcher
import com.assetsking.ledger.LedgerDelta
import com.assetsking.ledger.ReimbursementSplit
import com.assetsking.ledger.RuleBasedCategorizer
import com.assetsking.ledger.SmsSenderWhitelist
import com.assetsking.model.AccountType
import com.assetsking.model.InstallmentStatus
import com.assetsking.model.LoanInstallment
import com.assetsking.model.Money
import com.assetsking.model.TransactionCategory
import com.assetsking.model.TransactionType
import com.assetsking.model.WindfallStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

data class LearnedRule(
    val accountId: String,
    val type: String,
    val category: String
)

private class NotificationAlreadyHandledException : RuntimeException()

data class TransactionDeletionAccountPreview(
    val accountId: String,
    val currentBalanceCents: Long,
    val projectedBalanceCents: Long,
    val balanceStatus: String,
    val anchoredBeforeCheckpointCount: Int = 0
)

data class TransferDeletionAccountPreview(
    val accountId: String,
    val currentBalanceCents: Long,
    val projectedBalanceCents: Long,
    val balanceStatus: String
)

data class TransferBalanceDetail(
    val transfer: TransferEntity,
    val fromBeforeCents: Long?,
    val fromAfterCents: Long?,
    val toBeforeCents: Long?,
    val toAfterCents: Long?
)

private const val TRANSACTION_TRASH_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
private const val CATEGORY_SEED_VERSION_KEY = "category_seed_version"
private const val CATEGORY_SEED_VERSION_EXTERNAL_CASHFLOW = 2

private val notificationCardTailPatterns = listOf(
    Regex("""尾号[为是]?\s*(\d{4})"""),
    Regex("""账户(\d{4})""")
)

private fun RawNotificationEntity.cardTail(): String? {
    val text = listOfNotNull(title, content).joinToString(" ")
    return notificationCardTailPatterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }
}

private fun coveredLoanInstallmentNumbers(
    installments: List<LoanInstallment>,
    principalCents: Long,
    interestCents: Long,
    feeCents: Long,
    eligible: (LoanInstallment) -> Boolean
): Set<Int> {
    var principalCovered = 0L
    var interestCovered = 0L
    var feeCovered = 0L
    val result = linkedSetOf<Int>()
    installments.forEach { installment ->
        if (!eligible(installment)) return@forEach
        val nextPrincipal = principalCovered + installment.principal.cents
        val nextInterest = interestCovered + installment.interest.cents
        val nextFee = feeCovered + installment.fee.cents
        if (nextPrincipal <= principalCents && nextInterest <= interestCents && nextFee <= feeCents) {
            principalCovered = nextPrincipal
            interestCovered = nextInterest
            feeCovered = nextFee
            result += installment.number
        }
    }
    return result
}

class LedgerRepository(
    private val context: android.content.Context,
    private val database: AssetsKingDatabase,
    private val prefs: SharedPreferences
) {
    private val evidenceRecorder = LedgerEvidenceRecorder(database)
    private val evidenceAuditService = LedgerEvidenceAuditService(database)
    val accounts: Flow<List<AccountEntity>> = database.accountDao().observeAll()
    val transactions: Flow<List<TransactionEntity>> = database.transactionDao().observeAll()
    val deletedTransactions: Flow<List<TransactionEntity>> = database.transactionDao().observeDeleted()
    val transfers: Flow<List<TransferEntity>> = database.transferDao().observeAll()
    val deletedTransfers: Flow<List<TransferEntity>> = database.transferDao().observeDeleted()
    val unprocessedNotifications: Flow<Int> = database.rawNotificationDao().observeUnprocessedCount()
    val pendingNotifications: Flow<List<RawNotificationEntity>> =
        database.rawNotificationDao().observeByStatus("PENDING_CONFIRMATION")
    val linkedNotifications: Flow<List<RawNotificationEntity>> =
        database.rawNotificationDao().observeByStatus("LINKED")
    val ignoredNotifications: Flow<List<RawNotificationEntity>> =
        database.rawNotificationDao().observeByStatus("IGNORED")

    suspend fun runEvidenceAudit(): EvidenceAuditReport = evidenceAuditService.run()
    private val _lastReceivedAt = MutableStateFlow(prefs.getLong("last_notification_received_at", 0L))
    val lastReceivedAt: Flow<Long> = _lastReceivedAt
    private val _lastListenerHealthyAt = MutableStateFlow(prefs.getLong("last_listener_healthy_at", 0L))
    val lastListenerHealthyAt: Flow<Long> = _lastListenerHealthyAt
    private val _smsSenderWhitelist = MutableStateFlow(
        prefs.getString("sms_sender_whitelist", null)
            ?.split(",")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            ?: SmsSenderWhitelist.defaults
    )
    val smsSenderWhitelist: Flow<Set<String>> = _smsSenderWhitelist
    private val categorizer = RuleBasedCategorizer()
    private val cardInstallmentService = CreditCardInstallmentService(database)

    suspend fun seedKnownAccounts() {
        val seeds = listOf(
            AccountEntity("cmb", "招商银行", AccountType.ASSET.name, 0),
            AccountEntity("nbcb", "宁波银行", AccountType.ASSET.name, 0),
            AccountEntity("cgb", "广发信用卡", AccountType.CREDIT.name, 0),
            AccountEntity("huabei", "花呗", AccountType.CREDIT.name, 0)
        )
        database.accountDao().insertAll(seeds)
        // 为尚无检查点的账户补 opening 检查点（审核 BUG-1 修复）：种子账户用 insertAll 直插、
        // 不建检查点会导致 recomputeBalance 因 latestFor()==null 直接 return，余额永不更新。
        // 仅在该账户没有任何检查点时补（latestFor 为 null），不覆盖已有银行/手动检查点。
        seeds.forEach { acc ->
            if (database.balanceCheckpointDao().latestFor(acc.id) == null) {
                database.balanceCheckpointDao().upsert(
                    BalanceCheckpointEntity(
                        id = "opening_${acc.id}",
                        accountId = acc.id,
                        balanceCents = acc.balanceCents,
                        checkedAt = Long.MIN_VALUE,
                        source = "OPENING"
                    )
                )
            }
        }
    }

    fun categorize(merchant: String?, note: String? = null): TransactionCategory =
        categorizer.categorize(merchant, note)

    suspend fun saveRawNotification(
        notification: RawNotificationEntity,
        updateLastReceived: Boolean = true
    ): Boolean {
        val fingerprinted = if (notification.contentFingerprint.isBlank()) {
            notification.copy(
                contentFingerprint = ContentFingerprint.of(notification.title, notification.content)
            )
        } else {
            notification
        }
        // SMS_RECEIVED 与短信箱补扫过去使用两个 id 前缀，主键无法拦截同一条短信。
        // 在唯一持久化入口统一改写为稳定证据标签，调用方以后再增加入口也绕不过此门禁。
        val identified = if (fingerprinted.packageName == "sms") {
            fingerprinted.copy(
                id = ContentFingerprint.stableSmsEvidenceId(
                    fingerprinted.title,
                    fingerprinted.content,
                    fingerprinted.postedAt
                )
            )
        } else {
            fingerprinted
        }
        val insertedRowId = database.rawNotificationDao().insert(identified)
        // 金库「最近入库时间」：实时证据落库才刷新；补扫旧短信不刷新（避免被历史补回污染）
        if (updateLastReceived && insertedRowId != -1L) {
            _lastReceivedAt.value = notification.receivedAt
            prefs.edit().putLong("last_notification_received_at", notification.receivedAt).apply()
        }
        return insertedRowId != -1L
    }

    /** Last completed listener recovery window; persisted so a process restart does not widen the gap. */
    fun lastListenerHealthyAtValue(): Long = _lastListenerHealthyAt.value

    fun markListenerHealthy(at: Long = System.currentTimeMillis()) {
        _lastListenerHealthyAt.value = at
        prefs.edit().putLong("last_listener_healthy_at", at).apply()
    }

    // ── 待确认通知防丢：最后通知过几笔（prefs），心跳/开机/重连时据此补发 ──
    fun lastNotifiedPendingCount(): Int = prefs.getInt("pending_notified_count", 0)

    fun markPendingNotified(count: Int) {
        prefs.edit().putInt("pending_notified_count", count).apply()
    }

    // ── 主题选择（REQ 主题§12：新装默认浅绿，升级保留当前选择）──
    private val _themeKey = MutableStateFlow(prefs.getString("theme_key", null))
    val themeKey: Flow<String?> = _themeKey

    fun setThemeKey(key: String) {
        _themeKey.value = key
        prefs.edit().putString("theme_key", key).apply()
    }

    private val _customPaymentChannels = MutableStateFlow(
        prefs.getStringSet("custom_payment_channels", emptySet()).orEmpty().toSortedSet()
    )
    val customPaymentChannels: Flow<Set<String>> = _customPaymentChannels

    fun rememberPaymentChannel(channel: String) {
        val normalized = channel.trim().takeIf { it.isNotEmpty() } ?: return
        val updated = (_customPaymentChannels.value + normalized).toSortedSet()
        if (updated == _customPaymentChannels.value) return
        _customPaymentChannels.value = updated
        prefs.edit().putStringSet("custom_payment_channels", updated).apply()
    }

    // ── 自由开销额度（REQ 统计§12：初始 500 元/月，设置页可改）──
    private val _freeSpendingCents = MutableStateFlow(prefs.getLong("free_spending_cents", 50_000L))
    val freeSpendingCents: Flow<Long> = _freeSpendingCents

    fun setFreeSpendingCents(cents: Long) {
        _freeSpendingCents.value = cents
        prefs.edit().putLong("free_spending_cents", cents).apply()
    }

    // ── 备份 / 恢复（REQ 数据备份与恢复 §1-9）──

    fun backupPin(): String = prefs.getString("backup_pin", "").orEmpty()

    fun setBackupPin(pin: String) {
        prefs.edit().putString("backup_pin", pin).apply()
    }

    /** SAF 备份目录（REQ 备份§2）：null = 应用私有目录 */
    fun backupDirUri(): android.net.Uri? =
        prefs.getString("backup_dir_uri", null)?.let { android.net.Uri.parse(it) }

    fun setBackupDirUri(uri: android.net.Uri?) {
        if (uri == null) prefs.edit().remove("backup_dir_uri").apply()
        else prefs.edit().putString("backup_dir_uri", uri.toString()).apply()
    }

    /** 立即备份（手动或自动）。密码未设返回 false。自动备份每日最多一次，保留 7 份日备份+3 份月备份；手动永不删除。 */
    suspend fun backupNow(manual: Boolean): Boolean = withContext(Dispatchers.IO) {
        val pin = backupPin()
        if (pin.length != 6) return@withContext false
        // REQ 备份§14：自动备份每日最多一次（今日已备份则跳过，不重复产生文件）
        if (!manual && prefs.getLong("last_auto_backup_epoch_day", -1L) == java.time.LocalDate.now().toEpochDay()) {
            return@withContext true
        }
        runCatching {
            val dbFile = context.getDatabasePath("assets-king.db")
            require(dbFile.isFile) { "数据库文件不存在" }
            // 恢复只使用主库文件，因此 WAL checkpoint 失败时绝不能继续生成一个看似成功、
            // 实际缺少最近流水的备份。
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
                require(cursor.moveToFirst()) { "WAL checkpoint 未返回结果" }
                // SQLite 返回三列：busy / log frames / checkpointed frames。busy=0 才表示快照完整。
                require(cursor.getInt(0) == 0) { "数据库正忙，WAL checkpoint 未完成" }
            }
            val stamp = System.currentTimeMillis()
            val prefix = if (manual) "manual" else "auto"
            val kind = if (manual) "manual" else "auto"
            val localDir = java.io.File(context.filesDir, "backups/$kind")

            // 用户选过 SAF 目录后，写入失败必须返回 false；静默回退私有目录会让人误以为
            // 外部备份已经存在，真正恢复时却找不到文件。
            val safSub: androidx.documentfile.provider.DocumentFile? = backupDirUri()?.let { root ->
                val tree = requireNotNull(androidx.documentfile.provider.DocumentFile.fromTreeUri(context, root))
                val base = tree.findFile("assets-king-backups") ?: tree.createDirectory("assets-king-backups")
                requireNotNull(base?.findFile(kind) ?: base?.createDirectory(kind))
            }

            fun enc(name: String, data: ByteArray) {
                if (safSub != null) {
                    safSub.findFile(name)?.delete()
                    val f = requireNotNull(safSub.createFile("application/octet-stream", name))
                    requireNotNull(context.contentResolver.openOutputStream(f.uri)).use { it.write(data) }
                } else {
                    localDir.mkdirs()
                    java.io.File(localDir, name).writeBytes(data)
                }
            }
            val prefsJson = JSONObject().apply {
                prefs.all.forEach { (k, v) -> put(k, v) }
            }.toString()
            val bundle = com.assetsking.ledger.BackupBundle.pack(
                database = dbFile.readBytes(),
                preferencesJson = prefsJson.toByteArray(Charsets.UTF_8)
            )
            enc("${prefix}_${stamp}.akbackup", com.assetsking.ledger.PinCipher.encrypt(bundle, pin))
            if (!manual) {
                pruneAutoBackups(safSub, localDir)
                prefs.edit().putLong("last_auto_backup_epoch_day", java.time.LocalDate.now().toEpochDay()).apply()
            }
            true
        }.getOrDefault(false)
    }

    private fun pruneAutoBackups(safSub: androidx.documentfile.provider.DocumentFile?, localDir: java.io.File) {
        // 审核 §14 口径（用户拍板）：保留最近 7 份日备份 + 最近 3 份月备份；手动备份永不自动删除。
        // 日备份：每天只留最新一份，取最近 7 天；月备份：每月只留最新一份，取最近 3 个月；其余自动备份删除。
        fun backupStamp(name: String): String? = when {
            name.startsWith("auto_") && name.endsWith(".akbackup") ->
                name.removePrefix("auto_").removeSuffix(".akbackup")
            name.startsWith("auto_") && name.endsWith(".db.enc") ->
                name.removePrefix("auto_").removeSuffix(".db.enc")
            else -> null
        }
        val stamps: List<String> = if (safSub != null) {
            safSub.listFiles()
                .mapNotNull { f -> f.name?.let(::backupStamp) }
                .distinct()
                .sortedByDescending(String::toLongOrNull)
        } else {
            localDir.listFiles()
                ?.mapNotNull { backupStamp(it.name) }
                ?.distinct()
                ?.sortedByDescending(String::toLongOrNull)
                ?: emptyList()
        }
        val zone = java.time.ZoneId.systemDefault()
        // (stamp, LocalDate)，stamps 已按时间倒序，故每组第一个即该组最新一份
        val parsed = stamps.mapNotNull { s ->
            runCatching { java.time.Instant.ofEpochMilli(s.toLong()).atZone(zone).toLocalDate() }.getOrNull()?.let { s to it }
        }
        val dailyKeep = parsed.groupBy { it.second }
            .entries.sortedByDescending { it.key }
            .take(7)
            .mapNotNull { it.value.firstOrNull()?.first }
            .toSet()
        val monthlyKeep = parsed.groupBy { java.time.YearMonth.from(it.second) }
            .entries.sortedByDescending { it.key }
            .take(3)
            .mapNotNull { it.value.firstOrNull()?.first }
            .toSet()
        val keep = dailyKeep + monthlyKeep
        stamps.filter { it !in keep }.forEach { stale ->
            listOf(".akbackup", ".db.enc", ".wal.enc", ".shm.enc", ".prefs.enc").forEach { ext ->
                if (safSub != null) safSub.findFile("auto_$stale$ext")?.delete()
                else java.io.File(localDir, "auto_$stale$ext").delete()
            }
        }
    }

    // ── 旧版 → 重构版迁移门禁（REQ 旧功能清理 §4-8）──

    enum class MigrationStatus { DONE, NEED_PIN, PENDING_NOT_EMPTY, READY }

    fun migrationDone(): Boolean = prefs.getBoolean("refactor_migration_done", false)

    /** 有没有旧版流水数据：没有就视为新装，直接免门禁（REQ §6 只针对已积累的旧流水） */
    private suspend fun hasOldFlowData(): Boolean =
        database.transactionDao().countAll() > 0 ||
            database.transferDao().countAll() > 0 ||
            database.rawNotificationDao().countAll() > 0

    suspend fun migrationStatus(): MigrationStatus = withContext(Dispatchers.IO) {
        when {
            migrationDone() -> MigrationStatus.DONE
            !hasOldFlowData() -> {
                prefs.edit().putBoolean("refactor_migration_done", true).apply()
                MigrationStatus.DONE
            }
            backupPin().length != 6 -> MigrationStatus.NEED_PIN
            database.rawNotificationDao().countPendingConfirmation() > 0 -> MigrationStatus.PENDING_NOT_EMPTY
            else -> MigrationStatus.READY
        }
    }

    /** 门禁之一（REQ §5）：旧待确认箱必须为空。这里提供一次性永久清空旧候选与证据。 */
    suspend fun clearOldPendingBox() {
        database.rawNotificationDao().deleteAll()
    }

    /**
     * 执行迁移（REQ §4/§6-8）：
     * 先自动加密备份（失败即中止=数据不动，阻止使用）；再清旧流水/转账/证据/报销关联，
     * 并把各账户当前余额重锚为 OPENING 检查点（BalanceMath 口径一致，REQ §7 账户状态准确）；
     * 账户/贷款/预算/周期账单保留（REQ §2）。成功后进首页，无核对页（REQ §8）。
     */
    suspend fun runMigration(): Boolean = withContext(Dispatchers.IO) {
        if (migrationDone()) return@withContext true
        if (backupPin().length != 6) return@withContext false
        // 备份必须在事务外：wal_checkpoint 不能跑在活动事务里
        // 迁移会永久清空旧流水，不能复用“今日已自动备份”的旧快照；强制生成独立、永久保留的当前快照。
        if (!backupNow(manual = true)) return@withContext false
        val accounts = database.accountDao().all()
        database.withTransaction {
            database.transactionDao().deleteAll()
            database.transferDao().deleteAll()
            database.reimbursementLinkDao().deleteAll()
            database.rawNotificationDao().deleteAll()
            database.balanceCheckpointDao().deleteAll()
            accounts.forEach { a ->
                database.balanceCheckpointDao().upsert(
                    BalanceCheckpointEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        accountId = a.id,
                        balanceCents = a.balanceCents,
                        checkedAt = Long.MIN_VALUE,
                        source = "OPENING"
                    )
                )
            }
        }
        prefs.edit().putBoolean("refactor_migration_done", true).apply()
        true
    }

    /** 恢复：先自动备份当前数据（REQ §5），再整体替换，完成后重启进程生效。 */
    suspend fun restoreFromPicked(uri: android.net.Uri, pin: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val stamp = uri.lastPathSegment?.substringAfter("_")?.substringBefore(".") ?: "restore"
                val dir = java.io.File(context.filesDir, "backups/manual")
                val content = if (uri.scheme == android.content.ContentResolver.SCHEME_FILE) {
                    val path = uri.path ?: return@runCatching false
                    java.io.File(path).takeIf { it.isFile }?.readBytes() ?: return@runCatching false
                } else {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@runCatching false
                }
                val decrypted = com.assetsking.ledger.PinCipher.decrypt(content, pin)
                val bundle = com.assetsking.ledger.BackupBundle.unpack(decrypted)
                val dbBytes = bundle?.database ?: decrypted
                // 兼容旧 XOR 备份时，错误 PIN 只会产出乱码；覆盖前必须先验证 SQLite 文件头。
                val sqliteHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
                if (dbBytes.size < sqliteHeader.size || !dbBytes.copyOfRange(0, sqliteHeader.size).contentEquals(sqliteHeader)) {
                    return@runCatching false
                }
                if (!isHealthySqlite(dbBytes) || !isAssetsKingDatabase(dbBytes)) return@runCatching false
                val restoredPreferences = bundle?.preferencesJson?.let {
                    JSONObject(String(it, Charsets.UTF_8))
                } ?: java.io.File(dir, "manual_$stamp.prefs.enc")
                    .takeIf(java.io.File::exists)
                    ?.let { prefsFile ->
                        JSONObject(
                            String(
                                com.assetsking.ledger.PinCipher.decrypt(prefsFile.readBytes(), pin),
                                Charsets.UTF_8
                            )
                        )
                    }
                // 恢复是破坏性操作：当前数据备份失败时绝不继续覆盖。
                if (!backupNow(manual = true)) return@runCatching false
                // 审核 BUG-2 修复：恢复前关闭数据库连接并删除 -wal/-shm。
                // 否则当前进程的 WAL 里未 checkpoint 的数据会与覆盖后的主库混用，
                // 导致数据损坏或「恢复到旧数据」。关闭后本实例不可再用，调用方需重启进程。
                val dbFile = context.getDatabasePath("assets-king.db")
                runCatching { database.openHelper.close() }
                java.io.File(dbFile.path + "-wal").delete()
                java.io.File(dbFile.path + "-shm").delete()
                dbFile.writeBytes(dbBytes)
                restoredPreferences?.let { obj ->
                    prefs.edit().clear().commit()
                    val editor = prefs.edit()
                    obj.keys().forEach { k ->
                        when (val v = obj.get(k)) {
                            is String -> editor.putString(k, v)
                            is Boolean -> editor.putBoolean(k, v)
                            is Int -> editor.putInt(k, v)
                            is Long -> editor.putLong(k, v)
                            is Double -> editor.putLong(k, v.toLong())
                            is org.json.JSONArray -> editor.putStringSet(
                                k,
                                (0 until v.length()).mapNotNull { index ->
                                    v.optString(index).takeIf(String::isNotBlank)
                                }.toSet()
                            )
                        }
                    }
                    require(editor.commit()) { "应用偏好恢复失败" }
                }
                true
            }.getOrDefault(false)
        }

    /** 在覆盖当前账本前，对解密结果做 SQLite 自身的结构完整性检查。 */
    private fun isHealthySqlite(bytes: ByteArray): Boolean {
        val candidate = java.io.File.createTempFile("assets-king-restore-", ".db", context.cacheDir)
        return try {
            candidate.writeBytes(bytes)
            val opened = android.database.sqlite.SQLiteDatabase.openDatabase(
                candidate.path,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            try {
                opened.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
                    cursor.moveToFirst() && cursor.getString(0) == "ok"
                }
            } finally {
                opened.close()
            }
        } finally {
            candidate.delete()
            java.io.File(candidate.path + "-wal").delete()
            java.io.File(candidate.path + "-shm").delete()
        }
    }

    // ── 首页可配置模块（REQ 首页可配置模块 §1-10）：启用集合存 prefs，默认 本月预算+待报销+周期扣款 ──

    private val _enabledModules = MutableStateFlow(
        prefs.getStringSet("home_modules", defaultEnabledModules) ?: defaultEnabledModules
    )
    val enabledModules: Flow<Set<String>> = _enabledModules

    fun setHomeModules(enabled: Set<String>) {
        _enabledModules.value = enabled
        prefs.edit().putStringSet("home_modules", enabled).apply()
    }

    // 首页模块显示顺序（REQ 首页可配置模块§3 拖动排序）：逗号分隔字符串，只存启用过的顺序，其余回退默认
    private val _moduleOrder = MutableStateFlow(
        prefs.getString("home_module_order", null)?.split(",")?.filter { it.isNotBlank() }
            ?: defaultModuleOrder
    )
    val moduleOrder: Flow<List<String>> = _moduleOrder

    fun reorderHomeModules(ordered: List<String>) {
        _moduleOrder.value = ordered
        prefs.edit().putString("home_module_order", ordered.joinToString(",")).apply()
    }

    companion object {
        val defaultEnabledModules: Set<String> = setOf("reimbursement", "recurring", "budget", "accounts")
        val defaultModuleOrder: List<String> = listOf("reimbursement", "recurring", "budget")
    }

    fun observeNewNotifications(): Flow<List<RawNotificationEntity>> =
        database.rawNotificationDao().observeByStatus("NEW")

    suspend fun updateNotificationStatus(id: String, status: String) {
        database.rawNotificationDao().updateStatus(id, status)
    }

    /** 同额转出+转入两条通知合并确认「账户转账」（REQ 待确认交易类型§4）。 */
    suspend fun confirmTransferFromNotifications(
        outNotificationId: String,
        inNotificationId: String,
        fromAccountId: String,
        toAccountId: String,
        amountCents: Long,
        note: String?
    ) {
        require(outNotificationId != inNotificationId)
        require(amountCents > 0)
        try {
            database.withTransaction {
                // 两条证据必须在同一事务里全部认领成功。任一已被处理就整体回滚，
                // 防止双击/并发确认重复生成转账，或只把其中一条卡在 LINKING。
                if (database.rawNotificationDao().claimForConfirmation(outNotificationId) != 1 ||
                    database.rawNotificationDao().claimForConfirmation(inNotificationId) != 1
                ) {
                    throw NotificationAlreadyHandledException()
                }
                val postedAt = database.rawNotificationDao().findById(outNotificationId)?.postedAt
                    ?: System.currentTimeMillis()
                val sourceIds = listOf(outNotificationId, inNotificationId).sorted()
                addTransfer(
                    fromAccountId, toAccountId, amountCents, note, postedAt,
                    evidenceSources = sourceIds.map { EvidenceSourceRef(EvidenceSourceType.RAW_NOTIFICATION, it) },
                    evidenceGroupId = "notifications:${sourceIds.joinToString("|")}"
                )
                listOf(outNotificationId, inNotificationId).forEach { id ->
                    database.rawNotificationDao().updateProcessingNote(id, "merged-transfer")
                    database.rawNotificationDao().updateStatus(id, "IGNORED")
                }
            }
        } catch (_: NotificationAlreadyHandledException) {
            // 幂等无操作：先完成的那次确认已经是唯一有效结果。
        }
    }

    /**
     * 只收到转账一条腿时，用户补齐另一端账户后确认（REQ 待确认交易类型§5）。
     * 提现手续费与转账在同一事务落账：转账不算消费，手续费才是实际成本。
     */
    suspend fun confirmTransferFromNotification(
        notificationId: String,
        fromAccountId: String,
        toAccountId: String,
        amountCents: Long,
        feeCents: Long,
        note: String?
    ) {
        require(amountCents > 0)
        require(feeCents >= 0)
        require(fromAccountId != toAccountId)
        try {
            database.withTransaction {
                if (database.rawNotificationDao().claimForConfirmation(notificationId) != 1) {
                    throw NotificationAlreadyHandledException()
                }
                val postedAt = database.rawNotificationDao().findById(notificationId)?.postedAt
                    ?: System.currentTimeMillis()
                addTransfer(
                    fromAccountId, toAccountId, amountCents, note, postedAt,
                    evidenceSources = listOf(EvidenceSourceRef(EvidenceSourceType.RAW_NOTIFICATION, notificationId)),
                    evidenceGroupId = "notification:$notificationId"
                )
                if (feeCents > 0) {
                    addTransaction(
                        accountId = fromAccountId,
                        amountCents = feeCents,
                        type = TransactionType.FEE,
                        category = TransactionCategory.UNCATEGORIZED.name,
                        merchant = null,
                        note = note?.trim()?.takeIf { it.isNotEmpty() }
                            ?.let { "$it · 转账手续费" }
                            ?: "转账手续费",
                        occurredAt = postedAt,
                        notificationId = notificationId
                    )
                }
                database.rawNotificationDao().updateProcessingNote(
                    notificationId,
                    "single-transfer; feeCents=$feeCents"
                )
                database.rawNotificationDao().updateStatus(notificationId, "IGNORED")
            }
        } catch (_: NotificationAlreadyHandledException) {
            // 幂等无操作：先完成的确认已生成唯一一笔转账和至多一笔手续费。
        }
    }

    suspend fun updateNotificationNote(id: String, note: String) {
        database.rawNotificationDao().updateProcessingNote(id, note)
    }

    suspend fun splitNotification(id: String) {
        database.withTransaction {
            database.rawNotificationDao().updateProcessingNote(id, "")
            database.rawNotificationDao().updateStatus(id, "PENDING_CONFIRMATION")
        }
    }

    /**
     * 通知余额是按时间线重锚的权威检查点；同一张卡更早的待确认通知若尚未入账，
     * 先确认较新的余额会把旧事件重新放到检查点之前，造成后续对账漂移。
     */
    private suspend fun requireEarliestNotificationFirst(
        notification: RawNotificationEntity,
        accountId: String,
        bankBalanceCents: Long?,
        bankCardTail: String?
    ) {
        if (bankBalanceCents == null || bankBalanceCents < 0L || bankCardTail.isNullOrBlank()) return
        val account = database.accountDao().find(accountId) ?: return
        if (account.type != AccountType.ASSET.name || account.cardTail != bankCardTail) return
        val earlier = database.rawNotificationDao().pendingUpTo(notification.postedAt)
            .asSequence()
            .filter { it.id != notification.id && it.cardTail() == bankCardTail }
            .filter {
                it.postedAt < notification.postedAt ||
                    (it.postedAt == notification.postedAt &&
                        (it.receivedAt < notification.receivedAt ||
                            (it.receivedAt == notification.receivedAt && it.id < notification.id)))
            }
            .minWithOrNull(compareBy<RawNotificationEntity> { it.postedAt }.thenBy { it.receivedAt }.thenBy { it.id })
        require(earlier == null) {
            "同一资金账户还有更早的待确认通知，请先确认最早一条后再按通知余额入账"
        }
    }

    private suspend fun canUseAuthoritativeBalance(
        accountId: String,
        bankBalanceCents: Long?,
        bankCardTail: String?
    ): Boolean {
        if (bankBalanceCents == null || bankBalanceCents < 0L || bankCardTail.isNullOrBlank()) return false
        val account = database.accountDao().find(accountId) ?: return false
        return account.type == AccountType.ASSET.name && account.cardTail == bankCardTail
    }

    /**
     * 确认通知 → 创建交易 + 标记 LINKED，同一事务保证不会重复入账。
     * ponytail: 复用 addTransaction 的余额计算逻辑。
     */
    suspend fun confirmNotification(
        notificationId: String,
        accountId: String,
        amountCents: Long,
        type: TransactionType,
        category: String,
        merchant: String?,
        note: String?,
        bankBalanceCents: Long? = null,
        bankCardTail: String? = null,
        necessity: Boolean? = null,
        channel: String? = null,
        refundOfId: String? = null
    ) {
        require(amountCents > 0)
        database.withTransaction {
            // 短信接收器和系统短信通知可能各自留下一个待确认项；若其中一个已经
            // 确认入账，第二个必须在认领前按“正文完全相同”拦截，避免重复正式流水。
            val currentEvidence = database.rawNotificationDao().findById(notificationId)
            if (currentEvidence != null) {
                val linkedMirrorIds = database.rawNotificationDao().all()
                    .filter { it.id != notificationId && it.status == "LINKED" }
                    .filter {
                        ContentFingerprint.isSameSmsMirrorContent(
                            currentEvidence.packageName,
                            currentEvidence.content,
                            currentEvidence.postedAt,
                            it.packageName,
                            it.content,
                            it.postedAt
                        )
                    }
                    .map { it.id }
                if (linkedMirrorIds.isNotEmpty() && database.transactionDao().findActiveByNotificationIds(linkedMirrorIds).isNotEmpty()) {
                    database.rawNotificationDao().updateProcessingNote(notificationId, "与已确认的短信镜像证据重复，未生成第二笔流水")
                    database.rawNotificationDao().updateStatus(notificationId, "IGNORED")
                    return@withTransaction
                }
                requireEarliestNotificationFirst(
                    notification = currentEvidence,
                    accountId = accountId,
                    bankBalanceCents = bankBalanceCents,
                    bankCardTail = bankCardTail
                )
            }
            // 先原子认领再做任何余额或流水变更。重复点击、并发确认或已处理通知直接无操作返回。
            if (database.rawNotificationDao().claimForConfirmation(notificationId) != 1) {
                return@withTransaction
            }
            // 流水日期用通知到达时间，不用确认时刻：银行短信即时推，postedAt≈真实扣款时间；
            // 用户可能隔天才点确认，用确认时刻会把日期记错（真机实报：88 元短信 08:39 到、09:41 确认）
            val postedAt = currentEvidence?.postedAt ?: System.currentTimeMillis()
            val allowAuthoritativeBalanceOverride = canUseAuthoritativeBalance(
                accountId = accountId,
                bankBalanceCents = bankBalanceCents,
                bankCardTail = bankCardTail
            )
            // 周期账单反向认领：规则可能先于真实扣款自动记过一笔（同账户+同类型+金额±15%+前后5天）。
            // 认到就不再造第二笔，只把通知标 LINKED —— 否则规则+短信把同一笔记两遍。
            // ponytail: 同账户同金额同周的两笔真实消费会被误认领，宁可漏记不要虚增。
            val ruleTx = database.transactionDao().findInRange(
                postedAt - 5L * 24 * 60 * 60 * 1000, postedAt + 5L * 24 * 60 * 60 * 1000
            ).firstOrNull {
                it.recurringRuleId != null &&
                    it.type == type.name &&
                    it.accountId == accountId &&
                    kotlin.math.abs(it.amountCents - amountCents) * 100 <= amountCents * 15
            }
            if (ruleTx == null) {
                // 确认即认领：只有账户、类型、商户、金额与日期共同命中且候选唯一时才自动挂规则。
                // 同日同额多条规则保持未关联，交给“待核实”，避免把真实支出认到错误计划。
                val ruleId = uniqueRecurringRuleFor(
                    type = type,
                    accountId = accountId,
                    amountCents = amountCents,
                    merchant = merchant,
                    postedAt = postedAt
                )?.id
                if (type == TransactionType.LOAN_PAYMENT) {
                    // 贷款扣款自动匹配期次（REQ 贷款页 §6-8）：确认时按金额+日期匹配最接近的
                    // 未还期次，匹配上即标记已还并记实际本金/利息/手续费；对不上挂最近计划、
                    // 本金暂按全额（待确认页允许确认前修改，§8）。
                    val match = suggestLoanMatch(amountCents, postedAt)
                    val plan = match?.first
                    val inst = match?.second
                    if (plan != null && inst != null && inst.total.cents == amountCents) {
                        addTransaction(
                            accountId, amountCents, type, TransactionCategory.UNCATEGORIZED.name, merchant, note,
                            occurredAt = postedAt,
                            principalCents = inst.principal.cents,
                            interestCents = inst.interest.cents,
                            feeCents = inst.fee.cents,
                            loanPlanId = plan.id,
                            necessity = necessity, channel = channel, notificationId = notificationId,
                            allowAuthoritativeBalanceOverride = allowAuthoritativeBalanceOverride
                        )
                        markInstallmentPaid(plan, inst.number, inst.principal.cents)
                    } else {
                        addTransaction(
                            accountId, amountCents, type, TransactionCategory.UNCATEGORIZED.name, merchant, note,
                            occurredAt = postedAt, principalCents = amountCents, loanPlanId = plan?.id,
                            necessity = necessity, channel = channel, notificationId = notificationId,
                            allowAuthoritativeBalanceOverride = allowAuthoritativeBalanceOverride
                        )
                    }
                } else {
                    addTransaction(
                        accountId, amountCents, type, category, merchant, note,
                        occurredAt = postedAt, recurringRuleId = ruleId,
                        refundOfId = refundOfId.takeIf { type == TransactionType.REFUND },
                        necessity = necessity, channel = channel, notificationId = notificationId,
                        allowAuthoritativeBalanceOverride = allowAuthoritativeBalanceOverride
                    )
                }
            } else {
                database.transactionDao().attachNotification(ruleTx.id, notificationId)
                evidenceRecorder.link(
                    groupId = "notification:$notificationId",
                    subjectType = EvidenceSubjectType.TRANSACTION,
                    subjectId = ruleTx.id,
                    subjectRole = "CONFIRMED_RECURRING_EVENT",
                    sources = listOf(EvidenceSourceRef(EvidenceSourceType.RAW_NOTIFICATION, notificationId)),
                    linkedAt = postedAt
                )
                evidenceRecorder.lifecycle(
                    subjectType = EvidenceSubjectType.TRANSACTION,
                    subjectId = ruleTx.id,
                    action = EvidenceAction.LINKED,
                    occurredAt = postedAt,
                    payload = JSONObject()
                        .put("notificationId", notificationId)
                        .put("recurringRuleId", ruleTx.recurringRuleId)
                )
            }
            database.rawNotificationDao().updateStatus(notificationId, "LINKED")
            // addTransaction 刚按增量改过余额；银行自报的余额是权威值，最后再盖一次。
            // 顺序不能反，否则增量会把对好的余额又推歪。
            if (bankBalanceCents != null && bankCardTail != null) {
                applyBankBalance(accountId, bankCardTail, bankBalanceCents, postedAt)
            }
        }
    }

    /**
     * 用银行通知自报的余额对账：记录权威检查点并重算余额，不再直接覆盖
     * （覆盖会跟随后确认流水的增量二次扣减）。
     *
     * 只认储蓄卡（ASSET）：信用卡短信报的是可用额度、花呗报的是账单，都不是欠款余额，
     * 拿来盖会把负债算错。必须尾号对得上才动 —— 不知道是哪张卡的余额一律不用。
     *
     * 差额核对（REQ 账户对账 §4-5）：上次权威余额 + 期间已确认及待确认变化 = 本次银行余额。
     * 不一致仍照记银行检查点（银行是权威），但标 DISCREPANCY 并记一条可追溯调整记录，
     * 不静默吞差额。
     */
    private suspend fun applyBankBalance(
        accountId: String,
        cardTail: String,
        balanceCents: Long,
        checkedAt: Long,
        pendingDeltas: List<LedgerDelta> = emptyList(),
        thisEvidenceDeltaCents: Long? = null
    ) {
        val account = database.accountDao().find(accountId) ?: return
        if (account.cardTail != cardTail) return
        val accountType = AccountType.valueOf(account.type)
        if (accountType != AccountType.ASSET) return

        var status = "CONFIRMED"
        val prev = database.balanceCheckpointDao().latestFor(accountId)
        if (prev != null) {
            val allDeltas = accountDeltas(accountId, accountType) + pendingDeltas +
                (thisEvidenceDeltaCents?.let { listOf(LedgerDelta(checkedAt, it)) } ?: emptyList())
            val expected = BalanceMath.expectedBalance(
                BalanceCheckpoint(prev.balanceCents, prev.checkedAt),
                allDeltas,
                checkedAt
            )
            val diff = balanceCents - expected
            if (diff != 0L) {
                status = "DISCREPANCY"
                // 幂等：同一检查点重放（判重/补扫再触发）不会重复记调整
                database.balanceAdjustmentDao().upsert(
                    BalanceAdjustmentEntity(
                        id = "discrepancy_${accountId}_$checkedAt",
                        accountId = accountId,
                        beforeCents = expected,
                        afterCents = balanceCents,
                        diffCents = diff,
                        reason = "自动差额核对：账面应有与银行余额不符",
                        occurredAt = checkedAt
                    )
                )
            }
        }
        database.balanceCheckpointDao().upsert(
            BalanceCheckpointEntity(
                id = "bank_${accountId}_$checkedAt",
                accountId = accountId,
                balanceCents = balanceCents,
                checkedAt = checkedAt,
                source = "BANK_SMS"
            )
        )
        database.accountDao().upsert(account.copy(balanceStatus = status, lastCheckedAt = checkedAt))
        recomputeBalance(accountId)
    }

    /** 账户的全部已确认事件增量（流水 + 转账），余额重算与差额核对共用。 */
    private suspend fun accountDeltas(accountId: String, accountType: AccountType): List<LedgerDelta> {
        val txDeltas = database.transactionDao().all()
            .filter { it.accountId == accountId }
            .map {
                LedgerDelta(
                    occurredAt = it.occurredAt,
                    deltaCents = BalanceMath.transactionDelta(accountType, TransactionType.valueOf(it.type), it.amountCents)
                )
            }
        val transferDeltas = database.transferDao().all().flatMap { tf ->
            buildList {
                if (tf.fromAccountId == accountId) add(LedgerDelta(tf.occurredAt, BalanceMath.transferOutDelta(accountType, tf.amountCents)))
                if (tf.toAccountId == accountId) add(LedgerDelta(tf.occurredAt, BalanceMath.transferInDelta(accountType, tf.amountCents)))
            }
        }
        return txDeltas + transferDeltas
    }

    /**
     * 重算账户余额 = 最新检查点 + 其后已确认事件增量（决策 2 可信账务内核）。
     * 所有入账/删除/编辑/转账/对账路径统一「记事件 → 重算」，不再手工加减余额。
     */
    suspend fun recomputeBalance(accountId: String) {
        val account = database.accountDao().find(accountId) ?: return
        val checkpoint = database.balanceCheckpointDao().latestFor(accountId) ?: return
        val accountType = AccountType.valueOf(account.type)

        val newBalance = BalanceMath.balance(
            openingBalanceCents = 0L,
            checkpoint = BalanceCheckpoint(checkpoint.balanceCents, checkpoint.checkedAt),
            deltas = accountDeltas(accountId, accountType)
        )
        if (newBalance != account.balanceCents) {
            database.accountDao().upsert(account.copy(balanceCents = newBalance))
        }
    }

    suspend fun addAccount(
        name: String,
        type: AccountType,
        openingBalanceCents: Long,
        cardTail: String?,
        statementDay: Int? = null,
        dueDay: Int? = null,
        creditLimitCents: Long = 0
    ) {
        require(name.isNotBlank())
        require(openingBalanceCents >= 0)
        val id = UUID.randomUUID().toString()
        database.accountDao().upsert(
            AccountEntity(
                id = id,
                name = name.trim(),
                type = type.name,
                balanceCents = openingBalanceCents,
                cardTail = cardTail?.filter(Char::isDigit)?.takeLast(4)?.takeIf { it.isNotEmpty() },
                balanceStatus = "CONFIRMED",
                lastCheckedAt = System.currentTimeMillis(),
                statementDay = statementDay,
                dueDay = dueDay,
                creditLimitCents = creditLimitCents,
                startDateEpochDay = java.time.LocalDate.now().toEpochDay()
            )
        )
        database.balanceCheckpointDao().upsert(
            BalanceCheckpointEntity(
                id = "opening_$id",
                accountId = id,
                balanceCents = openingBalanceCents,
                checkedAt = Long.MIN_VALUE,
                source = "OPENING"
            )
        )
    }

    suspend fun updateAccount(account: AccountEntity) {
        database.withTransaction {
            val old = database.accountDao().find(account.id)
            database.accountDao().upsert(account)
            // 用户手动改了余额：先记可追溯的余额调整（REQ 账户对账 §7/§9），再记 MANUAL 检查点。
            // 手动对账视为用户已核对：清掉差额标志，DISCREPANCY → CONFIRMED。
            if (old != null && old.balanceCents != account.balanceCents) {
                val now = System.currentTimeMillis()
                database.balanceAdjustmentDao().upsert(
                    BalanceAdjustmentEntity(
                        id = UUID.randomUUID().toString(),
                        accountId = account.id,
                        beforeCents = old.balanceCents,
                        afterCents = account.balanceCents,
                        diffCents = account.balanceCents - old.balanceCents,
                        reason = "手动对账",
                        occurredAt = now
                    )
                )
                database.balanceCheckpointDao().upsert(
                    BalanceCheckpointEntity(
                        id = "manual_${account.id}_$now",
                        accountId = account.id,
                        balanceCents = account.balanceCents,
                        checkedAt = now,
                        source = "MANUAL"
                    )
                )
                database.accountDao().upsert(
                    account.copy(balanceStatus = "CONFIRMED", lastCheckedAt = now)
                )
            }
        }
    }

    suspend fun deleteAccount(accountId: String) {
        database.accountDao().deleteById(accountId)
    }

    /** 归档账户（REQ 账户对账 §14-15）：余额必须为 0，避免总资产凭空减少；历史仍可查。 */
    suspend fun archiveAccount(accountId: String) {
        val account = database.accountDao().find(accountId) ?: return
        require(account.balanceCents == 0L) { "归档前余额必须为 0" }
        database.accountDao().upsert(account.copy(archived = true))
    }

    suspend fun restoreAccount(accountId: String) {
        database.accountDao().find(accountId)?.let {
            database.accountDao().upsert(it.copy(archived = false))
        }
    }

    suspend fun updateTransactionCategory(id: String, category: TransactionCategory) {
        setTransactionCategoryName(id, category.name)
    }

    /** quick_check 只能证明“是健康 SQLite”，还需确认不是别的应用数据库。 */
    private fun isAssetsKingDatabase(bytes: ByteArray): Boolean {
        val candidate = java.io.File.createTempFile("assets-king-schema-", ".db", context.cacheDir)
        return try {
            candidate.writeBytes(bytes)
            val opened = android.database.sqlite.SQLiteDatabase.openDatabase(
                candidate.path,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            try {
                val tables = mutableSetOf<String>()
                opened.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table'",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) tables += cursor.getString(0)
                }
                val requiredTables = setOf("room_master_table", "accounts", "transactions", "transfers", "raw_notifications")
                if (!tables.containsAll(requiredTables)) return false
                opened.rawQuery("PRAGMA user_version", null).use { cursor ->
                    cursor.moveToFirst() && cursor.getInt(0) >= 9
                }
            } finally {
                opened.close()
            }
        } finally {
            candidate.delete()
            java.io.File(candidate.path + "-wal").delete()
            java.io.File(candidate.path + "-shm").delete()
        }
    }

    suspend fun setTransactionCategoryName(id: String, categoryName: String) {
        database.withTransaction {
            val old = requireNotNull(database.transactionDao().findById(id)) { "流水不存在" }
            requireTransactionNotAllocated(id)
            database.transactionDao().updateCategory(id, categoryName)
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.TRANSACTION,
                subjectId = id,
                action = EvidenceAction.EDITED,
                payload = JSONObject().put("field", "category").put("before", old.category).put("after", categoryName)
            )
        }
    }

    suspend fun setTransactionNecessity(id: String, necessity: Boolean?) {
        database.withTransaction {
            val old = requireNotNull(database.transactionDao().findById(id)) { "流水不存在" }
            requireTransactionNotAllocated(id)
            database.transactionDao().updateNecessity(id, necessity)
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.TRANSACTION,
                subjectId = id,
                action = EvidenceAction.EDITED,
                payload = JSONObject().put("field", "necessity").put("before", old.necessity).put("after", necessity)
            )
        }
    }

    suspend fun updateTransaction(
        id: String,
        amountCents: Long,
        type: TransactionType,
        category: String,
        merchant: String?,
        note: String?,
        accountId: String,
        occurredAt: Long,
        necessity: Boolean?,
        channel: String?,
        isReimbursable: Boolean? = null,
        refundOfId: String? = null
    ) {
        require(amountCents > 0)
        database.withTransaction {
            val old = requireNotNull(database.transactionDao().findById(id))
            requireTransactionNotAllocated(id)
            require(old.lendingPlanId == null) { "已关联出借计划的流水不能普通编辑，请先处理关联" }
            require(database.lendingPlanDao().findByReceivableAccount(accountId) == null) {
                "出借计划的应收账户不能用于普通记账，请使用借出或收回入口"
            }
            validateRefundLink(
                type = type,
                refundOfId = refundOfId,
                amountCents = amountCents,
                accountId = accountId,
                merchant = merchant,
                channel = channel,
                occurredAt = occurredAt,
                replacing = old
            )
            requireSufficientAssetBalance(
                accountId = accountId,
                amountCents = amountCents,
                type = type,
                occurredAt = occurredAt,
                replacing = old
            )
            // V5：借款/还款/提前还款流水不可编辑（联动了贷款计划），删除后重记
            val oldType = TransactionType.valueOf(old.type)
            val loanTypes = setOf(TransactionType.LOAN_DISBURSEMENT, TransactionType.LOAN_PAYMENT, TransactionType.LOAN_PREPAYMENT)
            val isLoanTx = type in loanTypes || oldType in loanTypes
            if (!isLoanTx) {
                database.transactionDao().update(
                    id, amountCents, type.name, category, merchant, note,
                    accountId, occurredAt, necessity, channel
                )
                isReimbursable?.let { requested ->
                    database.transactionDao().updateReimbursable(id, requested)
                }
                database.transactionDao().updateRefundOfId(
                    id,
                    refundOfId.takeIf { type == TransactionType.REFUND }
                )
                recomputeBalance(old.accountId)
                if (accountId != old.accountId) recomputeBalance(accountId)
                evidenceRecorder.lifecycle(
                    subjectType = EvidenceSubjectType.TRANSACTION,
                    subjectId = id,
                    action = EvidenceAction.EDITED,
                    payload = JSONObject()
                        .put("beforeType", old.type)
                        .put("afterType", type.name)
                        .put("beforeAmountCents", old.amountCents)
                        .put("afterAmountCents", amountCents)
                        .put("beforeAccountId", old.accountId)
                        .put("afterAccountId", accountId)
                )
            }
        }
    }

    /** 把已经确认的普通收入/支出补挂到借款计划；借款本身不进普通收入统计。 */
    suspend fun linkTransactionToLoanDisbursement(
        id: String,
        planId: String,
        amountCents: Long,
        merchant: String?,
        note: String?,
        accountId: String,
        occurredAt: Long,
        channel: String?
    ) {
        require(amountCents > 0L)
        database.withTransaction {
            val old = requireNotNull(database.transactionDao().findById(id)) { "流水不存在" }
            require(
                old.type == TransactionType.EXPENSE.name ||
                    old.type == TransactionType.INCOME.name ||
                    old.type == TransactionType.LOAN_DISBURSEMENT.name
            ) {
                "只有普通收支流水可以补挂借款计划"
            }
            requireTransactionNotAllocated(id)
            require(old.lendingPlanId == null) { "已关联出借计划的流水不能再挂借款计划" }
            require(database.reimbursementLinkDao().findByExpense(id).isEmpty()) { "已关联报销的流水不能改成借款到账" }
            val plan = requireNotNull(database.loanPlanDao().findById(planId)) { "借款计划不存在" }
            if (old.type == TransactionType.LOAN_DISBURSEMENT.name && old.loanPlanId != null) {
                require(old.loanPlanId == plan.id) { "已入账的借款到账不能改挂到另一份贷款计划" }
                require(old.amountCents == amountCents) { "已激活贷款计划后不能直接修改到账本金，请先处理计划关联" }
            }
            val account = requireNotNull(database.accountDao().find(accountId)) { "到账账户不存在" }
            require(account.type == AccountType.ASSET.name) { "借款到账必须进入资产账户" }
            database.transactionDao().updateManagedFields(
                id = id,
                amountCents = amountCents,
                type = TransactionType.LOAN_DISBURSEMENT.name,
                category = TransactionCategory.UNCATEGORIZED.name,
                merchant = merchant?.trim()?.takeIf { it.isNotEmpty() },
                note = note?.trim()?.takeIf { it.isNotEmpty() },
                accountId = accountId,
                occurredAt = occurredAt,
                principalCents = 0L,
                interestCents = 0L,
                feeCents = 0L,
                loanPlanId = plan.id,
                necessity = null,
                channel = channel
            )
            activateLoanPlanFromDisbursement(plan, id, amountCents, occurredAt)
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.TRANSACTION,
                subjectId = id,
                action = EvidenceAction.LINKED,
                occurredAt = occurredAt,
                payload = JSONObject().put("loanPlanId", plan.id).put("role", "LOAN_DISBURSEMENT")
            )
            recomputeBalance(old.accountId)
            if (old.accountId != accountId) recomputeBalance(accountId)
        }
    }

    /** 把已经确认的普通支出补挂到贷款计划，按用户提供的本金/息费拆分推进计划。 */
    suspend fun linkTransactionToLoanPayment(
        id: String,
        planId: String,
        amountCents: Long,
        principalCents: Long,
        interestCents: Long,
        feeCents: Long,
        note: String?,
        accountId: String,
        occurredAt: Long,
        channel: String?
    ) {
        require(amountCents > 0L)
        require(principalCents >= 0L && interestCents >= 0L && feeCents >= 0L)
        database.withTransaction {
            val old = requireNotNull(database.transactionDao().findById(id)) { "流水不存在" }
            require(old.type == TransactionType.EXPENSE.name) { "贷款还款必须从支出流水补挂" }
            requireTransactionNotAllocated(id)
            require(database.accountDao().find(accountId)?.type == AccountType.ASSET.name) {
                "贷款还款必须从资产账户付款"
            }
            requireSufficientAssetBalance(
                accountId = accountId,
                amountCents = amountCents,
                type = TransactionType.LOAN_PAYMENT,
                occurredAt = occurredAt,
                replacing = old
            )
            val plan = requireNotNull(database.loanPlanDao().findById(planId)) { "贷款计划不存在" }
            require(plan.status == "ACTIVE") { "只有已放款且未结清的计划可以补挂还款" }
            require(principalCents + interestCents + feeCents == amountCents) { "本金+利息+手续费必须等于流水金额" }
            require(principalCents <= remainingEffective(plan)) { "本金超过贷款计划剩余本金" }
            val insts = jsonToInstallments(plan.installmentsJson)
            val atDay = Instant.ofEpochMilli(occurredAt).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
            val matched = InstallmentMatcher.match(insts, amountCents, atDay)
            val updatedInsts = if (matched != null && matched.total.cents == amountCents && matched.principal.cents == principalCents) {
                insts.map { if (it.number == matched.number) it.copy(status = InstallmentStatus.PAID) else it }
            } else insts
            database.transactionDao().updateManagedFields(
                id = id,
                amountCents = amountCents,
                type = TransactionType.LOAN_PAYMENT.name,
                category = TransactionCategory.UNCATEGORIZED.name,
                merchant = null,
                note = note?.trim()?.takeIf { it.isNotEmpty() },
                accountId = accountId,
                occurredAt = occurredAt,
                principalCents = principalCents,
                interestCents = interestCents,
                feeCents = feeCents,
                loanPlanId = plan.id,
                necessity = null,
                channel = channel
            )
            database.loanPlanDao().upsert(
                plan.copy(
                    remainingPrincipalCents = maxOf(0L, remainingEffective(plan) - principalCents),
                    installmentsJson = installmentsToJson(updatedInsts),
                    status = if (principalCents >= remainingEffective(plan)) "PAID_OFF" else plan.status
                )
            )
            recordLoanEvent(plan.id, id, "LOAN_PAYMENT", occurredAt, principalCents, interestCents, feeCents)
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.TRANSACTION,
                subjectId = id,
                action = EvidenceAction.LINKED,
                occurredAt = occurredAt,
                payload = JSONObject()
                    .put("loanPlanId", plan.id)
                    .put("role", "LOAN_PAYMENT")
                    .put("principalCents", principalCents)
                    .put("interestCents", interestCents)
                    .put("feeCents", feeCents)
            )
            recomputeBalance(old.accountId)
            if (old.accountId != accountId) recomputeBalance(accountId)
        }
    }

    /** 把已确认的普通收入补挂为出借利息；不伪造本金支出，利息只计一次。 */
    suspend fun linkTransactionToLendingInterest(
        id: String,
        planId: String,
        amountCents: Long,
        merchant: String?,
        note: String?,
        accountId: String,
        occurredAt: Long,
        channel: String?
    ) {
        require(amountCents > 0L)
        database.withTransaction {
            val old = requireNotNull(database.transactionDao().findById(id)) { "流水不存在" }
            require(old.type == TransactionType.INCOME.name) { "只有收入流水可以补挂出借利息" }
            requireTransactionNotAllocated(id)
            require(old.lendingPlanId == null || old.lendingPlanId == planId) { "流水已关联其他出借计划" }
            require(old.loanPlanId == null) { "已关联贷款计划的流水不能再挂出借计划" }
            val account = requireNotNull(database.accountDao().find(accountId)) { "到账账户不存在" }
            require(account.type == AccountType.ASSET.name) { "出借利息必须进入资产账户" }
            val plan = requireNotNull(database.lendingPlanDao().findById(planId)) { "出借计划不存在" }
            require(plan.status != LendingPlanStatus.PENDING_DISBURSEMENT) { "待借出计划不能收取利息" }

            val previousInterest = if (old.lendingPlanId == planId) old.amountCents else 0L
            val delta = amountCents - previousInterest
            val updatedAt = System.currentTimeMillis()
            val reanchor = occurredAt <= plan.ledgerBaselineAt
            database.transactionDao().update(
                id = id,
                amountCents = amountCents,
                type = TransactionType.INCOME.name,
                category = "利息收益",
                merchant = merchant?.trim()?.takeIf { it.isNotEmpty() } ?: plan.borrowerName,
                note = note?.trim()?.takeIf { it.isNotEmpty() },
                accountId = accountId,
                occurredAt = occurredAt,
                necessity = null,
                channel = channel
            )
            database.transactionDao().updateLendingPlanId(id, planId)
            database.lendingPlanDao().upsert(
                plan.copy(
                    receivedInterestCents = (plan.receivedInterestCents + delta).coerceAtLeast(0L),
                    ledgerBaselineInterestCents = if (reanchor) {
                        (plan.ledgerBaselineInterestCents + delta).coerceAtLeast(0L)
                    } else plan.ledgerBaselineInterestCents,
                    ledgerBaselineAt = if (reanchor) updatedAt else plan.ledgerBaselineAt,
                    updatedAt = updatedAt
                )
            )
            evidenceRecorder.link(
                groupId = "lending-interest:$id",
                subjectType = EvidenceSubjectType.LENDING_PLAN,
                subjectId = planId,
                subjectRole = "INTEREST_RECEIPT_TRANSACTION",
                sources = listOf(EvidenceSourceRef(EvidenceSourceType.LEDGER_EVENT, id)),
                linkedAt = occurredAt
            )
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.TRANSACTION,
                subjectId = id,
                action = EvidenceAction.LINKED,
                occurredAt = occurredAt,
                payload = JSONObject().put("lendingPlanId", planId).put("role", "INTEREST_RECEIPT")
            )
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.LENDING_PLAN,
                subjectId = planId,
                action = EvidenceAction.LINKED,
                occurredAt = occurredAt,
                payload = JSONObject().put("transactionId", id).put("interestCents", amountCents)
            )
            recomputeBalance(old.accountId)
            if (accountId != old.accountId) recomputeBalance(accountId)
        }
    }

    /** 将已确认的本金+利息到账改造成本金划转与利息收入，现金净额保持不变。 */
    suspend fun convertIncomeToLendingRepayment(
        id: String,
        planId: String,
        totalCents: Long,
        principalCents: Long,
        interestCents: Long,
        note: String?,
        accountId: String,
        occurredAt: Long,
        channel: String?
    ) {
        require(totalCents > 0L)
        require(principalCents >= 0L && interestCents >= 0L)
        require(principalCents + interestCents == totalCents) { "本金与利息合计必须等于到账金额" }
        require(principalCents > 0L) { "本金到账必须大于零" }
        database.withTransaction {
            val old = requireNotNull(database.transactionDao().findById(id)) { "流水不存在" }
            require(old.type == TransactionType.INCOME.name) { "只有收入流水可以转换为出借收回" }
            require(old.lendingPlanId == null) { "该流水已经关联出借利息" }
            requireTransactionNotAllocated(id)
            val plan = requireNotNull(database.lendingPlanDao().findById(planId)) { "出借计划不存在" }
            require(plan.status != LendingPlanStatus.PENDING_DISBURSEMENT) { "待借出计划不能收回" }
            require(principalCents <= plan.remainingPrincipalCents) { "收回本金超过剩余应收" }
            val account = requireNotNull(database.accountDao().find(accountId)) { "到账账户不存在" }
            require(account.type == AccountType.ASSET.name && account.id != plan.receivableAccountId) { "请选择真实收款账户" }

            if (interestCents > 0L) {
                // 有实际利息时，原收入流水收敛为唯一的利息收入证据。
                database.transactionDao().update(
                    id = id,
                    amountCents = interestCents,
                    type = TransactionType.INCOME.name,
                    category = "利息收益",
                    merchant = old.merchant?.takeIf { it.isNotBlank() } ?: plan.borrowerName,
                    note = note?.trim()?.takeIf { it.isNotEmpty() },
                    accountId = accountId,
                    occurredAt = occurredAt,
                    necessity = null,
                    channel = channel
                )
                database.transactionDao().updateLendingPlanId(id, planId)
            } else {
                // 纯本金到账不应留下 0 元收入；本金只由下方应收→现金划转表达。
                // 原收入对象保留生命周期/来源墓碑，防止通知补扫或审计把它当成可复活流水。
                evidenceRecorder.lifecycle(
                    subjectType = EvidenceSubjectType.TRANSACTION,
                    subjectId = id,
                    action = EvidenceAction.EFFECT_REVERSED,
                    occurredAt = occurredAt,
                    payload = JSONObject()
                        .put("replacement", "LENDING_PRINCIPAL_REPAYMENT_TRANSFER")
                        .put("transferPrincipalCents", principalCents)
                )
                evidenceRecorder.lifecycle(
                    subjectType = EvidenceSubjectType.TRANSACTION,
                    subjectId = id,
                    action = EvidenceAction.PURGED,
                    occurredAt = occurredAt,
                    payload = JSONObject().put("reason", "纯本金收回不保留零元收入")
                )
                database.transactionDao().deleteById(id)
            }
            val transferId = addTransfer(
                fromAccountId = plan.receivableAccountId,
                toAccountId = accountId,
                amountCents = principalCents,
                note = note,
                occurredAt = occurredAt,
                lendingPlanId = planId,
                lendingRole = LendingTransferRole.PRINCIPAL_REPAYMENT,
                evidenceSources = listOf(EvidenceSourceRef(EvidenceSourceType.LEDGER_EVENT, id)),
                evidenceGroupId = "lending-conversion:$id",
                evidenceRole = "LENDING_PRINCIPAL_REPAYMENT"
            )
            val updatedAt = System.currentTimeMillis()
            val reanchor = occurredAt <= plan.ledgerBaselineAt
            database.lendingPlanDao().upsert(
                plan.copy(
                    remainingPrincipalCents = plan.remainingPrincipalCents - principalCents,
                    receivedInterestCents = plan.receivedInterestCents + interestCents,
                    status = if (plan.remainingPrincipalCents == principalCents) LendingPlanStatus.COMPLETED else LendingPlanStatus.ACTIVE,
                    ledgerBaselinePrincipalCents = if (reanchor) plan.remainingPrincipalCents - principalCents else plan.ledgerBaselinePrincipalCents,
                    ledgerBaselineInterestCents = if (reanchor) plan.receivedInterestCents + interestCents else plan.ledgerBaselineInterestCents,
                    ledgerBaselineAt = if (reanchor) updatedAt else plan.ledgerBaselineAt,
                    updatedAt = updatedAt
                )
            )
            evidenceRecorder.link(
                groupId = "lending-conversion:$id",
                subjectType = EvidenceSubjectType.LENDING_PLAN,
                subjectId = planId,
                subjectRole = "REPAYMENT_CONVERSION",
                sources = listOf(EvidenceSourceRef(EvidenceSourceType.LEDGER_EVENT, id)),
                linkedAt = occurredAt
            )
            if (interestCents > 0L) {
                evidenceRecorder.lifecycle(
                    subjectType = EvidenceSubjectType.TRANSACTION,
                    subjectId = id,
                    action = EvidenceAction.LINKED,
                    occurredAt = occurredAt,
                    payload = JSONObject()
                        .put("lendingPlanId", planId)
                        .put("transferId", transferId)
                        .put("principalCents", principalCents)
                        .put("interestCents", interestCents)
                )
            }
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.LENDING_PLAN,
                subjectId = planId,
                action = EvidenceAction.LINKED,
                occurredAt = occurredAt,
                payload = JSONObject()
                    .put("transactionId", id)
                    .put("transferId", transferId)
                    .put("role", "REPAYMENT_CONVERSION")
            )
            recomputeBalance(old.accountId)
            if (accountId != old.accountId) recomputeBalance(accountId)
            recomputeBalance(plan.receivableAccountId)
        }
    }

    suspend fun previewTransactionDeletion(ids: Collection<String>): List<TransactionDeletionAccountPreview> {
        val transactions = ids.distinct().mapNotNull { database.transactionDao().findById(it) }
        return transactions.groupBy { it.accountId }.mapNotNull { (accountId, accountTransactions) ->
            val account = database.accountDao().find(accountId) ?: return@mapNotNull null
            val checkpoint = database.balanceCheckpointDao().latestFor(accountId)
            val accountType = AccountType.valueOf(account.type)
            val removedDelta = accountTransactions
                .asSequence()
                .filter { checkpoint == null || it.occurredAt > checkpoint.checkedAt }
                .sumOf { tx ->
                    BalanceMath.transactionDelta(accountType, TransactionType.valueOf(tx.type), tx.amountCents)
                }
            TransactionDeletionAccountPreview(
                accountId = accountId,
                currentBalanceCents = account.balanceCents,
                projectedBalanceCents = account.balanceCents - removedDelta,
                balanceStatus = account.balanceStatus,
                anchoredBeforeCheckpointCount = accountTransactions.count {
                    checkpoint != null && it.occurredAt <= checkpoint.checkedAt
                }
            )
        }.sortedBy { it.accountId }
    }

    suspend fun deleteTransaction(id: String, deletedAt: Long = System.currentTimeMillis()) {
        deleteTransactions(listOf(id), deletedAt)
    }

    suspend fun deleteTransactions(ids: Collection<String>, deletedAt: Long = System.currentTimeMillis()) {
        database.withTransaction {
            val transactions = ids.distinct().map { id ->
                requireNotNull(database.transactionDao().findById(id)) { "流水不存在或已在垃圾箱" }
            }
            transactions.forEach { tx ->
                requireTransactionNotAllocated(tx.id)
                if (TransactionType.valueOf(tx.type) == TransactionType.EXPENSE) {
                    require(database.reimbursementLinkDao().findByExpense(tx.id).isEmpty()) {
                        "已关联报销到账，请先删除对应报销到账流水"
                    }
                    require(database.transactionDao().countRefundsFor(tx.id) == 0) {
                        "已关联退款（含垃圾箱），请先处理对应退款流水"
                    }
                }
            }
            require(
                transactions.mapNotNull { it.loanPlanId }.groupingBy { it }.eachCount().none { it.value > 1 }
            ) { "同一贷款计划的多笔流水请逐笔删除，避免恢复顺序破坏期次" }
            require(
                transactions.mapNotNull { it.lendingPlanId }.groupingBy { it }.eachCount().none { it.value > 1 }
            ) { "同一出借计划的多笔流水请逐笔删除，避免恢复顺序破坏应收状态" }
            val reimbursementExpenseIds = transactions
                .filter { it.type == TransactionType.REIMBURSEMENT.name }
                .flatMap { database.reimbursementLinkDao().findByReimbursement(it.id).map { link -> link.expenseTxId } }
            require(
                reimbursementExpenseIds.groupingBy { it }.eachCount().none { it.value > 1 }
            ) { "同一垫付被多笔报销到账覆盖时请逐笔删除，避免恢复快照冲突" }
            val trashContexts = transactions.associate { tx ->
                val before = captureTransactionEffectState(tx)
                rollbackTransactionEffects(tx)
                val after = captureTransactionEffectState(tx)
                tx.id to if (before != null && after != null) {
                    JSONObject().put("before", JSONObject(before)).put("after", JSONObject(after)).toString()
                } else null
            }
            transactions.forEach { tx ->
                check(database.transactionDao().moveToTrash(tx.id, deletedAt, trashContexts[tx.id]) == 1) {
                    "流水状态已变化，请刷新后重试"
                }
                evidenceRecorder.lifecycle(
                    subjectType = EvidenceSubjectType.TRANSACTION,
                    subjectId = tx.id,
                    action = EvidenceAction.TRASHED,
                    occurredAt = deletedAt,
                    payload = JSONObject()
                        .put("type", tx.type)
                        .put("amountCents", tx.amountCents)
                        .put("accountId", tx.accountId)
                )
                tx.loanPlanId?.let { planId ->
                    evidenceRecorder.lifecycle(
                        subjectType = EvidenceSubjectType.LOAN_PLAN,
                        subjectId = planId,
                        action = EvidenceAction.EFFECT_REVERSED,
                        occurredAt = deletedAt,
                        payload = JSONObject()
                            .put("transactionId", tx.id)
                            .put("transactionType", tx.type)
                            .put("reason", "TRANSACTION_TRASHED")
                    )
                }
                tx.lendingPlanId?.let { planId ->
                    evidenceRecorder.lifecycle(
                        subjectType = EvidenceSubjectType.LENDING_PLAN,
                        subjectId = planId,
                        action = EvidenceAction.EFFECT_REVERSED,
                        occurredAt = deletedAt,
                        payload = JSONObject()
                            .put("transactionId", tx.id)
                            .put("reason", "INTEREST_TRANSACTION_TRASHED")
                    )
                }
                tx.notificationId?.let { nid ->
                    database.rawNotificationDao().updateStatus(nid, "PENDING_CONFIRMATION")
                    database.rawNotificationDao().updateProcessingNote(
                        nid,
                        "关联流水已移入垃圾箱；通知回到待确认箱，可按原始证据重新记账"
                    )
                }
            }
            transactions.map { it.accountId }.distinct().forEach { recomputeBalance(it) }
        }
    }

    private suspend fun rollbackTransactionEffects(tx: TransactionEntity) {
            // V5 回滚：借款/还款/提前还款与贷款计划的联动还原，保持真相源对称
            when (TransactionType.valueOf(tx.type)) {
                TransactionType.EXPENSE -> Unit
                TransactionType.INCOME -> tx.lendingPlanId?.let { planId ->
                    database.lendingPlanDao().findById(planId)?.let { plan ->
                        val receivedInterest = (plan.receivedInterestCents - tx.amountCents).coerceAtLeast(0L)
                        val reanchorAt = System.currentTimeMillis()
                        database.lendingPlanDao().upsert(
                            plan.copy(
                                receivedInterestCents = receivedInterest,
                                ledgerBaselineInterestCents = if (tx.occurredAt <= plan.ledgerBaselineAt) receivedInterest else plan.ledgerBaselineInterestCents,
                                ledgerBaselineAt = if (tx.occurredAt <= plan.ledgerBaselineAt) reanchorAt else plan.ledgerBaselineAt,
                                updatedAt = reanchorAt
                            )
                        )
                    }
                }
                TransactionType.LOAN_PAYMENT -> tx.loanPlanId?.let { planId ->
                    database.loanPlanDao().findById(planId)?.let { plan ->
                        val current = jsonToInstallments(plan.installmentsJson)
                        val coveredNumbers = coveredLoanInstallmentNumbers(
                            current,
                            tx.principalCents,
                            tx.interestCents,
                            tx.feeCents
                        ) { it.status == InstallmentStatus.PAID }
                        val insts = current.map { inst ->
                            if (inst.number in coveredNumbers) inst.copy(status = InstallmentStatus.UPCOMING) else inst
                        }
                        database.loanPlanDao().upsert(
                            plan.copy(
                                remainingPrincipalCents = remainingEffective(plan) + tx.principalCents,
                                installmentsJson = installmentsToJson(insts),
                                status = "ACTIVE"  // 结清流水被删时恢复为进行中
                            )
                        )
                    }
                }
                TransactionType.LOAN_DISBURSEMENT -> tx.loanPlanId?.let { planId ->
                    database.loanPlanDao().findById(planId)?.let { plan ->
                        if (plan.originType == "DISBURSEMENT_EVENT" && plan.disbursementTransactionId == tx.id) {
                            val downstream = database.transactionDao().findActiveByLoanPlan(planId).filter {
                                it.id != tx.id && (it.type == TransactionType.LOAN_PAYMENT.name || it.type == TransactionType.LOAN_PREPAYMENT.name)
                            }
                            require(downstream.isEmpty()) {
                                "这笔到账后已有还款或提前还款；请先处理后续流水，或把贷款迁移为期初存量贷款"
                            }
                            database.loanPlanDao().upsert(
                                plan.copy(
                                    originType = "PENDING_DISBURSEMENT",
                                    disbursementTransactionId = null,
                                    status = "PENDING_DISBURSEMENT"
                                )
                            )
                        }
                    }
                }
                TransactionType.LOAN_PREPAYMENT -> tx.loanPlanId?.let { planId ->
                    database.loanPlanDao().findById(planId)?.let { plan ->
                        database.loanPlanDao().upsert(
                            plan.copy(
                                remainingPrincipalCents = remainingEffective(plan) + tx.principalCents,
                                earlyRepaidCents = maxOf(0, plan.earlyRepaidCents - tx.principalCents)
                            )
                        )
                    }
                }
                TransactionType.REIMBURSEMENT -> {
                    // 关联行留作恢复快照；这里只归还垫付的已报销金额。
                    database.reimbursementLinkDao().findByReimbursement(tx.id).forEach { link ->
                        database.transactionDao().findById(link.expenseTxId)?.let { expense ->
                            database.transactionDao().updateReimbursed(
                                expense.id,
                                (expense.reimbursedCents - link.coveredCents).coerceAtLeast(0L)
                            )
                        }
                    }
                }
                else -> Unit
            }
    }

    suspend fun restoreTransactionFromTrash(id: String) {
        database.withTransaction {
            val tx = requireNotNull(database.transactionDao().findIncludingDeleted(id)) { "垃圾箱中没有这条流水" }
            require(tx.deletedAt != null) { "这条流水不在垃圾箱" }
            tx.notificationId?.let { notificationId ->
                require(database.transactionDao().findActiveByNotificationId(notificationId) == null) {
                    "同一通知已重新记账，不能恢复旧垃圾箱流水"
                }
            }
            restoreTransactionEffectsFromSnapshot(tx)
            check(database.transactionDao().restoreFromTrash(id) == 1) { "流水状态已变化，请刷新后重试" }
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.TRANSACTION,
                subjectId = tx.id,
                action = EvidenceAction.RESTORED,
                payload = JSONObject().put("restoredIntoCurrentTimeline", true)
            )
            tx.loanPlanId?.let { planId ->
                evidenceRecorder.lifecycle(
                    subjectType = EvidenceSubjectType.LOAN_PLAN,
                    subjectId = planId,
                    action = EvidenceAction.EFFECT_RESTORED,
                    payload = JSONObject()
                        .put("transactionId", tx.id)
                        .put("transactionType", tx.type)
                )
            }
            tx.lendingPlanId?.let { planId ->
                evidenceRecorder.lifecycle(
                    subjectType = EvidenceSubjectType.LENDING_PLAN,
                    subjectId = planId,
                    action = EvidenceAction.EFFECT_RESTORED,
                    payload = JSONObject().put("transactionId", tx.id)
                )
            }
            recomputeBalance(tx.accountId)
            tx.notificationId?.let { nid ->
                database.rawNotificationDao().updateStatus(nid, "LINKED")
                database.rawNotificationDao().updateProcessingNote(nid, "关联流水已从垃圾箱恢复")
            }
        }
    }

    private suspend fun restoreTransactionEffectsFromSnapshot(tx: TransactionEntity) {
        val effectful = tx.loanPlanId != null || tx.lendingPlanId != null ||
            TransactionType.valueOf(tx.type) == TransactionType.REIMBURSEMENT
        if (!effectful) return
        val context = requireNotNull(tx.trashContextJson) { "缺少删除联动快照，无法安全恢复" }
        val root = JSONObject(context)
        val expectedAfter = root.getJSONObject("after")
        val current = requireNotNull(captureTransactionEffectState(tx)) { "关联数据不存在，无法安全恢复" }
        require(jsonStructurallyEquals(JSONObject(current), expectedAfter)) {
            "删除后关联贷款或报销数据已变化，不能强行恢复这条流水"
        }
        applyTransactionEffectState(root.getJSONObject("before"))
    }

    private suspend fun captureTransactionEffectState(tx: TransactionEntity): String? {
        tx.loanPlanId?.let { planId ->
            val plan = requireNotNull(database.loanPlanDao().findById(planId)) { "关联贷款计划不存在" }
            return JSONObject()
                .put("kind", "LOAN")
                .put("plan", loanPlanToTrashJson(plan))
                .toString()
        }
        tx.lendingPlanId?.let { planId ->
            val plan = requireNotNull(database.lendingPlanDao().findById(planId)) { "关联出借计划不存在" }
            return JSONObject()
                .put("kind", "LENDING")
                .put("plan", lendingPlanToTrashJson(plan))
                .toString()
        }
        if (TransactionType.valueOf(tx.type) == TransactionType.REIMBURSEMENT) {
            val expenses = JSONArray()
            database.reimbursementLinkDao().findByReimbursement(tx.id)
                .sortedBy { it.expenseTxId }
                .forEach { link ->
                    val expense = requireNotNull(database.transactionDao().findById(link.expenseTxId)) {
                        "关联垫付流水不存在"
                    }
                    expenses.put(
                        JSONObject()
                            .put("id", expense.id)
                            .put("reimbursedCents", expense.reimbursedCents)
                    )
                }
            return JSONObject().put("kind", "REIMBURSEMENT").put("expenses", expenses).toString()
        }
        return null
    }

    private suspend fun applyTransactionEffectState(state: JSONObject) {
        when (state.getString("kind")) {
            "LOAN" -> database.loanPlanDao().upsert(trashJsonToLoanPlan(state.getJSONObject("plan")))
            "LENDING" -> database.lendingPlanDao().upsert(trashJsonToLendingPlan(state.getJSONObject("plan")))
            "REIMBURSEMENT" -> {
                val expenses = state.getJSONArray("expenses")
                for (index in 0 until expenses.length()) {
                    val expenseState = expenses.getJSONObject(index)
                    val id = expenseState.getString("id")
                    requireNotNull(database.transactionDao().findById(id)) { "关联垫付流水不存在，无法恢复" }
                    database.transactionDao().updateReimbursed(id, expenseState.getLong("reimbursedCents"))
                }
            }
        }
    }

    suspend fun permanentlyDeleteTransactionFromTrash(id: String) {
        database.withTransaction {
            val tx = requireNotNull(database.transactionDao().findIncludingDeleted(id)) { "垃圾箱中没有这条流水" }
            require(tx.deletedAt != null) { "只能永久删除垃圾箱中的流水" }
            permanentlyDeleteTrashedTransaction(tx)
        }
    }

    suspend fun purgeExpiredTransactionTrash(now: Long = System.currentTimeMillis()): Int =
        database.withTransaction {
            val expired = database.transactionDao().deletedAtOrBefore(now - TRANSACTION_TRASH_RETENTION_MS)
            expired.forEach { permanentlyDeleteTrashedTransaction(it) }
            expired.size
        }

    private suspend fun permanentlyDeleteTrashedTransaction(tx: TransactionEntity) {
        if (TransactionType.valueOf(tx.type) == TransactionType.REIMBURSEMENT) {
            database.reimbursementLinkDao().deleteByReimbursement(tx.id)
        }
        evidenceRecorder.lifecycle(
            subjectType = EvidenceSubjectType.TRANSACTION,
            subjectId = tx.id,
            action = EvidenceAction.PURGED,
            payload = JSONObject().put("notificationTombstoneRetained", tx.notificationId != null)
        )
        database.transactionDao().deleteById(tx.id)
        // 原始通知继续保留在待确认箱；用户可按原始证据重新记账，稳定证据 ID 仍阻止重复插入。
    }

    /** 待确认贷款扣款：通知认领、真实还款、期次推进和状态更新必须同一事务。 */
    suspend fun confirmLoanPaymentNotification(
        notificationId: String,
        cashAccountId: String,
        planId: String,
        totalCents: Long,
        principalCents: Long,
        interestCents: Long,
        feeCents: Long,
        note: String?,
        bankBalanceCents: Long? = null,
        bankCardTail: String? = null
    ) {
        database.withTransaction {
            val notification = database.rawNotificationDao().findById(notificationId) ?: return@withTransaction
            requireEarliestNotificationFirst(notification, cashAccountId, bankBalanceCents, bankCardTail)
            if (database.rawNotificationDao().claimForConfirmation(notificationId) != 1) {
                return@withTransaction
            }
            val postedAt = notification.postedAt
            val allowAuthoritativeBalanceOverride = canUseAuthoritativeBalance(
                accountId = cashAccountId,
                bankBalanceCents = bankBalanceCents,
                bankCardTail = bankCardTail
            )
            addLoanPayment(
                cashAccountId = cashAccountId,
                planId = planId,
                totalCents = totalCents,
                principalCents = principalCents,
                interestCents = interestCents,
                feeCents = feeCents,
                note = note,
                occurredAt = postedAt,
                notificationId = notificationId,
                allowAuthoritativeBalanceOverride = allowAuthoritativeBalanceOverride
            )
            database.rawNotificationDao().updateStatus(notificationId, "LINKED")
            if (bankBalanceCents != null && bankCardTail != null) {
                applyBankBalance(cashAccountId, bankCardTail, bankBalanceCents, postedAt)
            }
        }
    }

    /** 待确认借款到账：只能挂到明确的借款计划，不能降级为普通收入。 */
    suspend fun confirmLoanDisbursementNotification(
        notificationId: String,
        cashAccountId: String,
        planId: String,
        amountCents: Long,
        note: String?,
        bankBalanceCents: Long? = null,
        bankCardTail: String? = null
    ) {
        require(amountCents > 0)
        database.withTransaction {
            val notification = database.rawNotificationDao().findById(notificationId) ?: return@withTransaction
            requireEarliestNotificationFirst(notification, cashAccountId, bankBalanceCents, bankCardTail)
            if (database.rawNotificationDao().claimForConfirmation(notificationId) != 1) {
                return@withTransaction
            }
            val plan = requireNotNull(database.loanPlanDao().findById(planId)) { "借款计划不存在" }
            val cashAccount = requireNotNull(database.accountDao().find(cashAccountId)) { "到账账户不存在" }
            require(cashAccount.type == AccountType.ASSET.name) { "借款到账必须进入资产账户" }
            val postedAt = notification.postedAt
            val allowAuthoritativeBalanceOverride = canUseAuthoritativeBalance(
                accountId = cashAccountId,
                bankBalanceCents = bankBalanceCents,
                bankCardTail = bankCardTail
            )
            val transactionId = addTransaction(
                accountId = cashAccountId,
                amountCents = amountCents,
                type = TransactionType.LOAN_DISBURSEMENT,
                category = TransactionCategory.UNCATEGORIZED.name,
                merchant = null,
                note = note,
                occurredAt = postedAt,
                loanPlanId = plan.id,
                notificationId = notificationId,
                allowAuthoritativeBalanceOverride = allowAuthoritativeBalanceOverride
            )
            activateLoanPlanFromDisbursement(plan, transactionId, amountCents, postedAt)
            database.rawNotificationDao().updateStatus(notificationId, "LINKED")
            if (bankBalanceCents != null && bankCardTail != null) {
                applyBankBalance(cashAccountId, bankCardTail, bankBalanceCents, postedAt)
            }
        }
    }

    private suspend fun requireTransactionNotAllocated(transactionId: String) {
        require(database.creditCardInstallmentAllocationDao().countByTransaction(transactionId) == 0) {
            "该信用卡消费已用于事后分期并进入审计链，原消费不可直接修改或删除"
        }
    }

    suspend fun addTransaction(
        accountId: String,
        amountCents: Long,
        type: TransactionType,
        category: String,
        merchant: String?,
        note: String?,
        occurredAt: Long = System.currentTimeMillis(),
        isReimbursable: Boolean = false,
        recurringRuleId: String? = null,
        principalCents: Long = 0,
        interestCents: Long = 0,
        feeCents: Long = 0,
        loanPlanId: String? = null,
        refundOfId: String? = null,
        necessity: Boolean? = null,
        channel: String? = null,
        notificationId: String? = null,
        lendingPlanId: String? = null,
        evidenceSources: List<EvidenceSourceRef>? = null,
        evidenceGroupId: String? = null,
        evidenceRole: String = "POSTED_EVENT",
        allowAuthoritativeBalanceOverride: Boolean = false
    ): String {
        require(amountCents > 0)
        return database.withTransaction {
            val account = requireNotNull(database.accountDao().find(accountId))
            require(database.lendingPlanDao().findByReceivableAccount(account.id) == null) {
                "出借计划的应收账户不能用于普通记账，请使用借出或收回入口"
            }
            validateRefundLink(
                type = type,
                refundOfId = refundOfId,
                amountCents = amountCents,
                accountId = account.id,
                merchant = merchant,
                channel = channel,
                occurredAt = occurredAt
            )
            if (!allowAuthoritativeBalanceOverride) {
                requireSufficientAssetBalance(
                    accountId = account.id,
                    amountCents = amountCents,
                    type = type,
                    occurredAt = occurredAt
                )
            }
            // 退款只使用用户在编辑器里明确选中的原消费；不再按金额猜测，避免冲错分类和预算。
            val linkedRefund = refundOfId.takeIf { type == TransactionType.REFUND }
            val transactionId = UUID.randomUUID().toString()
            database.transactionDao().insert(
                TransactionEntity(
                    id = transactionId,
                    accountId = accountId,
                    amountCents = amountCents,
                    type = type.name,
                    category = category,
                    occurredAt = occurredAt,
                    merchant = merchant?.trim()?.takeIf { it.isNotEmpty() },
                    note = note?.trim()?.takeIf { it.isNotEmpty() },
                    refundOfId = linkedRefund,
                    necessity = necessity,
                    channel = channel?.trim()?.takeIf { it.isNotEmpty() },
                    notificationId = notificationId,
                    isReimbursable = isReimbursable,
                    recurringRuleId = recurringRuleId,
                    principalCents = principalCents,
                    interestCents = interestCents,
                    feeCents = feeCents,
                    loanPlanId = loanPlanId,
                    lendingPlanId = lendingPlanId
                )
            )
            val sources = evidenceSources ?: when {
                notificationId != null -> listOf(EvidenceSourceRef(EvidenceSourceType.RAW_NOTIFICATION, notificationId))
                recurringRuleId != null -> listOf(EvidenceSourceRef(EvidenceSourceType.RECURRING_RULE, recurringRuleId))
                else -> listOf(EvidenceSourceRef(EvidenceSourceType.MANUAL_ENTRY, "manual:transaction:$transactionId"))
            }
            val groupId = evidenceGroupId ?: when {
                notificationId != null -> "notification:$notificationId"
                recurringRuleId != null -> "recurring:$recurringRuleId:$transactionId"
                else -> "manual:transaction:$transactionId"
            }
            evidenceRecorder.link(
                groupId = groupId,
                subjectType = EvidenceSubjectType.TRANSACTION,
                subjectId = transactionId,
                subjectRole = evidenceRole,
                sources = sources,
                linkedAt = occurredAt
            )
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.TRANSACTION,
                subjectId = transactionId,
                action = EvidenceAction.CREATED,
                occurredAt = occurredAt,
                payload = JSONObject()
                    .put("type", type.name)
                    .put("amountCents", amountCents)
                    .put("accountId", accountId)
                    .put("principalCents", principalCents)
                    .put("interestCents", interestCents)
                    .put("feeCents", feeCents)
                    .put("lendingPlanId", lendingPlanId ?: JSONObject.NULL)
            )
            recomputeBalance(accountId)
            transactionId
        }
    }

    /**
     * 资产账户不能在当前余额基础上继续产生负数余额。
     * 负债账户（信用卡/贷款）允许支出增加欠款；早于最近余额检查点的历史事件已包含在锚点中，
     * 修改它不会再次改变当前可用余额。编辑同一事件时先撤销旧事件对当前余额的影响，再校验新值。
     */
    private suspend fun requireSufficientAssetBalance(
        accountId: String,
        amountCents: Long,
        type: TransactionType,
        occurredAt: Long,
        replacing: TransactionEntity? = null
    ) {
        val account = requireNotNull(database.accountDao().find(accountId)) { "资金账户不存在" }
        if (account.type != AccountType.ASSET.name) return
        val checkpoint = database.balanceCheckpointDao().latestFor(accountId)
        fun effectiveDelta(txType: TransactionType, cents: Long, at: Long): Long =
            if (checkpoint == null || at > checkpoint.checkedAt) {
                BalanceMath.transactionDelta(AccountType.ASSET, txType, cents)
            } else 0L

        val availableBefore = account.balanceCents - (replacing
            ?.takeIf { it.accountId == accountId }
            ?.let { effectiveDelta(TransactionType.valueOf(it.type), it.amountCents, it.occurredAt) }
            ?: 0L)
        val proposedDelta = effectiveDelta(type, amountCents, occurredAt)
        val projected = availableBefore + proposedDelta
        require(projected >= 0L) {
            val shortfall = -projected
            "资产账户余额不足，还差 ${shortfall / 100}.${(shortfall % 100).toString().padStart(2, '0')} 元"
        }
    }

    /** 退款关联是写入层不变量：只能退同账户/渠道/商户的有效支出，且不能超过剩余可退额。 */
    private suspend fun validateRefundLink(
        type: TransactionType,
        refundOfId: String?,
        amountCents: Long,
        accountId: String,
        merchant: String?,
        channel: String?,
        occurredAt: Long,
        replacing: TransactionEntity? = null
    ) {
        if (type != TransactionType.REFUND || refundOfId == null) return
        val source = requireNotNull(database.transactionDao().findById(refundOfId)) { "原消费流水不存在或已删除" }
        require(source.id != replacing?.id) { "退款不能关联自身" }
        require(source.type == TransactionType.EXPENSE.name) { "退款只能关联支出流水" }
        require(source.accountId == accountId) { "退款必须回到原支出账户" }
        val sourceChannel = source.channel?.trim().orEmpty()
        val refundChannel = channel?.trim().orEmpty()
        require(sourceChannel.isNotEmpty() && sourceChannel.equals(refundChannel, ignoreCase = true)) {
            "退款支付渠道必须与原支出一致"
        }
        val sourceMerchant = source.merchant?.trim().orEmpty()
        val refundMerchant = merchant?.trim().orEmpty()
        require(sourceMerchant.isNotEmpty() && sourceMerchant.equals(refundMerchant, ignoreCase = true)) {
            "退款商户必须与原支出一致"
        }
        require(occurredAt >= source.occurredAt) { "退款时间不能早于原支出" }
        val refunded = database.transactionDao().allIncludingDeleted()
            .asSequence()
            .filter { it.deletedAt == null && it.id != replacing?.id && it.refundOfId == source.id }
            .sumOf { it.amountCents }
        require(refunded + amountCents <= source.amountCents) { "退款金额超过原消费剩余可退额度" }
    }

    /**
     * 报销到账（REQ 报销 §3-4）：加实际资金账户余额、关联勾选的垫付消费。
     * 一笔报销款按勾选顺序覆盖多笔垫付，最后一笔可部分覆盖；
     * 到账金额与勾选合计不一致时保留未报销差额（部分报销）。
     */
    suspend fun addReimbursement(
        accountId: String,
        amountCents: Long,
        note: String?,
        occurredAt: Long = System.currentTimeMillis(),
        expenseIds: List<String>,
        source: String? = null,
        notificationId: String? = null
    ) {
        require(amountCents > 0)
        database.withTransaction {
            val expenses = expenseIds.distinct()
                .map { requireNotNull(database.transactionDao().findById(it)) }
                .filter {
                    it.type == TransactionType.EXPENSE.name &&
                        it.isReimbursable &&
                        it.reimbursedCents < it.amountCents
                }
            // 只能覆盖每笔垫付尚未报销的余额；重复报销不得让 reimbursedCents 超过原消费。
            val covers = ReimbursementSplit.cover(
                expenses.map { (it.amountCents - it.reimbursedCents).coerceAtLeast(0L) },
                amountCents
            )
            val txId = UUID.randomUUID().toString()
            database.transactionDao().insert(
                TransactionEntity(
                    id = txId,
                    accountId = accountId,
                    amountCents = amountCents,
                    type = TransactionType.REIMBURSEMENT.name,
                    category = TransactionCategory.UNCATEGORIZED.name,
                    occurredAt = occurredAt,
                    merchant = source?.trim()?.takeIf { it.isNotEmpty() },
                    note = note?.trim()?.takeIf { it.isNotEmpty() },
                    notificationId = notificationId
                )
            )
            val evidenceSource = notificationId?.let {
                EvidenceSourceRef(EvidenceSourceType.RAW_NOTIFICATION, it)
            } ?: EvidenceSourceRef(EvidenceSourceType.MANUAL_ENTRY, "manual:transaction:$txId")
            evidenceRecorder.link(
                groupId = notificationId?.let { "notification:$it" } ?: "manual:transaction:$txId",
                subjectType = EvidenceSubjectType.TRANSACTION,
                subjectId = txId,
                subjectRole = "REIMBURSEMENT_RECEIPT",
                sources = listOf(evidenceSource),
                linkedAt = occurredAt
            )
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.TRANSACTION,
                subjectId = txId,
                action = EvidenceAction.CREATED,
                occurredAt = occurredAt,
                payload = JSONObject().put("type", TransactionType.REIMBURSEMENT.name).put("amountCents", amountCents)
            )
            expenses.zip(covers).forEach { (expense, cover) ->
                if (cover > 0) {
                    database.reimbursementLinkDao().insert(ReimbursementLinkEntity(txId, expense.id, cover))
                    database.transactionDao().updateReimbursed(expense.id, expense.reimbursedCents + cover)
                }
            }
            recomputeBalance(accountId)
        }
    }

    suspend fun reimbursementLinks(reimbursementId: String): List<ReimbursementLinkEntity> =
        database.reimbursementLinkDao().findByReimbursement(reimbursementId)

    suspend fun reimbursementLinksForExpense(expenseId: String): List<ReimbursementLinkEntity> =
        database.reimbursementLinkDao().findByExpense(expenseId)

    /** 修改报销到账：旧关联回退与新关联建立必须处于同一事务，避免重复冲减。 */
    suspend fun updateReimbursement(
        id: String,
        accountId: String,
        amountCents: Long,
        source: String? = null,
        note: String?,
        occurredAt: Long,
        expenseIds: List<String>
    ) {
        require(amountCents > 0L)
        database.withTransaction {
            val old = requireNotNull(database.transactionDao().findById(id))
            require(old.type == TransactionType.REIMBURSEMENT.name)

            val oldLinks = database.reimbursementLinkDao().findByReimbursement(id)
            val oldCoveredByExpense = oldLinks.associate { it.expenseTxId to it.coveredCents }
            oldLinks.forEach { link ->
                database.transactionDao().findById(link.expenseTxId)?.let { expense ->
                    database.transactionDao().updateReimbursed(
                        expense.id,
                        (expense.reimbursedCents - link.coveredCents).coerceAtLeast(0L)
                    )
                }
            }
            database.reimbursementLinkDao().deleteByReimbursement(id)

            val expenses = expenseIds.distinct().map {
                requireNotNull(database.transactionDao().findById(it))
            }
            if (expenses.isNotEmpty()) {
                require(expenses.all {
                    it.type == TransactionType.EXPENSE.name &&
                        (it.isReimbursable || oldCoveredByExpense.getOrDefault(it.id, 0L) > 0L)
                })
                val available = expenses.map { expense ->
                    if (expense.isReimbursable) {
                        (expense.amountCents - expense.reimbursedCents).coerceAtLeast(0L)
                    } else {
                        oldCoveredByExpense.getOrDefault(expense.id, 0L)
                    }
                }
                require(available.all { it > 0L } && available.sum() == amountCents) {
                    "报销金额需与勾选合计一致"
                }
                expenses.zip(available).forEach { (expense, covered) ->
                    database.transactionDao().updateReimbursed(expense.id, expense.reimbursedCents + covered)
                    database.reimbursementLinkDao().insert(ReimbursementLinkEntity(id, expense.id, covered))
                }
            }

            database.transactionDao().update(
                id = id,
                amountCents = amountCents,
                type = TransactionType.REIMBURSEMENT.name,
                category = old.category,
                merchant = source?.trim()?.takeIf { it.isNotEmpty() },
                note = note?.trim()?.takeIf { it.isNotEmpty() },
                accountId = accountId,
                occurredAt = occurredAt,
                necessity = old.necessity,
                channel = old.channel
            )
            recomputeBalance(old.accountId)
            if (accountId != old.accountId) recomputeBalance(accountId)
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.TRANSACTION,
                subjectId = id,
                action = EvidenceAction.EDITED,
                payload = JSONObject()
                    .put("role", "REIMBURSEMENT_RECEIPT")
                    .put("beforeAmountCents", old.amountCents)
                    .put("afterAmountCents", amountCents)
                    .put("linkedExpenseCount", expenses.size)
            )
        }
    }

    suspend fun addTransfer(
        fromAccountId: String,
        toAccountId: String,
        amountCents: Long,
        note: String?,
        occurredAt: Long,
        lendingPlanId: String? = null,
        lendingRole: String? = null,
        evidenceSources: List<EvidenceSourceRef>? = null,
        evidenceGroupId: String? = null,
        evidenceRole: String = "POSTED_EVENT",
        allowAuthoritativeBalanceOverride: Boolean = false
    ): String {
        require(amountCents > 0)
        require(fromAccountId != toAccountId)
        return database.withTransaction {
            val from = requireNotNull(database.accountDao().find(fromAccountId))
            requireNotNull(database.accountDao().find(toAccountId))
            if (lendingPlanId == null) {
                require(
                    database.lendingPlanDao().findByReceivableAccount(fromAccountId) == null &&
                        database.lendingPlanDao().findByReceivableAccount(toAccountId) == null
                ) { "出借计划的应收账户不能用于普通划转，请使用借出或收回入口" }
            }
            require(from.type == AccountType.ASSET.name) { "划转必须从资产账户转出" }
            if (!allowAuthoritativeBalanceOverride) {
                requireSufficientAssetBalance(
                    accountId = fromAccountId,
                    amountCents = amountCents,
                    type = TransactionType.EXPENSE,
                    occurredAt = occurredAt
                )
            }
            val transferId = UUID.randomUUID().toString()
            database.transferDao().insert(
                TransferEntity(
                    id = transferId,
                    fromAccountId = fromAccountId,
                    toAccountId = toAccountId,
                    amountCents = amountCents,
                    occurredAt = occurredAt,
                    note = note?.trim()?.takeIf { it.isNotEmpty() },
                    lendingPlanId = lendingPlanId,
                    lendingRole = lendingRole
                )
            )
            val sources = evidenceSources
                ?: listOf(EvidenceSourceRef(EvidenceSourceType.MANUAL_ENTRY, "manual:transfer:$transferId"))
            evidenceRecorder.link(
                groupId = evidenceGroupId ?: "manual:transfer:$transferId",
                subjectType = EvidenceSubjectType.TRANSFER,
                subjectId = transferId,
                subjectRole = evidenceRole,
                sources = sources,
                linkedAt = occurredAt
            )
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.TRANSFER,
                subjectId = transferId,
                action = EvidenceAction.CREATED,
                occurredAt = occurredAt,
                payload = JSONObject()
                    .put("fromAccountId", fromAccountId)
                    .put("toAccountId", toAccountId)
                    .put("amountCents", amountCents)
                    .put("lendingPlanId", lendingPlanId ?: JSONObject.NULL)
                    .put("lendingRole", lendingRole ?: JSONObject.NULL)
            )
            cardInstallmentService.autoMatchTransfer(transferId)
            recomputeBalance(fromAccountId)
            recomputeBalance(toAccountId)
            transferId
        }
    }

    suspend fun previewTransferDeletion(id: String): List<TransferDeletionAccountPreview> {
        val transfer = database.transferDao().findById(id) ?: return emptyList()
        return listOf(transfer.fromAccountId, transfer.toAccountId).distinct().mapNotNull { accountId ->
            val account = database.accountDao().find(accountId) ?: return@mapNotNull null
            val checkpoint = database.balanceCheckpointDao().latestFor(accountId)
            val accountType = AccountType.valueOf(account.type)
            val delta = if (checkpoint == null || transfer.occurredAt > checkpoint.checkedAt) {
                if (accountId == transfer.toAccountId) {
                    BalanceMath.transferInDelta(accountType, transfer.amountCents)
                } else {
                    BalanceMath.transferOutDelta(accountType, transfer.amountCents)
                }
            } else 0L
            TransferDeletionAccountPreview(
                accountId = accountId,
                currentBalanceCents = account.balanceCents,
                projectedBalanceCents = account.balanceCents - delta,
                balanceStatus = account.balanceStatus
            )
        }
    }

    /** 只读划转详情：基于发生时刻之前的检查点和事件重放，无法消除同刻歧义时返回 null。 */
    suspend fun transferBalanceDetail(id: String): TransferBalanceDetail? {
        val transfer = database.transferDao().findById(id) ?: return null
        suspend fun balanceAt(accountId: String, before: Boolean): Long? {
            val account = database.accountDao().find(accountId) ?: return null
            val checkpoints = database.balanceCheckpointDao().allFor(accountId)
            if (checkpoints.any { it.checkedAt == transfer.occurredAt }) return null
            val checkpoint = checkpoints.filter { it.checkedAt < transfer.occurredAt }.maxByOrNull { it.checkedAt }
            val base = checkpoint?.balanceCents ?: 0L
            val afterCheckpoint = checkpoint?.checkedAt ?: Long.MIN_VALUE
            val txs = database.transactionDao().all().filter {
                it.accountId == accountId && it.occurredAt > afterCheckpoint && it.occurredAt < transfer.occurredAt
            }
            val otherTransfers = database.transferDao().all().filter {
                it.id != transfer.id &&
                    (it.fromAccountId == accountId || it.toAccountId == accountId) &&
                    it.occurredAt > afterCheckpoint && it.occurredAt < transfer.occurredAt
            }
            if (database.transactionDao().all().any { it.accountId == accountId && it.occurredAt == transfer.occurredAt } ||
                database.transferDao().all().any {
                    it.id != transfer.id && it.occurredAt == transfer.occurredAt &&
                        (it.fromAccountId == accountId || it.toAccountId == accountId)
                }) return null
            val type = AccountType.valueOf(account.type)
            val beforeCents = base + txs.sumOf {
                BalanceMath.transactionDelta(type, TransactionType.valueOf(it.type), it.amountCents)
            } + otherTransfers.sumOf {
                when {
                    it.fromAccountId == accountId -> BalanceMath.transferOutDelta(type, it.amountCents)
                    else -> BalanceMath.transferInDelta(type, it.amountCents)
                }
            }
            if (before) return beforeCents
            val delta = when {
                transfer.fromAccountId == accountId -> BalanceMath.transferOutDelta(type, transfer.amountCents)
                else -> BalanceMath.transferInDelta(type, transfer.amountCents)
            }
            return beforeCents + delta
        }
        return TransferBalanceDetail(
            transfer = transfer,
            fromBeforeCents = balanceAt(transfer.fromAccountId, true),
            fromAfterCents = balanceAt(transfer.fromAccountId, false),
            toBeforeCents = balanceAt(transfer.toAccountId, true),
            toAfterCents = balanceAt(transfer.toAccountId, false)
        )
    }

    /** 删除划转采用软删除：先反向撤销信用卡匹配，再重算两端账户；七天后才物理清除。 */
    suspend fun deleteTransfer(id: String, deletedAt: Long = System.currentTimeMillis()) {
        database.withTransaction {
            val tf = database.transferDao().findById(id) ?: return@withTransaction
            val before = captureTransferEffectState(id)
            val notificationIds = database.ledgerEvidenceLinkDao()
                .findBySubject(EvidenceSubjectType.TRANSFER, id)
                .filter { it.sourceType == EvidenceSourceType.RAW_NOTIFICATION }
                .map { it.sourceId }
                .distinct()
            val feeTransactions = database.transactionDao().allIncludingDeleted().filter {
                it.deletedAt == null &&
                    it.type == TransactionType.FEE.name &&
                    it.accountId == tf.fromAccountId &&
                    it.occurredAt == tf.occurredAt &&
                    it.notificationId in notificationIds
            }
            cardInstallmentService.reverseTransferMatches(id)
            rollbackLendingTransferEffects(tf)
            val after = captureTransferEffectState(id)
            val trashContext = JSONObject()
                .put("before", JSONObject(before))
                .put("after", JSONObject(after))
                .put("feeTransactionIds", JSONArray(feeTransactions.map { it.id }))
            check(database.transferDao().moveToTrash(id, deletedAt, trashContext.toString()) == 1) {
                "划转状态已变化，请刷新后重试"
            }
            feeTransactions.forEach { fee ->
                check(database.transactionDao().moveToTrash(fee.id, deletedAt, null) == 1) {
                    "划转手续费状态已变化，请刷新后重试"
                }
            }
            updateRawNotificationLifecycleForTransfer(
                transferId = tf.id,
                status = "IGNORED",
                note = "关联划转已移入垃圾箱；保留忽略墓碑，防止通知补扫复活"
            )
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.TRANSFER,
                subjectId = tf.id,
                action = EvidenceAction.TRASHED,
                occurredAt = deletedAt,
                payload = JSONObject().put("amountCents", tf.amountCents)
            )
            tf.lendingPlanId?.let { planId ->
                evidenceRecorder.lifecycle(
                    subjectType = EvidenceSubjectType.LENDING_PLAN,
                    subjectId = planId,
                    action = EvidenceAction.EFFECT_REVERSED,
                    occurredAt = deletedAt,
                    payload = JSONObject()
                        .put("transferId", id)
                        .put("lendingRole", tf.lendingRole ?: JSONObject.NULL)
                )
            }
            recomputeBalance(tf.fromAccountId)
            recomputeBalance(tf.toAccountId)
        }
    }

    suspend fun restoreTransferFromTrash(id: String) {
        database.withTransaction {
            val tf = requireNotNull(database.transferDao().findIncludingDeleted(id)) { "垃圾箱中没有这条划转" }
            require(tf.deletedAt != null) { "这条划转不在垃圾箱" }
            // 删除后同一原始通知若已被重新确认生成新的活动划转，旧划转不能再次恢复，
            // 否则会让同一笔资金在当前时间线出现双记账。
            val sourceIds = database.ledgerEvidenceLinkDao()
                .findBySubject(EvidenceSubjectType.TRANSFER, id)
                .filter { it.sourceType == EvidenceSourceType.RAW_NOTIFICATION }
                .map { it.sourceId }
                .distinct()
            require(sourceIds.none { sourceId ->
                database.ledgerEvidenceLinkDao()
                    .findBySource(EvidenceSourceType.RAW_NOTIFICATION, sourceId)
                    .any { link ->
                        link.subjectType == EvidenceSubjectType.TRANSFER &&
                            link.subjectId != id &&
                            database.transferDao().findById(link.subjectId) != null
                    }
            }) { "同一通知已重新记账，不能恢复旧垃圾箱划转" }
            val context = requireNotNull(tf.trashContextJson) { "缺少划转联动快照，无法安全恢复" }
            val root = JSONObject(context)
            val current = captureTransferEffectState(id)
            require(jsonStructurallyEquals(JSONObject(current), root.getJSONObject("after"))) {
                "删除后信用卡分期状态已变化，不能强行恢复这条划转"
            }
            applyTransferEffectState(root.getJSONObject("before").toString())
            check(database.transferDao().restoreFromTrash(id) == 1) { "划转状态已变化，请刷新后重试" }
            if (root.has("feeTransactionIds")) {
                val fees = root.getJSONArray("feeTransactionIds")
                for (index in 0 until fees.length()) {
                    val feeId = fees.getString(index)
                    val fee = requireNotNull(database.transactionDao().findIncludingDeleted(feeId)) {
                        "关联手续费已被永久删除，不能恢复这条划转"
                    }
                    require(fee.deletedAt != null) { "关联手续费已单独恢复，请先重新删除手续费后再恢复划转" }
                    check(database.transactionDao().restoreFromTrash(feeId) == 1) {
                        "关联手续费状态已变化，请刷新后重试"
                    }
                }
            }
            updateRawNotificationLifecycleForTransfer(
                transferId = tf.id,
                status = "LINKED",
                note = "关联划转已从垃圾箱恢复"
            )
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.TRANSFER,
                subjectId = tf.id,
                action = EvidenceAction.RESTORED,
                payload = JSONObject().put("restoredIntoCurrentTimeline", true)
            )
            database.creditCardInstallmentPaymentMatchDao().findByTransfer(id)
                .filter { it.status == "AUTO_MATCHED" || it.status == "USER_CONFIRMED" }
                .map { it.planId }
                .distinct()
                .forEach { planId ->
                    evidenceRecorder.lifecycle(
                        subjectType = EvidenceSubjectType.CARD_INSTALLMENT,
                        subjectId = planId,
                        action = EvidenceAction.EFFECT_RESTORED,
                        payload = JSONObject().put("transferId", id)
                    )
                }
            tf.lendingPlanId?.let { planId ->
                evidenceRecorder.lifecycle(
                    subjectType = EvidenceSubjectType.LENDING_PLAN,
                    subjectId = planId,
                    action = EvidenceAction.EFFECT_RESTORED,
                    payload = JSONObject()
                        .put("transferId", id)
                        .put("lendingRole", tf.lendingRole ?: JSONObject.NULL)
                )
            }
            recomputeBalance(tf.fromAccountId)
            recomputeBalance(tf.toAccountId)
        }
    }

    suspend fun permanentlyDeleteTransferFromTrash(id: String) {
        database.withTransaction {
            val tf = requireNotNull(database.transferDao().findIncludingDeleted(id)) { "垃圾箱中没有这条划转" }
            require(tf.deletedAt != null) { "只能永久删除垃圾箱中的划转" }
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.TRANSFER,
                subjectId = tf.id,
                action = EvidenceAction.PURGED
            )
            database.creditCardInstallmentPaymentMatchDao().deleteByTransfer(id)
            if (tf.trashContextJson != null) {
                val fees = JSONObject(tf.trashContextJson).optJSONArray("feeTransactionIds")
                if (fees != null) for (index in 0 until fees.length()) {
                    database.transactionDao().findIncludingDeleted(fees.getString(index))?.let { fee ->
                        if (fee.deletedAt != null) database.transactionDao().deleteById(fee.id)
                    }
                }
            }
            database.transferDao().delete(id)
        }
    }

    private suspend fun updateRawNotificationLifecycleForTransfer(
        transferId: String,
        status: String,
        note: String
    ) {
        database.ledgerEvidenceLinkDao()
            .findBySubject(EvidenceSubjectType.TRANSFER, transferId)
            .asSequence()
            .filter { it.sourceType == EvidenceSourceType.RAW_NOTIFICATION }
            .map { it.sourceId }
            .distinct()
            .forEach { notificationId ->
                database.rawNotificationDao().updateStatus(notificationId, status)
                database.rawNotificationDao().updateProcessingNote(notificationId, note)
            }
    }

    suspend fun purgeExpiredTransferTrash(now: Long = System.currentTimeMillis()): Int =
        database.withTransaction {
            val expired = database.transferDao().deletedAtOrBefore(now - TRANSACTION_TRASH_RETENTION_MS)
            expired.forEach {
                evidenceRecorder.lifecycle(
                    subjectType = EvidenceSubjectType.TRANSFER,
                    subjectId = it.id,
                    action = EvidenceAction.PURGED,
                    occurredAt = now,
                    payload = JSONObject().put("expired", true)
                )
                database.creditCardInstallmentPaymentMatchDao().deleteByTransfer(it.id)
                if (it.trashContextJson != null) {
                    val fees = JSONObject(it.trashContextJson).optJSONArray("feeTransactionIds")
                    if (fees != null) for (index in 0 until fees.length()) {
                        database.transactionDao().findIncludingDeleted(fees.getString(index))?.let { fee ->
                            if (fee.deletedAt != null) database.transactionDao().deleteById(fee.id)
                        }
                    }
                }
                database.transferDao().delete(it.id)
            }
            expired.size
        }

    private suspend fun captureTransferEffectState(transferId: String): String {
        val matches = database.creditCardInstallmentPaymentMatchDao().findByTransfer(transferId)
        val planIds = matches.map { it.planId }.toSet()
        val schedules = planIds.flatMap { database.creditCardInstallmentScheduleDao().findByPlan(it) }
        val plans = planIds.mapNotNull { database.creditCardInstallmentDao().findById(it) }
        val transfer = database.transferDao().findIncludingDeleted(transferId)
        val lendingPlan = transfer?.lendingPlanId?.let { database.lendingPlanDao().findById(it) }
        return JSONObject()
            .put("matches", JSONArray().apply { matches.sortedWith(compareBy({ it.scheduleId }, { it.planId })).forEach { put(installmentMatchToTrashJson(it)) } })
            .put("schedules", JSONArray().apply { schedules.sortedBy { it.id }.forEach { put(installmentScheduleToTrashJson(it)) } })
            .put("plans", JSONArray().apply { plans.sortedBy { it.id }.forEach { put(installmentPlanToTrashJson(it)) } })
            .put("lendingPlan", lendingPlan?.let(::lendingPlanToTrashJson) ?: JSONObject.NULL)
            .toString()
    }

    private suspend fun applyTransferEffectState(state: String) {
        val root = JSONObject(state)
        if (!root.isNull("lendingPlan")) {
            database.lendingPlanDao().upsert(trashJsonToLendingPlan(root.getJSONObject("lendingPlan")))
        }
        val plans = root.getJSONArray("plans")
        for (index in 0 until plans.length()) {
            database.creditCardInstallmentDao().upsert(trashJsonToInstallmentPlan(plans.getJSONObject(index)))
        }
        val schedules = root.getJSONArray("schedules")
        val scheduleRows = buildList {
            for (index in 0 until schedules.length()) add(trashJsonToInstallmentSchedule(schedules.getJSONObject(index)))
        }
        if (scheduleRows.isNotEmpty()) database.creditCardInstallmentScheduleDao().upsertAll(scheduleRows)
        val matches = root.getJSONArray("matches")
        val matchRows = buildList {
            for (index in 0 until matches.length()) add(trashJsonToInstallmentMatch(matches.getJSONObject(index)))
        }
        if (matchRows.isNotEmpty()) database.creditCardInstallmentPaymentMatchDao().upsertAll(matchRows)
    }

    private suspend fun rollbackLendingTransferEffects(transfer: TransferEntity) {
        val planId = transfer.lendingPlanId ?: return
        val plan = requireNotNull(database.lendingPlanDao().findById(planId)) { "关联出借计划不存在" }
        when (transfer.lendingRole) {
            LendingTransferRole.DISBURSEMENT -> {
                require(plan.disbursementTransferId == transfer.id && plan.originType == LendingOriginType.DISBURSEMENT_TRANSFER) {
                    "借出划转与出借计划来源不一致"
                }
                val downstreamTransfers = database.transferDao().findActiveByLendingPlan(planId)
                    .filter { it.id != transfer.id && it.lendingRole == LendingTransferRole.PRINCIPAL_REPAYMENT }
                val downstreamInterest = database.transactionDao().findActiveByLendingPlan(planId)
                require(downstreamTransfers.isEmpty() && downstreamInterest.isEmpty()) {
                    "借出后已有本金或利息收回；请先处理后续流水，或把计划迁移为期初应收"
                }
                database.lendingPlanDao().upsert(
                    plan.copy(
                        remainingPrincipalCents = 0L,
                        status = LendingPlanStatus.PENDING_DISBURSEMENT,
                        originType = LendingOriginType.PENDING_DISBURSEMENT,
                        disbursementTransferId = null,
                        ledgerBaselinePrincipalCents = 0L,
                        ledgerBaselineInterestCents = 0L,
                        ledgerBaselineAt = transfer.occurredAt,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            LendingTransferRole.PRINCIPAL_REPAYMENT -> {
                val restoredRemaining = plan.remainingPrincipalCents + transfer.amountCents
                require(restoredRemaining <= plan.principalCents) { "恢复后的应收本金超过原始本金" }
                val reanchorAt = System.currentTimeMillis()
                database.lendingPlanDao().upsert(
                    plan.copy(
                        remainingPrincipalCents = restoredRemaining,
                        status = LendingPlanStatus.ACTIVE,
                        ledgerBaselinePrincipalCents = if (transfer.occurredAt <= plan.ledgerBaselineAt) restoredRemaining else plan.ledgerBaselinePrincipalCents,
                        ledgerBaselineAt = if (transfer.occurredAt <= plan.ledgerBaselineAt) reanchorAt else plan.ledgerBaselineAt,
                        updatedAt = reanchorAt
                    )
                )
            }
            else -> error("出借划转缺少有效角色")
        }
    }

    // ── Reimbursement ──

    val reimbursableTransactions: Flow<List<TransactionEntity>> =
        database.transactionDao().observeReimbursable()

    suspend fun toggleReimbursable(id: String, isReimbursable: Boolean) {
        database.withTransaction {
            val old = requireNotNull(database.transactionDao().findById(id)) { "流水不存在" }
            database.transactionDao().updateReimbursable(id, isReimbursable)
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.TRANSACTION,
                subjectId = id,
                action = EvidenceAction.EDITED,
                payload = JSONObject().put("field", "isReimbursable").put("before", old.isReimbursable).put("after", isReimbursable)
            )
        }
    }

    suspend fun linkToRecurringRule(transactionId: String, ruleId: String?) {
        database.withTransaction {
            val transaction = requireNotNull(database.transactionDao().findById(transactionId)) { "流水不存在" }
            if (ruleId != null) requireNotNull(database.recurringRuleDao().findById(ruleId)) { "周期规则不存在" }
            database.transactionDao().updateRecurringRuleId(transactionId, ruleId)
            if (ruleId != null) {
                evidenceRecorder.link(
                    groupId = "recurring:$ruleId:$transactionId",
                    subjectType = EvidenceSubjectType.TRANSACTION,
                    subjectId = transactionId,
                    subjectRole = "RECURRING_OCCURRENCE",
                    sources = listOf(EvidenceSourceRef(EvidenceSourceType.RECURRING_RULE, ruleId)),
                    linkedAt = transaction.occurredAt
                )
            }
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.TRANSACTION,
                subjectId = transactionId,
                action = EvidenceAction.LINKED,
                payload = JSONObject().put("recurringRuleId", ruleId ?: JSONObject.NULL)
            )
        }
    }

    suspend fun transactionsByRule(ruleId: String): List<TransactionEntity> =
        database.transactionDao().findByRecurringRule(ruleId)

    // ── Budget CRUD ──

    val budgets: Flow<List<BudgetEntity>> = database.budgetDao().observeAll()

    suspend fun saveBudget(budget: BudgetEntity) {
        database.budgetDao().upsert(budget)
    }

    suspend fun deleteBudget(id: String) {
        database.budgetDao().deleteById(id)
    }

    // ── Snapshots ──

    suspend fun saveTodaySnapshot() {
        val today = java.time.LocalDate.now().toEpochDay()
        if (database.snapshotDao().findByDay(today) != null) return
        val all = allAccounts().filter { !it.archived }
        val receivableAccountIds = database.lendingPlanDao().all().mapTo(hashSetOf()) { it.receivableAccountId }
        val assets = all.filter {
            it.type == AccountType.ASSET.name && it.id !in receivableAccountIds
        }.sumOf { it.balanceCents }
        val debts = all.filter { it.type != AccountType.ASSET.name }.sumOf { it.balanceCents }
        database.snapshotDao().upsert(
            SnapshotEntity(
                id = "snapshot_$today",
                dateEpochDay = today,
                totalAssets = assets,
                totalDebts = debts,
                netWorth = assets - debts
            )
        )
    }

    val snapshots: Flow<List<SnapshotEntity>> = database.snapshotDao().observeAll()

    // ── 账户详情（REQ 账户对账 §2/§8）：对账历史与余额调整 ──

    suspend fun checkpointsFor(accountId: String): List<BalanceCheckpointEntity> =
        database.balanceCheckpointDao().allFor(accountId)

    suspend fun adjustmentsFor(accountId: String): List<BalanceAdjustmentEntity> =
        database.balanceAdjustmentDao().allFor(accountId)

    // ── 分类库（REQ 初始分类库 §1-21）──

    val categories: Flow<List<CategoryEntity>> = database.categoryDao().observeAll()

    /**
     * 首次启动播种分类库，并对既有安装执行一次性的分类补种。
     *
     * 不能在每次启动时把所有缺失的默认分类补回来，否则用户主动删除的分类会“复活”。
     * 因此升级只补本版本新增的稳定 ID，并用版本标记保证只执行一次。
     */
    suspend fun seedDefaultCategoriesIfEmpty() {
        val existing = database.categoryDao().all()
        suspend fun seedIfMissing(kind: String, list: List<com.assetsking.ledger.CategorySeed>) {
            if (existing.any { it.kind == kind }) return
            database.categoryDao().insertAll(
                list.mapIndexed { index, s ->
                    CategoryEntity(
                        id = s.id, name = s.name, shortName = s.shortName,
                        parentId = s.parentId, iconKey = s.iconKey,
                        defaultNecessary = s.defaultNecessary,
                        kind = kind,
                        sortOrder = index, isCustom = false
                    )
                }
            )
        }
        seedIfMissing("EXPENSE", DefaultCategories.seeds)
        seedIfMissing("INCOME", DefaultCategories.incomeSeeds)

        if (prefs.getInt(CATEGORY_SEED_VERSION_KEY, 0) < CATEGORY_SEED_VERSION_EXTERNAL_CASHFLOW) {
            val upgradeIds = setOf("social-other-gift", "income-other")
            val upgrades = (DefaultCategories.seeds + DefaultCategories.incomeSeeds)
                .filter { it.id in upgradeIds }
                .mapIndexed { index, seed ->
                    CategoryEntity(
                        id = seed.id,
                        name = seed.name,
                        shortName = seed.shortName,
                        parentId = seed.parentId,
                        iconKey = seed.iconKey,
                        defaultNecessary = seed.defaultNecessary,
                        kind = seed.kind,
                        sortOrder = existing.maxOfOrNull { it.sortOrder }?.plus(index + 1) ?: index,
                        isCustom = false
                    )
                }
            database.categoryDao().insertAll(upgrades)
            prefs.edit()
                .putInt(CATEGORY_SEED_VERSION_KEY, CATEGORY_SEED_VERSION_EXTERNAL_CASHFLOW)
                .apply()
        }
    }

    suspend fun addCategory(
        name: String,
        shortName: String,
        parentId: String?,
        iconKey: String,
        defaultNecessary: Boolean?,
        kind: String = "EXPENSE"
    ): CategoryEntity {
        val maxOrder = database.categoryDao().all().maxOfOrNull { it.sortOrder } ?: 0
        val entity = CategoryEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(), shortName = shortName.trim().take(2),
            parentId = parentId, iconKey = iconKey, defaultNecessary = defaultNecessary,
            kind = kind,
            sortOrder = maxOrder + 1, isCustom = true
        )
        database.categoryDao().upsert(entity)
        return entity
    }

    /** 改名/换图标/调整归属：改名同步更新所有关联历史流水（REQ 分类§20/§22），历史统计不变口径。 */
    suspend fun updateCategory(id: String, name: String?, shortName: String?, parentId: String?, iconKey: String? = null) {
        val cat = database.categoryDao().findById(id) ?: return
        val newName = name?.trim()?.takeIf { it.isNotEmpty() } ?: cat.name
        if (newName != cat.name) {
            database.transactionDao().updateCategoryName(cat.name, newName)
        }
        database.categoryDao().upsert(
            cat.copy(name = newName, shortName = shortName?.take(2) ?: cat.shortName, parentId = parentId ?: cat.parentId, iconKey = iconKey ?: cat.iconKey)
        )
    }

    /** 已使用的分类只归档不物理删除（REQ 分类§21）；从未使用才允许真删。 */
    suspend fun deleteCategory(id: String) {
        val cat = database.categoryDao().findById(id) ?: return
        val used = database.transactionDao().countByCategory(cat.name) > 0
        if (used) {
            database.categoryDao().upsert(cat.copy(isArchived = true))
        } else {
            database.categoryDao().deleteById(id)
        }
    }

    suspend fun restoreCategory(id: String) {
        database.categoryDao().findById(id)?.let { database.categoryDao().upsert(it.copy(isArchived = false)) }
    }

    /** 一级分类拖动排序（REQ 编辑器§8）：按给定顺序重写 sortOrder，二级顺序不动。 */
    suspend fun reorderCategories(orderedIds: List<String>) {
        val byId = database.categoryDao().all().associateBy { it.id }
        orderedIds.forEachIndexed { index, id ->
            byId[id]?.let { if (it.sortOrder != index) database.categoryDao().upsert(it.copy(sortOrder = index)) }
        }
    }

    /** 合并分类（REQ 分类§23）：历史流水、学习规则迁移到目标，来源分类移除。 */
    suspend fun mergeCategory(sourceId: String, targetId: String) {
        val source = database.categoryDao().findById(sourceId) ?: return
        val target = database.categoryDao().findById(targetId) ?: return
        database.transactionDao().updateCategoryName(source.name, target.name)
        database.categoryDao().deleteById(sourceId)
    }

    // ── 交易对象库与学习规则（REQ 商户库 §3-8）：标准商户 + 原名别名 + 学习规则 ──

    val merchants: Flow<List<MerchantEntity>> = database.merchantDao().observeAll()

    /** 用户确认后学习：记住 商户→(账户,收支类型,分类) */
    suspend fun learnRule(merchant: String?, accountId: String, type: String, category: String) {
        if (merchant.isNullOrBlank()) return
        val name = merchant.trim()
        val existing = database.merchantDao().findByName(name)
        database.merchantDao().upsert(
            (existing ?: MerchantEntity(id = name)).copy(
                learnedAccountId = accountId,
                learnedType = type,
                learnedCategory = category
            )
        )
    }

    /** 匹配已学规则：标准名或别名命中即返回完整记账规则 */
    suspend fun matchLearnedRule(merchant: String?): LearnedRule? {
        if (merchant.isNullOrBlank()) return null
        val name = merchant.trim()
        val row = database.merchantDao().findByName(name)
            ?: database.merchantDao().all().firstOrNull { m ->
                parseAliases(m.aliasesJson).any { it == name }
            }
        return row?.let {
            if (it.learnedAccountId == null || it.learnedType == null) null
            else LearnedRule(it.learnedAccountId, it.learnedType, it.learnedCategory ?: "")
        }
    }

    /** 合并标准对象（REQ 商户库§8）：流水与学习规则迁移到目标，原名作为别名保留。 */
    suspend fun mergeMerchants(targetName: String, sourceNames: List<String>) {
        val target = database.merchantDao().findByName(targetName) ?: return
        val aliases = parseAliases(target.aliasesJson).toMutableSet()
        sourceNames.forEach { src ->
            if (src == targetName) return@forEach
            aliases.add(src)
            database.transactionDao().updateMerchantName(src, targetName)
            database.merchantDao().deleteByName(src)
        }
        database.merchantDao().upsert(target.copy(aliasesJson = JSONArray(aliases.toList()).toString()))
    }

    /** 删除商户映射规则，不改历史流水；下次确认同名商户时可重新学习。 */
    suspend fun deleteMerchantMapping(name: String) {
        database.merchantDao().deleteByName(name.trim())
    }

    private fun parseAliases(json: String): List<String> =
        runCatching {
            JSONArray(json).let { arr -> (0 until arr.length()).map { arr.getString(it) } }
        }.getOrDefault(emptyList())

    // ── Recurring Rules ──

    val recurringRules: Flow<List<RecurringRuleEntity>> = database.recurringRuleDao().observeAll()

    suspend fun saveRecurringRule(rule: RecurringRuleEntity) {
        database.withTransaction {
            val existing = database.recurringRuleDao().findById(rule.id)
            val occurredAt = System.currentTimeMillis()
            database.recurringRuleDao().upsert(rule)
            if (existing == null) {
                evidenceRecorder.link(
                    groupId = "manual:recurring-rule:${rule.id}",
                    subjectType = EvidenceSubjectType.RECURRING_RULE,
                    subjectId = rule.id,
                    subjectRole = "RULE_ORIGIN",
                    sources = listOf(EvidenceSourceRef(EvidenceSourceType.MANUAL_ENTRY, "manual:recurring-rule:${rule.id}")),
                    linkedAt = occurredAt
                )
            }
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.RECURRING_RULE,
                subjectId = rule.id,
                action = if (existing == null) EvidenceAction.CREATED else EvidenceAction.EDITED,
                occurredAt = occurredAt,
                payload = JSONObject()
                    .put("accountId", rule.accountId)
                    .put("amountCents", rule.amountCents)
                    .put("nextRunAt", rule.nextRunAt)
            )
        }
    }

    suspend fun deleteRecurringRule(id: String) {
        database.withTransaction {
            requireNotNull(database.recurringRuleDao().findById(id)) { "周期规则不存在" }
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.RECURRING_RULE,
                subjectId = id,
                action = EvidenceAction.PURGED,
                payload = JSONObject().put("historicalOccurrencesRetained", true)
            )
            database.recurringRuleDao().deleteById(id)
        }
    }

    /**
     * 处理到期的周期性账单：只认领真实流水并推进下次日期，绝不自动造消费。
     * 找不到真实扣款时保留已到期的 nextRunAt，账单页据此显示“待核实”；
     * 用户确认通知或手工关联后，下次处理才会推进规则。
     */
    suspend fun processRecurring(): Int {
        val now = System.currentTimeMillis()
        val due = database.recurringRuleDao().observeActive().let { it.first() }
            .filter { it.nextRunAt <= now }
        if (due.isEmpty()) return 0

        var linked = 0
        database.withTransaction {
            for (rule in due) {
                var cursor = rule.nextRunAt
                while (cursor <= now) {
                    val existing = findRealChargeFor(rule, cursor)
                    if (existing != null) {
                        if (existing.recurringRuleId == null) {
                            database.transactionDao().updateRecurringRuleId(existing.id, rule.id)
                            evidenceRecorder.link(
                                groupId = "recurring:${rule.id}:${existing.id}",
                                subjectType = EvidenceSubjectType.TRANSACTION,
                                subjectId = existing.id,
                                subjectRole = "RECURRING_OCCURRENCE",
                                sources = listOf(EvidenceSourceRef(EvidenceSourceType.RECURRING_RULE, rule.id)),
                                linkedAt = existing.occurredAt
                            )
                            evidenceRecorder.lifecycle(
                                subjectType = EvidenceSubjectType.TRANSACTION,
                                subjectId = existing.id,
                                action = EvidenceAction.LINKED,
                                payload = JSONObject().put("recurringRuleId", rule.id)
                            )
                            linked++
                        }
                        cursor = nextRun(cursor, rule.interval)
                    } else {
                        // 未发生或尚未捕获都不能假定已经扣款。停在最早未核实期次，
                        // 避免余额、预算和统计被一笔“计划”污染。
                        break
                    }
                }
                if (cursor != rule.nextRunAt) {
                    database.recurringRuleDao().upsert(rule.copy(nextRunAt = cursor))
                }
            }
        }
        return linked
    }

    /**
     * 该周期内是否已经有一笔真实扣款（通知抓的或手工记的）。
     * 匹配条件：同商户 + 同类型 + 金额相差 15% 以内 + 应扣日前后 5 天，且还没被别的规则认领。
     */
    /**
     * 在所有进行中的贷款计划里匹配扣款对应的期次（REQ 贷款页 §6）。
     * 评分 = 金额差 × 100000 + 日期差：金额完全一致优先，其次日期接近。
     * 编辑器用它给「贷款还款」预填计划与明细。
     */
    suspend fun suggestLoanMatch(
        amountCents: Long,
        postedAt: Long
    ): Pair<LoanPlanEntity, com.assetsking.model.LoanInstallment>? {
        val atDay = java.time.Instant.ofEpochMilli(postedAt)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
        return database.loanPlanDao().observeAll().first()
            .filter { it.status == "ACTIVE" }
            .mapNotNull { plan ->
                InstallmentMatcher.match(jsonToInstallments(plan.installmentsJson), amountCents, atDay)
                    ?.let { plan to it }
            }
            .minByOrNull { (_, inst) ->
                kotlin.math.abs(inst.total.cents - amountCents) * 100_000L +
                    kotlin.math.abs(inst.dueDateEpochDay - atDay)
            }
    }

    /** 把匹配到的具体期次标为已还并扣减剩余本金（不按「最早优先」——通知对应哪期就标哪期）。 */
    private suspend fun markInstallmentPaid(
        plan: LoanPlanEntity,
        number: Int,
        principalCents: Long
    ) {
        val insts = jsonToInstallments(plan.installmentsJson).map { inst ->
            if (inst.number == number && inst.status != InstallmentStatus.PAID) {
                inst.copy(status = InstallmentStatus.PAID)
            } else inst
        }
        database.loanPlanDao().upsert(
            plan.copy(
                remainingPrincipalCents = maxOf(0, remainingEffective(plan) - principalCents),
                installmentsJson = installmentsToJson(insts)
            )
        )
    }

    private suspend fun findRealChargeFor(rule: RecurringRuleEntity, dueAt: Long): TransactionEntity? {
        val merchant = rule.merchant?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val window = 5L * 24 * 60 * 60 * 1000
        val inWindow = database.transactionDao().findInRange(dueAt - window, dueAt + window)
        // 通知确认时可能已经唯一匹配并挂上规则；它同样证明本期真实扣款已发生，必须推进到下期。
        inWindow.firstOrNull {
            it.recurringRuleId == rule.id && it.type == rule.type && it.accountId == rule.accountId
        }?.let { return it }
        val candidates = inWindow
            .filter {
                it.recurringRuleId == null &&
                    it.type == rule.type &&
                    it.accountId == rule.accountId &&
                    it.merchant?.trim() == merchant &&
                    kotlin.math.abs(it.amountCents - rule.amountCents) * 100 <= rule.amountCents * 15
            }
        for (candidate in candidates) {
            val type = runCatching { TransactionType.valueOf(candidate.type) }.getOrNull() ?: continue
            if (uniqueRecurringRuleFor(
                    type = type,
                    accountId = candidate.accountId,
                    amountCents = candidate.amountCents,
                    merchant = candidate.merchant,
                    postedAt = candidate.occurredAt
                )?.id == rule.id
            ) {
                return candidate
            }
        }
        return null
    }

    private fun nextRun(from: Long, interval: String): Long {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = from }
        when (interval) {
            "DAILY" -> cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            "WEEKLY" -> cal.add(java.util.Calendar.WEEK_OF_YEAR, 1)
            "MONTHLY" -> cal.add(java.util.Calendar.MONTH, 1)
            "QUARTERLY" -> cal.add(java.util.Calendar.MONTH, 3)  // REQ 导航§10：每季度
            "YEARLY" -> cal.add(java.util.Calendar.YEAR, 1)
        }
        return cal.timeInMillis
    }

    // ── Loan Plan CRUD ──

    val loanPlans: Flow<List<LoanPlanEntity>> = database.loanPlanDao().observeAll()

    suspend fun saveLoanPlan(plan: LoanPlanEntity) {
        database.withTransaction {
            val existing = database.loanPlanDao().findById(plan.id)
            val now = System.currentTimeMillis()
            val normalizedOrigin = when (plan.originType) {
                "OPENING_BALANCE" -> plan.copy(status = if (plan.status == "PAID_OFF") "PAID_OFF" else "ACTIVE", disbursementTransactionId = null)
                "PENDING_DISBURSEMENT" -> plan.copy(status = "PENDING_DISBURSEMENT", disbursementTransactionId = null)
                "DISBURSEMENT_EVENT" -> {
                    require(!plan.disbursementTransactionId.isNullOrBlank()) { "真实放款计划必须关联到账流水" }
                    plan.copy(status = if (plan.status == "PAID_OFF") "PAID_OFF" else "ACTIVE")
                }
                else -> error("未知贷款来源：${plan.originType}")
            }
            val effectiveRemaining = remainingEffective(normalizedOrigin)
            val resetsBaseline = existing == null ||
                existing.originType != normalizedOrigin.originType ||
                existing.remainingPrincipalCents != effectiveRemaining
            val normalized = normalizedOrigin.copy(
                remainingPrincipalCents = effectiveRemaining,
                ledgerBaselinePrincipalCents = when {
                    normalizedOrigin.originType == "PENDING_DISBURSEMENT" -> 0L
                    resetsBaseline -> effectiveRemaining
                    else -> existing!!.ledgerBaselinePrincipalCents
                },
                ledgerBaselineAt = if (resetsBaseline) now else existing!!.ledgerBaselineAt
            )
            database.loanPlanDao().upsert(normalized)
            if (existing == null) {
                val source = if (normalized.originType == "OPENING_BALANCE") {
                    EvidenceSourceRef(EvidenceSourceType.OPENING_BALANCE, "opening:loan:${normalized.id}")
                } else {
                    EvidenceSourceRef(EvidenceSourceType.MANUAL_ENTRY, "manual:loan-contract:${normalized.id}")
                }
                evidenceRecorder.link(
                    groupId = source.id,
                    subjectType = EvidenceSubjectType.LOAN_PLAN,
                    subjectId = normalized.id,
                    subjectRole = "PLAN_ORIGIN",
                    sources = listOf(source),
                    linkedAt = normalized.startDateEpochDay * 86_400_000L
                )
            }
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.LOAN_PLAN,
                subjectId = normalized.id,
                action = if (existing == null) EvidenceAction.CREATED else EvidenceAction.EDITED,
                payload = JSONObject()
                    .put("originType", normalized.originType)
                    .put("status", normalized.status)
                    .put("principalCents", normalized.principalCents)
                    .put("remainingPrincipalCents", normalized.remainingPrincipalCents)
            )
        }
    }

    suspend fun deleteLoanPlan(id: String) {
        database.withTransaction {
            val plan = requireNotNull(database.loanPlanDao().findById(id)) { "贷款计划不存在" }
            require(plan.status == "PENDING_DISBURSEMENT" && plan.originType == "PENDING_DISBURSEMENT") {
                "已有负债或还款证据的贷款计划不能永久删除；结清后保留历史证据"
            }
            require(database.transactionDao().findIncludingDeletedByLoanPlan(id).isEmpty()) {
                "该计划已有流水证据，不能永久删除"
            }
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.LOAN_PLAN,
                subjectId = id,
                action = EvidenceAction.PURGED,
                payload = JSONObject().put("reason", "UNFUNDED_PLAN_REMOVED")
            )
            database.loanPlanDao().deleteById(id)
        }
    }

    /** 编辑单期（REQ 贷款页§5/§15）：任意一期单独改金额/状态；改完剩余本金按未还期次重算。 */
    suspend fun updateLoanInstallment(
        planId: String,
        number: Int,
        dueDateEpochDay: Long?,
        principalCents: Long?,
        interestCents: Long?,
        feeCents: Long?,
        status: String?
    ) {
        database.withTransaction {
            val plan = database.loanPlanDao().findById(planId) ?: return@withTransaction
            require(plan.status != "PENDING_DISBURSEMENT") { "待到账计划尚不能编辑还款期次" }
            val insts = jsonToInstallments(plan.installmentsJson).map { inst ->
                if (inst.number != number) inst else inst.copy(
                    dueDateEpochDay = dueDateEpochDay ?: inst.dueDateEpochDay,
                    principal = principalCents?.let { Money(it) } ?: inst.principal,
                    interest = interestCents?.let { Money(it) } ?: inst.interest,
                    fee = feeCents?.let { Money(it) } ?: inst.fee,
                    status = status?.let { s -> runCatching { InstallmentStatus.valueOf(s) }.getOrDefault(inst.status) } ?: inst.status
                )
            }
            val unpaidSum = insts.filter { it.status != InstallmentStatus.PAID }.sumOf { it.principal.cents }
            val editedAt = System.currentTimeMillis()
            database.loanPlanDao().upsert(
                plan.copy(
                    installmentsJson = installmentsToJson(insts),
                    remainingPrincipalCents = unpaidSum,
                    earlyRepaidCents = 0L,
                    ledgerBaselinePrincipalCents = unpaidSum,
                    ledgerBaselineAt = editedAt
                )
            )
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.LOAN_PLAN,
                subjectId = planId,
                action = EvidenceAction.EDITED,
                occurredAt = editedAt,
                payload = JSONObject()
                    .put("role", "INSTALLMENT_EDITED")
                    .put("installmentNumber", number)
                    .put("remainingPrincipalCents", unpaidSum)
            )
        }
    }

    // ── V5 借款到账 / 贷款还款（铁律：借款不是收入，还款不是消费）──

    /** 借款到账：现金+、贷款剩余本金+；绝不进收入统计（类型为 LOAN_DISBURSEMENT） */
    suspend fun addLoanDisbursement(
        cashAccountId: String,
        amountCents: Long,
        planId: String?,
        note: String?,
        occurredAt: Long = System.currentTimeMillis()
    ) {
        require(amountCents > 0)
        database.withTransaction {
            val requiredPlanId = requireNotNull(planId) { "借款到账必须关联贷款计划" }
            val plan = requireNotNull(database.loanPlanDao().findById(requiredPlanId)) { "贷款计划不存在" }
            val transactionId = addTransaction(
                accountId = cashAccountId,
                amountCents = amountCents,
                type = TransactionType.LOAN_DISBURSEMENT,
                category = TransactionCategory.UNCATEGORIZED.name,
                merchant = null,
                note = note,
                occurredAt = occurredAt,
                loanPlanId = requiredPlanId
            )
            activateLoanPlanFromDisbursement(plan, transactionId, amountCents, occurredAt)
        }
    }

    private suspend fun activateLoanPlanFromDisbursement(
        plan: LoanPlanEntity,
        transactionId: String,
        amountCents: Long,
        occurredAt: Long
    ) {
        when (plan.originType) {
            "PENDING_DISBURSEMENT" -> {
                require(amountCents == plan.principalCents) { "到账金额必须与待放款本金一致；如有扣费请先按合同本金核对" }
                val activated = plan.copy(
                    originType = "DISBURSEMENT_EVENT",
                    disbursementTransactionId = transactionId,
                    status = "ACTIVE",
                    remainingPrincipalCents = amountCents,
                    ledgerBaselinePrincipalCents = amountCents,
                    ledgerBaselineAt = occurredAt
                )
                database.loanPlanDao().upsert(activated)
                evidenceRecorder.link(
                    groupId = "loan-disbursement:$transactionId",
                    subjectType = EvidenceSubjectType.LOAN_PLAN,
                    subjectId = plan.id,
                    subjectRole = "ACTIVATED_BY_DISBURSEMENT",
                    sources = listOf(EvidenceSourceRef(EvidenceSourceType.LEDGER_EVENT, transactionId)),
                    linkedAt = occurredAt
                )
                evidenceRecorder.lifecycle(
                    subjectType = EvidenceSubjectType.LOAN_PLAN,
                    subjectId = plan.id,
                    action = EvidenceAction.LINKED,
                    occurredAt = occurredAt,
                    payload = JSONObject().put("disbursementTransactionId", transactionId)
                )
            }
            "OPENING_BALANCE" -> error("期初存量贷款不能再补造到账流水；新到账请创建待放款计划")
            "DISBURSEMENT_EVENT" -> require(plan.disbursementTransactionId == transactionId) {
                "贷款计划已经关联另一笔到账流水"
            }
            else -> error("未知贷款来源：${plan.originType}")
        }
    }

    /** 贷款还款：现金−总额、剩余本金−本金、利息/费为真实成本；最早未还分期自动标 PAID */
    suspend fun addLoanPayment(
        cashAccountId: String,
        planId: String,
        totalCents: Long,
        principalCents: Long,
        interestCents: Long,
        feeCents: Long,
        note: String?,
        occurredAt: Long = System.currentTimeMillis(),
        notificationId: String? = null,
        allowAuthoritativeBalanceOverride: Boolean = false
    ) {
        require(totalCents > 0)
        require(principalCents >= 0 && interestCents >= 0 && feeCents >= 0)
        require(principalCents + interestCents + feeCents == totalCents) { "本金+利息+手续费必须等于总额" }
        database.withTransaction {
            val plan = requireNotNull(database.loanPlanDao().findById(planId)) { "贷款计划不存在" }
            require(plan.status == "ACTIVE") { "只有已放款且未结清的计划可以还款" }
            require(database.accountDao().find(cashAccountId)?.type == AccountType.ASSET.name) {
                "贷款还款必须从资产账户付款"
            }
            require(principalCents <= remainingEffective(plan)) { "本金超过剩余本金" }
            val transactionId = addTransaction(
                accountId = cashAccountId,
                amountCents = totalCents,
                type = TransactionType.LOAN_PAYMENT,
                category = TransactionCategory.UNCATEGORIZED.name,
                merchant = null,
                note = note,
                occurredAt = occurredAt,
                principalCents = principalCents,
                interestCents = interestCents,
                feeCents = feeCents,
                loanPlanId = planId,
                notificationId = notificationId,
                allowAuthoritativeBalanceOverride = allowAuthoritativeBalanceOverride
            )
            // 本金、利息、手续费都完整覆盖才推进期次。先息后本的 0 本金期不能仅凭
            // “0 <= 本次本金”把后续所有期次一起标为已还。
            val current = jsonToInstallments(plan.installmentsJson)
            val coveredNumbers = coveredLoanInstallmentNumbers(
                current,
                principalCents,
                interestCents,
                feeCents
            ) { it.status != InstallmentStatus.PAID }
            val insts = current.map { inst ->
                if (inst.number in coveredNumbers) inst.copy(status = InstallmentStatus.PAID) else inst
            }
            database.loanPlanDao().upsert(
                plan.copy(
                    remainingPrincipalCents = maxOf(0, remainingEffective(plan) - principalCents),
                    installmentsJson = installmentsToJson(insts)
                )
            )
            recordLoanEvent(planId, transactionId, "LOAN_PAYMENT", occurredAt, principalCents, interestCents, feeCents)
        }
    }

    /** V5 公式层输入映射：贷款计划实体 → DTO（分期 JSON 解析只在此处一份） */
    fun v5PlanInput(plan: LoanPlanEntity): com.assetsking.ledger.V5PlanInput =
        com.assetsking.ledger.V5PlanInput(
            accountId = plan.accountId,
            remainingPrincipalCents = plan.remainingPrincipalCents,
            fallbackPrincipalCents = (plan.principalCents - plan.earlyRepaidCents).coerceAtLeast(0),
            annualRateBps = plan.annualRateBps,
            repaymentDay = plan.repaymentDay,
            status = plan.status,
            installments = jsonToInstallments(plan.installmentsJson).map {
                com.assetsking.ledger.V5InstallmentInput(
                    dueDateEpochDay = it.dueDateEpochDay,
                    principalCents = it.principal.cents,
                    interestCents = it.interest.cents,
                    feeCents = it.fee.cents,
                    isPaid = it.status == InstallmentStatus.PAID
                )
            }
        )

    /** 提前还款：本金减余额，手续费只计现金流；银行给出新计划后到贷款页更新 */
    suspend fun addLoanPrepayment(
        cashAccountId: String,
        planId: String,
        principalCents: Long,
        note: String?,
        occurredAt: Long = System.currentTimeMillis(),
        feeCents: Long = 0L
    ) {
        require(principalCents > 0)
        require(feeCents >= 0)
        database.withTransaction {
            val plan = requireNotNull(database.loanPlanDao().findById(planId)) { "贷款计划不存在" }
            require(plan.status == "ACTIVE") { "只有已放款且未结清的计划可以提前还款" }
            require(database.accountDao().find(cashAccountId)?.type == AccountType.ASSET.name) {
                "贷款提前还款必须从资产账户付款"
            }
            require(principalCents <= remainingEffective(plan)) { "本金超过剩余本金" }
            val transactionId = addTransaction(
                accountId = cashAccountId,
                amountCents = principalCents + feeCents,
                type = TransactionType.LOAN_PREPAYMENT,
                category = TransactionCategory.UNCATEGORIZED.name,
                merchant = null,
                note = note,
                occurredAt = occurredAt,
                principalCents = principalCents,
                feeCents = feeCents,
                loanPlanId = planId
            )
            database.loanPlanDao().upsert(
                plan.copy(
                    remainingPrincipalCents = maxOf(0, remainingEffective(plan) - principalCents),
                    earlyRepaidCents = plan.earlyRepaidCents + principalCents
                )
            )
            recordLoanEvent(planId, transactionId, "LOAN_PREPAYMENT", occurredAt, principalCents, 0L, feeCents)
        }
    }

    /** 提前结清：本金归零、全部期次标 PAID、计划 PAID_OFF、未来计划取消（V5 §36） */
    suspend fun settleLoanPlan(
        cashAccountId: String,
        planId: String,
        principalCents: Long,
        interestCents: Long,
        feeCents: Long,
        note: String?,
        occurredAt: Long = System.currentTimeMillis()
    ) {
        require(principalCents >= 0 && interestCents >= 0 && feeCents >= 0)
        require(principalCents + interestCents + feeCents > 0) { "结清金额必须大于 0" }
        database.withTransaction {
            val plan = requireNotNull(database.loanPlanDao().findById(planId)) { "贷款计划不存在" }
            require(plan.status == "ACTIVE") { "只有已放款且未结清的计划可以结清" }
            require(principalCents == remainingEffective(plan)) { "结清本金必须等于当前剩余本金" }
            val transactionId = addTransaction(
                accountId = cashAccountId,
                amountCents = principalCents + interestCents + feeCents,
                type = TransactionType.LOAN_PAYMENT,
                category = TransactionCategory.UNCATEGORIZED.name,
                merchant = null,
                note = note,
                occurredAt = occurredAt,
                principalCents = principalCents,
                interestCents = interestCents,
                feeCents = feeCents,
                loanPlanId = planId
            )
            val allPaid = jsonToInstallments(plan.installmentsJson)
                .map { it.copy(status = InstallmentStatus.PAID) }
            database.loanPlanDao().upsert(
                plan.copy(
                    remainingPrincipalCents = 0,
                    installmentsJson = installmentsToJson(allPaid),
                    status = "PAID_OFF"
                )
            )
            recordLoanEvent(planId, transactionId, "LOAN_SETTLEMENT", occurredAt, principalCents, interestCents, feeCents)
        }
    }

    private suspend fun recordLoanEvent(
        planId: String,
        transactionId: String,
        role: String,
        occurredAt: Long,
        principalCents: Long,
        interestCents: Long,
        feeCents: Long
    ) {
        evidenceRecorder.link(
            groupId = "loan-event:$transactionId",
            subjectType = EvidenceSubjectType.LOAN_PLAN,
            subjectId = planId,
            subjectRole = role,
            sources = listOf(EvidenceSourceRef(EvidenceSourceType.LEDGER_EVENT, transactionId)),
            linkedAt = occurredAt
        )
        evidenceRecorder.lifecycle(
            subjectType = EvidenceSubjectType.LOAN_PLAN,
            subjectId = planId,
            action = EvidenceAction.LINKED,
            occurredAt = occurredAt,
            payload = JSONObject()
                .put("role", role)
                .put("transactionId", transactionId)
                .put("principalCents", principalCents)
                .put("interestCents", interestCents)
                .put("feeCents", feeCents)
        )
    }

    // ── 出借计划：本金是应收资产划转，只有利息进入收入 ──

    val lendingPlans: Flow<List<LendingPlanEntity>> = database.lendingPlanDao().observeAll()

    suspend fun auditEvidence(): EvidenceAuditReport = LedgerEvidenceAuditService(database).run()

    suspend fun createLendingPlan(draft: LendingPlanDraft): String = database.withTransaction {
        require(draft.label.isNotBlank()) { "出借名称不能为空" }
        require(draft.borrowerName.isNotBlank()) { "借款人不能为空" }
        require(draft.principalCents > 0L) { "出借本金必须大于零" }
        require(draft.expectedInterestCents >= 0L) { "预计利息不能为负数" }
        require(draft.originType in setOf(LendingOriginType.OPENING_BALANCE, LendingOriginType.PENDING_DISBURSEMENT)) {
            "新建出借只能选择期初存量或待借出"
        }
        require(draft.expectedDueDateEpochDay == null || draft.expectedDueDateEpochDay >= draft.startDateEpochDay) {
            "预计收回日不能早于开始日"
        }

        val createdAt = System.currentTimeMillis()
        val planId = UUID.randomUUID().toString()
        val accountId = UUID.randomUUID().toString()
        val openingPrincipal = if (draft.originType == LendingOriginType.OPENING_BALANCE) draft.principalCents else 0L
        val status = if (draft.originType == LendingOriginType.OPENING_BALANCE) {
            LendingPlanStatus.ACTIVE
        } else {
            LendingPlanStatus.PENDING_DISBURSEMENT
        }
        database.accountDao().upsert(
            AccountEntity(
                id = accountId,
                name = "应收 · ${draft.borrowerName.trim()}",
                type = AccountType.ASSET.name,
                balanceCents = openingPrincipal,
                balanceStatus = "CONFIRMED",
                lastCheckedAt = createdAt,
                groupName = "资产",
                startDateEpochDay = draft.startDateEpochDay
            )
        )
        database.balanceCheckpointDao().upsert(
            BalanceCheckpointEntity(
                id = "opening_$accountId",
                accountId = accountId,
                balanceCents = openingPrincipal,
                checkedAt = Long.MIN_VALUE,
                source = if (openingPrincipal > 0L) "OPENING_LENDING" else "OPENING"
            )
        )
        val plan = LendingPlanEntity(
            id = planId,
            receivableAccountId = accountId,
            label = draft.label.trim(),
            borrowerName = draft.borrowerName.trim(),
            principalCents = draft.principalCents,
            remainingPrincipalCents = openingPrincipal,
            expectedInterestCents = draft.expectedInterestCents,
            startDateEpochDay = draft.startDateEpochDay,
            expectedDueDateEpochDay = draft.expectedDueDateEpochDay,
            status = status,
            originType = draft.originType,
            ledgerBaselinePrincipalCents = openingPrincipal,
            ledgerBaselineAt = createdAt,
            createdAt = createdAt,
            updatedAt = createdAt
        )
        database.lendingPlanDao().upsert(plan)
        val source = if (draft.originType == LendingOriginType.OPENING_BALANCE) {
            EvidenceSourceRef(EvidenceSourceType.OPENING_BALANCE, "opening:lending:$planId")
        } else {
            EvidenceSourceRef(EvidenceSourceType.MANUAL_ENTRY, "manual:lending-contract:$planId")
        }
        evidenceRecorder.link(
            groupId = source.id,
            subjectType = EvidenceSubjectType.LENDING_PLAN,
            subjectId = planId,
            subjectRole = "PLAN_ORIGIN",
            sources = listOf(source),
            linkedAt = createdAt
        )
        evidenceRecorder.lifecycle(
            subjectType = EvidenceSubjectType.LENDING_PLAN,
            subjectId = planId,
            action = EvidenceAction.CREATED,
            occurredAt = createdAt,
            payload = JSONObject()
                .put("originType", draft.originType)
                .put("principalCents", draft.principalCents)
                .put("receivableAccountId", accountId)
        )
        planId
    }

    suspend fun deleteLendingPlan(id: String) = database.withTransaction {
        val plan = requireNotNull(database.lendingPlanDao().findById(id)) { "出借计划不存在" }
        val receivableAccount = requireNotNull(database.accountDao().find(plan.receivableAccountId)) { "出借计划账户不存在" }
        requireLendingPlanDeletable(
            originType = plan.originType,
            hasActiveFlows = database.transferDao().findActiveByLendingPlan(id).isNotEmpty() ||
                database.transactionDao().findActiveByLendingPlan(id).isNotEmpty(),
            receivableBalanceStatus = receivableAccount.balanceStatus,
            receivableBalanceCents = receivableAccount.balanceCents,
            remainingPrincipalCents = plan.remainingPrincipalCents
        )
        database.transactionDao().findIncludingDeletedByLendingPlan(id)
            .filter { it.deletedAt != null }
            .forEach { permanentlyDeleteTrashedTransaction(it) }
        database.transferDao().findIncludingDeletedByLendingPlan(id)
            .filter { it.deletedAt != null }
            .forEach { transfer ->
                evidenceRecorder.lifecycle(
                    subjectType = EvidenceSubjectType.TRANSFER,
                    subjectId = transfer.id,
                    action = EvidenceAction.PURGED,
                    payload = JSONObject().put("lendingPlanDeleted", id)
                )
                database.creditCardInstallmentPaymentMatchDao().deleteByTransfer(transfer.id)
                database.transferDao().delete(transfer.id)
            }
        evidenceRecorder.lifecycle(
            subjectType = EvidenceSubjectType.LENDING_PLAN,
            subjectId = id,
            action = EvidenceAction.PURGED,
            payload = JSONObject()
                .put(
                    "reason",
                    if (plan.originType == LendingOriginType.OPENING_BALANCE) {
                        "OPENING_LENDING_REMOVED_WITHOUT_CASH_RESTATEMENT"
                    } else {
                        "LENDING_PLAN_REMOVED_AFTER_RECONCILIATION"
                    }
                )
                .put("removedReceivableCents", receivableAccount.balanceCents)
        )
        database.lendingPlanDao().deleteById(id)
        database.balanceAdjustmentDao().deleteByAccount(plan.receivableAccountId)
        database.balanceCheckpointDao().deleteByAccount(plan.receivableAccountId)
        database.accountDao().deleteById(plan.receivableAccountId)
    }

    suspend fun addLendingDisbursement(
        cashAccountId: String,
        planId: String,
        amountCents: Long,
        note: String?,
        occurredAt: Long = System.currentTimeMillis()
    ): String = database.withTransaction {
        val groupId = "manual:lending-disbursement:${UUID.randomUUID()}"
        recordLendingDisbursement(
            cashAccountId = cashAccountId,
            planId = planId,
            amountCents = amountCents,
            note = note,
            occurredAt = occurredAt,
            sources = listOf(EvidenceSourceRef(EvidenceSourceType.MANUAL_ENTRY, groupId)),
            groupId = groupId
        )
    }

    suspend fun confirmLendingDisbursementNotification(
        notificationId: String,
        cashAccountId: String,
        planId: String,
        amountCents: Long,
        note: String?,
        bankBalanceCents: Long? = null,
        bankCardTail: String? = null
    ) = database.withTransaction {
        val notification = database.rawNotificationDao().findById(notificationId) ?: return@withTransaction
        requireEarliestNotificationFirst(notification, cashAccountId, bankBalanceCents, bankCardTail)
        if (database.rawNotificationDao().claimForConfirmation(notificationId) != 1) return@withTransaction
        val postedAt = notification.postedAt
        val allowAuthoritativeBalanceOverride = canUseAuthoritativeBalance(
            cashAccountId, bankBalanceCents, bankCardTail
        )
        recordLendingDisbursement(
            cashAccountId = cashAccountId,
            planId = planId,
            amountCents = amountCents,
            note = note,
            occurredAt = postedAt,
            sources = listOf(EvidenceSourceRef(EvidenceSourceType.RAW_NOTIFICATION, notificationId)),
            groupId = "notification:$notificationId",
            allowAuthoritativeBalanceOverride = allowAuthoritativeBalanceOverride
        )
        database.rawNotificationDao().updateStatus(notificationId, "LINKED")
        if (bankBalanceCents != null && bankCardTail != null) {
            applyBankBalance(cashAccountId, bankCardTail, bankBalanceCents, postedAt)
        }
    }

    private suspend fun recordLendingDisbursement(
        cashAccountId: String,
        planId: String,
        amountCents: Long,
        note: String?,
        occurredAt: Long,
        sources: List<EvidenceSourceRef>,
        groupId: String,
        allowAuthoritativeBalanceOverride: Boolean = false
    ): String {
        require(amountCents > 0L)
        val plan = requireNotNull(database.lendingPlanDao().findById(planId)) { "出借计划不存在" }
        require(plan.status == LendingPlanStatus.PENDING_DISBURSEMENT && plan.originType == LendingOriginType.PENDING_DISBURSEMENT) {
            "只有待借出计划能关联新的借出划转"
        }
        require(amountCents == plan.principalCents) { "借出金额必须等于计划本金" }
        val cash = requireNotNull(database.accountDao().find(cashAccountId)) { "资金账户不存在" }
        require(cash.type == AccountType.ASSET.name && cashAccountId != plan.receivableAccountId) { "请选择真实资金账户" }
        val transferId = addTransfer(
            fromAccountId = cashAccountId,
            toAccountId = plan.receivableAccountId,
            amountCents = amountCents,
            note = note,
            occurredAt = occurredAt,
            lendingPlanId = planId,
            lendingRole = LendingTransferRole.DISBURSEMENT,
            evidenceSources = sources,
            evidenceGroupId = groupId,
            evidenceRole = "LENDING_DISBURSEMENT",
            allowAuthoritativeBalanceOverride = allowAuthoritativeBalanceOverride
        )
        database.lendingPlanDao().upsert(
            plan.copy(
                remainingPrincipalCents = amountCents,
                status = LendingPlanStatus.ACTIVE,
                originType = LendingOriginType.DISBURSEMENT_TRANSFER,
                disbursementTransferId = transferId,
                ledgerBaselinePrincipalCents = amountCents,
                ledgerBaselineInterestCents = plan.receivedInterestCents,
                ledgerBaselineAt = occurredAt,
                updatedAt = System.currentTimeMillis()
            )
        )
        linkLendingPlanEvent(planId, transferId, "DISBURSEMENT_TRANSFER", groupId, occurredAt)
        return transferId
    }

    suspend fun addLendingRepayment(
        cashAccountId: String,
        planId: String,
        principalCents: Long,
        interestCents: Long,
        note: String?,
        occurredAt: Long = System.currentTimeMillis()
    ) = database.withTransaction {
        val groupId = "manual:lending-repayment:${UUID.randomUUID()}"
        recordLendingRepayment(
            cashAccountId = cashAccountId,
            planId = planId,
            principalCents = principalCents,
            interestCents = interestCents,
            note = note,
            occurredAt = occurredAt,
            sources = listOf(EvidenceSourceRef(EvidenceSourceType.MANUAL_ENTRY, groupId)),
            groupId = groupId
        )
    }

    suspend fun confirmLendingRepaymentNotification(
        notificationId: String,
        cashAccountId: String,
        planId: String,
        totalCents: Long,
        principalCents: Long,
        interestCents: Long,
        note: String?,
        bankBalanceCents: Long? = null,
        bankCardTail: String? = null
    ) = database.withTransaction {
        require(principalCents + interestCents == totalCents) { "本金与利息合计必须等于到账金额" }
        val notification = database.rawNotificationDao().findById(notificationId) ?: return@withTransaction
        requireEarliestNotificationFirst(notification, cashAccountId, bankBalanceCents, bankCardTail)
        if (database.rawNotificationDao().claimForConfirmation(notificationId) != 1) return@withTransaction
        val postedAt = notification.postedAt
        recordLendingRepayment(
            cashAccountId = cashAccountId,
            planId = planId,
            principalCents = principalCents,
            interestCents = interestCents,
            note = note,
            occurredAt = postedAt,
            sources = listOf(EvidenceSourceRef(EvidenceSourceType.RAW_NOTIFICATION, notificationId)),
            groupId = "notification:$notificationId"
        )
        database.rawNotificationDao().updateStatus(notificationId, "LINKED")
        if (bankBalanceCents != null && bankCardTail != null) {
            applyBankBalance(cashAccountId, bankCardTail, bankBalanceCents, postedAt)
        }
    }

    private suspend fun recordLendingRepayment(
        cashAccountId: String,
        planId: String,
        principalCents: Long,
        interestCents: Long,
        note: String?,
        occurredAt: Long,
        sources: List<EvidenceSourceRef>,
        groupId: String
    ) {
        require(principalCents >= 0L && interestCents >= 0L && principalCents + interestCents > 0L)
        val plan = requireNotNull(database.lendingPlanDao().findById(planId)) { "出借计划不存在" }
        require(plan.status != LendingPlanStatus.PENDING_DISBURSEMENT) { "尚未借出的计划不能登记收回" }
        require(principalCents <= plan.remainingPrincipalCents) { "收回本金超过剩余应收" }
        val cash = requireNotNull(database.accountDao().find(cashAccountId)) { "资金账户不存在" }
        require(cash.type == AccountType.ASSET.name && cashAccountId != plan.receivableAccountId) { "请选择真实收款账户" }

        if (principalCents > 0L) {
            val transferId = addTransfer(
                fromAccountId = plan.receivableAccountId,
                toAccountId = cashAccountId,
                amountCents = principalCents,
                note = note,
                occurredAt = occurredAt,
                lendingPlanId = planId,
                lendingRole = LendingTransferRole.PRINCIPAL_REPAYMENT,
                evidenceSources = sources,
                evidenceGroupId = groupId,
                evidenceRole = "LENDING_PRINCIPAL_REPAYMENT"
            )
            linkLendingPlanEvent(planId, transferId, "PRINCIPAL_REPAYMENT_TRANSFER", groupId, occurredAt)
        }
        if (interestCents > 0L) {
            val transactionId = addTransaction(
                accountId = cashAccountId,
                amountCents = interestCents,
                type = TransactionType.INCOME,
                category = "利息收益",
                merchant = plan.borrowerName,
                note = note,
                occurredAt = occurredAt,
                lendingPlanId = planId,
                evidenceSources = sources,
                evidenceGroupId = groupId,
                evidenceRole = "LENDING_INTEREST_RECEIPT"
            )
            linkLendingPlanEvent(planId, transactionId, "INTEREST_RECEIPT_TRANSACTION", groupId, occurredAt)
        }
        val remaining = plan.remainingPrincipalCents - principalCents
        val receivedInterest = plan.receivedInterestCents + interestCents
        val updatedAt = System.currentTimeMillis()
        val reanchor = occurredAt <= plan.ledgerBaselineAt
        database.lendingPlanDao().upsert(
            plan.copy(
                remainingPrincipalCents = remaining,
                receivedInterestCents = receivedInterest,
                status = if (remaining == 0L) LendingPlanStatus.COMPLETED else LendingPlanStatus.ACTIVE,
                ledgerBaselinePrincipalCents = if (reanchor) remaining else plan.ledgerBaselinePrincipalCents,
                ledgerBaselineInterestCents = if (reanchor) receivedInterest else plan.ledgerBaselineInterestCents,
                ledgerBaselineAt = if (reanchor) updatedAt else plan.ledgerBaselineAt,
                updatedAt = updatedAt
            )
        )
        evidenceRecorder.lifecycle(
            subjectType = EvidenceSubjectType.LENDING_PLAN,
            subjectId = planId,
            action = EvidenceAction.LINKED,
            occurredAt = occurredAt,
            payload = JSONObject()
                .put("groupId", groupId)
                .put("principalCents", principalCents)
                .put("interestCents", interestCents)
                .put("remainingPrincipalCents", remaining)
        )
    }

    private suspend fun linkLendingPlanEvent(
        planId: String,
        eventId: String,
        role: String,
        groupId: String,
        linkedAt: Long
    ) {
        evidenceRecorder.link(
            groupId = groupId,
            subjectType = EvidenceSubjectType.LENDING_PLAN,
            subjectId = planId,
            subjectRole = role,
            sources = listOf(EvidenceSourceRef(EvidenceSourceType.LEDGER_EVENT, eventId)),
            linkedAt = linkedAt
        )
        evidenceRecorder.lifecycle(
            subjectType = EvidenceSubjectType.LENDING_PLAN,
            subjectId = planId,
            action = EvidenceAction.LINKED,
            occurredAt = linkedAt,
            payload = JSONObject().put("eventId", eventId).put("role", role)
        )
    }

    // ── V5 月度负债锚点 / 年终奖 / 信用卡分期 ──

    val monthDebtAnchors: Flow<List<MonthDebtAnchorEntity>> = database.monthDebtAnchorDao().observeAll()

    /** 当月无锚点才建档（=当天 V5 总负债），从今天起记录、不回填历史（铁律 7） */
    suspend fun ensureMonthAnchor(totalDebtCents: Long) {
        val ym = YearMonth.now().toString()
        if (database.monthDebtAnchorDao().findByYearMonth(ym) == null) {
            database.monthDebtAnchorDao().upsert(MonthDebtAnchorEntity(ym, totalDebtCents))
        }
    }

    val windfalls: Flow<List<WindfallEntity>> = database.windfallDao().observeAll()

    suspend fun saveWindfall(windfall: WindfallEntity) {
        database.windfallDao().upsert(windfall)
    }

    suspend fun deleteWindfall(id: String) {
        database.windfallDao().deleteById(id)
    }

    /** 年终奖到账：状态流转 + 记一笔 INCOME 流水（到账后才算现金和收入，铁律 8） */
    suspend fun markWindfallReceived(id: String, actualCents: Long, cashAccountId: String) {
        require(actualCents > 0)
        val wf = requireNotNull(database.windfallDao().findById(id))
        require(wf.status == WindfallStatus.EXPECTED.name) { "只有预期中的年终奖才能标记到账" }
        database.withTransaction {
            addTransaction(
                accountId = cashAccountId,
                amountCents = actualCents,
                type = TransactionType.INCOME,
                category = TransactionCategory.UNCATEGORIZED.name,
                merchant = null,
                note = "${wf.name} 到账",
                loanPlanId = null,
                evidenceSources = listOf(EvidenceSourceRef(EvidenceSourceType.SYSTEM_EVENT, "windfall:${wf.id}")),
                evidenceGroupId = "windfall:${wf.id}",
                evidenceRole = "WINDFALL_RECEIPT"
            )
            database.windfallDao().upsert(
                wf.copy(
                    status = WindfallStatus.RECEIVED.name,
                    receivedAmountCents = actualCents,
                    receivedAtEpochDay = LocalDate.now().toEpochDay()
                )
            )
        }
    }

    val cardInstallments: Flow<List<CreditCardInstallmentEntity>> =
        database.creditCardInstallmentDao().observeAll()

    val cardInstallmentAllocations: Flow<List<CreditCardInstallmentAllocationEntity>> =
        database.creditCardInstallmentAllocationDao().observeAll()

    val cardInstallmentSchedules: Flow<List<CreditCardInstallmentScheduleEntity>> =
        database.creditCardInstallmentScheduleDao().observeAll()

    val cardInstallmentPaymentMatches: Flow<List<CreditCardInstallmentPaymentMatchEntity>> =
        database.creditCardInstallmentPaymentMatchDao().observeAll()

    suspend fun deleteCardInstallment(id: String) {
        database.withTransaction {
            cardInstallmentService.cancel(id)
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.CARD_INSTALLMENT,
                subjectId = id,
                action = EvidenceAction.EDITED,
                payload = JSONObject().put("status", "CANCELLED")
            )
        }
    }

    suspend fun createCardInstallment(draft: CreditCardInstallmentDraft): String = database.withTransaction {
        val id = cardInstallmentService.create(draft)
        val plan = requireNotNull(database.creditCardInstallmentDao().findById(id))
        val allocationSources = database.creditCardInstallmentAllocationDao().findByPlan(id)
            .map { EvidenceSourceRef(EvidenceSourceType.LEDGER_EVENT, it.transactionId) }
            .ifEmpty { listOf(EvidenceSourceRef(EvidenceSourceType.MANUAL_ENTRY, "manual:card-installment:$id")) }
        evidenceRecorder.link(
            groupId = "card-installment:$id",
            subjectType = EvidenceSubjectType.CARD_INSTALLMENT,
            subjectId = id,
            subjectRole = "PLAN_ORIGIN",
            sources = allocationSources,
            linkedAt = plan.createdAt
        )
        evidenceRecorder.lifecycle(
            subjectType = EvidenceSubjectType.CARD_INSTALLMENT,
            subjectId = id,
            action = EvidenceAction.CREATED,
            occurredAt = plan.createdAt,
            payload = JSONObject().put("originalPrincipalCents", plan.originalPrincipalCents)
        )
        id
    }

    suspend fun adjustCardInstallmentTerms(id: String, terms: CreditCardInstallmentTerms) {
        database.withTransaction {
            cardInstallmentService.adjustTerms(id, terms)
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.CARD_INSTALLMENT,
                subjectId = id,
                action = EvidenceAction.EDITED,
                payload = JSONObject().put("role", "TERMS_ADJUSTED")
            )
        }
    }

    suspend fun confirmCardInstallmentPaymentMatch(
        transferId: String,
        scheduleId: String,
        principalCents: Long
    ) {
        cardInstallmentService.confirmPaymentMatch(transferId, scheduleId, principalCents)
    }

    // ── V5 现金流设置（SharedPreferences：设置不是记录，不进 DB）──

    private val _monthlyIncomeCents = MutableStateFlow(prefs.getLong("v5_monthly_income_cents", 0L))
    val monthlyIncomeCents: Flow<Long> = _monthlyIncomeCents

    fun setMonthlyIncomeCents(cents: Long) {
        _monthlyIncomeCents.value = cents
        prefs.edit().putLong("v5_monthly_income_cents", cents).apply()
    }

    // 必要生活 = 预算合计（GetV5MetricsUseCase）；旧手填值只作没设预算时的回退，不再提供编辑入口（REQ 旧功能清理§3）
    val necessaryLivingCents: Flow<Long> = MutableStateFlow(prefs.getLong("v5_necessary_living_cents", 0L))

    // ── 非必要消费分类（决定"自由消费"要扣哪些已花支出）──

    private val defaultOptionalCategories =
        setOf("ENTERTAINMENT", "SHOPPING", "OTHER", "UNCATEGORIZED")

    private val _optionalCategories = MutableStateFlow(
        prefs.getString("v5_optional_categories", null)
            ?.split(",")?.filter { it.isNotBlank() }?.toSet()
            ?: defaultOptionalCategories
    )
    val optionalCategories: Flow<Set<String>> = _optionalCategories

    fun setOptionalCategories(categories: Set<String>) {
        _optionalCategories.value = categories
        prefs.edit().putString("v5_optional_categories", categories.joinToString(",")).apply()
    }

    // ── 通知来源白名单：只收「能扣钱」的 app，避免聊天/外卖/系统通知淹没待确认箱 ──

    private val _notificationWhitelist = MutableStateFlow(
        prefs.getString("notif_whitelist", null)
            ?.split(",")?.filter { it.isNotBlank() }?.toSet()
            ?: DEFAULT_PAYMENT_PACKAGES
    )
    val notificationWhitelist: Flow<Set<String>> = _notificationWhitelist

    fun setNotificationWhitelist(packages: Set<String>) {
        _notificationWhitelist.value = packages
        prefs.edit().putString("notif_whitelist", packages.joinToString(",")).apply()
    }

    /** 监听服务在 onNotificationPosted 里同步调用，等不了 Flow */
    fun isWhitelisted(packageName: String): Boolean = packageName in _notificationWhitelist.value

    fun setSmsSenderWhitelist(senders: Set<String>) {
        val normalized = senders.map(String::trim).filter(String::isNotEmpty).toSet()
        _smsSenderWhitelist.value = normalized
        prefs.edit().putString("sms_sender_whitelist", normalized.joinToString(",")).apply()
    }

    private suspend fun uniqueRecurringRuleFor(
        type: TransactionType,
        accountId: String,
        amountCents: Long,
        merchant: String?,
        postedAt: Long
    ): RecurringRuleEntity? {
        val normalizedMerchant = merchant?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val window = 5L * 24 * 60 * 60 * 1000
        val candidates = database.recurringRuleDao().observeActive().first().filter { rule ->
            val ruleMerchant = rule.merchant?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            rule.type == type.name &&
                rule.accountId == accountId &&
                ruleMerchant != null &&
                (normalizedMerchant.contains(ruleMerchant) || ruleMerchant.contains(normalizedMerchant)) &&
                kotlin.math.abs(rule.amountCents - amountCents) * 100 <= rule.amountCents * 15 &&
                kotlin.math.abs(rule.nextRunAt - postedAt) <= window &&
                database.transactionDao().findInRange(rule.nextRunAt - window, rule.nextRunAt + window)
                    .none { it.recurringRuleId == rule.id }
        }
        return candidates.singleOrNull()
    }

    /** Shared by SMS_RECEIVED and inbox rescan; unknown senders are rejected before parsing. */
    fun isSmsSenderWhitelisted(sender: String): Boolean =
        SmsSenderWhitelist.isAllowed(sender, _smsSenderWhitelist.value)

    /** 自动发现的通知来源：包名 → 应用名。设置页据此列出可开关的清单 */
    private val _notificationSources = MutableStateFlow(readNotificationSources())
    val notificationSources: Flow<Map<String, String>> = _notificationSources

    private fun readNotificationSources(): Map<String, String> =
        prefs.getStringSet("notif_sources", emptySet()).orEmpty()
            .mapNotNull { entry ->
                val sep = entry.indexOf('|')
                if (sep <= 0) null else entry.substring(0, sep) to entry.substring(sep + 1)
            }.toMap()

    /**
     * 登记一个通知来源（不放行的也记）。已存在则不动。
     * ponytail: 上限 200 条，防止异常 app 刷爆 prefs；到顶后不再收新来源，够用了。
     */
    fun recordNotificationSource(packageName: String, label: String?) {
        val current = _notificationSources.value
        if (packageName in current || current.size >= 200) return
        val updated = current + (packageName to (label?.takeIf { it.isNotBlank() } ?: packageName))
        _notificationSources.value = updated
        prefs.edit()
            .putStringSet("notif_sources", updated.map { "${it.key}|${it.value}" }.toSet())
            .apply()
    }

    // ── Stats ──

    /** 给定时间范围内的所有流水，用于聚合统计 */
    suspend fun transactionsInRange(start: Long, end: Long): List<TransactionEntity> =
        database.transactionDao().findInRange(start, end)

    /** 与统计流水同窗口的真实转账；信用卡还款由此进入全局“已还款”口径。 */
    suspend fun transfersInRange(start: Long, end: Long): List<TransferEntity> =
        database.transferDao().all().filter { it.occurredAt in start until end }

    /** 统计聚合判断转账业务类型所需的账户快照。 */
    suspend fun accountsSnapshot(): List<AccountEntity> = database.accountDao().all()

    /** 所有流水（导出用） */
    suspend fun allTransactions(): List<TransactionEntity> =
        database.transactionDao().all()

    // ── Backup ──

    suspend fun exportCsvTransactions(): String = buildString {
        appendLine("日期,类型,分类,金额,商户,备注,账户")
        for (tx in allTransactions()) {
            val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(tx.occurredAt))
            val type = txTypeCsvLabel(tx.type)
            val catLabel = txCategoryCsvLabel(tx.category)
            val amount = "%.2f".format(tx.amountCents / 100.0)
            val merchant = tx.merchant?.replace(",", "，") ?: ""
            val note = tx.note?.replace(",", "，") ?: ""
            appendLine("$date,$type,$catLabel,$amount,$merchant,$note,${tx.accountId}")
        }
    }

    suspend fun exportAllData(): String {
        val accounts = database.accountDao().observeAll().let { flow ->
            // ponytail: 直接用 suspend 查询，不走 Flow
            allAccounts()
        }
        val txs = allTransactions()
        val transfers = allTransfers()
        val budgets = allBudgets()
        val plans = allLoanPlans()

        return buildString {
            appendLine("{")
            appendLine("""  "accounts": """)
            appendLine(accounts.joinToString(prefix = "[", postfix = "]") { a ->
                """{"id":"${a.id}","name":"${a.name}","type":"${a.type}","balanceCents":${a.balanceCents},"""
                    .plus(""""cardTail":${a.cardTail?.let{"\"$it\""} ?: "null"},"balanceStatus":"${a.balanceStatus}","""
                        .plus(""""lastCheckedAt":${a.lastCheckedAt ?: "null"}}"""))
            })
            appendLine(",")
            appendLine("""  "transactions": """)
            appendLine(txs.joinToString(prefix = "[", postfix = "]") { tx ->
                """{"id":"${tx.id}","accountId":"${tx.accountId}","amountCents":${tx.amountCents},"""
                    .plus(""""type":"${tx.type}","category":"${tx.category}","occurredAt":${tx.occurredAt},"""
                        .plus(""""merchant":${tx.merchant?.let{"\"$it\""} ?: "null"},"""
                            .plus(""""note":${tx.note?.let{"\"$it\""} ?: "null"},"""
                                .plus(""""status":"${tx.status}"}"""))))
            })
            appendLine(",")
            appendLine("""  "transfers": ${transfers.size}""")
            appendLine(",")
            appendLine("""  "budgets": ${budgets.size}""")
            appendLine(",")
            appendLine("""  "loanPlans": ${plans.size}""")
            appendLine("}")
        }
    }

    private suspend fun allAccounts(): List<AccountEntity> =
        database.accountDao().observeAll().let { it.first() }

    private suspend fun allTransfers(): List<TransferEntity> =
        database.transferDao().observeAll().let { it.first() }

    private suspend fun allBudgets(): List<BudgetEntity> =
        database.budgetDao().observeAll().let { it.first() }

    private suspend fun allLoanPlans(): List<LoanPlanEntity> =
        database.loanPlanDao().observeAll().let { it.first() }

    private fun txTypeCsvLabel(type: String): String = when (type) {
        "EXPENSE" -> "支出"
        "INCOME" -> "收入"
        "REFUND" -> "退款"
        "FEE" -> "手续费"
        "LOAN_DISBURSEMENT" -> "借款到账"
        "LOAN_PAYMENT" -> "贷款还款"
        "LOAN_PREPAYMENT" -> "提前还款"
        "REIMBURSEMENT" -> "报销到账"
        else -> type
    }

    private fun txCategoryCsvLabel(cat: String): String = when (cat) {
        "DINING" -> "餐饮"
        "TRANSPORT" -> "交通"
        "SHOPPING" -> "购物"
        "HOUSING" -> "居住"
        "UTILITIES" -> "水电"
        "MEDICAL" -> "医疗"
        "EDUCATION" -> "教育"
        "ENTERTAINMENT" -> "娱乐"
        "DIGITAL_SERVICES" -> "数字服务"
        "FINANCIAL_FEES" -> "手续费"
        "OTHER" -> "其他"
        else -> "未分类"
    }

    // ── Loan 分期 JSON 序列化（core-database 不能依赖 app，此处自持副本）──

    private fun remainingEffective(plan: LoanPlanEntity): Long =
        com.assetsking.ledger.effectiveRemainingPrincipalCents(
            plan.remainingPrincipalCents,
            (plan.principalCents - plan.earlyRepaidCents).coerceAtLeast(0),
            plan.status
        )

    private fun installmentsToJson(list: List<LoanInstallment>): String =
        JSONArray().apply {
            list.forEach { inst ->
                put(JSONObject().apply {
                    put("number", inst.number)
                    put("dueDateEpochDay", inst.dueDateEpochDay)
                    put("principal", inst.principal.cents)
                    put("interest", inst.interest.cents)
                    put("fee", inst.fee.cents)
                    put("status", inst.status.name)
                })
            }
        }.toString()

    private fun jsonToInstallments(json: String): List<LoanInstallment> =
        runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                LoanInstallment(
                    number = obj.getInt("number"),
                    dueDateEpochDay = obj.getLong("dueDateEpochDay"),
                    principal = Money(obj.getLong("principal")),
                    interest = Money(obj.getLong("interest")),
                    fee = Money(obj.getLong("fee")),
                    status = runCatching { InstallmentStatus.valueOf(obj.getString("status")) }
                        .getOrDefault(InstallmentStatus.UPCOMING)
                )
            }
        }.getOrDefault(emptyList())
}
