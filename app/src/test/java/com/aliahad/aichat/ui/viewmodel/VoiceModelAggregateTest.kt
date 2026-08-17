package com.aliahad.aichat.ui.viewmodel

import com.aliahad.aichat.core.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Call mode needs all six artifacts, so the card above them must not report
 * progress optimistically. These pin the "worst news first" ordering.
 */
class VoiceModelAggregateTest {

    @Test
    fun `no records is not downloaded`() {
        assertEquals(DownloadStatus.NOT_DOWNLOADED, aggregateDownloadStatus(emptyList()))
    }

    @Test
    fun `every part ready is ready`() {
        assertEquals(
            DownloadStatus.READY,
            aggregateDownloadStatus(List(6) { DownloadStatus.READY }),
        )
    }

    @Test
    fun `one missing part is not ready`() {
        val statuses = List(5) { DownloadStatus.READY } + DownloadStatus.NOT_DOWNLOADED
        assertEquals(DownloadStatus.NOT_DOWNLOADED, aggregateDownloadStatus(statuses))
    }

    @Test
    fun `a single failure outranks five successes`() {
        val statuses = List(5) { DownloadStatus.READY } + DownloadStatus.FAILED
        assertEquals(DownloadStatus.FAILED, aggregateDownloadStatus(statuses))
    }

    @Test
    fun `failure outranks an in-flight download`() {
        val statuses = listOf(DownloadStatus.DOWNLOADING, DownloadStatus.FAILED)
        assertEquals(DownloadStatus.FAILED, aggregateDownloadStatus(statuses))
    }

    @Test
    fun `queued or verifying still reads as downloading`() {
        assertEquals(
            DownloadStatus.DOWNLOADING,
            aggregateDownloadStatus(listOf(DownloadStatus.READY, DownloadStatus.QUEUED)),
        )
        assertEquals(
            DownloadStatus.DOWNLOADING,
            aggregateDownloadStatus(listOf(DownloadStatus.READY, DownloadStatus.VERIFYING)),
        )
    }

    @Test
    fun `an in-flight download outranks a paused one`() {
        val statuses = listOf(DownloadStatus.PAUSED, DownloadStatus.DOWNLOADING)
        assertEquals(DownloadStatus.DOWNLOADING, aggregateDownloadStatus(statuses))
    }

    @Test
    fun `paused surfaces when nothing is moving`() {
        val statuses = listOf(DownloadStatus.READY, DownloadStatus.PAUSED)
        assertEquals(DownloadStatus.PAUSED, aggregateDownloadStatus(statuses))
    }
}
