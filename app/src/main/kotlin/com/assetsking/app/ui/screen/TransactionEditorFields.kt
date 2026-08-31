package com.assetsking.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.assetsking.database.AccountEntity
import com.assetsking.model.AccountType
import com.assetsking.ledger.OrderPlatform
import com.assetsking.ui.component.FormField

internal val commonPaymentChannels = listOf("微信", "支付宝", "云闪付", "银行卡", "现金")

internal fun isCustomPaymentChannel(channel: String): Boolean =
    channel.isNotBlank() && channel !in commonPaymentChannels

internal fun shouldUseCustomPaymentChannelEditor(channel: String, savedChannels: Set<String>): Boolean =
    isCustomPaymentChannel(channel) && channel !in savedChannels

internal fun fundingAccounts(accounts: List<AccountEntity>): List<AccountEntity> =
    accounts.filter { !it.archived && it.type != AccountType.LOAN.name }

@Composable
internal fun AccountChannelFields(
    accounts: List<AccountEntity>,
    selectedAccountId: String,
    fallbackAccountName: String = "请选择账户",
    selectedChannel: String,
    savedChannels: Set<String> = emptySet(),
    customChannelSelected: Boolean,
    onAccountSelected: (String) -> Unit,
    onChannelSelected: (String) -> Unit,
    onCustomChannelSelected: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AccountDropdownField(
            label = "资金账户",
            accounts = fundingAccounts(accounts),
            selectedAccountId = selectedAccountId,
            fallbackAccountName = fallbackAccountName,
            onAccountSelected = onAccountSelected,
            modifier = Modifier.weight(1f)
        )
        PaymentChannelDropdownField(
            selectedChannel = selectedChannel,
            savedChannels = savedChannels,
            customChannelSelected = customChannelSelected,
            onChannelSelected = onChannelSelected,
            onCustomChannelSelected = onCustomChannelSelected,
            modifier = Modifier.weight(1f)
        )
    }
    if (customChannelSelected) {
        Spacer(Modifier.height(8.dp))
        FormField(value = selectedChannel, onValueChange = onChannelSelected, label = "自定义支付渠道")
    }
}

@Composable
internal fun AccountDropdownField(
    label: String,
    accounts: List<AccountEntity>,
    selectedAccountId: String,
    fallbackAccountName: String = "请选择账户",
    onAccountSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedName = accounts.firstOrNull { it.id == selectedAccountId }?.name ?: fallbackAccountName
    SelectDropdownField(
        label = label,
        selectedLabel = selectedName,
        options = accounts.filter { !it.archived }.map { it.id to it.name },
        onSelected = onAccountSelected,
        modifier = modifier
    )
}

@Composable
internal fun PaymentChannelDropdownField(
    selectedChannel: String,
    savedChannels: Set<String> = emptySet(),
    customChannelSelected: Boolean,
    onChannelSelected: (String) -> Unit,
    onCustomChannelSelected: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "支付渠道"
) {
    val otherValue = "__other_payment_channel__"
    SelectDropdownField(
        label = label,
        selectedLabel = when {
            customChannelSelected -> "其他"
            selectedChannel.isBlank() -> "未设置"
            else -> selectedChannel
        },
        options = buildList {
            add("" to "未设置")
            commonPaymentChannels.forEach { add(it to it) }
            savedChannels.asSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && it !in commonPaymentChannels && !OrderPlatform.isKnown(it) }
                .sorted()
                .forEach { add(it to it) }
            add(otherValue to "其他")
        },
        onSelected = { value ->
            if (value == otherValue) {
                if (!customChannelSelected) onChannelSelected("")
                onCustomChannelSelected(true)
            } else {
                onChannelSelected(value)
                onCustomChannelSelected(false)
            }
        },
        modifier = modifier
    )
}

@Composable
internal fun OrderPlatformDropdownField(
    selectedPlatform: String,
    savedPlatforms: Set<String> = emptySet(),
    customPlatformSelected: Boolean,
    onPlatformSelected: (String) -> Unit,
    onCustomPlatformSelected: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val otherValue = "__other_order_platform__"
    SelectDropdownField(
        label = "订单平台",
        selectedLabel = when {
            customPlatformSelected -> "其他"
            selectedPlatform.isBlank() -> "未设置"
            else -> selectedPlatform
        },
        options = buildList {
            add("" to "未设置")
            commonOrderPlatforms.forEach { add(it to it) }
            savedPlatforms.asSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && it !in commonOrderPlatforms }
                .sorted()
                .forEach { add(it to it) }
            add(otherValue to "其他")
        },
        onSelected = { value ->
            if (value == otherValue) {
                if (!customPlatformSelected) onPlatformSelected("")
                onCustomPlatformSelected(true)
            } else {
                onPlatformSelected(value)
                onCustomPlatformSelected(false)
            }
        },
        modifier = modifier
    )
}

@Composable
internal fun SelectDropdownField(
    label: String,
    selectedLabel: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedLabel, maxLines = 1)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, optionLabel) ->
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = {
                            onSelected(value)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
