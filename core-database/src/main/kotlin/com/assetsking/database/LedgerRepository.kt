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
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

data class LearnedRule(
    val accountId: String,
    val type: String,
    val category: String
)

class LedgerRepository(
    private val context: android.content.Context,
    private val database: AssetsKingDatabase,
    private val prefs: SharedPreferences
) {
    val accounts: Flow<List<AccountEntity>> = database.accountDao().observeAll()
    val transactions: Flow<List<TransactionEntity>> = database.transactionDao().observeAll()
    val transfers: Flow<List<TransferEntity>> = database.transferDao().observeAll()
    val unprocessedNotifications: Flow<Int> = database.rawNotificationDao().observeUnprocessedCount()
    val pendingNotifications: Flow<List<RawNotificationEntity>> =
        database.rawNotificationDao().observeByStatus("PENDING_CONFIRMATION")
    val linkedNotifications: Flow<List<RawNotificationEntity>> =
        database.rawNotificationDao().observeByStatus("LINKED")
    val ignoredNotifications: Flow<List<RawNotificationEntity>> =
        database.rawNotificationDao().observeByStatus("IGNORED")
    private val _lastReceivedAt = MutableStateFlow(prefs.getLong("last_notification_received_at", 0L))
    val lastReceivedAt: Flow<Long> = _lastReceivedAt
    private val categorizer = RuleBasedCategorizer()

    suspend fun seedKnownAccounts() {
        database.accountDao().insertAll(
            listOf(
                AccountEntity("cmb", "招商银行", AccountType.ASSET.name, 0),
                AccountEntity("nbcb", "宁波银行", AccountType.ASSET.name, 0),
                AccountEntity("cgb", "广发信用卡", AccountType.CREDIT.name, 0),
                AccountEntity("huabei", "花呗", AccountType.CREDIT.name, 0)
            )
        )
    }

    fun categorize(merchant: String?, note: String? = null): TransactionCategory =
        categorizer.categorize(merchant, note)

    suspend fun saveRawNotification(notification: RawNotificationEntity, updateLastReceived: Boolean = true) {
        database.rawNotificationDao().insert(
            if (notification.contentFingerprint.isBlank())
                notification.copy(contentFingerprint = ContentFingerprint.of(notification.title, notification.content))
            else notification
        )
        // 金库「最近入库时间」：实时证据落库才刷新；补扫旧短信不刷新（避免被历史补回污染）
        if (updateLastReceived) {
            _lastReceivedAt.value = notification.receivedAt
            prefs.edit().putLong("last_notification_received_at", notification.receivedAt).apply()
        }
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

    /** 立即备份（手动或自动）。密码未设返回 false。自动备份保留最近 13 份，手动永不删除。 */
    suspend fun backupNow(manual: Boolean): Boolean = withContext(Dispatchers.IO) {
        val pin = backupPin()
        if (pin.length != 6) return@withContext false
        runCatching {
            val dbFile = context.getDatabasePath("assets-king.db")
            // WAL 先 checkpoint，尽量让主库文件自包含
            runCatching { database.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)") }
            val stamp = System.currentTimeMillis()
            val prefix = if (manual) "manual" else "auto"
            val kind = if (manual) "manual" else "auto"
            val localDir = java.io.File(context.filesDir, "backups/$kind")

            // SAF 目录（REQ 备份§2）：所选目录下建 assets-king-backups/{manual,auto}/；失败回退应用私有目录
            val safSub: androidx.documentfile.provider.DocumentFile? = backupDirUri()?.let { root ->
                runCatching {
                    val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, root)
                        ?: return@runCatching null
                    val base = tree.findFile("assets-king-backups") ?: tree.createDirectory("assets-king-backups")
                    base?.findFile(kind) ?: base?.createDirectory(kind)
                }.getOrNull()
            }

            fun enc(name: String, data: ByteArray) {
                if (safSub != null) {
                    runCatching {
                        safSub.findFile(name)?.delete()
                        val f = safSub.createFile("application/octet-stream", name) ?: return
                        context.contentResolver.openOutputStream(f.uri)?.use { it.write(data) }
                    }
                } else {
                    localDir.mkdirs()
                    java.io.File(localDir, name).writeBytes(data)
                }
            }
            fun encFile(src: java.io.File, ext: String) {
                if (!src.exists()) return
                enc("${prefix}_${stamp}$ext", com.assetsking.ledger.PinCipher.encrypt(src.readBytes(), pin))
            }
            encFile(dbFile, ".db.enc")
            encFile(java.io.File(dbFile.path + "-wal"), ".wal.enc")
            encFile(java.io.File(dbFile.path + "-shm"), ".shm.enc")
            val prefsJson = JSONObject().apply {
                prefs.all.forEach { (k, v) -> put(k, v) }
            }.toString()
            enc("${prefix}_${stamp}.prefs.enc", com.assetsking.ledger.PinCipher.encrypt(prefsJson.toByteArray(Charsets.UTF_8), pin))
            if (!manual) pruneAutoBackups(safSub, localDir)
            true
        }.getOrDefault(false)
    }

    private fun pruneAutoBackups(safSub: androidx.documentfile.provider.DocumentFile?, localDir: java.io.File) {
        // 自动备份保留最近 13 份（约三个月，REQ 备份§3）；手动备份永不自动删除
        val stamps: List<String> = if (safSub != null) {
            safSub.listFiles()
                .mapNotNull { f ->
                    f.name?.takeIf { it.startsWith("auto_") && it.endsWith(".db.enc") }
                        ?.removePrefix("auto_")?.removeSuffix(".db.enc")
                }
                .sortedDescending()
        } else {
            localDir.listFiles { f -> f.name.startsWith("auto_") && f.name.endsWith(".db.enc") }
                ?.sortedByDescending { it.lastModified() }
                ?.map { it.name.removePrefix("auto_").removeSuffix(".db.enc") }
                ?: emptyList()
        }
        stamps.drop(13).forEach { stale ->
            listOf(".db.enc", ".wal.enc", ".shm.enc", ".prefs.enc").forEach { ext ->
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
        if (!backupNow(manual = false)) return@withContext false
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
                val content = context.contentResolver.openInputStream(uri)?.readBytes() ?: return@runCatching false
                val dbBytes = com.assetsking.ledger.PinCipher.decrypt(content, pin)
                backupNow(manual = true)
                context.getDatabasePath("assets-king.db").writeBytes(dbBytes)
                // 偏好设置同步恢复：同名手动备份的 prefs 文件（若存在）
                val prefsFile = java.io.File(dir, "manual_$stamp.prefs.enc")
                if (prefsFile.exists()) {
                    val json = String(
                        com.assetsking.ledger.PinCipher.decrypt(prefsFile.readBytes(), pin),
                        Charsets.UTF_8
                    )
                    val obj = JSONObject(json)
                    prefs.edit().clear().apply()
                    val editor = prefs.edit()
                    obj.keys().forEach { k ->
                        when (val v = obj.get(k)) {
                            is String -> editor.putString(k, v)
                            is Boolean -> editor.putBoolean(k, v)
                            is Int -> editor.putInt(k, v)
                            is Long -> editor.putLong(k, v)
                            is Double -> editor.putLong(k, v.toLong())
                        }
                    }
                    editor.apply()
                }
                true
            }.getOrDefault(false)
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
        val defaultEnabledModules: Set<String> = setOf("budget", "reimbursement", "recurring")
        val defaultModuleOrder: List<String> = listOf("budget", "reimbursement", "recurring", "week", "ranking", "trend", "accounts", "repayments")
    }

    fun observeNewNotifications(): Flow<List<RawNotificationEntity>> =
        database.rawNotificationDao().observeByStatus("NEW")

    suspend fun updateNotificationStatus(id: String, status: String) {
        database.rawNotificationDao().updateStatus(id, status)
    }

    /** 同额转出+转入两条通知合并确认「账户转账」（REQ 待确认交易类型§4）：记转账 + 两条通知标 IGNORED（去重指纹保留，补扫不复活）。 */
    suspend fun confirmTransferFromNotifications(
        outNotificationId: String,
        inNotificationId: String,
        fromAccountId: String,
        toAccountId: String,
        amountCents: Long,
        note: String?
    ) {
        val postedAt = database.rawNotificationDao().findById(outNotificationId)?.postedAt
            ?: System.currentTimeMillis()
        addTransfer(fromAccountId, toAccountId, amountCents, note, postedAt)
        listOf(outNotificationId, inNotificationId).forEach { id ->
            database.rawNotificationDao().updateProcessingNote(id, "merged-transfer")
            database.rawNotificationDao().updateStatus(id, "IGNORED")
        }
    }

    suspend fun updateNotificationNote(id: String, note: String) {
        database.rawNotificationDao().updateProcessingNote(id, note)
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
        channel: String? = null
    ) {
        require(amountCents > 0)
        database.withTransaction {
            // 流水日期用通知到达时间，不用确认时刻：银行短信即时推，postedAt≈真实扣款时间；
            // 用户可能隔天才点确认，用确认时刻会把日期记错（真机实报：88 元短信 08:39 到、09:41 确认）
            val postedAt = database.rawNotificationDao().findById(notificationId)?.postedAt
                ?: System.currentTimeMillis()
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
                // 确认即认领：金额±15%、应扣日±5天、同类型的周期账单，把流水挂到规则下。
                // 挂上后：账单页显示「本月已扣」；规则自动记账时会认领它而不重复造笔。
                // 不要求账户一致——短信说的是真实扣款账户，规则里的账户只是默认值。
                // ponytail: 同日同额的规则（如老爹/老妈意外险各85.26）会都匹配到第一条，显示层已知。
                val ruleId = database.recurringRuleDao().observeAll().first()
                    .firstOrNull {
                        it.type == type.name &&
                            kotlin.math.abs(it.amountCents - amountCents) * 100 <= amountCents * 15 &&
                            kotlin.math.abs(it.nextRunAt - postedAt) <= 5L * 24 * 60 * 60 * 1000
                    }?.id
                // 退款关联原消费（REQ 待确认交易类型 §6-8）：同账户、30 天内的支出中取金额
                // 最接近且 ≤ 退款额的（同额取最近）——冲减原消费的分类与必要性，不计入本月收入。
                // ponytail: 找不到原消费仍允许独立退款确认（§7），只不参与冲减。
                val refundOfId = if (type == TransactionType.REFUND) {
                    database.transactionDao().findInRange(
                        postedAt - 30L * 24 * 60 * 60 * 1000, postedAt
                    ).filter {
                        it.accountId == accountId &&
                            it.type == TransactionType.EXPENSE.name &&
                            it.amountCents <= amountCents
                    }.maxWithOrNull(compareBy({ it.amountCents }, { it.occurredAt }))?.id
                } else null
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
                            necessity = necessity, channel = channel, notificationId = notificationId
                        )
                        markInstallmentPaid(plan, inst.number, inst.principal.cents)
                    } else {
                        addTransaction(
                            accountId, amountCents, type, TransactionCategory.UNCATEGORIZED.name, merchant, note,
                            occurredAt = postedAt, principalCents = amountCents, loanPlanId = plan?.id,
                            necessity = necessity, channel = channel, notificationId = notificationId
                        )
                    }
                } else {
                    addTransaction(
                        accountId, amountCents, type, category, merchant, note,
                        occurredAt = postedAt, recurringRuleId = ruleId, refundOfId = refundOfId,
                        necessity = necessity, channel = channel, notificationId = notificationId
                    )
                }
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

    /**
     * 通知一到就自动对账，不必等用户确认那笔流水。按尾号找账户，找不到就什么都不做。
     *
     * @param postedAt 通知的推送时间。补扫会把旧短信重新读一遍，旧余额不能覆盖新余额，
     *   所以比 lastCheckedAt 旧的直接丢弃。
     * @param pendingDeltas 可归属本账户的待确认证据增量（REQ 对账 §4 要求待确认变化也参与校验）
     * @param thisEvidenceDeltaCents 本条证据自身的增量（银行余额是扣款后的值，校验须包含它）
     * @return 是否真的对上了账
     */
    suspend fun reconcileFromNotification(
        cardTail: String,
        balanceCents: Long,
        postedAt: Long,
        pendingDeltas: List<LedgerDelta> = emptyList(),
        thisEvidenceDeltaCents: Long? = null
    ): Boolean {
        val candidates = database.accountDao().findByCardTail(cardTail, AccountType.ASSET.name)
        // 两张卡尾号相同就无法判断是哪张，宁可不对
        val account = candidates.singleOrNull() ?: return false
        if ((account.lastCheckedAt ?: 0L) >= postedAt) return false
        applyBankBalance(account.id, cardTail, balanceCents, postedAt, pendingDeltas, thisEvidenceDeltaCents)
        return true
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
        database.transactionDao().updateCategory(id, category.name)
    }

    suspend fun setTransactionCategoryName(id: String, categoryName: String) {
        database.transactionDao().updateCategory(id, categoryName)
    }

    suspend fun setTransactionNecessity(id: String, necessity: Boolean?) {
        database.transactionDao().updateNecessity(id, necessity)
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
        necessity: Boolean?
    ) {
        require(amountCents > 0)
        database.withTransaction {
            val old = requireNotNull(database.transactionDao().findById(id))
            // V5：借款/还款/提前还款流水不可编辑（联动了贷款计划），删除后重记
            val oldType = TransactionType.valueOf(old.type)
            val loanTypes = setOf(TransactionType.LOAN_DISBURSEMENT, TransactionType.LOAN_PAYMENT, TransactionType.LOAN_PREPAYMENT)
            val isLoanTx = type in loanTypes || oldType in loanTypes
            if (!isLoanTx) {
                database.transactionDao().update(
                    id, amountCents, type.name, category, merchant, note,
                    accountId, occurredAt, necessity
                )
                recomputeBalance(old.accountId)
                if (accountId != old.accountId) recomputeBalance(accountId)
            }
        }
    }

    suspend fun deleteTransaction(id: String) {
        database.withTransaction {
            val tx = requireNotNull(database.transactionDao().findById(id))
            val accountId = tx.accountId
            // V5 回滚：借款/还款/提前还款与贷款计划的联动还原，保持真相源对称
            when (TransactionType.valueOf(tx.type)) {
                TransactionType.LOAN_PAYMENT -> tx.loanPlanId?.let { planId ->
                    database.loanPlanDao().findById(planId)?.let { plan ->
                        // ponytail: 跨笔还款时按"最早优先"近似还原，单人串行操作下与标记过程对称
                        var covered = 0L
                        val insts = jsonToInstallments(plan.installmentsJson).map { inst ->
                            if (inst.status == InstallmentStatus.PAID && covered + inst.principal.cents <= tx.principalCents) {
                                covered += inst.principal.cents
                                inst.copy(status = InstallmentStatus.UPCOMING)
                            } else inst
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
                        database.loanPlanDao().upsert(
                            plan.copy(remainingPrincipalCents = maxOf(0, remainingEffective(plan) - tx.amountCents))
                        )
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
                    // 报销流水删除：解除关联并归还垫付的已报销金额
                    database.reimbursementLinkDao().findByReimbursement(tx.id).forEach { link ->
                        database.transactionDao().findById(link.expenseTxId)?.let { expense ->
                            database.transactionDao().updateReimbursed(
                                expense.id,
                                (expense.reimbursedCents - link.coveredCents).coerceAtLeast(0L)
                            )
                        }
                    }
                    database.reimbursementLinkDao().deleteByReimbursement(tx.id)
                }
                else -> Unit
            }
            database.transactionDao().deleteById(id)
            recomputeBalance(accountId)
            // 由通知确认的流水删除后：原通知回到待确认箱重新处理（REQ 流水§9）
            tx.notificationId?.let { nid ->
                database.rawNotificationDao().updateStatus(nid, "PENDING_CONFIRMATION")
                database.rawNotificationDao().updateProcessingNote(nid, "流水已删除，回到待确认")
            }
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
        notificationId: String? = null
    ) {
        require(amountCents > 0)
        database.withTransaction {
            requireNotNull(database.accountDao().find(accountId))
            // 手动记退款同样自动关联原消费（REQ 待确认§6-8）：与通知确认路径同一条规则
            val linkedRefund = refundOfId ?: if (type == TransactionType.REFUND) {
                database.transactionDao().findInRange(
                    occurredAt - 30L * 24 * 60 * 60 * 1000, occurredAt
                ).filter {
                    it.accountId == accountId &&
                        it.type == TransactionType.EXPENSE.name &&
                        it.amountCents <= amountCents
                }.maxWithOrNull(compareBy({ it.amountCents }, { it.occurredAt }))?.id
            } else null
            database.transactionDao().insert(
                TransactionEntity(
                    id = UUID.randomUUID().toString(),
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
                    loanPlanId = loanPlanId
                )
            )
            recomputeBalance(accountId)
        }
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
        expenseIds: List<String>
    ) {
        require(amountCents > 0)
        database.withTransaction {
            val expenses = expenseIds
                .map { requireNotNull(database.transactionDao().findById(it)) }
                .filter { it.type == TransactionType.EXPENSE.name }
            val covers = ReimbursementSplit.cover(expenses.map { it.amountCents }, amountCents)
            val txId = UUID.randomUUID().toString()
            database.transactionDao().insert(
                TransactionEntity(
                    id = txId,
                    accountId = accountId,
                    amountCents = amountCents,
                    type = TransactionType.REIMBURSEMENT.name,
                    category = TransactionCategory.UNCATEGORIZED.name,
                    occurredAt = occurredAt,
                    note = note?.trim()?.takeIf { it.isNotEmpty() }
                )
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

    suspend fun addTransfer(fromAccountId: String, toAccountId: String, amountCents: Long, note: String?, occurredAt: Long) {
        require(amountCents > 0)
        require(fromAccountId != toAccountId)
        database.withTransaction {
            requireNotNull(database.accountDao().find(fromAccountId))
            requireNotNull(database.accountDao().find(toAccountId))
            database.transferDao().insert(
                TransferEntity(
                    id = UUID.randomUUID().toString(),
                    fromAccountId = fromAccountId,
                    toAccountId = toAccountId,
                    amountCents = amountCents,
                    occurredAt = occurredAt,
                    note = note?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
            recomputeBalance(fromAccountId)
            recomputeBalance(toAccountId)
        }
    }

    /** 删除转账：删除后重算两边余额。账户已删的自动跳过（recomputeBalance 里 find 不到即返回）。 */
    suspend fun deleteTransfer(id: String) {
        database.withTransaction {
            val tf = database.transferDao().findById(id) ?: return@withTransaction
            database.transferDao().delete(id)
            recomputeBalance(tf.fromAccountId)
            recomputeBalance(tf.toAccountId)
        }
    }

    // ── Reimbursement ──

    val reimbursableTransactions: Flow<List<TransactionEntity>> =
        database.transactionDao().observeReimbursable()

    suspend fun toggleReimbursable(id: String, isReimbursable: Boolean) {
        database.transactionDao().updateReimbursable(id, isReimbursable)
    }

    suspend fun linkToRecurringRule(transactionId: String, ruleId: String?) {
        database.transactionDao().updateRecurringRuleId(transactionId, ruleId)
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
        val assets = all.filter { it.type == AccountType.ASSET.name }.sumOf { it.balanceCents }
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

    // ── Custom Categories ──

    val customCategories: Flow<List<CustomCategoryEntity>> = database.customCategoryDao().observeAll()

    suspend fun addCustomCategory(name: String) {
        database.customCategoryDao().upsert(CustomCategoryEntity(name.trim()))
    }

    suspend fun deleteCustomCategory(name: String) {
        database.customCategoryDao().deleteByName(name)
    }

    // ── 分类库（REQ 初始分类库 §1-21）──

    val categories: Flow<List<CategoryEntity>> = database.categoryDao().observeAll()

    /** 首次启动播种分类库（REQ 初始分类库 + 预期收入§4）：消费/收入两类独立判断，缺哪类种哪类；用户已动过的那类不再动。 */
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

    private fun parseAliases(json: String): List<String> =
        runCatching {
            JSONArray(json).let { arr -> (0 until arr.length()).map { arr.getString(it) } }
        }.getOrDefault(emptyList())

    // ── Recurring Rules ──

    val recurringRules: Flow<List<RecurringRuleEntity>> = database.recurringRuleDao().observeAll()

    suspend fun saveRecurringRule(rule: RecurringRuleEntity) {
        database.recurringRuleDao().upsert(rule)
    }

    suspend fun deleteRecurringRule(id: String) {
        database.recurringRuleDao().deleteById(id)
    }

    /**
     * 处理到期的周期性账单：插入流水 + 计算下次执行时间。循环补漏多周期。
     *
     * 关键：**真实扣款已经被通知抓到时，绝不再造一笔**，改为把已有那笔认领给规则。
     * 否则「周期账单自动生成」+「通知自动记账」会把同一笔房租/订阅记两次，
     * 余额和 V5 的必须还/缺口全部虚高。
     */
    suspend fun processRecurring(): Int {
        val now = System.currentTimeMillis()
        val due = database.recurringRuleDao().observeActive().let { it.first() }
            .filter { it.nextRunAt <= now }
        if (due.isEmpty()) return 0

        var inserted = 0
        database.withTransaction {
            for (rule in due) {
                var cursor = rule.nextRunAt
                while (cursor <= now) {
                    val existing = findRealChargeFor(rule, cursor)
                    if (existing != null) {
                        database.transactionDao().updateRecurringRuleId(existing.id, rule.id)
                    } else {
                        addTransaction(
                            accountId = rule.accountId,
                            amountCents = rule.amountCents,
                            type = TransactionType.valueOf(rule.type),
                            category = rule.category,
                            merchant = rule.merchant,
                            note = rule.note,
                            occurredAt = cursor,
                            recurringRuleId = rule.id
                        )
                        inserted++
                    }
                    cursor = nextRun(cursor, rule.interval)
                }
                database.recurringRuleDao().upsert(rule.copy(nextRunAt = cursor))
            }
        }
        return inserted
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
        return database.transactionDao().findInRange(dueAt - window, dueAt + window)
            .firstOrNull {
                it.recurringRuleId == null &&
                    it.type == rule.type &&
                    it.merchant?.trim() == merchant &&
                    kotlin.math.abs(it.amountCents - rule.amountCents) * 100 <= rule.amountCents * 15
            }
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
        database.loanPlanDao().upsert(plan)
    }

    suspend fun deleteLoanPlan(id: String) {
        database.loanPlanDao().deleteById(id)
    }

    /** 编辑单期（REQ 贷款页§5/§15）：任意一期单独改金额/状态；改完剩余本金按未还期次重算。 */
    suspend fun updateLoanInstallment(
        planId: String,
        number: Int,
        principalCents: Long?,
        interestCents: Long?,
        feeCents: Long?,
        status: String?
    ) {
        val plan = database.loanPlanDao().findById(planId) ?: return
        val insts = jsonToInstallments(plan.installmentsJson).map { inst ->
            if (inst.number != number) inst else inst.copy(
                principal = principalCents?.let { Money(it) } ?: inst.principal,
                interest = interestCents?.let { Money(it) } ?: inst.interest,
                fee = feeCents?.let { Money(it) } ?: inst.fee,
                status = status?.let { s -> runCatching { InstallmentStatus.valueOf(s) }.getOrDefault(inst.status) } ?: inst.status
            )
        }
        val unpaidSum = insts.filter { it.status != InstallmentStatus.PAID }.sumOf { it.principal.cents }
        database.loanPlanDao().upsert(plan.copy(installmentsJson = installmentsToJson(insts), remainingPrincipalCents = unpaidSum))
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
            addTransaction(
                accountId = cashAccountId,
                amountCents = amountCents,
                type = TransactionType.LOAN_DISBURSEMENT,
                category = TransactionCategory.UNCATEGORIZED.name,
                merchant = null,
                note = note,
                occurredAt = occurredAt,
                loanPlanId = planId
            )
            if (planId != null) {
                database.loanPlanDao().findById(planId)?.let { plan ->
                    database.loanPlanDao().upsert(
                        plan.copy(remainingPrincipalCents = remainingEffective(plan) + amountCents)
                    )
                }
            }
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
        occurredAt: Long = System.currentTimeMillis()
    ) {
        require(totalCents > 0)
        require(principalCents >= 0 && interestCents >= 0 && feeCents >= 0)
        require(principalCents + interestCents + feeCents == totalCents) { "本金+利息+手续费必须等于总额" }
        val plan = requireNotNull(database.loanPlanDao().findById(planId)) { "贷款计划不存在" }
        require(principalCents <= remainingEffective(plan)) { "本金超过剩余本金" }
        database.withTransaction {
            addTransaction(
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
                loanPlanId = planId
            )
            // 完全覆盖该期本金才标 PAID（部分覆盖不标）
            var covered = 0L
            val insts = jsonToInstallments(plan.installmentsJson).map { inst ->
                if (inst.status != InstallmentStatus.PAID && covered + inst.principal.cents <= principalCents) {
                    covered += inst.principal.cents
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

    /** 提前还款：只减本金、不当消费、不标普通期次；银行给出新计划后到贷款页更新 */
    suspend fun addLoanPrepayment(
        cashAccountId: String,
        planId: String,
        principalCents: Long,
        note: String?,
        occurredAt: Long = System.currentTimeMillis()
    ) {
        require(principalCents > 0)
        val plan = requireNotNull(database.loanPlanDao().findById(planId)) { "贷款计划不存在" }
        require(principalCents <= remainingEffective(plan)) { "本金超过剩余本金" }
        database.withTransaction {
            addTransaction(
                accountId = cashAccountId,
                amountCents = principalCents,
                type = TransactionType.LOAN_PREPAYMENT,
                category = TransactionCategory.UNCATEGORIZED.name,
                merchant = null,
                note = note,
                occurredAt = occurredAt,
                principalCents = principalCents,
                loanPlanId = planId
            )
            database.loanPlanDao().upsert(
                plan.copy(
                    remainingPrincipalCents = maxOf(0, remainingEffective(plan) - principalCents),
                    earlyRepaidCents = plan.earlyRepaidCents + principalCents
                )
            )
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
        val plan = requireNotNull(database.loanPlanDao().findById(planId)) { "贷款计划不存在" }
        require(principalCents <= remainingEffective(plan)) { "本金超过剩余本金" }
        database.withTransaction {
            addTransaction(
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
        }
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
                loanPlanId = null
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

    suspend fun saveCardInstallment(installment: CreditCardInstallmentEntity) {
        database.creditCardInstallmentDao().upsert(installment)
    }

    suspend fun deleteCardInstallment(id: String) {
        database.creditCardInstallmentDao().deleteById(id)
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
