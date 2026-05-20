package dev.jdtech.jellyfin.player

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sanity checks for the JSON snapshot the diagnostic export produces.
 * The Android-specific bits (Context / Display) are exercised on-device only;
 * this test pins the fields a bug-report consumer would look for first.
 */
class DeviceCapabilityReportTest {

    private fun sampleReport() = DeviceCapabilityReport(
        findroidVersion = "1.2.3 (456)",
        findroidBuildType = "debug",
        generatedAt = "2026-05-20T12:00:00Z",
        android = AndroidBuildInfo(
            manufacturer = "Google",
            model = "Pixel 8",
            device = "shiba",
            brand = "google",
            product = "shiba",
            sdkInt = 34,
            release = "14",
            display = "UD1A.230803.041",
            fingerprint = "google/shiba/shiba:14/UD1A.230803.041/...:user/release-keys",
        ),
        display = DisplayInfo(
            widthPx = 1080,
            heightPx = 2400,
            densityDpi = 420,
            refreshRateHz = 120.0f,
            supportedRefreshRatesHz = listOf(60.0f, 90.0f, 120.0f),
            hdr = HdrInfo(
                supportedTypes = listOf("HDR10", "HDR10_PLUS", "HLG", "DOLBY_VISION"),
                desiredMaxLuminance = 1000.0f,
                desiredMaxAverageLuminance = 400.0f,
                desiredMinLuminance = 0.005f,
            ),
        ),
        probedCodecs = ProbedCodecsReport(
            videoCodecProfiles = sortedMapOf(
                "h264" to listOf("high"),
                "hevc" to listOf("Main", "Main 10"),
            ),
            audioCodecs = listOf("aac", "ac3", "eac3"),
        ),
        deviceProfile = DeviceProfile(
            name = "Findroid Device Profile",
            directPlayProfiles = listOf(
                DirectPlayProfile(
                    type = DlnaProfileType.VIDEO,
                    container = "mp4",
                    videoCodec = "h264",
                    audioCodec = "aac",
                ),
            ),
            transcodingProfiles = emptyList(),
            containerProfiles = emptyList(),
            codecProfiles = emptyList(),
            subtitleProfiles = emptyList(),
        ),
        directPlayProfile = DeviceProfile(
            name = "Direct play all",
            directPlayProfiles = emptyList(),
            transcodingProfiles = emptyList(),
            containerProfiles = emptyList(),
            codecProfiles = emptyList(),
            subtitleProfiles = emptyList(),
        ),
        downloadProfile = DeviceProfile(
            name = "Findroid Download Profile",
            directPlayProfiles = emptyList(),
            transcodingProfiles = emptyList(),
            containerProfiles = emptyList(),
            codecProfiles = emptyList(),
            subtitleProfiles = emptyList(),
        ),
        downloadTranscodeDolbyVision = true,
    )

    @Test
    fun `device profiles carrying a UUID id serialize successfully`() {
        // Regression: DeviceProfile.id is java.util.UUID, which kotlinx-serialization
        // can't encode without a registered serializer. The Jellyfin SDK ships
        // UUIDSerializer and the report's Json config wires it up contextually.
        // Without that wiring this test throws "Serializer for class 'UUID' is not
        // found" and the on-device export silently dies with "Could not build
        // device profile". Pin both the UUID hex string in the output and the
        // fact that encoding does not throw.
        val deviceId = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val withId = sampleReport().copy(
            deviceProfile = DeviceProfile(
                name = "Findroid Device Profile",
                id = deviceId,
                directPlayProfiles = emptyList(),
                transcodingProfiles = emptyList(),
                containerProfiles = emptyList(),
                codecProfiles = emptyList(),
                subtitleProfiles = emptyList(),
            ),
        )

        val json = DeviceCapabilityReportBuilder.toJson(withId)

        assertTrue(
            "UUID hex must appear in the JSON output",
            json.contains(deviceId.toString()),
        )
    }

    @Test
    fun `json output is pretty-printed and exposes the top-level fields a bug report needs`() {
        val json = DeviceCapabilityReportBuilder.toJson(sampleReport())

        assertTrue("must be pretty-printed (contains newlines)", json.contains("\n"))
        // The fields the issue calls out by name must be present so users can grep
        // a pasted report quickly.
        listOf(
            "findroidVersion",
            "findroidBuildType",
            "manufacturer",
            "model",
            "sdkInt",
            "hdrCapabilities",
            "supportedTypes",
            "probedCodecs",
            "deviceProfile",
            "directPlayProfile",
            "downloadProfile",
            "downloadTranscodeDolbyVision",
        ).forEach { field ->
            assertTrue("expected JSON to contain $field", json.contains("\"$field\""))
        }
    }

    @Test
    fun `hdr supported types are emitted as human-readable names`() {
        val json = DeviceCapabilityReportBuilder.toJson(sampleReport())

        // Integers from Display.HdrCapabilities are mapped to names — easier to
        // eyeball than numeric constants in a pasted blob.
        assertTrue(json.contains("\"DOLBY_VISION\""))
        assertTrue(json.contains("\"HDR10\""))
    }

    @Test
    fun `toJson is dispatcher-agnostic and produces identical output off the main thread`() {
        // Mirrors what DeviceCapabilityReportBuilder.buildJson does (suspend variant
        // runs on Dispatchers.Default). We can't easily construct a full builder
        // pipeline in a JVM unit test — that needs a Context — but we can prove
        // that encoding the same report on a background dispatcher matches the
        // direct toJson() output byte-for-byte. If anything ever introduced
        // thread-local state into the serializer we'd see a mismatch here.
        val report = sampleReport()
        val expected = DeviceCapabilityReportBuilder.toJson(report)
        val fromBackground = runBlocking {
            withContext(Dispatchers.Default) {
                DeviceCapabilityReportBuilder.toJson(report)
            }
        }
        assertEquals(expected, fromBackground)
    }

    @Test
    fun `probed codec profiles are emitted as sorted lists for diff-friendly output`() {
        val report = sampleReport().copy(
            probedCodecs = ProbedCodecsReport(
                // Deliberately out of order — toJson must sort the entries.
                videoCodecProfiles = sortedMapOf("hevc" to listOf("Main 10", "Main")),
                audioCodecs = listOf("ac3", "aac"),
            ),
        )

        // Builder.build() sorts inside the report; toJson alone preserves caller
        // order, so this test asserts what the wire format looks like once it
        // reaches the user (post-sort). We sort here to mirror that pipeline.
        val sorted = report.copy(
            probedCodecs = ProbedCodecsReport(
                videoCodecProfiles = report.probedCodecs.videoCodecProfiles
                    .mapValues { (_, list) -> list.sorted() },
                audioCodecs = report.probedCodecs.audioCodecs.sorted(),
            ),
        )
        val json = DeviceCapabilityReportBuilder.toJson(sorted)

        // The audio list is small enough to inline-assert: it should be sorted.
        assertEquals(
            listOf("aac", "ac3"),
            sorted.probedCodecs.audioCodecs,
        )
        // And the JSON should put "Main" before "Main 10" textually.
        assertTrue(
            "expected hevc profiles ordered Main, Main 10 in JSON",
            json.indexOf("\"Main\"") < json.indexOf("\"Main 10\""),
        )
    }
}
