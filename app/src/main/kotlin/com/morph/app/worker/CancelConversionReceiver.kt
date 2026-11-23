package com.morph.app.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.morph.app.MorphApplication
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CancelConversionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = runCatching { UUID.fromString(intent.data?.schemeSpecificPart?.removePrefix("cancel/")) }
            .getOrNull() ?: return
        val app = context.applicationContext as MorphApplication
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (app.database.dao().updateActive(id.toString(), "Cancelled", "Cancelled") == 1) {
                    WorkManager.getInstance(app).cancelWorkById(id)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
