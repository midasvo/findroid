package dev.jdtech.jellyfin.core.presentation.downloader

/** Outcome of one download-speed sample. */
internal data class SpeedSample(
    /** Transfer rate in bytes/sec to display. */
    val bytesPerSecond: Long,
    /** True when real progress was observed and the sample clock should advance. */
    val advanced: Boolean,
)

/**
 * Computes the download speed for one poll tick.
 *
 * DownloadManager refreshes its byte counter less often (and less regularly) than the
 * queue polls (~1 s), so many polls see the exact same byte count. A naive delta/elapsed
 * then yields 0 on those polls, which makes the speed + ETA flash in the UI. This keeps
 * the last known speed across flat polls and only advances the sample clock on real
 * progress, so the next delta is measured over the true interval.
 */
internal fun nextDownloadSpeed(
    prevSample: Pair<Long, Long>?,
    currentBytes: Long,
    nowMs: Long,
    previousSpeed: Long,
): SpeedSample {
    if (currentBytes < 0) return SpeedSample(previousSpeed, advanced = false)
    if (prevSample == null) return SpeedSample(previousSpeed, advanced = true)
    val (prevBytes, prevAt) = prevSample
    val deltaBytes = currentBytes - prevBytes
    val elapsedMs = nowMs - prevAt
    return if (deltaBytes > 0 && elapsedMs > 0) {
        SpeedSample((deltaBytes * 1000L / elapsedMs).coerceAtLeast(0L), advanced = true)
    } else {
        // Byte counter has not advanced since the last sample — keep the last
        // known speed instead of flashing 0.
        SpeedSample(previousSpeed, advanced = false)
    }
}
