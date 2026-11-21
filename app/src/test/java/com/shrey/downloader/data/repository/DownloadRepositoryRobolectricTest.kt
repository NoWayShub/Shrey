package com.shrey.downloader.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shrey.downloader.core.database.ShreyDatabase
import com.shrey.downloader.domain.model.DownloadMode
import com.shrey.downloader.domain.model.DownloadProgress
import com.shrey.downloader.domain.model.DownloadRequest
import com.shrey.downloader.domain.model.DownloadStage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DownloadRepositoryRobolectricTest {

    private lateinit var database: ShreyDatabase
    private lateinit var repository: RoomDownloadRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShreyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomDownloadRepository(database.downloadDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun sampleRequest(id: String) = DownloadRequest(
        downloadId = id,
        sourceUrl = "https://example.org/watch?v=$id",
        mediaTitle = "Test Media $id",
        creator = "Creator",
        thumbnailUrl = null,
        mode = DownloadMode.VIDEO_AUDIO,
        selectedFormatId = "va_18",
        formatSelector = "18",
        outputExtension = "mp4",
        qualityLabel = "Video · 360p"
    )

    @Test
    fun `enqueue progress completion and history removal work deterministically`() = runTest {
        val id = repository.enqueue(sampleRequest("job-1"))
        val queued = repository.getDownload(id)
        assertNotNull(queued)
        assertEquals(DownloadStage.QUEUED, queued?.stage)

        repository.updateProgress(
            DownloadProgress(
                downloadId = id,
                stage = DownloadStage.DOWNLOADING,
                downloadedBytes = 5_000_000L,
                totalBytes = 10_000_000L,
                progressPercent = 50f,
                speedText = "2.5MiB/s",
                etaSeconds = 2L,
                message = "Downloading · 2.5MiB/s"
            )
        )
        val inProgress = repository.getDownload(id)
        assertEquals(DownloadStage.DOWNLOADING, inProgress?.stage)
        assertEquals(50f, inProgress?.progressPercent)

        repository.markCompleted(
            downloadId = id,
            destinationUri = "content://media/external/video/media/42",
            displayName = "Test Media job-1.mp4",
            mimeType = "video/mp4",
            fileSizeBytes = 10_000_000L
        )
        val completed = repository.getDownload(id)
        assertEquals(DownloadStage.COMPLETED, completed?.stage)
        assertEquals(100f, completed?.progressPercent)
        assertEquals("content://media/external/video/media/42", completed?.destinationUri)

        val observed = repository.observeDownloads().first()
        assertEquals(1, observed.size)

        repository.removeFromHistory(id)
        assertNull(repository.getDownload(id))
    }

    @Test
    fun `failure retry cancel and process death reconciliation transition cleanly`() = runTest {
        repository.enqueue(sampleRequest("job-fail"))
        repository.markFailed("job-fail", "Network lost", "UnknownHostException")
        assertEquals(DownloadStage.FAILED, repository.getDownload("job-fail")?.stage)

        val retryReq = repository.prepareRetry("job-fail")
        assertNotNull(retryReq)
        val retried = repository.getDownload("job-fail")
        assertEquals(DownloadStage.QUEUED, retried?.stage)
        assertEquals(1, retried?.retryCount)

        repository.markCancelled("job-fail")
        assertEquals(DownloadStage.CANCELLED, repository.getDownload("job-fail")?.stage)

        // Simulate in-flight job interrupted by process death
        repository.enqueue(sampleRequest("job-inflight"))
        repository.updateProgress(
            DownloadProgress(downloadId = "job-inflight", stage = DownloadStage.DOWNLOADING, progressPercent = 34f)
        )
        val recoveredCount = repository.reconcileInterruptedJobs()
        assertEquals(1, recoveredCount)
        assertEquals(DownloadStage.INTERRUPTED, repository.getDownload("job-inflight")?.stage)
    }
}
