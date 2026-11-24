package com.morph.app.engine

import android.graphics.BitmapFactory
import com.morph.app.domain.graph.*
import com.morph.app.domain.model.*
import com.morph.app.domain.model.Format.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.CRC32
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object SemanticOutputValidator {
    suspend fun verify(file: File, target: Format, options: ProOptions, input: File?, source: Format) {
        require(file.isFile && file.length() > 0) { "The converter produced an empty file." }
        val format = MagicByteDetector.detect(file,file.name).actual
        val pageBundle = target.category == Category.IMAGE && source == PDF && format == ZIP
        when {
            target == RAW -> return
            pageBundle || target == FRAMES || target in setOf(ZIP,DOCX,PPTX,EPUB,ODT,ODS,XLSX) -> {
                if (options.password.isNotEmpty() && target == ZIP) {
                    net.lingala.zip4j.ZipFile(file,options.password.toCharArray()).use { zip ->
                        for (entry in zip.fileHeaders.filterNot { it.isDirectory }) zip.getInputStream(entry).use { it.transfer(sink) }
                    }
                } else ZipFile(file).use { zip ->
                    if (target !in setOf(ZIP,FRAMES) && !pageBundle) require(format == target) { "The output document is missing a required package part." }
                    for (entry in zip.entries()) {
                        currentCoroutineContext().ensureActive()
                        val crc = CRC32()
                        val length = java.util.zip.CheckedInputStream(zip.getInputStream(entry),crc).use { it.transfer(sink) }
                        require(length == entry.size && crc.value == entry.crc) { "An output archive entry failed its integrity check." }
                    }
                }
                if (target == XLSX || target == ODS) require(SpreadsheetEngine.tables(file,target,options.copy(sheet="")).isNotEmpty())
            }
            target == PDF -> PDDocument.load(file,com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly().setTempDir(file.parentFile)).use { require(it.numberOfPages > 0) { "The output PDF contains no pages." } }
            target.category == Category.AUDIO || target.category == Category.VIDEO -> {
                val info = MediaEngine.probe(file)
                val type = if (target.category == Category.AUDIO) "audio" else "video"
                require(info.streams.any { it.type == type }) { "The output is missing its $type stream." }
                if (input != null && source.category in setOf(Category.AUDIO,Category.VIDEO)) {
                    val original = MediaEngine.probe(input)
                    if (type == "video" && !options.mute) require(info.streams.count { it.type == "audio" } == original.streams.count { it.type == "audio" }) { "Output audio tracks are missing." }
                    val duration = original.duration?.toDoubleOrNull()
                    val expected = (options.trimEnd ?: duration)?.let { it - (options.trimStart ?: 0.0) }
                    val actual = info.duration?.toDoubleOrNull()
                    if (expected != null && actual != null) require(kotlin.math.abs(expected - actual) <= maxOf(1.0, expected * .02)) { "The output duration differs from the selected input." }
                }
            }
            target.category == Category.IMAGE -> {
                if (target == PNG) PngIntegrity.verify(file)
                if (target == JPG) java.io.RandomAccessFile(file,"r").use { jpeg ->
                    require(jpeg.length() >= 4)
                    jpeg.seek(jpeg.length()-2)
                    require(jpeg.readUnsignedShort() == 0xffd9) { "The JPEG output is truncated." }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path,bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) require(MediaEngine.probe(file).streams.any { (it.width ?: 0) > 0 && (it.height ?: 0) > 0 }) { "The output image has invalid dimensions." }
            }
            target in FormatRegistry.tables -> require(SpreadsheetEngine.tables(file,target,options.copy(sheet="")).isNotEmpty()) { "No output sheet could be opened." }
            target in FormatRegistry.structured -> DataEngine.read(file,target)
            target == HTML -> require(org.jsoup.Jsoup.parse(file,"UTF-8").body().text().isNotBlank()) { "The HTML output contains no readable content." }
            target in setOf(TXT,MD) -> require(file.bufferedReader().useLines { lines -> lines.any { it.isNotBlank() } }) { "The output contains no text." }
            target in FormatRegistry.archives -> {
                require(format == target) { "The output archive header is invalid." }
                val dir = File(file.parentFile,"verify-archive")
                try { ArchiveEngine.unpack(file,target,dir,options) } finally {dir.deleteRecursively()}
            }
            target == RTF -> require(format == RTF && RtfReader.text(file.readText(Charsets.ISO_8859_1)).isNotBlank()) { "The RTF has no readable content." }
            target == B64 -> file.inputStream().use { decodeBase64(it,sink) }
            target == SQL -> require(file.bufferedReader().use { it.readLine()?.startsWith("CREATE TABLE") == true }) { "Missing SQL table definition." }
        }
    }
    private val sink = object: java.io.OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
}
