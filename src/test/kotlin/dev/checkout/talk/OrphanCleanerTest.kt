package dev.checkout.talk

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Backs the slide version of the cleaner: it really does find and remove exactly the
 * rows a migration orphaned, and it needs no list of tables to do it.
 */
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class OrphanCleanerTest : SQLiteDatabaseTest by SQLiteDatabaseTest(
    create = {
        writeableDatabase(version = 1) {
            onCreate {
                execSQL("CREATE TABLE OrderEntity (id INTEGER PRIMARY KEY NOT NULL)")
                execSQL(
                    "CREATE TABLE OrderProductEntity (" +
                        "id INTEGER PRIMARY KEY NOT NULL, " +
                        "orderId INTEGER NOT NULL, " +
                        "FOREIGN KEY(orderId) REFERENCES OrderEntity(id) ON DELETE CASCADE" +
                        ")"
                )
            }
        }
    }
) {

    @Test
    fun `clears exactly the rows the migration orphaned`() {
        db.execSQL("INSERT INTO OrderEntity (id) VALUES (1), (2)")
        db.execSQL("INSERT INTO OrderProductEntity (id, orderId) VALUES (10, 1), (11, 2)")

        // The migration clears one order and leaves its product behind.
        db.asMigration { execSQL("DELETE FROM OrderEntity WHERE id = 1") }
        assertEquals(2, db.countOf("OrderProductEntity"))

        db.clearRowsWithoutParent()

        // The orphan is gone; the product of the surviving order is untouched.
        assertEquals(1, db.countOf("OrderProductEntity"))
        assertEquals(1, db.countOf("OrderProductEntity", "orderId = 2"))
    }

    @Test
    fun `does nothing when every row still has its parent`() {
        db.execSQL("INSERT INTO OrderEntity (id) VALUES (1)")
        db.execSQL("INSERT INTO OrderProductEntity (id, orderId) VALUES (10, 1)")

        db.clearRowsWithoutParent()

        assertEquals(1, db.countOf("OrderEntity"))
        assertEquals(1, db.countOf("OrderProductEntity"))
    }

    private fun SupportSQLiteDatabase.asMigration(block: SupportSQLiteDatabase.() -> Unit) {
        execSQL("PRAGMA foreign_keys = OFF")
        block()
    }

    private fun SupportSQLiteDatabase.countOf(table: String, where: String? = null): Int {
        val sql = "SELECT COUNT(*) FROM $table" + if (where == null) "" else " WHERE $where"
        return query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }
}
