package com.morph.app

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.morph.app.data.ConversionJob
import com.morph.app.data.JobVisibility
import com.morph.app.data.clearedVaultJobs
import com.morph.app.data.dismissedQueueJobs
import org.junit.Assert.*
import org.junit.Test

class JobVisibilityTest {
    private val completed = ConversionJob("first", "", "first.jpg", "PNG", "JPG", 0, 0, false, "{}", "test", status = "Complete")
    private val jobs = listOf(completed)

    @Test fun clearedQueueStaysEmptyOnItsFirstFrameWhileTheWriteIsPending() {
        val initial = JobVisibility().loaded(emptyPreferences())
        assertEquals(jobs, initial.queue(jobs))

        val afterTap = initial.hide(dismissedQueueJobs, setOf(completed.id))
        assertEquals(emptyList<ConversionJob>(), afterTap.queue(jobs))
        assertEquals(jobs, afterTap.vault(jobs))

        val stalePreferences = afterTap.loaded(emptyPreferences())
        assertEquals(emptyList<ConversionJob>(), stalePreferences.queue(jobs))

        val saved = preferencesOf(dismissedQueueJobs to setOf(completed.id))
        assertEquals(emptyList<ConversionJob>(), stalePreferences.loaded(saved).queue(jobs))
        assertEquals(emptyList<ConversionJob>(), JobVisibility().loaded(saved).queue(jobs))
    }

    @Test fun savedRowsAreNotDrawnBeforeClearPreferencesLoad() {
        val saved = preferencesOf(
            dismissedQueueJobs to setOf(completed.id),
            clearedVaultJobs to setOf(completed.id)
        )
        val coldStart = JobVisibility()
        assertNull(coldStart.queue(jobs))
        assertNull(coldStart.vault(jobs))
        assertEquals(emptyList<ConversionJob>(), coldStart.loaded(saved).queue(jobs))
        assertEquals(emptyList<ConversionJob>(), coldStart.loaded(saved).vault(jobs))
    }

    @Test fun activeAndNewJobsRemainVisibleAndFailedDismissalCanBeRetried() {
        val initial = JobVisibility().loaded(emptyPreferences())
        val pending = initial.hide(dismissedQueueJobs, setOf(completed.id))
        val new = completed.copy(id = "second")
        val active = completed.copy(status = "Running")
        assertEquals(listOf(new), pending.queue(listOf(completed, new)))
        assertEquals(listOf(active), pending.queue(listOf(active)))
        assertEquals(jobs, pending.discard(dismissedQueueJobs, setOf(completed.id)).queue(jobs))
    }
}
