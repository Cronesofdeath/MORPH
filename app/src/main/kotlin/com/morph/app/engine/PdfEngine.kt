package com.morph.app.engine

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.morph.app.domain.model.*
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object PdfEngine {
    fun pages(range: String,count: Int): List<Int> {
        if(range.isBlank()) return (0 until count).toList()
        val entries = range.split(',')
        require(entries.size <= 10_000) { "Too many page selection entries" }
        return buildSet {
            for (entry in entries) {
                val ends = entry.split('-')
                val start = ends.first().trim().toIntOrNull()
                val end = ends.last().trim().toIntOrNull()
                require(ends.size <= 2 && start != null && end != null && start in 1..count && end in start..count) {
                    "Invalid page range: ${entry.trim()}"
                }
                addAll(start - 1 until end)
            }
        }.toList()
    }
    suspend fun convert(src: File,dst: File,to: Format,opt: ProOptions,limit: Long?,step: suspend (String)->Unit = {}): File {
        if(to==Format.TXT || to==Format.PDF) {
            PDDocument.load(src,MemoryUsageSetting.setupTempFileOnly().setTempDir(dst.parentFile)).use { doc ->
                if(opt.pageRange.isNotBlank()) {
                    val selected=pages(opt.pageRange,doc.numberOfPages).toSet()
                    for(i in doc.numberOfPages-1 downTo 0) if(i !in selected) doc.removePage(i)
                }
                if(to==Format.TXT) { require(doc.numberOfPages > 0) { "This PDF contains no pages." }
                    val stripper = PDFTextStripper()
                    dst.bufferedWriter().use { writer ->
                        for (page in 1..doc.numberOfPages) {
                            currentCoroutineContext().ensureActive()
                            stripper.startPage = page
                            stripper.endPage = page
                            val embedded = stripper.getText(doc)
                            val text = if (embedded.isNotBlank()) embedded else {
                                step("Running offline OCR • Page $page/${doc.numberOfPages}")
                                OfflineOcr.page(doc,page-1)
                            }
                            require(text.isNotBlank()) {
                                "Page $page has no readable text after offline recognition. Export the pages as images or use a clearer scan."
                            }
                            writer.write(text)
                        }
                    }
                    return dst }
                doc.documentInformation=PDDocumentInformation()
                doc.save(dst)
            }
            if(limit==null || dst.length()<=limit) return dst
            for(dpi in listOf(300,150,110,72,50)) {
                suspend fun optimizeImages(quality:Int):Long {
                    PDDocument.load(src,MemoryUsageSetting.setupTempFileOnly().setTempDir(dst.parentFile)).use { doc ->
                        val selected=pages(opt.pageRange,doc.numberOfPages).toSet()
                        for(i in doc.numberOfPages-1 downTo 0) if(i !in selected) doc.removePage(i)
                        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<com.tom_roush.pdfbox.cos.COSDictionary,Boolean>())
                        var replacements=0
                        suspend fun visit(resources:PDResources?,maxEdge:Int) {
                            if(resources==null || !seen.add(resources.cosObject)) return
                            for(name in resources.xObjectNames.toList()) {
                                currentCoroutineContext().ensureActive()
                                when(val x=resources.getXObject(name)) {
                                    is com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject -> visit(x.resources,maxEdge)
                                    is com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject -> {
                                        if(x.width.toLong()*x.height>8_000_000L || x.softMask!=null || x.isStencil) continue
                                        val original=x.image ?: continue
                                        val scale=minOf(1.0,maxEdge.toDouble()/maxOf(original.width,original.height))
                                        val bitmap=if(scale==1.0) original else Bitmap.createScaledBitmap(original,maxOf(1,(original.width*scale).toInt()),maxOf(1,(original.height*scale).toInt()),true)
                                        try {resources.put(name,JPEGFactory.createFromImage(doc,bitmap,quality/100f));replacements++}
                                        finally {if(bitmap!==original) bitmap.recycle()}
                                    }
                                }
                            }
                        }
                        for(page in doc.pages) visit(page.resources,(maxOf(page.mediaBox.width,page.mediaBox.height)*dpi/72).toInt().coerceAtLeast(32))
                        if(replacements==0) return Long.MAX_VALUE
                        doc.documentInformation=PDDocumentInformation(); doc.save(dst)
                    };return dst.length()
                }
                if(optimizeImages(10)==Long.MAX_VALUE) break
                val quality=TargetSizeConvergence.highestQuality(limit,10,92) {optimizeImages(it)}
                if(quality!=null) {optimizeImages(quality);return dst}
            }
        }
        ParcelFileDescriptor.open(src,ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { pdf ->
            val indices=pages(opt.pageRange,pdf.pageCount)
            suspend fun render(index:Int,dpi:Int,block:(Bitmap,Int,Int)->Unit) {
                currentCoroutineContext().ensureActive()
                pdf.openPage(index).use { page ->
                    val scale=minOf(dpi/72.0,3500.0/maxOf(page.width,page.height))
                    val b=Bitmap.createBitmap(maxOf(1,(page.width*scale).toInt()),maxOf(1,(page.height*scale).toInt()),Bitmap.Config.ARGB_8888)
                    try { b.eraseColor(Color.WHITE); page.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_PRINT); block(b,page.width,page.height) } finally { b.recycle() }
                }
            }
            if(to==Format.PDF) {
                for(dpi in listOf(150,110,72,50)) {
                    suspend fun encode(q:Int):Long {
                        PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(dst.parentFile)).use { doc ->
                            for(i in indices) render(i,dpi) { b,w,h -> val page=PDPage(PDRectangle(w.toFloat(),h.toFloat())); doc.addPage(page)
                                PDPageContentStream(doc,page).use { it.drawImage(JPEGFactory.createFromImage(doc,b,q/100f),0f,0f,w.toFloat(),h.toFloat()) } }
                            doc.save(dst)
                        }; return dst.length()
                    }
                    val q=TargetSizeConvergence.highestQuality(limit!!) { encode(it) }
                    if(q!=null) { encode(q); return dst }
                }
                throw TargetTooSmall(dst.length())
            }
            val files=indices.map { i -> val file=File(dst.parentFile,"page-${i+1}.${to.extension}")
                render(i,opt.dpi) { b,_,_ -> file.outputStream().use {
                    @Suppress("DEPRECATION")
                    check(b.compress(when(to) { Format.JPG -> Bitmap.CompressFormat.JPEG; Format.WEBP -> Bitmap.CompressFormat.WEBP; else -> Bitmap.CompressFormat.PNG },92,it))
                } }; file.name to file }
            if(files.size==1) { files.first().second.copyTo(dst,true); return dst }
            val bundle=File(dst.parentFile,dst.nameWithoutExtension+".zip")
            ArchiveEngine.pack(files,bundle,Format.ZIP,opt); return bundle
        } }
    }
}
