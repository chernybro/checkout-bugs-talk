package dev.checkout.talk

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Bug 2 — identifiers from a previous app version attach to a new order.
 *
 * Migration42to43 dropped and recreated QueuedOrder and
 * QueuedProduct, but left QueuedProductRid alone — the
 * grandchild, two edges down. Its rows survived, pointing at productIds that no longer
 * existed. DROP TABLE also removes the table's sqlite_sequence row, so AUTOINCREMENT
 * restarted at 1 and the next order's product took the id the orphans were waiting for.
 *
 * A three-level schema makes the surviving grandchild visible.
 */
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class PhantomRidsTest : SQLiteDatabaseTest by SQLiteDatabaseTest(
    create = { writeableDatabase(version = 1) { onCreate { createOrderQueueSchema() } } }
) {

    /** The bug, end to end: a new order carries a rid from the previous app version. */
    @Test
    fun `an order created after the migration carries rids from the old version`() {
        // Old version: one local order that was never submitted.
        db.insertOrder(orderId = 1, productId = 1, rid = "OLD-RID")

        db.migration42to43()

        // New version: a brand new order, and the ids start over from 1.
        db.insertOrder(orderId = 1, productId = 1, rid = "NEW-RID")

        assertEquals(
            listOf("NEW-RID", "OLD-RID"),
            db.ridsOfOrder(orderId = 1).sorted(),
        )
    }

    /** Why it fired at all: DROP TABLE resets AUTOINCREMENT, DELETE FROM does not. */
    @Test
    fun `DROP TABLE restarts autoincrement but DELETE FROM does not`() {
        db.insertOrder(orderId = 1, productId = 1, rid = "r1")

        db.asMigration { execSQL("DELETE FROM QueuedProduct") }
        assertEquals(2, db.insertProduct(orderId = 1)) // sqlite_sequence survived

        db.asMigration {
            execSQL("DROP TABLE QueuedProduct")
            createProductTable()
        }
        assertEquals(1, db.insertProduct(orderId = 1)) // sequence gone, ids restart
    }

    /** The fix that shipped, Migration44to45: clear the grandchild too. */
    @Test
    fun `clearing all three tables leaves nothing to re-attach`() {
        db.insertOrder(orderId = 1, productId = 1, rid = "OLD-RID")

        db.asMigration {
            execSQL("DELETE FROM QueuedOrder")
            execSQL("DELETE FROM QueuedProduct")
            execSQL("DELETE FROM QueuedProductRid")
        }
        db.insertOrder(orderId = 1, productId = 1, rid = "NEW-RID")

        assertEquals(listOf("NEW-RID"), db.ridsOfOrder(orderId = 1))
    }

    /**
     * What `foreign_key_check` would have said, run once after the broken migration.
     * It needs no list of tables and cannot go stale.
     */
    @Test
    fun `foreign_key_check names the orphaned rids with no table list`() {
        db.insertOrder(orderId = 1, productId = 1, rid = "OLD-RID")

        db.migration42to43()

        assertEquals(listOf("QueuedProductRid"), db.orphanTables())
    }

    // --- the migration under test, verbatim in shape ---

    private fun SupportSQLiteDatabase.migration42to43() = asMigration {
        execSQL("DROP TABLE IF EXISTS `QueuedOrder`")
        execSQL("DROP TABLE IF EXISTS `QueuedProduct`")
        // QueuedProductRid is not in the list. That is the whole bug.
        createMainInfoTable()
        createProductTable()
    }

    // --- helpers ---

    private fun SupportSQLiteDatabase.insertOrder(orderId: Int, productId: Int, rid: String) {
        execSQL("INSERT INTO QueuedOrder (id) VALUES ($orderId)")
        execSQL("INSERT INTO QueuedProduct (id, orderId) VALUES ($productId, $orderId)")
        execSQL("INSERT INTO QueuedProductRid (productId, rid) VALUES ($productId, '$rid')")
    }

    private fun SupportSQLiteDatabase.insertProduct(orderId: Int): Long {
        execSQL("INSERT INTO QueuedProduct (orderId) VALUES ($orderId)")
        return query("SELECT last_insert_rowid()").use { it.moveToFirst(); it.getLong(0) }
    }

    private fun SupportSQLiteDatabase.ridsOfOrder(orderId: Int): List<String> = query(
        "SELECT r.rid FROM QueuedProductRid r " +
            "JOIN QueuedProduct p ON r.productId = p.id " +
            "WHERE p.orderId = $orderId"
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    private fun SupportSQLiteDatabase.orphanTables(): List<String> =
        query("PRAGMA foreign_key_check").use { cursor ->
            val table = cursor.getColumnIndexOrThrow("table")
            buildList { while (cursor.moveToNext()) add(cursor.getString(table)) }.distinct()
        }

    private fun SupportSQLiteDatabase.asMigration(block: SupportSQLiteDatabase.() -> Unit) {
        execSQL("PRAGMA foreign_keys = OFF") // Room does this for every migration
        block()
    }
}

private fun SupportSQLiteDatabase.createMainInfoTable() = execSQL(
    "CREATE TABLE IF NOT EXISTS `QueuedOrder` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)"
)

private fun SupportSQLiteDatabase.createProductTable() = execSQL(
    "CREATE TABLE IF NOT EXISTS `QueuedProduct` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`orderId` INTEGER NOT NULL, " +
        "FOREIGN KEY(`orderId`) REFERENCES `QueuedOrder`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)"
)

private fun SupportSQLiteDatabase.createOrderQueueSchema() {
    createMainInfoTable()
    createProductTable()
    execSQL(
        "CREATE TABLE IF NOT EXISTS `QueuedProductRid` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`productId` INTEGER NOT NULL, " +
            "`rid` TEXT NOT NULL, " +
            "FOREIGN KEY(`productId`) REFERENCES `QueuedProduct`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE)"
    )
}
