package dev.jdtech.jellyfin.film.presentation.home

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mechanism-level proof for the crash documented in BUGREPORT_ANALYSIS.md.
 *
 * Reproduces the structured-concurrency behaviour of [HomeViewModel.loadData] in
 * isolation — no Android, no ViewModel, no Jellyfin SDK — so the crash can be
 * demonstrated deterministically without a device.
 *
 * `loadData()` runs, in essence:
 * ```
 * viewModelScope.launch(Dispatchers.Default) {   // viewModelScope = SupervisorJob + ...
 *     try {
 *         awaitAll(async { ... }, async { ... }, async { ... }, async { ... })
 *     } catch (e: Exception) { ... }
 * }
 * ```
 *
 * Each test builds a scope shaped like `viewModelScope` (a [SupervisorJob]) with a
 * [CoroutineExceptionHandler] installed, so we can observe whether an exception
 * "escapes". Escaping == reaching the scope's exception handler. In the real
 * `viewModelScope`, which has no handler, that handler is the app's uncaught
 * exception handler == process crash.
 */
class LoadDataConcurrencyMechanismTest {

    /**
     * THE BUG. An exception thrown inside an `async { }` child coroutine propagates
     * to the parent [launch] Job *independently* of `awaitAll()` / `await()`. The
     * `try/catch` around `awaitAll` catches the re-thrown exception, yet the failure
     * still reaches the scope's exception handler — i.e. the app still crashes.
     */
    @Test
    fun `async child failure escapes the try-catch around awaitAll`() = runBlocking {
        val escaped = CompletableDeferred<Throwable>()
        val scope =
            CoroutineScope(
                SupervisorJob() + CoroutineExceptionHandler { _, e -> escaped.complete(e) }
            )

        var caughtByTryCatch: Throwable? = null
        val job =
            scope.launch(Dispatchers.Default) {
                try {
                    awaitAll(
                        async { throw IOException("offline: getSuggestions()") },
                        async { delay(50) }, // loadResumeItems()
                        async { delay(50) }, // loadNextUpItems()
                        async { delay(50) }, // loadViews()
                    )
                } catch (e: Exception) {
                    caughtByTryCatch = e
                }
            }
        job.join()

        // The developer DID add a try/catch, and it even caught something...
        assertNotNull("try/catch around awaitAll caught an exception", caughtByTryCatch)
        // ...but the async-child failure ALSO propagated to the parent Job and
        // reached the scope's exception handler. In the real viewModelScope — which
        // installs no handler — this is the app's uncaught exception handler == crash.
        val escapedException = withTimeoutOrNull(2_000) { escaped.await() }
        assertNotNull(
            "async-child failure escaped the try/catch and would crash the app",
            escapedException,
        )
        assertTrue(
            "the escaped exception is the offline IOException",
            escapedException is IOException,
        )
    }

    /**
     * UPSTREAM behaviour. Plain sequential `suspend` calls in the `launch` body. A
     * thrown exception is an ordinary throw inside the coroutine body, fully
     * contained by the `try/catch`. Nothing escapes — no crash.
     */
    @Test
    fun `sequential suspend calls are fully contained by the try-catch`() = runBlocking {
        val escaped = CompletableDeferred<Throwable>()
        val scope =
            CoroutineScope(
                SupervisorJob() + CoroutineExceptionHandler { _, e -> escaped.complete(e) }
            )

        var caughtByTryCatch: Throwable? = null
        val job =
            scope.launch(Dispatchers.Default) {
                try {
                    offlineCall("getSuggestions()")
                    offlineCall("getResumeItems()")
                    offlineCall("getNextUp()")
                    offlineCall("getUserViews()")
                } catch (e: Exception) {
                    caughtByTryCatch = e
                }
            }
        job.join()

        assertNotNull("try/catch caught the exception", caughtByTryCatch)
        assertNull(
            "nothing escaped: sequential calls do not crash the app",
            withTimeoutOrNull(500) { escaped.await() },
        )
    }

    /**
     * PROPOSED FIX — Option A in BUGREPORT_ANALYSIS.md. Wrapping the `awaitAll` in a
     * `coroutineScope { }` makes a child failure surface as an ordinary thrown
     * exception at the `coroutineScope` call site — caught by the `try/catch`, never
     * propagating to the parent Job. Parallel loading is preserved.
     */
    @Test
    fun `coroutineScope wrapper keeps the async failure inside the try-catch`() = runBlocking {
        val escaped = CompletableDeferred<Throwable>()
        val scope =
            CoroutineScope(
                SupervisorJob() + CoroutineExceptionHandler { _, e -> escaped.complete(e) }
            )

        var caughtByTryCatch: Throwable? = null
        val job =
            scope.launch(Dispatchers.Default) {
                try {
                    coroutineScope {
                        awaitAll(
                            async { throw IOException("offline: getSuggestions()") },
                            async { delay(50) },
                            async { delay(50) },
                            async { delay(50) },
                        )
                    }
                } catch (e: Exception) {
                    caughtByTryCatch = e
                }
            }
        job.join()

        assertNotNull("try/catch caught the exception", caughtByTryCatch)
        assertNull(
            "nothing escaped: coroutineScope { } contains the failure",
            withTimeoutOrNull(500) { escaped.await() },
        )
    }

    /** Models a network call that fails because the device is offline. */
    private suspend fun offlineCall(name: String): Nothing {
        delay(10)
        throw IOException("offline: $name")
    }
}
