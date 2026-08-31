package com.assetsking.app.ui.screen

/** 下单平台与支付渠道是两个维度：平台回答“去哪里找订单”，渠道回答“钱从哪里付”。 */
internal val commonOrderPlatforms = listOf("美团", "淘宝", "京东", "拼多多", "淘宝闪购", "抖音")

internal fun shouldUseCustomOrderPlatformEditor(platform: String, savedPlatforms: Set<String> = emptySet()): Boolean =
    platform.isNotBlank() && platform !in commonOrderPlatforms && platform !in savedPlatforms

internal fun inferOrderPlatform(
    packageName: String?,
    sourceLabel: String?,
    merchant: String?
): String? {
    val packagePlatform = when {
        packageName == "com.sankuai.meituan" -> "美团"
        packageName == "com.taobao.taobao" -> "淘宝"
        packageName == "com.jingdong.app.mall" -> "京东"
        packageName == "com.xunmeng.pinduoduo" -> "拼多多"
        packageName == "me.ele" -> "淘宝闪购"
        packageName == "com.ss.android.ugc.aweme" -> "抖音"
        else -> null
    }
    return packagePlatform
        ?: sourceLabel?.trim()?.takeIf { it in commonOrderPlatforms }
        ?: merchant?.trim()?.substringBefore('-', missingDelimiterValue = "")
            ?.takeIf { it in commonOrderPlatforms || it == "饿了么" }
}

internal fun merchantForDisplay(merchant: String?, platform: String?): String? {
    val value = merchant?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val prefix = platform?.trim()?.takeIf { it.isNotEmpty() } ?: return value
    return value.removePrefix("$prefix-").removePrefix("$prefix－").trim().ifEmpty { value }
}
