package com.assetsking.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IconLibraryTest {
    @Test
    fun commonAssetAndExpenseScenariosHaveChineseSearchKeywords() {
        val expected = mapOf(
            "餐饮" to "restaurant",
            "买菜" to "shopping-cart",
            "咖啡" to "local-cafe",
            "交通" to "directions-bus",
            "汽车" to "directions-car",
            "居住" to "home",
            "房租" to "apartment",
            "水电" to "water-drop",
            "购物" to "shopping-bag",
            "数码" to "devices",
            "医疗" to "local-hospital",
            "药品" to "medication",
            "教育" to "school",
            "书籍" to "menu-book",
            "宠物" to "pets",
            "运动" to "fitness-center",
            "娱乐" to "sports-esports",
            "旅行" to "luggage",
            "育儿" to "child-care",
            "人情" to "volunteer-activism",
            "办公" to "business-center",
            "保险" to "policy",
            "税费" to "request-quote",
            "工资" to "payments",
            "投资" to "trending-up",
            "储蓄" to "savings"
        )

        expected.forEach { (query, key) ->
            assertTrue(
                IconLibrary.search(query).any { it.key == key },
                "搜索「$query」应包含 $key"
            )
        }
    }

    @Test
    fun searchIsTrimmedAndCaseInsensitiveForEnglishKeys() {
        assertEquals("local-cafe", IconLibrary.search("  LOCAL-CAFE ").single().key)
        assertEquals(IconLibrary.entries.size, IconLibrary.search(" ").size)
    }

    @Test
    fun iconKeysAreUniqueAndUnknownKeysUseFallback() {
        assertEquals(IconLibrary.entries.size, IconLibrary.entries.map { it.key }.distinct().size)
        assertEquals(IconLibrary.byKey("more-horiz"), IconLibrary.byKey("missing-icon"))
    }
}
