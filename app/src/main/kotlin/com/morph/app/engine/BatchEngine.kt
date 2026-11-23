package com.morph.app.engine

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.morph.app.data.StorageManager
import com.morph.app.domain.graph.MagicByteDetector
import com.morph.app.domain.graph.FormatRegistry
import com.morph.app.domain.model.*
import org.json.JSONArray
import java.io.File
import kotlin.math.min
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object BatchEngine {
    suspend fun combine(context:Context,sources:JSONArray,folder:File,to:Format,opt:ProOptions,target:SizeTarget?,step:suspend(String)->Unit):File {
        val names=mutableSetOf<String>()
        val files=(0 until sources.length()).map { i->
            val item=sources.getJSONObject(i); val uri=Uri.parse(item.getString("uri"))
            val name=StorageManager.safe(item.getString("name"))
            var entry=name; var suffix=1
            while(!names.add(entry)) entry="${suffix++}-$name"
            val f=File(folder,"input-$i").apply {mkdirs()}.resolve(name)
            context.contentResolver.openInputStream(uri)!!.use {stream->f.outputStream().use {stream.transfer(it)}}
            entry to f
        }
        var output=File(folder,"collection.${to.extension}")
        if(to in FormatRegistry.archives) ArchiveEngine.pack(files,output,to,opt) else {
            require(to==Format.PDF)
            PdfDocument().use {doc->
                files.forEachIndexed {i,(name,file)->
                    currentCoroutineContext().ensureActive();step("Rendering page ${i+1} of ${files.size}")
                    val b=ImageEngine.bitmap(file,MagicByteDetector.detect(file,name).actual,opt)
                    try {
                        val dims=when(opt.pageSize) {"Fit"->b.width to b.height;"Letter"->612 to 792;else->595 to 842}
                        val w=if(opt.landscape) dims.second else dims.first;val h=if(opt.landscape) dims.first else dims.second
                        val margin=min(opt.margin,min(w,h)/4);val scale=min((w-2*margin).toFloat()/b.width,(h-2*margin).toFloat()/b.height)
                        val dx=(w-scale*b.width)/2;val dy=(h-scale*b.height)/2
                        val page=doc.startPage(PdfDocument.PageInfo.Builder(w,h,i+1).create())
                        page.canvas.drawColor(Color.WHITE);page.canvas.drawBitmap(b,null,RectF(dx,dy,w-dx,h-dy),Paint(Paint.FILTER_BITMAP_FLAG));doc.finishPage(page)
                    } finally {b.recycle()}
                };output.outputStream().use(doc::writeTo)
            }
            if(target!=null && output.length()>target.bytes) output=PdfEngine.convert(output,File(folder,"optimized.pdf"),to,opt,target.bytes)
        }
        if(target!=null) {
            if(output.length()>target.bytes) throw TargetTooSmall(output.length())
            if(target.exact) TargetSizeOrchestrator.pad(output,to,target.bytes)
        }
        return output
    }
}
