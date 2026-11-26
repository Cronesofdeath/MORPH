package com.morph.app.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringSetPreferencesKey

internal val dismissedQueueJobs = stringSetPreferencesKey("dismissedQueueJobs")
internal val clearedVaultJobs = stringSetPreferencesKey("clearedVaultJobs")
internal val finishedJobStatuses = setOf("Complete", "Failed", "Cancelled")

internal data class JobVisibility(
    val preferences: Preferences? = null,
    private val pending: Map<Preferences.Key<Set<String>>, Set<String>> = emptyMap()
) {
    fun queue(jobs: List<ConversionJob>): List<ConversionJob>? =
        hidden(dismissedQueueJobs)?.let { ids ->
            jobs.filter { it.status !in finishedJobStatuses || it.id !in ids }
        }

    fun vault(jobs: List<ConversionJob>): List<ConversionJob>? =
        hidden(clearedVaultJobs)?.let { ids ->
            jobs.filter { it.status == "Complete" && it.id !in ids }
        }

    fun hidden(key: Preferences.Key<Set<String>>): Set<String>? =
        preferences?.let { it[key].orEmpty() + pending[key].orEmpty() }

    fun hide(key: Preferences.Key<Set<String>>, ids: Set<String>) =
        copy(pending = pending + Pair(key, pending[key].orEmpty() + ids))

    fun discard(key: Preferences.Key<Set<String>>, ids: Set<String>) =
        copy(pending = (pending + Pair(key, pending[key].orEmpty() - ids)).filterValues { it.isNotEmpty() })

    fun loaded(saved: Preferences) = copy(
        preferences = saved,
        pending = pending.mapValues { (key, ids) -> ids - saved[key].orEmpty() }.filterValues { it.isNotEmpty() }
    )
}
