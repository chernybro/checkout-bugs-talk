package dev.checkout.talk

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Bug 1 — "The frozen app", slides 11-12.
 *
 * The order-save transaction awaited a value it could only get by going back to the
 * database (and, three layers down, to the network). The transaction holds the write
 * lock for the whole wait, so every other database call queues behind it.
 */
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class FrozenAppTest {

    private lateinit var db: TalkDatabase
    private lateinit var elsewhere: CoroutineScope

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().context
        db = Room.inMemoryDatabaseBuilder(context, TalkDatabase::class.java).build()
        elsewhere = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        elsewhere.cancel()
        db.close()
    }

    /**
     * the shape that actually froze the app: the transaction awaits a flow
     * whose producer needs the same database. Neither side can proceed.
     */
    @Test
    fun `a transaction awaiting a database-backed flow never returns`() = runBlocking {
        val shippings: Flow<Int> = flow {
            // The shared producer reads the same database from another scope.
            emit(elsewhere.async { db.orderDao().count() }.await())
        }

        val finished = withTimeoutOrNull(3.seconds) {
            db.withTransaction {
                db.orderDao().insert(OrderRow(1))
                shippings.first() // deadlock: this read queues behind our own write lock
            }
        }

        assertNull(finished) // the transaction never completed, and the lock was never released
    }

    /**
     * the other half: a network call three layers under a use case.
     * The transaction stays open for the whole request.
     * A short delay stands in for network latency here.
     */
    @Test
    fun `a slow call inside a transaction blocks every other database call`() = runBlocking {
        val networkDelay = 500.milliseconds
        val readFinished = AtomicBoolean(false)

        var reader: Job? = null
        db.withTransaction {
            db.orderDao().insert(OrderRow(1))
            // An ordinary read on an unrelated screen, started while the transaction is open.
            reader = elsewhere.launch {
                db.orderDao().count()
                readFinished.set(true)
            }
            delay(networkDelay) // getStocksInfo -> repository -> remote source -> HTTP
            assertFalse(readFinished.get(), "the read should still be queued behind the transaction")
        }

        reader?.join()
        assertTrue(readFinished.get()) // it only got through once the lock was released
    }

    /**
     * The systemic fix: mark the transaction's coroutine context and refuse network
     * calls made from inside it. Reproduced locally, because the real guard lives in
     * the network layer and must not be removed from it.
     */
    @Test
    fun `the marker turns the freeze into a loud failure`() = runBlocking {
        val error = assertFailsWith<IllegalStateException> {
            db.guardedTransaction {
                db.orderDao().insert(OrderRow(1))
                requestSomething("https://example.com/stocks")
            }
        }

        assertEquals(
            "prohibit network request from database transaction Coroutine Context. " +
                "Url: https://example.com/stocks",
            error.message,
        )
    }

    /** A transaction marker for the coroutine context. */
    private object DbTransactionMarker : CoroutineContext.Element {
        override val key: CoroutineContext.Key<*> get() = Key
        object Key : CoroutineContext.Key<DbTransactionMarker>
    }

    private fun CoroutineContext.isInDbTransaction(): Boolean = this[DbTransactionMarker.Key] != null

    private suspend fun <R> TalkDatabase.guardedTransaction(block: suspend () -> R): R =
        kotlinx.coroutines.withContext(DbTransactionMarker) { withTransaction(block) }

    private suspend fun requestSomething(url: String): Nothing {
        if (coroutineContext.isInDbTransaction()) {
            error("prohibit network request from database transaction Coroutine Context. Url: $url")
        }
        error("unreachable in this test")
    }
}
