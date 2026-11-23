package com.morph.app.engine

import android.graphics.pdf.PdfDocument
import com.morph.app.domain.graph.*
import com.morph.app.domain.model.*
import com.morph.app.domain.model.Format.*
import java.io.*
import java.util.Base64
import java.util.zip.CRC32
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

inline fun <T> PdfDocument.use(block:(PdfDocument)->T):T = try { block(this) } finally { close() }

object TargetSizeOrchestrator {
    suspend fun convert(src: File,folder: File,from: Format,to: Format,opt: ProOptions,target: SizeTarget?,step: suspend (String)->Unit): File {
        if(target!=null && target.bytes < minimumHeader(to)) throw TargetTooSmall(minimumHeader(to))
        val route=ConversionGraphRouter().route(from,to) ?: error("No compatible conversion route")
        var input=src
        for((index,edge) in route.withIndex()) {
            currentCoroutineContext().ensureActive()
            val out=File(folder,"stage-$index.${edge.to.extension}")
            val limit=target?.bytes?.takeIf { index==route.lastIndex }
            step("${edge.from.label} → ${edge.to.label}")
            val result=when(edge.engine) {
                Engine.IMAGE -> { ImageEngine.convert(input,out,edge.from,edge.to,opt,limit,step); out }
                Engine.MEDIA -> { MediaEngine.convert(input,out,edge.to,opt,limit,step); out }
                Engine.PDF -> PdfEngine.convert(input,out,edge.to,opt,limit)
                Engine.SHEET -> {
                    if(edge.from in listOf(XLS,XLSX) && edge.to in listOf(XLS,XLSX)) SpreadsheetEngine.migrate(input,out,edge.to,opt)
                    else SpreadsheetEngine.export(SpreadsheetEngine.tables(input,edge.from,opt),out,edge.to,opt)
                    out
                }
                Engine.DOCUMENT,Engine.SLIDES -> { DocumentEngine.convert(input,out,edge.from,edge.to,opt); out }
                Engine.DATA -> {
                    when {
                        edge.to==B64 -> Base64.getEncoder().wrap(out.outputStream()).use { stream -> input.inputStream().use { it.transfer(stream) } }
                        edge.from==B64 -> input.inputStream().buffered().use { stream ->
                            out.outputStream().buffered().use { decodeBase64(stream, it) }
                        }
                        else -> DataEngine.write(DataEngine.read(input,edge.from),out,edge.to,opt)
                    }; out
                }
                Engine.ARCHIVE -> {
                    val container=edge.from==edge.to && edge.from !in FormatRegistry.archives
                    val files=if(container || edge.from in FormatRegistry.archives)
                        ArchiveEngine.unpack(input,edge.from,File(folder,"unpack-$index"),opt) else listOf(src.name to input)
                    if(edge.to==RAW) {
                        if(files.size==1) files.first().second.copyTo(File(folder,File(files.first().first).name+"-extracted."+File(files.first().first).extension),true)
                        else File(folder,"extracted.zip").also { ArchiveEngine.pack(files,it,ZIP,opt) }
                    } else {
                        ArchiveEngine.pack(files,out,edge.to,opt)
                        if(container && limit!=null && out.length()>limit) {
                            for((name,blob) in files) {
                                val image=Format.named(name)
                                if(image !in FormatRegistry.bitmapOutputs) continue
                                val reduced=File(folder,"reduced.${image!!.extension}")
                                val share=(blob.length()*(limit.toDouble()/out.length())*.90).toLong().coerceAtLeast(100)
                                ImageEngine.convert(blob,reduced,image,image,opt,share,step)
                                reduced.copyTo(blob,true); reduced.delete()
                            }
                            ArchiveEngine.pack(files,out,edge.to,opt)
                        }; out
                    }
                }
            }
            if(input!=src && input!=result) input.delete()
            input=result
        }
        if(target!=null) {
            step("Verifying bytes")
            if(input.length()>target.bytes && to==PDF) {
                val reduced=File(folder,"optimized.pdf"); PdfEngine.convert(input,reduced,PDF,opt.copy(pageRange=""),target.bytes); input=reduced
            }
            if(input.length()>target.bytes) throw TargetTooSmall(input.length())
            if(target.exact && input.length()!=target.bytes) pad(input,Format.named(input.name) ?: to,target.bytes)
            check(input.length()<=target.bytes && (!target.exact || input.length()==target.bytes))
        }
        return input
    }

