package dev.jdtech.jellyfin.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.player.DeviceCapabilityReportBuilder
import dev.jdtech.jellyfin.player.DeviceProfileBuilder
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Inject

/**
 * Backs the About screen's "Export device profile" action.
 *
 * Lives in `core` because both the device-profile builder (in `data`) and
 * `AppPreferences` (in `settings`) need to be assembled, and `core` is the
 * lowest module that depends on both. The AboutScreen itself stays in
 * `app/phone` and consumes this VM via `hiltViewModel()`.
 */
@HiltViewModel
class AboutViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val deviceProfileBuilder: DeviceProfileBuilder,
    private val appPreferences: AppPreferences,
) : ViewModel() {

    /**
     * Builds a pretty-printed JSON capability report for the current device.
     * Caller passes in build metadata so we don't pull a BuildConfig from any
     * specific app module.
     */
    fun buildCapabilityReportJson(
        findroidVersion: String,
        findroidBuildType: String,
    ): String {
        val report = DeviceCapabilityReportBuilder.build(
            context = context,
            deviceProfileBuilder = deviceProfileBuilder,
            findroidVersion = findroidVersion,
            findroidBuildType = findroidBuildType,
            transcodeDolbyVision = appPreferences.getValue(appPreferences.downloadTranscodeDolbyVision),
        )
        return DeviceCapabilityReportBuilder.toJson(report)
    }
}
