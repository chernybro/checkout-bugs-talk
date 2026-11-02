package dev.checkout.talk

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Bug 2 — "Phantom items from other orders", slides 15-18.
 *
 * ON DELETE CASCADE is not a property of the schema alone. It only fires while the
 * `foreign_keys` pragma is on. Room turns it on at runtime and off inside migrations,
 * so the very same DELETE statement means two different things depending on where it runs.
 */
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class PhantomOrderItemsTest : SQLiteDatabaseTest by SQLiteDatabaseTest(
    create = {
        writeableDatabase(version = 1) {
            onCreate {
                execSQL(
                    "CREATE TABLE OrderEntity (" +
                        "id INTEGER PRIMARY KEY NOT NULL" +
                        ")"
                )
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

    /** The surprising result: this is what the migration did. */
    @Test
    fun `DELETE inside a migration leaves the children behind`() {
        db.seedOrderWithProduct()

        db.asMigration { BrokenOrderQueueMigration().migrate(this) }

        assertEquals(0, db.countOf("OrderEntity"))
        assertEquals(2, db.countOf("OrderProductEntity")) // orphans, waiting for the next orders to reuse ids 1 and 2
    }

    /** The same DELETE through Room's runtime, where the pragma is on. */
    @Test
    fun `DELETE at runtime cascades`() {
        db.seedOrderWithProduct()

        db.asRoomRuntime { execSQL("DELETE FROM OrderEntity") }

        assertEquals(0, db.countOf("OrderEntity"))
        assertEquals(0, db.countOf("OrderProductEntity"))
    }

    /** The fix: walk the children ourselves, because the pragma cannot be trusted here. */
    @Test
    fun `findAndClearTableAndChildren clears the children inside a migration`() {
        db.seedOrderWithProduct()

        db.asMigration { FixedOrderQueueMigration().migrate(this) }

        assertEquals(0, db.countOf("OrderEntity"))
        assertEquals(0, db.countOf("OrderProductEntity"))
    }

    /**
     * The systemic fix (): after every migration, ask SQLite itself which rows
     * now point nowhere and delete them. No migration author has to remember anything.
     */
    @Test
    fun `foreign_key_check finds the orphan the migration left behind`() {
        db.seedOrderWithProduct()
        db.asMigration { execSQL("DELETE FROM OrderEntity") }

        val orphanRowIds = db.query("PRAGMA foreign_key_check").use { cursor ->
            val table = cursor.getColumnIndexOrThrow("table")
            val rowId = cursor.getColumnIndexOrThrow("rowid")
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(table) to cursor.getLong(rowId))
            }
        }

        assertEquals(listOf("OrderProductEntity" to 1L, "OrderProductEntity" to 2L), orphanRowIds)

        orphanRowIds.forEach { (table, rowId) -> db.execSQL("DELETE FROM $table WHERE rowid=$rowId") }
        assertEquals(0, db.countOf("OrderProductEntity"))
    }

    /** What the orphan then does: it attaches to the next order that reuses the id. */
    @Test
    fun `an orphaned product attaches to the next order reusing the id`() {
        db.execSQL("INSERT INTO OrderEntity (id) VALUES (1)")
        db.execSQL("INSERT INTO OrderProductEntity (id, orderId) VALUES (100, 1)")

        db.asMigration { execSQL("DELETE FROM OrderEntity") }
        db.execSQL("INSERT INTO OrderEntity (id) VALUES (1)") // a brand new order, same autoincrement id
        db.execSQL("INSERT INTO OrderProductEntity (id, orderId) VALUES (200, 1)")

        // The new order now carries an item the user never put in it.
        assertEquals(2, db.countOf("OrderProductEntity", "orderId = 1"))
    }

    private fun SupportSQLiteDatabase.seedOrderWithProduct() {
        execSQL("INSERT INTO OrderEntity (id) VALUES (1)")
        execSQL("INSERT INTO OrderProductEntity (id, orderId) VALUES (1, 1)")
        execSQL("INSERT INTO OrderEntity (id) VALUES (2)")
        execSQL("INSERT INTO OrderProductEntity (id, orderId) VALUES (2, 2)")
    }

    /** Room runs migrations with foreign keys disabled. */
    private fun SupportSQLiteDatabase.asMigration(block: SupportSQLiteDatabase.() -> Unit) {
        execSQL("PRAGMA foreign_keys = OFF")
        block()
    }

    /** Room enables foreign keys for normal DAO traffic. */
    private fun SupportSQLiteDatabase.asRoomRuntime(block: SupportSQLiteDatabase.() -> Unit) {
        execSQL("PRAGMA foreign_keys = ON")
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
