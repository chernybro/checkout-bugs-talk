package dev.checkout.talk

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Bug 2 on the real Room entities — `OrderEntity` and `OrderProductEntity`, slides 15-18.
 *
 * Both have `@PrimaryKey(autoGenerate = true)`, same as the production queue
 * (`QueuedOrder`, `QueuedProduct`,
 * `QueuedProductRid` all do). That matters: AUTOINCREMENT keeps a high-water
 * mark in `sqlite_sequence`, so which statement the migration used decides whether the
 * orphans merely linger or actually attach to somebody else's order.
 */
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class OrderTablesCascadeTest {

    private lateinit var db: TalkDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().context
        db = Room.inMemoryDatabaseBuilder(context, TalkDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    /** The production path. This is the half everyone expects. */
    @Test
    fun `deleting through Room cascades to the products`() = runBlocking {
        val orders = db.orders()
        val orderId = orders.seedOrderWithTwoProducts()

        orders.delete(OrderEntity(id = orderId))

        assertEquals(0, orders.orderCount())
        assertEquals(0, orders.productCount())
    }

    /** The migration path. This is the half that shipped the bug. */
    @Test
    fun `the same DELETE inside a migration leaves the products behind`() = runBlocking {
        val orders = db.orders()
        orders.seedOrderWithTwoProducts()

        db.asMigration { execSQL("DELETE FROM OrderEntity") }

        assertEquals(0, orders.orderCount())
        assertEquals(2, orders.productCount()) // orphans: they outlived their order
    }

    /**
     * But DELETE alone does not hand them to anybody: AUTOINCREMENT remembers the
     * high-water mark, so the next order gets a fresh id and the orphans keep pointing
     * at an id that will never come back.
     */
    @Test
    fun `after a migration DELETE the orphans do not attach to the next order`() = runBlocking {
        val orders = db.orders()
        orders.seedOrderWithTwoProducts()

        db.asMigration { execSQL("DELETE FROM OrderEntity") }
        val nextOrderId = orders.insert(OrderEntity())

        assertEquals(2, nextOrderId) // the counter survived DELETE FROM
        assertEquals(0, orders.productsOf(nextOrderId))
    }

    /**
     * the shape that actually shipped: the migration dropped and recreated
     * the table. DROP TABLE also removes its `sqlite_sequence` row, so ids restart at 1 —
     * and the orphans were waiting on exactly that id.
     */
    @Test
    fun `after a migration that drops the table the orphans attach to the next order`() = runBlocking {
        val orders = db.orders()
        orders.seedOrderWithTwoProducts()

        db.asMigration {
            execSQL("DROP TABLE OrderEntity")
            execSQL("CREATE TABLE `OrderEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
        }
        val nextOrderId = orders.insert(OrderEntity())

        assertEquals(1, nextOrderId) // the counter went with the table
        assertEquals(2, orders.productsOf(nextOrderId)) // two items nobody ordered
    }

    /** @return the id SQLite assigned to the order. */
    private suspend fun OrderDao.seedOrderWithTwoProducts(): Long {
        val orderId = insert(OrderEntity())
        insert(OrderProductEntity(orderId = orderId))
        insert(OrderProductEntity(orderId = orderId))
        return orderId
    }

    private fun OrderDao.productsOf(orderId: Long): Int =
        db.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM OrderProductEntity WHERE orderId = $orderId")
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }

    /** Room runs every migration with foreign keys disabled. */
    private fun TalkDatabase.asMigration(block: SupportSQLiteDatabase.() -> Unit) {
        openHelper.writableDatabase.run {
            execSQL("PRAGMA foreign_keys = OFF")
            block()
            execSQL("PRAGMA foreign_keys = ON")
        }
    }
}
