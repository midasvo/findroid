package dev.jdtech.jellyfin.player

import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure profile-assembly half of [DeviceProfileBuilder]. The
 * `MediaCodecList` hardware probe needs a device and is not covered here.
 */
class DeviceProfileBuilderTest {

    private fun hevcRangeCondition(probed: ProbedCodecs) =
        DeviceProfileBuilder.buildDeviceProfile(probed)
            .codecProfiles
            .filter { it.codec == "hevc" }
            .flatMap { it.conditions }
            .firstOrNull { it.property == ProfileConditionValue.VIDEO_RANGE_TYPE }

    @Test
    fun `hevc codec profile excludes every Dolby Vision range from direct play`() {
        val probed = ProbedCodecs(
            videoCodecProfiles = mapOf("hevc" to setOf("Main", "Main 10")),
            audioCodecs = setOf("aac"),
        )

        val rangeCondition = hevcRangeCondition(probed)

        assertTrue("hevc must carry a VIDEO_RANGE_TYPE condition", rangeCondition != null)
        val value = rangeCondition!!.value.orEmpty()
        // Dolby Vision must never be advertised as direct-playable.
        assertFalse("DV ranges leaked into direct play: $value", value.contains("DOVI"))
        // Regular HDR/SDR ranges stay direct-playable so they are not needlessly transcoded.
        assertTrue(value.contains("HDR10"))
        assertTrue(value.contains("SDR"))
    }

    @Test
    fun `hevc is still range-restricted when the decoder reports no profiles`() {
        val probed = ProbedCodecs(
            videoCodecProfiles = mapOf("hevc" to emptySet()),
            audioCodecs = setOf("aac"),
        )

        assertTrue(
            "DV must be excluded even with no enumerable hevc profiles",
            hevcRangeCondition(probed) != null,
        )
    }

    @Test
    fun `non-hevc codecs are not range restricted`() {
        val probed = ProbedCodecs(
            videoCodecProfiles = mapOf("h264" to setOf("high", "main")),
            audioCodecs = setOf("aac"),
        )

        val h264RangeConditions = DeviceProfileBuilder.buildDeviceProfile(probed)
            .codecProfiles
            .filter { it.codec == "h264" }
            .flatMap { it.conditions }
            .filter { it.property == ProfileConditionValue.VIDEO_RANGE_TYPE }

        assertEquals(emptyList<Any>(), h264RangeConditions)
    }

    @Test
    fun `device profile direct-plays supported codecs and offers an HLS transcode`() {
        val probed = ProbedCodecs(
            videoCodecProfiles = mapOf("h264" to setOf("high"), "hevc" to setOf("Main 10")),
            audioCodecs = setOf("aac"),
        )

        val profile = DeviceProfileBuilder.buildDeviceProfile(probed)

        assertTrue(
            "hevc should be a direct-play video codec",
            profile.directPlayProfiles.any { it.videoCodec?.contains("hevc") == true },
        )
        assertTrue(
            "an h264 HLS transcoding profile is required for the DV fallback",
            profile.transcodingProfiles.any {
                it.videoCodec == "h264" && it.protocol == MediaStreamProtocol.HLS
            },
        )
    }

    @Test
    fun `unsupported codecs are not advertised for direct play`() {
        val probed = ProbedCodecs(
            videoCodecProfiles = mapOf("h264" to setOf("high")),
            audioCodecs = setOf("aac"),
        )

        val profile = DeviceProfileBuilder.buildDeviceProfile(probed)

        assertFalse(
            "hevc must not be direct-play when the device cannot decode it",
            profile.directPlayProfiles.any { it.videoCodec?.contains("hevc") == true },
        )
        assertTrue(profile.codecProfiles.none { it.codec == "hevc" })
    }

    @Test
    fun `image-based subtitle formats are deliverable embedded and external`() {
        val probed = ProbedCodecs(
            videoCodecProfiles = mapOf("h264" to setOf("high")),
            audioCodecs = setOf("aac"),
        )

        val subtitleProfiles = DeviceProfileBuilder.buildDeviceProfile(probed).subtitleProfiles

        // PGS (BluRay sup), DVDSub (VobSub), DVB are all decoded natively by media3
        // and must be advertised as deliverable both embedded in the container and as
        // sidecar files, so the server never bitmap-transcodes them to text.
        for (format in listOf("pgssub", "dvdsub", "dvbsub")) {
            assertTrue(
                "$format must be deliverable embedded",
                subtitleProfiles.any {
                    it.format == format && it.method == SubtitleDeliveryMethod.EMBED
                },
            )
            assertTrue(
                "$format must be deliverable as external sidecar",
                subtitleProfiles.any {
                    it.format == format && it.method == SubtitleDeliveryMethod.EXTERNAL
                },
            )
        }
    }

    @Test
    fun `direct-play profile is permissive and never transcodes`() {
        val profile = DeviceProfileBuilder.buildDirectPlayProfile()

        assertTrue(
            "mpv profile must direct-play any container",
            profile.directPlayProfiles.all { it.container == "" },
        )
        assertEquals(emptyList<Any>(), profile.transcodingProfiles)
        assertEquals(emptyList<Any>(), profile.codecProfiles)
    }

    @Test
    fun `download transcode profile keeps non-DV original but routes DV to a progressive transcode`() {
        val profile = DeviceProfileBuilder.buildDownloadTranscodeProfile()

        // Direct-plays every container, so non-DV files download as the original.
        assertTrue(profile.directPlayProfiles.any { it.type.toString().contains("VIDEO", true) })
        assertTrue(profile.directPlayProfiles.all { it.container == "" })

        // DV is excluded from direct play, so the server transcodes it instead.
        val rangeCondition = profile.codecProfiles
            .filter { it.codec == "hevc" }
            .flatMap { it.conditions }
            .firstOrNull { it.property == ProfileConditionValue.VIDEO_RANGE_TYPE }
        assertTrue("DV must be excluded from direct play", rangeCondition != null)
        assertFalse(rangeCondition!!.value.orEmpty().contains("DOVI"))

        // The transcode must be a single progressive HTTP stream — DownloadManager
        // cannot reassemble HLS segments.
        val transcode = profile.transcodingProfiles.single()
        assertEquals(MediaStreamProtocol.HTTP, transcode.protocol)
        assertEquals("h264", transcode.videoCodec)
    }
}
