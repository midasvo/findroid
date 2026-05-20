/*
 * Device profile construction adapted from jellyfin-android
 * (https://github.com/jellyfin/jellyfin-android),
 * file: app/src/main/java/org/jellyfin/mobile/player/deviceprofile/DeviceProfileBuilder.kt
 *
 * jellyfin-android is licensed under GPL-2.0; Findroid is likewise GPL-licensed,
 * so this port is compatible.
 *
 * Findroid-specific changes:
 *  - The MediaCodecList hardware probe is split from the (pure) profile assembly so
 *    the assembly can be unit-tested without a device — see [buildDeviceProfile].
 *  - Dolby Vision is deliberately excluded from direct play (see [buildDeviceProfile]):
 *    ExoPlayer does not reliably forward DV RPU/EL metadata even on DV-capable
 *    hardware, so the server is asked to transcode DV to HDR10/SDR instead.
 *  - The "direct play everything" profile ([buildDirectPlayProfile]) is kept for the
 *    mpv backend, which software-decodes practically anything.
 */
package dev.jdtech.jellyfin.player

import android.media.MediaCodecList
import org.jellyfin.sdk.model.api.CodecProfile
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.ContainerProfile
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileCondition
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
import org.jellyfin.sdk.model.api.TranscodingProfile

/**
 * Result of the [MediaCodecList] hardware probe, reduced to plain types so the
 * profile assembly stays pure and testable.
 */
data class ProbedCodecs(
    /** ffmpeg video codec name -> set of decoder profile names the device advertises. */
    val videoCodecProfiles: Map<String, Set<String>>,
    /** ffmpeg audio codec names the device can decode. */
    val audioCodecs: Set<String>,
)

class DeviceProfileBuilder {

    private val probedCodecs: ProbedCodecs = probeCodecs()

    /**
     * Honest, hardware-probed profile for ExoPlayer. Files the device cannot
     * direct-play (including all Dolby Vision) get a server-side transcode.
     */
    fun getDeviceProfile(): DeviceProfile = buildDeviceProfile(probedCodecs)

    /**
     * Permissive "direct play everything" profile for the mpv backend, which
     * software-decodes practically anything and never needs a transcode.
     */
    fun getDirectPlayProfile(): DeviceProfile = buildDirectPlayProfile()

    /**
     * Profile for the downloader. With [transcodeDolbyVision] off this is just the
     * permissive direct-play profile (downloads stay original). With it on, Dolby
     * Vision is excluded from direct play and a progressive H.264 transcoding
     * profile is offered, so DV files download as a device-compatible copy that
     * plays offline — every other file still downloads as the original.
     */
    fun getDownloadProfile(transcodeDolbyVision: Boolean): DeviceProfile =
        if (transcodeDolbyVision) buildDownloadTranscodeProfile() else buildDirectPlayProfile()

    /**
     * The [MediaCodecList] probe result this builder was constructed with.
     * Exposed so the diagnostic capability report can surface the raw codec
     * advertising data alongside the assembled profile — see
     * [DeviceCapabilityReportBuilder].
     */
    fun probedCodecsSnapshot(): ProbedCodecs = probedCodecs

    /** Probes the device's decoders via [MediaCodecList] (REGULAR_CODECS). */
    private fun probeCodecs(): ProbedCodecs {
        val videoCodecs: MutableMap<String, DeviceCodec.Video> = HashMap()
        val audioCodecs: MutableMap<String, DeviceCodec.Audio> = HashMap()
        val androidCodecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (codecInfo in androidCodecs.codecInfos) {
            if (codecInfo.isEncoder) continue

            for (mimeType in codecInfo.supportedTypes) {
                val codec = try {
                    DeviceCodec.from(codecInfo.getCapabilitiesForType(mimeType))
                } catch (_: Exception) {
                    // Some OEM codecs throw on getCapabilitiesForType — skip them.
                    null
                } ?: continue
                when (codec) {
                    is DeviceCodec.Video -> {
                        videoCodecs[codec.name] =
                            videoCodecs[codec.name]?.mergeCodec(codec) ?: codec
                    }
                    is DeviceCodec.Audio -> {
                        audioCodecs[codec.name] =
                            audioCodecs[codec.name]?.mergeCodec(codec) ?: codec
                    }
                }
            }
        }
        return ProbedCodecs(
            videoCodecProfiles = videoCodecs.mapValues { (_, codec) -> codec.profiles },
            audioCodecs = audioCodecs.keys.toSet(),
        )
    }

