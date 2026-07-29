package com.assetsking.ledger

import com.assetsking.model.TransactionCategory

data class ClassificationRule(
    val id: String,
    val keywords: Set<String>,
    val category: TransactionCategory,
    val priority: Int = 0
)

/**
 * 先用可解释的关键词规则分类。用户纠错后可以追加一条高优先级规则，
 * 规则命中前不擅自把流水标成具体类别。
 */
class RuleBasedCategorizer(
    customRules: Iterable<ClassificationRule> = emptyList()
) {
    private val rules = (customRules + defaultRules)
        .sortedByDescending { it.priority }

    fun categorize(merchant: String?, note: String? = null): TransactionCategory {
        val text = listOfNotNull(merchant, note).joinToString(" ").lowercase()
        if (text.isBlank()) return TransactionCategory.UNCATEGORIZED
        return rules.firstOrNull { rule ->
            rule.keywords.any { keyword -> text.contains(keyword.lowercase()) }
        }?.category ?: TransactionCategory.UNCATEGORIZED
    }

    companion object {
        private val defaultRules = listOf(
            ClassificationRule("dining", setOf("美团外卖", "饿了么", "星巴克", "肯德基", "麦当劳", "餐饮"), TransactionCategory.DINING),
            ClassificationRule("transport", setOf("滴滴", "高德打车", "地铁", "公交", "铁路", "12306"), TransactionCategory.TRANSPORT),
            ClassificationRule("shopping", setOf("淘宝", "天猫", "京东", "拼多多", "唯品会"), TransactionCategory.SHOPPING),
            ClassificationRule("housing", setOf("房租", "物业", "链家"), TransactionCategory.HOUSING),
            ClassificationRule("utilities", setOf("电费", "水费", "燃气费", "话费", "宽带"), TransactionCategory.UTILITIES),
            ClassificationRule("digital", setOf("腾讯视频", "爱奇艺", "网易云音乐", "会员续费"), TransactionCategory.DIGITAL_SERVICES),
            ClassificationRule("fee", setOf("手续费", "服务费", "利息"), TransactionCategory.FINANCIAL_FEES)
        )
    }
}
