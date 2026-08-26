package com.assetsking.app.ui.screen

import com.assetsking.database.CategoryEntity

private val legacyCategoryLabels = mapOf(
    "DINING" to "餐饮",
    "TRANSPORT" to "交通",
    "SHOPPING" to "购物",
    "HOUSING" to "居住",
    "UTILITIES" to "水电",
    "MEDICAL" to "医疗",
    "EDUCATION" to "教育",
    "ENTERTAINMENT" to "娱乐",
    "DIGITAL_SERVICES" to "数字服务",
    "FINANCIAL_FEES" to "手续费",
    "OTHER" to "其他"
)

/** 兼容旧数据中的英文分类代码，同时优先使用当前分类库里的中文名。 */
fun categoryDisplayName(raw: String, categories: List<CategoryEntity>): String {
    if (raw.isBlank()) return "未分类"
    categories.firstOrNull { it.name == raw || it.id == raw }?.let { return it.name }
    val lower = raw.lowercase()
    categories.firstOrNull { it.id.lowercase() == lower }?.let { return it.name }
    return legacyCategoryLabels[raw.uppercase()] ?: raw
}

/** 兼容旧数据中的英文分类代码，找到当前分类库实体。 */
fun categoryEntityFor(raw: String, categories: List<CategoryEntity>): CategoryEntity? {
    if (raw.isBlank()) return null
    return categories.firstOrNull { it.name == raw || it.id == raw }
        ?: categories.firstOrNull { it.id.lowercase() == raw.lowercase() }
}
