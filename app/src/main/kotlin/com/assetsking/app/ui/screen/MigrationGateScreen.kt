package com.assetsking.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.assetsking.database.LedgerRepository
import kotlinx.coroutines.launch

/**
 * 旧版 → 重构版迁移门禁（REQ 旧功能清理 §4-8）：
 * NEED_PIN → 设 6 位备份密码；PENDING_NOT_EMPTY → 清空旧待确认箱；READY → 开始迁移。
 * 迁移先自动加密备份，失败 = 回滚（数据不动），不迁移不放行；成功直接进首页（§8）。
 */
@Composable
fun MigrationGateScreen(repository: LedgerRepository, onDone: () -> Unit) {
    var status by remember { mutableStateOf<LedgerRepository.MigrationStatus?>(null) }
    var pinInput by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    suspend fun refresh() { status = repository.migrationStatus() }
    LaunchedEffect(Unit) { refresh() }

    if (status == LedgerRepository.MigrationStatus.DONE) {
        LaunchedEffect(Unit) { onDone() }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("升级迁移", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "新版从升级日起重新积累流水。迁移前会自动生成一份加密备份，账户/贷款/预算/周期账单保留。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        when (status) {
            null -> Text("检查中…", style = MaterialTheme.typography.bodyMedium)
            LedgerRepository.MigrationStatus.NEED_PIN -> {
                Text(
                    "请先设置 6 位备份密码，迁移前的自动加密备份需要它（密码遗忘后备份无法恢复）。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("6 位备份密码") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (pinInput.length == 6) {
                            repository.setBackupPin(pinInput)
                            scope.launch { refresh() }
                        } else msg = "密码必须是 6 位数字"
                    },
                    enabled = pinInput.length == 6
                ) { Text("保存密码") }
            }
            LedgerRepository.MigrationStatus.PENDING_NOT_EMPTY -> {
                Text(
                    "旧待确认箱还有候选未处理。旧版流水不会迁移，请先清空旧待确认箱（升级前的加密备份里仍保留证据）。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { scope.launch { repository.clearOldPendingBox(); refresh() } }) {
                    Text("清空旧待确认箱")
                }
            }
            LedgerRepository.MigrationStatus.READY -> {
                Button(onClick = {
                    scope.launch {
                        if (repository.runMigration()) onDone()
                        else msg = "自动备份失败，迁移未执行（数据未动）。请检查存储空间后重试。"
                    }
                }) { Text("开始迁移") }
            }
            LedgerRepository.MigrationStatus.DONE -> Unit
        }
        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