    companion object {
        /**
         * List of container formats supported by ExoPlayer.
         *
         * IMPORTANT: keep aligned with [AVAILABLE_VIDEO_CODECS] and [AVAILABLE_AUDIO_CODECS] —
         * the three arrays are indexed in parallel.
         */
        private val SUPPORTED_CONTAINER_FORMATS = arrayOf(
            "mp4", "fmp4", "webm", "mkv", "mp3", "ogg", "wav", "mpegts", "flv", "aac", "flac", "3gp",
        )

        private val AVAILABLE_VIDEO_CODECS = arrayOf(
            // mp4
            arrayOf("mpeg1video", "mpeg2video", "h263", "mpeg4", "h264", "hevc", "av1", "vp9"),
            // fmp4
            arrayOf("mpeg1video", "mpeg2video", "h263", "mpeg4", "h264", "hevc", "av1", "vp9"),
            // webm
            arrayOf("vp8", "vp9", "av1"),
            // mkv
            arrayOf("mpeg1video", "mpeg2video", "h263", "mpeg4", "h264", "hevc", "av1", "vp8", "vp9"),
            // mp3
            emptyArray(),
            // ogg
            emptyArray(),
            // wav
            emptyArray(),
            // mpegts
            arrayOf("mpeg1video", "mpeg2video", "mpeg4", "h264", "hevc"),
            // flv
            arrayOf("mpeg4", "h264"),
            // aac
            emptyArray(),
            // flac
            emptyArray(),
            // 3gp
            arrayOf("h263", "mpeg4", "h264", "hevc"),
        )

        /** PCM codecs supported by ExoPlayer by default. */
        private val PCM_CODECS = arrayOf(
            "pcm_s8", "pcm_s16be", "pcm_s16le", "pcm_s24le", "pcm_s32le",
            "pcm_f32le", "pcm_alaw", "pcm_mulaw",
        )

        private val AVAILABLE_AUDIO_CODECS = arrayOf(
            // mp4
            arrayOf("mp1", "mp2", "mp3", "aac", "alac", "ac3", "opus"),
            // fmp4
            arrayOf("mp3", "aac", "ac3", "eac3"),
            // webm
            arrayOf("vorbis", "opus"),
            // mkv
            arrayOf(
                *PCM_CODECS, "mp1", "mp2", "mp3", "aac", "vorbis", "opus", "flac", "alac",
                "ac3", "eac3", "dts", "mlp", "truehd",
            ),
            // mp3
            arrayOf("mp3"),
            // ogg
            arrayOf("vorbis", "opus", "flac"),
            // wav
            PCM_CODECS,
            // mpegts
            arrayOf(*PCM_CODECS, "mp1", "mp2", "mp3", "aac", "ac3", "eac3", "dts", "mlp", "truehd"),
            // flv
            arrayOf("mp3", "aac"),
            // aac
            arrayOf("aac"),
            // flac
            arrayOf("flac"),
            // 3gp
            arrayOf("3gpp", "aac", "flac"),
        )

        /**
         * Audio codecs added to the profile regardless of [MediaCodecList] advertising
         * them — these are handled by decoders bundled with ExoPlayer or its ffmpeg
         * extension (Findroid ships the media3 ffmpeg decoder).
         */
        private val FORCED_AUDIO_CODECS =
            setOf(*PCM_CODECS, "alac", "aac", "ac3", "eac3", "dts", "mlp", "truehd")

        private val EXO_EMBEDDED_SUBTITLES = arrayOf("dvbsub", "pgssub", "srt", "subrip", "ttml", "ass", "ssa")
        private val EXO_EXTERNAL_SUBTITLES = arrayOf("srt", "subrip", "ttml", "vtt", "webvtt", "ass", "ssa")

        /**
         * Video range types that may be direct-played. Every Dolby Vision range
         * (DOVI, DOVIWith*) is intentionally absent so the server transcodes it —
         * see the file header for why. `Unknown` is kept so HEVC streams whose
         * range the server cannot classify are not needlessly transcoded.
         */
        private const val DIRECT_PLAY_VIDEO_RANGE_TYPES = "SDR|HDR10|HDR10Plus|HLG|Unknown"

        // Bitrate ceilings, taken from jellyfin-web's browserDeviceProfile.js.
        private const val MAX_STREAMING_BITRATE = 120_000_000
        private const val MAX_STATIC_BITRATE = 100_000_000
        private const val MAX_MUSIC_TRANSCODING_BITRATE = 384_000

        private val TRANSCODING_PROFILES = listOf(
            TranscodingProfile(
                type = DlnaProfileType.VIDEO,
                container = "ts",
                videoCodec = "h264",
                audioCodec = "mp1,mp2,mp3,aac,ac3,eac3,dts,mlp,truehd",
                protocol = MediaStreamProtocol.HLS,
                conditions = emptyList(),
            ),
            TranscodingProfile(
                type = DlnaProfileType.AUDIO,
                container = "mp3",
                videoCodec = "",
                audioCodec = "mp3",
                protocol = MediaStreamProtocol.HTTP,
                conditions = emptyList(),
            ),
        )

        private fun subtitleProfiles(): List<SubtitleProfile> = buildList {
            for (format in EXO_EMBEDDED_SUBTITLES) {
                add(SubtitleProfile(format = format, method = SubtitleDeliveryMethod.EMBED))
            }
            for (format in EXO_EXTERNAL_SUBTITLES) {
                add(SubtitleProfile(format = format, method = SubtitleDeliveryMethod.EXTERNAL))
            }
        }

        /**
         * Pure assembly of the honest device profile from a [ProbedCodecs] snapshot.
         * Kept side-effect-free and Android-free so it can be unit-tested.
         */
        internal fun buildDeviceProfile(probed: ProbedCodecs): DeviceProfile {
            val containerProfiles = ArrayList<ContainerProfile>()
            val directPlayProfiles = ArrayList<DirectPlayProfile>()
            val codecProfiles = ArrayList<CodecProfile>()

            for (i in SUPPORTED_CONTAINER_FORMATS.indices) {
                val container = SUPPORTED_CONTAINER_FORMATS[i]
                val videoCodecs = AVAILABLE_VIDEO_CODECS[i].filter { codec ->
                    probed.videoCodecProfiles.containsKey(codec)
                }
                val audioCodecs = AVAILABLE_AUDIO_CODECS[i].filter { codec ->
                    codec in probed.audioCodecs || codec in FORCED_AUDIO_CODECS
                }

                if (videoCodecs.isNotEmpty()) {
                    containerProfiles.add(
                        ContainerProfile(
                            type = DlnaProfileType.VIDEO,
                            container = container,
                            conditions = emptyList(),
                        ),
                    )
                    directPlayProfiles.add(
                        DirectPlayProfile(
                            type = DlnaProfileType.VIDEO,
                            container = container,
                            videoCodec = videoCodecs.joinToString(","),
                            audioCodec = audioCodecs.joinToString(","),
                        ),
                    )
                    for (videoCodec in videoCodecs) {
                        generateCodecProfile(container, videoCodec, probed.videoCodecProfiles)
                            ?.let(codecProfiles::add)
                    }
                }
                if (audioCodecs.isNotEmpty()) {
                    containerProfiles.add(
                        ContainerProfile(
                            type = DlnaProfileType.AUDIO,
                            container = container,
                            conditions = emptyList(),
                        ),
                    )
                    directPlayProfiles.add(
                        DirectPlayProfile(
                            type = DlnaProfileType.AUDIO,
                            container = container,
                            audioCodec = audioCodecs.joinToString(","),
                        ),
                    )
                }
            }

            return DeviceProfile(
                name = "Findroid Device Profile",
                directPlayProfiles = directPlayProfiles,
                transcodingProfiles = TRANSCODING_PROFILES,
                containerProfiles = containerProfiles,
                codecProfiles = codecProfiles,
                subtitleProfiles = subtitleProfiles(),
                maxStreamingBitrate = MAX_STREAMING_BITRATE,
                maxStaticBitrate = MAX_STATIC_BITRATE,
                musicStreamingTranscodingBitrate = MAX_MUSIC_TRANSCODING_BITRATE,
            )
        }

        /**
         * Builds the codec profile for one (container, videoCodec) pair. The
         * `VIDEO_PROFILE` condition restricts direct play to decoder-supported
         * profiles; for HEVC an extra `VIDEO_RANGE_TYPE` condition excludes every
         * Dolby Vision range so the server always transcodes DV.
         */
        private fun generateCodecProfile(
            container: String,
            videoCodec: String,
            videoCodecProfiles: Map<String, Set<String>>,
        ): CodecProfile? {
            val profilesSet = videoCodecProfiles[videoCodec].orEmpty()
            val isHevc = videoCodec == "hevc"
            if (profilesSet.isEmpty() && !isHevc) {
                return null
            }

            val conditions = buildList {
                if (profilesSet.isNotEmpty()) {
                    add(
                        ProfileCondition(
                            condition = ProfileConditionType.EQUALS_ANY,
                            property = ProfileConditionValue.VIDEO_PROFILE,
                            value = profilesSet.joinToString("|"),
                            isRequired = false,
                        ),
                    )
                }
                if (isHevc) {
                    add(
                        ProfileCondition(
                            condition = ProfileConditionType.EQUALS_ANY,
                            property = ProfileConditionValue.VIDEO_RANGE_TYPE,
                            value = DIRECT_PLAY_VIDEO_RANGE_TYPES,
                            isRequired = false,
                        ),
                    )
                }
            }

            return CodecProfile(
                type = CodecType.VIDEO,
                container = container,
                codec = videoCodec,
                applyConditions = emptyList(),
                conditions = conditions,
            )
        }

        /**
         * The permissive profile: direct-play any container/codec, never transcode.
         * Used for the mpv backend. Mirrors the profile Findroid historically sent.
         */
        internal fun buildDirectPlayProfile(): DeviceProfile = DeviceProfile(
            name = "Direct play all",
            maxStreamingBitrate = 1_000_000_000,
            maxStaticBitrate = 1_000_000_000,
            directPlayProfiles = listOf(
                DirectPlayProfile(type = DlnaProfileType.VIDEO, container = ""),
                DirectPlayProfile(type = DlnaProfileType.AUDIO, container = ""),
            ),
            transcodingProfiles = emptyList(),
            containerProfiles = emptyList(),
            codecProfiles = emptyList(),
            subtitleProfiles = listOf(
                SubtitleProfile("srt", SubtitleDeliveryMethod.EXTERNAL),
                SubtitleProfile("ass", SubtitleDeliveryMethod.EXTERNAL),
            ),
        )

        /**
         * Download profile that forces Dolby Vision through a progressive H.264
         * transcode while leaving every other file as a direct-play original.
         *
         * The transcoding profile uses HTTP — a single continuous `.ts` stream —
         * rather than HLS, because `DownloadManager` downloads one URL and cannot
         * reassemble HLS segments. `.ts` is also safe to write progressively (no
         * trailing index to patch in, unlike `.mp4`).
         */
        internal fun buildDownloadTranscodeProfile(): DeviceProfile = DeviceProfile(
            name = "Findroid Download Profile",
            maxStreamingBitrate = 1_000_000_000,
            maxStaticBitrate = 1_000_000_000,
            directPlayProfiles = listOf(
                DirectPlayProfile(type = DlnaProfileType.VIDEO, container = ""),
                DirectPlayProfile(type = DlnaProfileType.AUDIO, container = ""),
            ),
            transcodingProfiles = listOf(
                TranscodingProfile(
                    type = DlnaProfileType.VIDEO,
                    container = "ts",
                    videoCodec = "h264",
                    audioCodec = "aac,ac3,eac3,mp3",
                    protocol = MediaStreamProtocol.HTTP,
                    conditions = emptyList(),
                ),
            ),
            containerProfiles = emptyList(),
            // Empty container = match every container: Dolby Vision is excluded from
            // direct play regardless of how the file is muxed, so it transcodes.
            codecProfiles = listOf(
                CodecProfile(
                    type = CodecType.VIDEO,
                    container = "",
                    codec = "hevc",
                    applyConditions = emptyList(),
                    conditions = listOf(
                        ProfileCondition(
                            condition = ProfileConditionType.EQUALS_ANY,
                            property = ProfileConditionValue.VIDEO_RANGE_TYPE,
                            value = DIRECT_PLAY_VIDEO_RANGE_TYPES,
                            isRequired = false,
                        ),
                    ),
                ),
            ),
            subtitleProfiles = listOf(
                SubtitleProfile("srt", SubtitleDeliveryMethod.EXTERNAL),
                SubtitleProfile("ass", SubtitleDeliveryMethod.EXTERNAL),
            ),
        )
    }
}
