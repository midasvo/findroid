package dev.jdtech.jellyfin.utils

import android.content.SharedPreferences
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [isOfflineModeActive] — the shared decision used by both RepositoryModule (which
 * data source to inject) and MainViewModel (what the UI shows).
 *
 * Effective offline = manual offlineMode preference OR no network connectivity. Covers the full
 * truth table.
 */
class OfflineModeResolutionTest {

    @Test
    fun `online with preference off resolves to online`() {
        assertFalse(
            isOfflineModeActive(
                appPreferences(offlineMode = false),
                connectivity(online = true),
            )
        )
    }

    @Test
    fun `no connectivity with preference off resolves to offline`() {
        // The auto-detect case: the user never toggled offline mode, but there is no network.
        assertTrue(
            isOfflineModeActive(
                appPreferences(offlineMode = false),
                connectivity(online = false),
            )
        )
    }

    @Test
    fun `preference on while online resolves to offline`() {
        // The manual override is always respected.
        assertTrue(
            isOfflineModeActive(
                appPreferences(offlineMode = true),
                connectivity(online = true),
            )
        )
    }

    @Test
    fun `preference on without connectivity resolves to offline`() {
        assertTrue(
            isOfflineModeActive(
                appPreferences(offlineMode = true),
                connectivity(online = false),
            )
        )
    }

    private fun connectivity(online: Boolean): NetworkConnectivity =
        object : NetworkConnectivity {
            override fun isOnline() = online
        }

    /** Real AppPreferences backed by a fake SharedPreferences with a fixed offlineMode value. */
    private fun appPreferences(offlineMode: Boolean): AppPreferences =
        AppPreferences(fakeSharedPreferences(mapOf("pref_offline_mode" to offlineMode)))

    private fun fakeSharedPreferences(booleans: Map<String, Boolean>): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                // AppPreferences.getValue passes (key, defaultValue).
                "getBoolean" -> booleans[args!![0] as String] ?: args[1]
                "getString",
                "getInt",
                "getLong",
                "getFloat",
                "getStringSet" -> args!![1]
                "contains" -> false
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "fakeSharedPreferences"
                "equals" -> proxy === args?.getOrNull(0)
                else -> throw UnsupportedOperationException("SharedPreferences.${method.name}")
            }
        } as SharedPreferences
}
