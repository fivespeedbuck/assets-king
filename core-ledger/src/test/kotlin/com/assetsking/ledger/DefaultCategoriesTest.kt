package com.assetsking.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultCategoriesTest {

    @Test
    fun `ids are unique`() {
        val ids = DefaultCategories.seeds.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every child has an existing parent`() {
        val parentIds = DefaultCategories.seeds.filter { it.parentId == null }.map { it.id }.toSet()
        DefaultCategories.seeds.filter { it.parentId != null }.forEach {
            assertTrue(it.parentId in parentIds, "孤儿分类：${it.id}")
        }
    }

    @Test
    fun `short names fit the 2-char grid cell`() {
        DefaultCategories.seeds.forEach {
            assertTrue(it.shortName.length <= 2, "${it.id} 简称超两字：${it.shortName}")
        }
    }

    @Test
    fun `exactly 11 top-level categories`() {
        assertEquals(11, DefaultCategories.seeds.count { it.parentId == null })
    }

    @Test
    fun `income seeds are valid`() {
        val income = DefaultCategories.incomeSeeds
        assertEquals(5, income.size)
        // 两库 ID 全局唯一，播种到同一张 categories 表不冲突
        val allIds = (DefaultCategories.seeds + income).map { it.id }
        assertEquals(allIds.size, allIds.toSet().size)
        income.forEach {
            assertTrue(it.kind == "INCOME", "${it.id} 收入种子 kind 错误")
            assertTrue(it.parentId == null, "收入种子应为一级：${it.id}")
            assertTrue(it.shortName.length <= 2, "${it.id} 简称超两字：${it.shortName}")
        }
    }
}
