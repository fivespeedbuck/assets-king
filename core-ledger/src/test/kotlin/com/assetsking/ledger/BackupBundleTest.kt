package com.assetsking.ledger

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BackupBundleTest {
    @Test
    fun packsDatabaseAndPreferencesIntoOneContainer() {
        val database = "SQLite format 3\u0000ledger".toByteArray()
        val preferences = "{\"theme_key\":\"light-green\"}".toByteArray()

        val unpacked = requireNotNull(BackupBundle.unpack(BackupBundle.pack(database, preferences)))

        assertContentEquals(database, unpacked.database)
        assertContentEquals(preferences, unpacked.preferencesJson)
    }

    @Test
    fun legacyDatabaseBytesAreNotMistakenForABundle() {
        assertNull(BackupBundle.unpack("SQLite format 3\u0000".toByteArray()))
    }

    @Test
    fun truncatedBundleIsRejected() {
        val packed = BackupBundle.pack(byteArrayOf(1, 2, 3), byteArrayOf(4))

        assertFailsWith<IllegalArgumentException> { BackupBundle.unpack(packed.copyOf(packed.size - 1)) }
    }

    @Test
    fun invalidDeclaredLengthsAreRejectedBeforeAllocation() {
        val packed = BackupBundle.pack(byteArrayOf(1, 2, 3), byteArrayOf(4))
        packed[5] = 0x7f

        assertFailsWith<IllegalArgumentException> { BackupBundle.unpack(packed) }
    }
}
