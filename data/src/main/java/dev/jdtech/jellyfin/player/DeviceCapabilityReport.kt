/*
 * Builds a JSON snapshot of the device's playback capabilities so users can paste
 * it into a bug report when something refuses to direct-play or transcodes when
 * it shouldn't. Inspired by the media capability report shipped in
 * jellyfin-androidtv v0.19.
 *
 * The report bundles:
 *   - Findroid version + build type
 *   - Android Build.* identifiers
 *   - The current display's HDR / screen / refresh-rate metadata
 *   - The MediaCodecList probe ([ProbedCodecs])
 *   - The three assembled DeviceProfiles (ExoPlayer, mpv, downloads)
 *
 * The DeviceProfile / SubtitleProfile / CodecProfile etc. classes from the
 * Jellyfin SDK are already kotlinx-serializable, so they round-trip into JSON
 * directly. The wrapper [DeviceCapabilityReport] keeps everything in one tree
 * and is pretty-printed for paste-into-issue readability.
 */
package dev.jdtech.jellyfin.player

import android.content.Context
import android.os.Build
import android.view.Display
import android.view.WindowManager
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.api.DeviceProfile

@Serializable
data class DeviceCapabilityReport(
    val findroidVersion: String,
    val findroidBuildType: String,
    val generatedAt: String,
    val android: AndroidBuildInfo,
    val display: DisplayInfo,
    val probedCodecs: ProbedCodecsReport,
    val deviceProfile: DeviceProfile,
    val directPlayProfile: DeviceProfile,
    val downloadProfile: DeviceProfile,
    val downloadTranscodeDolbyVision: Boolean,
)

@Serializable
data class AndroidBuildInfo(
    val manufacturer: String,
    val model: String,
    val device: String,
    val brand: String,
    val product: String,
    val sdkInt: Int,
    val release: String,
    val display: String,
    val fingerprint: String,
)

@Serializable
data class DisplayInfo(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val refreshRateHz: Float,
    val supportedRefreshRatesHz: List<Float>,
    @SerialName("hdrCapabilities") val hdr: HdrInfo,
)

@Serializable
data class HdrInfo(
    val supportedTypes: List<String>,
    val desiredMaxLuminance: Float,
    val desiredMaxAverageLuminance: Float,
    val desiredMinLuminance: Float,
)

/**
 * Flat, JSON-friendly view of [ProbedCodecs] — `Set` is not directly serializable
 * with the default kotlinx config, so we expose them as sorted lists.
 */
@Serializable
data class ProbedCodecsReport(
    val videoCodecProfiles: Map<String, List<String>>,
    val audioCodecs: List<String>,
)

/**
 * Builds a [DeviceCapabilityReport] from the device + the supplied profile
 * builder. Kept Hilt-free so callers can construct it with whatever Context /
 * builder / preferences they already have.
 */
object DeviceCapabilityReportBuilder {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun build(
        context: Context,
        deviceProfileBuilder: DeviceProfileBuilder,
        findroidVersion: String,
        findroidBuildType: String,
        transcodeDolbyVision: Boolean,
        clock: () -> Long = System::currentTimeMillis,
    ): DeviceCapabilityReport {
        val display = currentDisplay(context)
        val probed = deviceProfileBuilder.probedCodecsSnapshot()
        return DeviceCapabilityReport(
            findroidVersion = findroidVersion,
            findroidBuildType = findroidBuildType,
            generatedAt = java.time.Instant.ofEpochMilli(clock()).toString(),
            android = AndroidBuildInfo(
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                device = Build.DEVICE.orEmpty(),
                brand = Build.BRAND.orEmpty(),
                product = Build.PRODUCT.orEmpty(),
                sdkInt = Build.VERSION.SDK_INT,
                release = Build.VERSION.RELEASE.orEmpty(),
                display = Build.DISPLAY.orEmpty(),
                fingerprint = Build.FINGERPRINT.orEmpty(),
            ),
            display = display,
            probedCodecs = ProbedCodecsReport(
                videoCodecProfiles = probed.videoCodecProfiles
                    .mapValues { (_, profiles) -> profiles.sorted() }
                    .toSortedMap(),
                audioCodecs = probed.audioCodecs.sorted(),
            ),
            deviceProfile = deviceProfileBuilder.getDeviceProfile(),
            directPlayProfile = deviceProfileBuilder.getDirectPlayProfile(),
            downloadProfile = deviceProfileBuilder.getDownloadProfile(transcodeDolbyVision),
            downloadTranscodeDolbyVision = transcodeDolbyVision,
        )
    }

    fun toJson(report: DeviceCapabilityReport): String = json.encodeToString(report)

    private fun currentDisplay(context: Context): DisplayInfo {
        // Context.display was added in API 30; on older devices fall back to the
        // deprecated WindowManager.defaultDisplay. Either is fine for an
        // informational diagnostic — we just want any sane display.
        val display: Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("UnsafeOptInUsageError")
            context.display
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        }
        if (display == null) {
            return DisplayInfo(
                widthPx = 0,
                heightPx = 0,
                densityDpi = context.resources.displayMetrics.densityDpi,
                refreshRateHz = 0f,
                supportedRefreshRatesHz = emptyList(),
                hdr = HdrInfo(
                    supportedTypes = emptyList(),
                    desiredMaxLuminance = 0f,
                    desiredMaxAverageLuminance = 0f,
                    desiredMinLuminance = 0f,
                ),
            )
        }

        val metrics = context.resources.displayMetrics
        val supportedRates = try {
            display.supportedModes.map { it.refreshRate }.distinct().sorted()
        } catch (_: Exception) {
            emptyList()
        }
        val hdr = try {
            val caps = display.hdrCapabilities
            // Display.getHdrCapabilities().supportedHdrTypes was soft-deprecated
            // in API 34 in favour of Display.Mode.supportedHdrTypes; the data is
            // identical and we want it across all API levels, so keep it.
            @Suppress("DEPRECATION")
            val supported = caps?.supportedHdrTypes?.map(::hdrTypeName).orEmpty()
            HdrInfo(
                supportedTypes = supported,
                desiredMaxLuminance = caps?.desiredMaxLuminance ?: 0f,
                desiredMaxAverageLuminance = caps?.desiredMaxAverageLuminance ?: 0f,
                desiredMinLuminance = caps?.desiredMinLuminance ?: 0f,
            )
        } catch (_: Exception) {
            HdrInfo(emptyList(), 0f, 0f, 0f)
        }

        return DisplayInfo(
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            refreshRateHz = display.refreshRate,
            supportedRefreshRatesHz = supportedRates,
            hdr = hdr,
        )
    }

    private fun hdrTypeName(type: Int): String = when (type) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "DOLBY_VISION"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10_PLUS"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        else -> "UNKNOWN($type)"
    }
}