    fun minimumHeader(fmt:Format):Long=when(fmt) {
        PNG -> 67;JPG -> 128;PDF -> 128;ZIP -> 22;WAV -> 44;MP4,MOV,M4V -> 32;GZ -> 20;else -> 1
    }

    suspend fun pad(file: File,fmt: Format,target: Long) {
        val gap=target-file.length()
        if(gap==0L) return
        require(gap>0 && gap<file.parentFile!!.usableSpace) { "Insufficient storage for exact-byte padding" }
        RandomAccessFile(file,"rw").use { io ->
            val length=io.length()
            suspend fun fill(count: Long,value: Byte,crc:CRC32?=null) {
                val block=ByteArray(65536) { value }
                var left=count
                while(left>0) { currentCoroutineContext().ensureActive(); val n=minOf(left,block.size.toLong()).toInt(); io.write(block,0,n); crc?.update(block,0,n); left-=n }
            }
            io.seek(length)
            when(fmt) {
                TXT,JSON,HTML -> fill(gap,32)
                MOV,M4V,MP4 -> {
                    require(gap >= 8) { "MP4 padding needs at least eight bytes" }
                    val extended = gap > 0xffffffffL
                    io.writeInt(if (extended) 1 else gap.toInt())
                    io.writeBytes("free")
                    if (extended) io.writeLong(gap)
                    fill(gap - if (extended) 16 else 8, 0)
                }
                JPG -> {
                    io.seek(length-2); require(io.readUnsignedShort()==0xffd9); io.seek(length-2)
                    if (gap < 4) {
                        // JPEG permits fill bytes before a marker, including EOI.
                        fill(gap, 0xff.toByte())
                        io.writeShort(0xffd9)
                        return
                    }
                    var left=gap
                    while(left>0) { var chunk=minOf(left,65537L); if(left-chunk in 1..3) chunk-=4
                        io.writeShort(0xfffe); io.writeShort((chunk-2).toInt()); fill(chunk-4,32); left-=chunk }
                    io.writeShort(0xffd9)
                }
                PNG -> {
                    require(gap in 14..Int.MAX_VALUE.toLong()) { "PNG text padding requires fourteen bytes" }
                    val tail=ByteArray(12); io.seek(length-12); io.readFully(tail); io.seek(length-12)
                    io.writeInt((gap-12).toInt()); val prefix="tEXtP\u0000".toByteArray(); val crc=CRC32()
                    io.write(prefix); crc.update(prefix); fill(gap-14,32,crc); io.writeInt(crc.value.toInt()); io.write(tail)
                }
                ZIP -> {
                    require(gap<=65535); val size=minOf(length,65557).toInt(); val tail=ByteArray(size); io.seek(length-size); io.readFully(tail)
                    val pos=(size-22 downTo 0).firstOrNull { i -> tail[i]==80.toByte() && tail[i+1]==75.toByte() && tail[i+2]==5.toByte() && tail[i+3]==6.toByte() && i+22+(tail[i+20].toInt() and 255)+((tail[i+21].toInt() and 255) shl 8)==size } ?: error("Missing ZIP directory")
                    val comment=size-pos-22+gap; require(comment<=65535); io.seek(length-size+pos+20)
                    io.write(comment.toInt() and 255); io.write((comment.toInt() ushr 8) and 255); io.seek(length); fill(gap,32)
                }
                PDF -> {
                    val tail=ByteArray(minOf(length,2048).toInt()); io.seek(length-tail.size); io.readFully(tail)
                    val pos=tail.toString(Charsets.ISO_8859_1).lastIndexOf("startxref"); require(pos>=0)
                    io.seek(length-tail.size+pos); fill(gap,32); io.write(tail,pos,tail.size-pos)
                }
                else -> error("Exact-byte padding is unavailable for ${fmt.label}")
            }
        }
    }
}
