package com.morph.app.engine

import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import com.morph.app.domain.model.*
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object FileInspector {
    private val exifTags=listOf("Make","Model","DateTimeOriginal","DateTimeDigitized","FNumber","ExposureTime","PhotographicSensitivity","FocalLength","LensModel","Artist","Copyright","GPSLatitude","GPSLatitudeRef","GPSLongitude","GPSLongitudeRef","GPSAltitude","GPSAltitudeRef","GPSDateStamp","GPSTimeStamp","XResolution","YResolution","ResolutionUnit")
    fun metadata(file:File):Map<String,String> = runCatching {ExifInterface(file).let {exif->exifTags.mapNotNull {tag->exif.getAttribute(tag)?.let {tag to it}}.toMap()}}.getOrDefault(emptyMap())
    fun writeMetadata(file:File,tags:Map<String,String>) {
        if(tags.isEmpty()) return
        ExifInterface(file).apply {tags.forEach { (key,value)->setAttribute(key,value) };saveAttributes()}
    }
    fun detail(ctx:Context,uri:Uri,fmt:Format):String=runCatching {
        when(fmt.category) {
            Category.AUDIO,Category.VIDEO -> MediaMetadataRetriever().let { m ->
                try {m.setDataSource(ctx,uri)
                    val duration=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000) ?: 0
                    val w=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);val h=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    listOfNotNull(if(w!=null && h!=null) "$w × $h px" else null,"%d:%02d".format(duration/60,duration%60),m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)).joinToString(" · ")
                } finally { m.release() }
            }
            Category.DOCUMENT -> if(fmt==Format.PDF) ctx.contentResolver.openFileDescriptor(uri,"r")?.use {fd->PdfRenderer(fd).use {"${it.pageCount} pages"}}.orEmpty() else ""
            Category.IMAGE -> {
                val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};ctx.contentResolver.openInputStream(uri)?.use {BitmapFactory.decodeStream(it,null,bounds)}
                val gps=runCatching {ctx.contentResolver.openInputStream(uri)?.use {ExifInterface(it).latLong!=null}}.getOrNull()
                listOfNotNull(if(bounds.outWidth>0) "${bounds.outWidth} × ${bounds.outHeight} px" else null,if(gps==true) "GPS present" else null).joinToString(" · ")
            }
            Category.SHEET -> if(fmt in listOf(Format.XLSX,Format.XLS)) ctx.contentResolver.openFileDescriptor(uri,"r")?.use {fd->
                if(fd.statSize !in 1..(8L shl 20)) "" else org.apache.poi.ss.usermodel.WorkbookFactory.create(File("/proc/self/fd/${fd.fd}"),null,true).use {"${it.numberOfSheets} sheets"}
            }.orEmpty() else ""
            else -> ""
        }
    }.getOrDefault("")
    suspend fun hash(source:InputStream):String {
        val digest=MessageDigest.getInstance("SHA-256")
        java.security.DigestInputStream(source,digest).use {it.transfer(object:java.io.OutputStream(){override fun write(b:Int){};override fun write(b:ByteArray,off:Int,len:Int){} })}
        return digest.digest().joinToString("") {"%02x".format(it)}
    }
}
