package com.morph.app.engine

import android.graphics.Bitmap
import java.io.*
import java.util.zip.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Median-cut quantization in RGBA space, followed by indexed PNG and level-nine DEFLATE. */
object IndexedPng {
    private fun key(pixel:Int) = ((pixel ushr 12) and 0xf00) or ((pixel ushr 16) and 0xf000) or ((pixel ushr 8) and 0xf0) or ((pixel ushr 4) and 15)
    private fun component(key:Int,shift:Int) = (key ushr shift) and 15
    suspend fun write(bitmap:Bitmap,file:File,count:Int) {
        val histogram=IntArray(65536)
        val row=IntArray(bitmap.width)
        for(y in 0 until bitmap.height) {
            currentCoroutineContext().ensureActive()
            bitmap.getPixels(row,0,row.size,0,y,row.size,1); row.forEach { histogram[key(it)]++ }
        }
        data class Box(val keys:List<Int>) {
            val weight=keys.sumOf { histogram[it] }
            val span=(0..3).map { c -> keys.maxOf {component(it,c*4)}-keys.minOf {component(it,c*4)} }
            val channel=span.indices.maxBy {span[it]}
        }
        val boxes=mutableListOf(Box(histogram.indices.filter { histogram[it]>0 }))
        while(boxes.size<count) {
            val box=boxes.filter {it.keys.size>1}.maxByOrNull {it.span[it.channel]*it.weight.toLong()} ?: break
            boxes.remove(box)
            val sorted=box.keys.sortedBy {component(it,box.channel*4)}
            var index=0; var weight=0
            while(index<sorted.lastIndex && weight<box.weight/2) {weight+=histogram[sorted[index++]]}
            val split=index.coerceIn(1,sorted.lastIndex)
            boxes+=Box(sorted.subList(0,split)); boxes+=Box(sorted.subList(split,sorted.size))
        }
        val lookup=ByteArray(65536); val rgb=ByteArray(boxes.size*3); val alpha=ByteArray(boxes.size)
        for((i,box) in boxes.withIndex()) {
            box.keys.forEach {lookup[it]=i.toByte()}
            fun mean(shift:Int)=(box.keys.sumOf {component(it,shift)*17L*histogram[it]}/box.weight).toByte()
            rgb[i*3]=mean(8); rgb[i*3+1]=mean(4); rgb[i*3+2]=mean(0); alpha[i]=mean(12)
        }
        DataOutputStream(file.outputStream().buffered()).use { out ->
            fun chunk(type:String,data:ByteArray) {
                val tag=type.toByteArray(); val crc=CRC32(); crc.update(tag);crc.update(data)
                out.writeInt(data.size);out.write(tag);out.write(data);out.writeInt(crc.value.toInt())
            }
            out.write(byteArrayOf(-119,80,78,71,13,10,26,10))
            val header=ByteArrayOutputStream(); DataOutputStream(header).use {it.writeInt(bitmap.width);it.writeInt(bitmap.height);it.write(byteArrayOf(8,3,0,0,0))}
            chunk("IHDR",header.toByteArray());chunk("PLTE",rgb)
            if(alpha.any {it!=(-1).toByte()}) chunk("tRNS",alpha)
            val deflater=Deflater(9)
            val sink=object:OutputStream(){
                override fun write(b:Int)=chunk("IDAT",byteArrayOf(b.toByte()))
                override fun write(b:ByteArray,off:Int,len:Int) {if(len>0) chunk("IDAT",b.copyOfRange(off,off+len))}
            }
            try { DeflaterOutputStream(sink,deflater,65536).use { z ->
                val indexed=ByteArray(row.size+1)
                for(y in 0 until bitmap.height) {
                    currentCoroutineContext().ensureActive()
                    bitmap.getPixels(row,0,row.size,0,y,row.size,1);row.forEachIndexed {i,pixel->indexed[i+1]=lookup[key(pixel)]};z.write(indexed)
                }
            } } finally {deflater.end()}
            chunk("IEND",byteArrayOf())
        }
    }
}
