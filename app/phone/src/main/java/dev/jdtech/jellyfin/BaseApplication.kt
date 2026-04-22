package dev.jdtech.jellyfin

import android.app.Application
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.google.android.material.color.DynamicColors
import dagger.hilt.android.HiltAndroidApp
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadQueue
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.work.MpvCleanupWorker
import dev.jdtech.jellyfin.work.OrphanSweepWorker
import dev.jdtech.jellyfin.work.SyncWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import timber.log.Timber

@HiltAndroidApp
class BaseApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var appPreferences: AppPreferences

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var downloadQueue: DownloadQueue

    @Inject lateinit var downloader: Downloader

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val mode =
                when (appPreferences.getValue(appPreferences.theme)) {
                    "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        if (appPreferences.getValue(appPreferences.dynamicColors)) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }

        val workManager = WorkManager.getInstance(applicationContext)

        scheduleUserDataSync(workManager)
        scheduleMpvCleanup(workManager)

        // Schedule daily orphan sweep to clean up stale downloads.
        workManager.enqueueUniquePeriodicWork(
            "orphan-sweep",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<OrphanSweepWorker>(1, TimeUnit.DAYS).build(),
        )

        // Re-attach any downloads left in-flight by a previous process (survives
        // process death because Android DownloadManager is a system service).
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Drop DB rows and files left behind by crashes / external deletions
                // before we re-attach to live DownloadManager jobs.
                downloader.sweepOrphans()
            } catch (e: Exception) {
                Timber.e(e, "Failed to sweep orphan downloads")
            }
            try {
                downloadQueue.restoreAll()
            } catch (e: Exception) {
                Timber.e(e, "Failed to restore active downloads")
            }
        }
    }

    @OptIn(ExperimentalCoilApi::class, ExperimentalTime::class)
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(cacheStrategy = { CacheControlCacheStrategy() }))
                add(SvgDecoder.Factory())
            }
            .diskCachePolicy(
                if (appPreferences.getValue(appPreferences.imageCache)) CachePolicy.ENABLED
                else CachePolicy.DISABLED
            )
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(
                        appPreferences.getValue(appPreferences.imageCacheSize) * 1024L * 1024
                    )
                    .build()
            }
            .crossfade(true)
            .build()
    }

    private fun scheduleUserDataSync(workManager: WorkManager) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncWorkRequest =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

        workManager
            .enqueueUniqueWork(
                uniqueWorkName = "syncUserData",
                existingWorkPolicy = ExistingWorkPolicy.KEEP,
                request = syncWorkRequest
            )
    }

    private fun scheduleMpvCleanup(workManager: WorkManager) {
        val constraints = Constraints.Builder()
            .setRequiresDeviceIdle(true)
            .setRequiresBatteryNotLow(true)
            .build()

        val cleanupRequest =
            OneTimeWorkRequestBuilder<MpvCleanupWorker>()
                .setConstraints(constraints)
                .build()

        workManager.enqueueUniqueWork(
            uniqueWorkName = "mpv_cleanup",
            existingWorkPolicy = ExistingWorkPolicy.KEEP,
            request = cleanupRequest
        )
    }
}
