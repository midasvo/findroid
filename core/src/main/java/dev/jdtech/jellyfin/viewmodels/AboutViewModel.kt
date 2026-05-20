package dev.jdtech.jellyfin.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.player.DeviceCapabilityReportBuilder
import dev.jdtech.jellyfin.player.DeviceProfileBuilder
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Backs the About screen's "Export device profile" action.
 *
 * Lives in `core` because both the device-profile builder (in `data`) and
 * `AppPreferences` (in `settings`) need to be assembled, and `core` is the
 * lowest module that depends on both. The AboutScreen itself stays in
 * `app/phone` and consumes this VM via `hiltViewModel()`.
 *
 * Report assembly + JSON encoding runs on [kotlinx.coroutines.Dispatchers.Default]
 * (see [DeviceCapabilityReportBuilder.buildJson]) so the UI thread is never
 * blocked while a few KB of `kotlinx.serialization` output is produced. The
 * resulting payload is delivered to the UI as a one-shot event.
 */
@HiltViewModel
class AboutViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val deviceProfileBuilder: DeviceProfileBuilder,
    private val appPreferences: AppPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(AboutState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<AboutEvent>()
    val events = eventsChannel.receiveAsFlow()

    fun onAction(action: AboutAction) {
        when (action) {
            is AboutAction.OnExportDeviceProfile -> {
                exportDeviceProfile(
                    findroidVersion = action.findroidVersion,
                    findroidBuildType = action.findroidBuildType,
                    target = action.target,
                )
            }
        }
    }

    private fun exportDeviceProfile(
        findroidVersion: String,
        findroidBuildType: String,
        target: ExportTarget,
    ) {
        // Don't queue a second export while one is already running — the JSON
        // build is cheap but each tap also has UI side effects (chooser /
        // toast) we don't want to fire twice in a row.
        if (_state.value.isExporting) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isExporting = true)
            try {
                val json = DeviceCapabilityReportBuilder.buildJson(
                    context = context,
                    deviceProfileBuilder = deviceProfileBuilder,
                    findroidVersion = findroidVersion,
                    findroidBuildType = findroidBuildType,
                    transcodeDolbyVision = appPreferences.getValue(
                        appPreferences.downloadTranscodeDolbyVision,
                    ),
                )
                eventsChannel.send(
                    when (target) {
                        ExportTarget.Share -> AboutEvent.ShareDeviceProfile(json)
                        ExportTarget.Clipboard -> AboutEvent.CopyDeviceProfile(json)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                eventsChannel.send(AboutEvent.ExportFailed(e))
            } finally {
                _state.value = _state.value.copy(isExporting = false)
            }
        }
    }
}

data class AboutState(val isExporting: Boolean = false)

sealed interface AboutAction {
    data class OnExportDeviceProfile(
        val findroidVersion: String,
        val findroidBuildType: String,
        val target: ExportTarget,
    ) : AboutAction
}

enum class ExportTarget { Share, Clipboard }

sealed interface AboutEvent {
    data class ShareDeviceProfile(val json: String) : AboutEvent
    data class CopyDeviceProfile(val json: String) : AboutEvent
    data class ExportFailed(val cause: Throwable) : AboutEvent
}
