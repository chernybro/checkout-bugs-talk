package dev.checkout.talk

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "Would DELETE FROM have helped instead of DROP TABLE?"
 *
 * Short answer: it would have hidden this occurrence, and only by accident. The cause was
 * never the DROP — it was the grandchild that nobody cleared. The DROP only decided *when*
 * the orphans became visible.
 */
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class WouldDeleteHaveHelpedTest : SQLiteDatabaseTest by SQLiteDatabaseTest(
    create = { writeableDatabase(version = 1) { onCreate { } } }
) {

    /** 1. With AUTOINCREMENT, DELETE FROM keeps the counter, so no id is ever reused. */
    @Test
    fun `DELETE FROM with AUTOINCREMENT does not re-attach the orphan`() {
        db.createSchema(autoincrement = true)
        db.insertProductWithRid(rid = "OLD")

        db.asMigration { execSQL("DELETE FROM product") }
        val newProductId = db.insertProduct()

        assertEquals(2, newProductId)                       // counter survived
        assertEquals(emptyList(), db.ridsOf(newProductId))  // orphan left behind, invisible
        assertEquals(1, db.orphanCount())                   // but it is still in the table
    }

    /** 2. The same DELETE without the AUTOINCREMENT keyword re-attaches it anyway. */
    @Test
    fun `DELETE FROM without AUTOINCREMENT re-attaches the orphan`() {
        db.createSchema(autoincrement = false)
        db.insertProductWithRid(rid = "OLD")

        db.asMigration { execSQL("DELETE FROM product") }
        val newProductId = db.insertProduct()

        assertEquals(1, newProductId)                    // max(rowid) + 1, and the table is empty
        assertEquals(listOf("OLD"), db.ridsOf(newProductId))
    }

    /**
     * 3. Even with AUTOINCREMENT, the next routine schema change brings the bug back.
     * This is Room's own table-rebuild pattern, as used on these very tables in
     * Migration46to47: CREATE `_new_x`, copy rows, DROP `x`, RENAME. On an empty table
     * there is nothing to copy, so the counter starts from scratch.
     */
    @Test
    fun `a later Room table rebuild re-attaches the orphan that DELETE FROM left`() {
        db.createSchema(autoincrement = true)
        db.insertProductWithRid(rid = "OLD")

        db.asMigration { execSQL("DELETE FROM product") }   // migration N: looks safe
        db.asMigration { rebuildProductTable() }            // migration N+k: adds a column
        val newProductId = db.insertProduct()

        assertEquals(1, newProductId)                       // counter is gone again
        assertEquals(listOf("OLD"), db.ridsOf(newProductId))
    }

    /** 4. Clearing the grandchild is the only thing that works in all three cases. */
    @Test
    fun `clearing the child table is what actually fixes it`() {
        db.createSchema(autoincrement = true)
        db.insertProductWithRid(rid = "OLD")

        db.asMigration {
            execSQL("DELETE FROM product")
            execSQL("DELETE FROM rid") // the line Migration42to43 was missing
        }
        db.asMigration { rebuildProductTable() }
        val newProductId = db.insertProduct()

        assertEquals(emptyList(), db.ridsOf(newProductId))
        assertEquals(0, db.orphanCount())
    }

    // --- helpers ---

    private fun SupportSQLiteDatabase.createSchema(autoincrement: Boolean) {
        val pk = if (autoincrement) "INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL" else "INTEGER PRIMARY KEY NOT NULL"
        execSQL("CREATE TABLE product (`id` $pk)")
        execSQL(
            "CREATE TABLE rid (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`productId` INTEGER NOT NULL, " +
                "`rid` TEXT NOT NULL, " +
                "FOREIGN KEY(`productId`) REFERENCES product(`id`) ON DELETE CASCADE)"
        )
    }

    /** Room's rebuild of `product` to add a column, on a table that is already empty. */
    private fun SupportSQLiteDatabase.rebuildProductTable() {
        execSQL("CREATE TABLE `_new_product` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `extra` TEXT)")
        execSQL("INSERT INTO `_new_product` (`id`) SELECT `id` FROM product")
        execSQL("DROP TABLE product")
        execSQL("ALTER TABLE `_new_product` RENAME TO product")
    }

    private fun SupportSQLiteDatabase.insertProductWithRid(rid: String) {
        val productId = insertProduct()
        execSQL("INSERT INTO rid (productId, rid) VALUES ($productId, '$rid')")
    }

    private fun SupportSQLiteDatabase.insertProduct(): Long {
        execSQL("INSERT INTO product (`id`) VALUES (NULL)")
        return query("SELECT last_insert_rowid()").use { it.moveToFirst(); it.getLong(0) }
    }

    private fun SupportSQLiteDatabase.ridsOf(productId: Long): List<String> =
        query("SELECT rid FROM rid WHERE productId = $productId").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun SupportSQLiteDatabase.orphanCount(): Int =
        query("PRAGMA foreign_key_check").use { cursor ->
            var n = 0
            while (cursor.moveToNext()) n++
            n
        }

    private fun SupportSQLiteDatabase.asMigration(block: SupportSQLiteDatabase.() -> Unit) {
        execSQL("PRAGMA foreign_keys = OFF")
        block()
    }
}
