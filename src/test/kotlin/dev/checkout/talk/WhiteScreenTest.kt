package dev.checkout.talk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Bug 4 — "The white screen", slides 27-28.
 *
 * combine() emits nothing until every input has emitted at least once. One silent flow
 * out of N and the screen has no first frame at all — not a slow frame, no frame.
 * A loader would not have helped: the flow was not slow, it was stuck.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WhiteScreenTest {

    private data class Screen(
        val products: String,
        val user: String,
        val currency: String,
        val promos: String,
    )

    @Test
    fun `combine renders nothing while one input stays silent`() = runTest {
        var rendered: Screen? = null

        combine(
            flowOf("products"),
            flowOf("user"),
            flowOf("RUB"),
            emptyFlow<String>(), // the promo flow that never emitted
            ::Screen,
        ).onEach { rendered = it }.launchIn(backgroundScope)

        advanceUntilIdle()

        assertNull(rendered) // white screen
    }

    @Test
    fun `combine renders only once the last input finally emits`() = runTest {
        val promos = MutableSharedFlow<String>()
        var rendered: Screen? = null

        val job = launch {
            combine(
                flowOf("products"),
                flowOf("user"),
                flowOf("RUB"),
                promos,
                ::Screen,
            ).collect { rendered = it }
        }

        advanceUntilIdle()
        assertNull(rendered) // still nothing: the screen waits on the least important feature

        promos.emit("promo")
        advanceUntilIdle()
        assertEquals("promo", rendered?.promos)

        job.cancel()
    }

    /**
     * The fix, in the shape CheckoutRepositoryBase.startReactiveFlow() uses:
     * a mandatory tier awaited imperatively gates the first frame, everything else
     * arrives later and blocks nothing.
     */
    @Test
    fun `tiered loading renders the first frame without the optional tier`() = runTest {
        val states = mutableListOf<State>()
        val optional: Flow<String> = emptyFlow() // still never emits

        launch {
            val main = loadMain() // Primary: products, user, currency
            states += State.Base(main)
            states += State.Enriched(main, loadAdditional()) // Secondary
            optional.onEach { states += State.Enriched(main, loadAdditional(), it) }
                .launchIn(backgroundScope)
        }

        advanceUntilIdle()

        // The screen has content, and keeps it, even though the optional tier never arrived.
        assertEquals(State.Base(Main("products", "user", "RUB")), states.first())
        assertEquals(null, (states.last() as State.Enriched).optional)
    }

    private data class Main(val products: String, val user: String, val currency: String)

    private sealed interface State {
        data class Base(val main: Main) : State
        data class Enriched(val main: Main, val additional: String, val optional: String? = null) : State
    }

    private suspend fun loadMain(): Main = Main(
        products = flowOf("products").first(),
        user = flowOf("user").first(),
        currency = flowOf("RUB").first(),
    )

    private suspend fun loadAdditional(): String = flowOf("deliveryPrices").first()
}
