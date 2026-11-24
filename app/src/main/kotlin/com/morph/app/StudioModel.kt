package com.morph.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.morph.app.data.*
import com.morph.app.domain.graph.*
import com.morph.app.domain.model.*
import com.morph.app.engine.transfer
import com.morph.app.worker.ConversionWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

class StudioModel(private val app:Application):AndroidViewModel(app) {
    val dao=(app as MorphApplication).database.dao()
    val studioOptions = androidx.compose.runtime.mutableStateOf(ProOptions())
    val files=MutableStateFlow<List<Selection>>(emptyList())
    val importing=MutableStateFlow(false)
    val events=MutableSharedFlow<String>(extraBufferCapacity=8)
    val jobs=dao.jobs().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val recipes=dao.recipes().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    private val resolver=app.contentResolver
    private val importSlot = Mutex()
    private suspend fun inspect(uri:Uri, persist:Boolean):Selection {
        require(uri.scheme=="content") { "Select a file from a document provider" }
        var name="input.bin"; var bytes=0L
        // A readable document need not implement the optional metadata query.
        runCatching {
            resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name=c.getString(it) ?: name }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { bytes=c.getLong(it) }
                }
            }
        }
        val retained=persist && runCatching { resolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
        val stored=if(retained) uri else {
            val dir=File(app.filesDir,"imports/${UUID.randomUUID()}").apply { mkdirs() }
            val f=File(dir,StorageManager.safe(name))
            try { resolver.openInputStream(uri)!!.use { stream -> f.outputStream().use { stream.transfer(it) } }; bytes=f.length(); Uri.fromFile(f) }
            catch(e:Throwable) { dir.deleteRecursively(); throw e }
        }
        return describe(stored,name,bytes)
    }
    private suspend fun describe(uri:Uri,name:String,size:Long):Selection {
        var actualSize=size
        val detect = if(uri.scheme=="file") {
            ContentSniffer.detect(File(requireNotNull(uri.path)),name)
        } else {
            val cache = File.createTempFile("morph-inspect-",".bin",app.cacheDir)
            try {
                val source = resolver.openInputStream(uri) ?: error("This provider could not open the file.")
                actualSize=source.use { stream -> cache.outputStream().use { stream.transfer(it,cache.usableSpace) } }
                ContentSniffer.detect(cache,name)
            } finally { cache.delete() }
        }
        val detail=com.morph.app.engine.FileInspector.detail(app,uri,detect.actual)
        return Selection(uri.toString(),name,actualSize,detect.actual,detect.mismatch,detail,detect.isAnimated,detect.isMultiPage)
    }
    private fun ingest(action:suspend ()->List<Selection>) {
        viewModelScope.launch {
            importSlot.withLock {
                importing.value=true
                try { val new=withContext(Dispatchers.IO) { action() }; files.update { (it+new).distinctBy(Selection::uri) } }
                catch(e:CancellationException) { throw e }
                catch(e:Exception) { events.emit(e.message ?: "Import failed") }
                finally { importing.value=false }
            }
        }
    }
    fun pick(uris:List<Uri>,persist:Boolean=true,permission:android.view.DragAndDropPermissions?=null)=ingest {
        try {
            var failed = 0
            var firstReason = ""
            val imported = uris.distinct().mapNotNull { uri ->
                try { inspect(uri, persist) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (failed == 0) firstReason = MorphError.explain(e)
                    android.util.Log.w("MorphImport","Document import failed",e)
                    failed++
                    null
                }
            }
            if (failed > 0) events.emit("Could not import $failed of ${imported.size + failed}. $firstReason")
            imported
        } finally { permission?.release() }
    }
    fun folder(tree:Uri)=ingest {
        resolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val documents=mutableListOf<Selection>()
        val pending=ArrayDeque<String>(); pending.add(DocumentsContract.getTreeDocumentId(tree))
        val seen=mutableSetOf<String>()
        while(pending.isNotEmpty()) {
            val parent=pending.removeFirst(); if(!seen.add(parent)) continue
            resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent),arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_MIME_TYPE,OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { c ->
                while(c.moveToNext()) { val id=c.getString(0)
                    if(c.getString(1)==DocumentsContract.Document.MIME_TYPE_DIR) pending.add(id)
                    else documents+=describe(DocumentsContract.buildDocumentUriUsingTree(tree,id),c.getString(2),c.getLong(3))
                }
            }
        }; documents
    }
    fun paste(text:String)=ingest {
        val dir=File(app.filesDir,"imports/${UUID.randomUUID()}").apply { mkdirs() }
        val f=File(dir,"Clipboard.txt"); f.writeText(text); listOf(describe(Uri.fromFile(f),f.name,f.length()))
    }
    suspend fun enqueue(target:Format,budget:Long,exact:Boolean,opt:ProOptions,shared:Boolean,combine:Boolean=false) {
        val items=files.value; require(items.isNotEmpty())
        val budgets=if(shared && budget>0) SizeUnitCalculator.allocate(budget,items.map { it.size }) else List(items.size) { budget }
        val batch=UUID.randomUUID().toString()
        if(combine && items.size>1) {
            val manifest=org.json.JSONArray().apply { items.forEach { put(org.json.JSONObject().put("uri",it.uri).put("name",it.name)) } }
            val req=OneTimeWorkRequestBuilder<ConversionWorker>().build()
            submit(ConversionJob(req.id.toString(),"batch:"+manifest.toString(),"Collection",items.first().format.name,target.name,items.sumOf {it.size},budget,exact,opt.encode(),batch),req)
            return
        }
        for((i,file) in items.withIndex()) {
            val req=OneTimeWorkRequestBuilder<ConversionWorker>().build()
            submit(ConversionJob(req.id.toString(),file.uri,file.name,file.format.name,target.name,file.size,budgets[i],exact,opt.encode(),batch),req)
        }
    }
    suspend fun bundle(jobs:List<ConversionJob>) {
        require(jobs.size>1 && jobs.all {it.status=="Complete"})
        val sources=org.json.JSONArray().apply { jobs.forEach {job->
            val f=File(job.output);put(org.json.JSONObject().put("uri",Uri.fromFile(f).toString()).put("name",f.name))
        }}
        val req=OneTimeWorkRequestBuilder<ConversionWorker>().build()
        submit(ConversionJob(req.id.toString(),"batch:$sources","Batch outputs",Format.ZIP.name,Format.ZIP.name,jobs.sumOf {it.bytes},0,false,ProOptions().encode(),UUID.randomUUID().toString()),req)
    }
    suspend fun rerun(job:ConversionJob,budget:Long=job.budget) {
        val req=OneTimeWorkRequestBuilder<ConversionWorker>().build()
        submit(job.copy(id=req.id.toString(),budget=budget,status="Queued",stage="",minimum=0,output="",bytes=0,hash="",created=System.currentTimeMillis()),req)
    }
    private suspend fun submit(job:ConversionJob,req:OneTimeWorkRequest) {
        dao.save(job); WorkManager.getInstance(app).enqueue(req)
    }
    fun cancel(job:ConversionJob)=viewModelScope.launch {
        if (dao.updateActive(job.id, "Cancelled", "Cancelled") == 1) {
            WorkManager.getInstance(app).cancelWorkById(UUID.fromString(job.id))
        }
    }
}
