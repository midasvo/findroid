package dev.jdtech.jellyfin.film.presentation.home

import android.content.SharedPreferences
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression test for the offline crash documented in BUGREPORT_ANALYSIS.md.
 *
 * Drives the REAL [HomeViewModel] against a repository that behaves as if the
 * device is offline — every call throws [IOException], exactly like the Jellyfin
 * SDK does with no network.
 *
 * Asserts that [HomeViewModel.loadData] does NOT leak an uncaught exception: the
 * offline failure must be caught and surfaced in [HomeState.error] for the UI to
 * show. Before the `coroutineScope { }` fix this test failed — `loadData()`
 * crashed the app on startup. [LoadDataConcurrencyMechanismTest] proves *why*.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelOfflineCrashTest {

    private var originalUncaughtHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        // viewModelScope is built with Dispatchers.Main.immediate; provide one.
        Dispatchers.setMain(StandardTestDispatcher())
        originalUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(originalUncaughtHandler)
        Dispatchers.resetMain()
    }

    @Test
    fun `loadData surfaces the offline failure in state without crashing`() {
        // An uncaught coroutine exception with no CoroutineExceptionHandler in
        // context ends up here; on a real device this handler kills the process.
        val uncaught = CompletableDeferred<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.complete(e) }

        val viewModel =
            HomeViewModel(
                repository = offlineRepository(),
                appPreferences = AppPreferences(defaultsOnlySharedPreferences()),
                database = unusedDao(),
            )

        viewModel.loadData()

        // loadData() runs on Dispatchers.Default; wait for it to settle.
        val state =
            runBlocking {
                withTimeoutOrNull(5_000) {
                    while (with(viewModel.state.value) { error == null || isLoading }) {
                        delay(20)
                    }
                    viewModel.state.value
                }
            }

        // The fix: nothing escapes -> the app does not crash.
        assertNull(
            "HomeViewModel.loadData() must not leak an uncaught exception while offline",
            runBlocking { withTimeoutOrNull(500) { uncaught.await() } },
        )

        // The offline failure is surfaced in HomeState instead, for the UI to show.
        assertNotNull("loadData() should have settled within the timeout", state)
        assertFalse("loadData() should finish loading", state!!.isLoading)
        assertNotNull("loadData() should surface the offline error in HomeState", state.error)
        assertTrue(
            "the surfaced error should be the offline IOException",
            generateSequence<Throwable>(state.error) { it.cause }.any { it is IOException },
        )
    }

    /**
     * Every repository call throws [IOException] — models a device with no network,
     * which is how the Jellyfin SDK fails. [HomeViewModel] only calls a handful of
     * these (getSuggestions / getResumeItems / getNextUp / getUserViews); the rest
     * are never reached.
     */
    private fun offlineRepository(): JellyfinRepository =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { proxy, method, args ->
            when {
                method.declaringClass == Any::class.java ->
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(proxy)
                        "toString" -> "offlineRepository"
                        "equals" -> proxy === args?.getOrNull(0)
                        else -> null
                    }
                else -> throw IOException("Simulated offline: ${method.name}")
            }
        } as JellyfinRepository

    /**
     * Not used by `loadData()` when there is no current server (see
     * [defaultsOnlySharedPreferences]); it only has to exist for the constructor.
     */
    private fun unusedDao(): ServerDatabaseDao =
        Proxy.newProxyInstance(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, _ ->
            throw NotImplementedError("ServerDatabaseDao.${method.name} not expected in this test")
        } as ServerDatabaseDao

    /**
     * A [SharedPreferences] whose getters return the supplied default value.
     * `AppPreferences` then reports its own declared defaults: `currentServer` =
     * null (so `loadData()` skips the database entirely) and the `home*` toggles =
     * true (so every section actually calls the repository).
     */
    private fun defaultsOnlySharedPreferences(): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                // AppPreferences.getValue always passes the default as the 2nd arg.
                "getString",
                "getBoolean",
                "getInt",
                "getLong",
                "getFloat",
                "getStringSet" -> args!![1]
                "contains" -> false
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "defaultsOnlySharedPreferences"
                "equals" -> proxy === args?.getOrNull(0)
                else -> throw UnsupportedOperationException("SharedPreferences.${method.name}")
            }
        } as SharedPreferences
}
