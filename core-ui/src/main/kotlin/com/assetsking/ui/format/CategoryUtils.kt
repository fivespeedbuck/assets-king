package com.assetsking.ui.format

/**
 * 返回内置 + 自定义分类的完整列表（按名称排序）
 */
fun allCategories(customNames: List<String>): List<String> {
    val builtIn = com.assetsking.model.TransactionCategory.entries.map { it.name }
    return (builtIn + customNames).distinct()
}

/**
 * 内置 + 自定义分类的显示名。自定义分类直接显示原名。
 */
fun categoryLabelOrName(cat: String, customNames: List<String>): String {
    val builtIn = runCatching { com.assetsking.model.TransactionCategory.valueOf(cat) }.getOrNull()
    return if (builtIn != null) categoryLabel(builtIn) else cat
}
