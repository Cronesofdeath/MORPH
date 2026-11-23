package com.morph.app

import android.app.Application
import androidx.room.Room
import com.morph.app.data.MorphDatabase
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import com.morph.app.data.preferences
import androidx.datastore.preferences.core.intPreferencesKey

@HiltAndroidApp
class MorphApplication:Application() {
    val database by lazy { Room.databaseBuilder(this,MorphDatabase::class.java,"morph.db").build() }
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        // The worker runs in this process. At process start no worker owns an old sandbox.
        java.io.File(cacheDir,"morph_work").listFiles()?.forEach { it.deleteRecursively() }
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            val age=preferences.data.first()[intPreferencesKey("cacheDays")] ?: 7
            val cutoff=System.currentTimeMillis()-age.coerceIn(1,30)*86400000L
            val referenced=database.dao().jobs().first().map {it.uri}
            java.io.File(filesDir,"imports").listFiles()?.filter {dir->
                dir.lastModified()<cutoff && referenced.none {it.contains(dir.name)}
            }?.forEach {it.deleteRecursively()}
        }
    }
}
