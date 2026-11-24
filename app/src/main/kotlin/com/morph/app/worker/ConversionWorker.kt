package com.morph.app.worker

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.morph.app.*
import com.morph.app.data.*
import com.morph.app.domain.graph.MagicByteDetector
import com.morph.app.domain.model.*
import com.morph.app.engine.*
import kotlinx.coroutines.*
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.datastore.preferences.core.booleanPreferencesKey

class ConversionWorker(private val ctx:Context,params:WorkerParameters):CoroutineWorker(ctx,params) {
    private companion object {
        val processingSlot = Mutex()
    }
    private val dao=(ctx.applicationContext as MorphApplication).database.dao()
    private fun notification(name:String,stage:String):ForegroundInfo {
        val manager=ctx.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("conversions","Conversions",NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, CancelConversionReceiver::class.java)
                .setData(Uri.parse("morph:cancel/$id")),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification=NotificationCompat.Builder(ctx,"conversions").setSmallIcon(com.morph.app.R.drawable.morph_logo)
            .setContentTitle(name).setContentText(stage).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(0,0,true).addAction(0,"Cancel",stop).build()
        val key=id.hashCode() and Int.MAX_VALUE
        return if(Build.VERSION.SDK_INT>=29) ForegroundInfo(key,notification,if(Build.VERSION.SDK_INT>=35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(key,notification)
    }
    override suspend fun doWork():Result = withContext(Dispatchers.IO) {
        val job=dao.job(id.toString()) ?: return@withContext Result.failure()
        if(job.status=="Complete" && File(job.output).isFile) return@withContext Result.success()
        if(job.status=="Cancelled") return@withContext Result.failure()
        val folder=File(ctx.cacheDir,"morph_work/$id")
        folder.deleteRecursively(); folder.mkdirs()
        val exportFolder=File(ctx.filesDir,"exports/$id")
        var published=false
        try {
            suspend fun step(stage:String) {
                currentCoroutineContext().ensureActive()
                if (dao.updateActive(job.id, "Running", stage) == 0) throw CancellationException()
                setForeground(notification(job.name,stage))
            }
            step("Waiting for processing slot")
            val combined = job.uri.startsWith("batch:")
            val options = decodeOptions(job.options)
            val target = Format.valueOf(job.target)
            val input = File(File(folder, "source").apply { mkdirs() }, StorageManager.safe(job.name))
            val (output, fmt) = processingSlot.withLock {
                step("Reading file")
                if (!combined) {
                    val stream = ctx.contentResolver.openInputStream(Uri.parse(job.uri)) ?: error("Input file is unavailable")
                    stream.use { source -> input.outputStream().use { source.transfer(it, folder.usableSpace) } }
                }
                val identity = if (combined) null else MagicByteDetector.detect(input, job.name)
                val detected = identity?.actual ?: Format.valueOf(job.source)
                require(identity?.isEncrypted != true || (identity.mismatch && detected == target && job.budget == 0L && options.permitsCorrection())) { "This document is encrypted. Export an unlocked copy first." }
                val outputDir = File(folder, "stages").apply { mkdirs() }
                val size = job.budget.takeIf { it > 0 }?.let { SizeTarget(it, job.exact) }
                val result = if (combined) {
                    BatchEngine.combine(ctx, org.json.JSONArray(job.uri.removePrefix("batch:")), outputDir, target, options, size, ::step)
                } else if (identity?.mismatch == true && detected == target && size == null && options.permitsCorrection()) {
                    step("Verifying lossless format correction")
                    val corrected = File(outputDir, "corrected.${target.extension}")
                    FileInspector.correctedCopy(input,corrected)
                } else {
                    TargetSizeOrchestrator.convert(input, outputDir, detected, target, options, size, ::step)
                }
                if (result.name != "corrected.${target.extension}") {
                    step("Validating output")
                    SemanticOutputValidator.verify(result, target, options, if (combined) null else input, detected)
                }
                result to detected
            }
            val ext=output.extension.ifBlank { target.extension }
            val realExt=if(target.extension.contains('.') && output.name.endsWith(target.extension)) target.extension else ext
            val name=StorageManager.safe(options.filename.replace("{name}",Format.basename(job.name)).replace("{size}",output.length().toString()).replace("{ext}",realExt))
            exportFolder.mkdirs()
            val exported=File(exportFolder,if(name.endsWith(".$realExt",true)) name else "$name.$realExt")
            val hash = if (options.sha256) output.inputStream().use { FileInspector.hash(it) } else ""
            currentCoroutineContext().ensureActive()
            if (!output.renameTo(exported)) {
                output.inputStream().use { stream -> exported.outputStream().use { stream.transfer(it, exportFolder.usableSpace) } }
            }
            val original = if (combined) job.original else input.length()
            folder.deleteRecursively()
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                published = dao.complete(job.id, fmt.name, original,
                    exported.path, exported.length(), hash) == 1
            }
            if (!published) throw CancellationException()
            val autoSave=ctx.preferences.data.first()[booleanPreferencesKey("autoSave")] ?: (Build.VERSION.SDK_INT>=29)
            if(autoSave && Build.VERSION.SDK_INT>=29) {
                val summary = try {
                    StorageManager.save(ctx, exported)
                    "Verified · Saved to output folder"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Verified · Auto-save failed. Save from Vault."
                }
                dao.updateCompletedStage(job.id, summary)
            }
            Result.success()
        } catch(e:CancellationException) {
            if (!published && isStopped) {
                // WorkManager may also stop a worker for rescheduling. Leave it eligible to retry.
                withContext(NonCancellable) { dao.updateActive(job.id, "Queued", "Waiting to resume") }
            }
            throw e
        } catch(e:OutOfMemoryError) {
            if (published) Result.success() else {
                dao.updateActive(job.id, "Failed", "This file exceeded available memory. Try a smaller source or convert one item at a time.")
                Result.failure()
            }
        } catch(e:Exception) {
            if (published) Result.success() else {
                dao.updateActive(job.id, "Failed", MorphError.explain(e), (e as? TargetTooSmall)?.minimum ?: 0)
                Result.failure()
            }
        } finally {
            folder.deleteRecursively()
            if(!published) exportFolder.deleteRecursively()
        }
    }
}
