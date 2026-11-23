package com.morph.app.engine

import com.morph.app.domain.graph.FormatRegistry
import com.morph.app.domain.model.Format
import com.morph.app.domain.model.Format.*
import com.morph.app.domain.model.ProOptions
import java.io.*
import java.util.zip.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.*
import org.apache.commons.compress.archivers.tar.*
import org.apache.commons.compress.compressors.bzip2.*
import org.apache.commons.compress.compressors.xz.*

suspend fun InputStream.transfer(out: OutputStream, max: Long = Long.MAX_VALUE): Long {
    val buffer = ByteArray(65536)
    var total = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val n = read(buffer)
        if (n < 0) return total
        total += n
        require(total <= max) { "File exceeds available processing space" }
        out.write(buffer,0,n)
    }
}

object ArchiveEngine {
    private fun decompress(format: Format, input: InputStream): InputStream = when(format) {
        TGZ,GZ -> GZIPInputStream(input)
        TBZ,BZ -> BZip2CompressorInputStream(input, true)
        TXZ,XZ -> org.tukaani.xz.XZInputStream(input,65536)
        else -> input
    }
    private fun compress(format: Format, output: OutputStream): OutputStream = when(format) {
        TGZ,GZ -> GZIPOutputStream(output)
        TBZ,BZ -> BZip2CompressorOutputStream(output)
        TXZ,XZ -> XZCompressorOutputStream(output)
        else -> output
    }

    suspend fun unpack(src: File, format: Format, folder: File, opts: ProOptions): List<Pair<String,File>> {
        folder.mkdirs()
        val result = mutableListOf<Pair<String,File>>()
        val names = mutableSetOf<String>()
        var remaining = minOf(folder.usableSpace / 2, 8L shl 30)
        suspend fun extract(name: String, stream: InputStream) {
            require(result.size < 10000) { "Archive has too many entries" }
            val path = name.replace('\\','/').removePrefix("./")
            require(path.isNotEmpty() && !path.startsWith("/") && path.split('/').none { it in listOf("..",".","") || ':' in it }) { "Invalid archive path" }
            require(names.add(path)) { "Duplicate archive entry" }
            val file = File(folder,"entry-${result.size}")
            val count = file.outputStream().buffered().use { stream.transfer(it,remaining) }
            remaining -= count
            result += path to file
        }
        when(format) {
            ZIP,EPUB,DOCX,XLSX,PPTX,ODT,ODS -> net.lingala.zip4j.ZipFile(src,opts.inputPassword.toCharArray()).use { zip ->
                zip.fileHeaders.filterNot { it.isDirectory }.forEach { e -> zip.getInputStream(e).use { extract(e.fileName,it) } }
            }
            SEVEN -> SevenZFile.builder().setFile(src).setMaxMemoryLimitKiB(65_536)
                .apply { if (opts.inputPassword.isNotEmpty()) setPassword(opts.inputPassword.toCharArray()) }
                .get().use { archive ->
                var e = archive.nextEntry
                while (e != null) {
                    if (!e.isDirectory) archive.getInputStream(e).use { extract(e.name,it) }
                    e = archive.nextEntry
                }
            }
            in FormatRegistry.singleStreams -> src.inputStream().buffered().use { raw -> decompress(format,raw).use { extract(src.nameWithoutExtension,it) } }
            else -> src.inputStream().buffered().use { raw -> TarArchiveInputStream(decompress(format,raw)).use { tar ->
                var e = tar.nextEntry
                while (e != null) {
                    if (e.isFile) extract(e.name,tar)
                    e = tar.nextEntry
                }
            } }
        }
        return result
    }

    suspend fun pack(files: List<Pair<String,File>>, dst: File, fmt: Format, opts: ProOptions) {
        if (fmt == ZIP && opts.password.isNotEmpty()) {
            net.lingala.zip4j.ZipFile(dst,opts.password.toCharArray()).use { zip ->
                files.forEach { (name,file) ->
                    currentCoroutineContext().ensureActive()
                    val parameters = ZipParameters().also { it.fileNameInZip=name; it.isEncryptFiles=true; it.encryptionMethod=EncryptionMethod.AES }
                    file.inputStream().use { zip.addStream(it,parameters) }
                }
            }
            return
        }
        when(fmt) {
            ZIP,EPUB,DOCX,XLSX,PPTX,ODT,ODS -> ZipOutputStream(dst.outputStream().buffered()).use { zip ->
                zip.setLevel(opts.compression.coerceIn(0,9))
                files.sortedBy { if (it.first == "mimetype") 0 else 1 }.forEach { (name,file) ->
                    val entry = ZipEntry(name)
                    if (name == "mimetype") {
                        val crc = CRC32()
                        file.inputStream().use { CheckedInputStream(it,crc).transfer(sink) }
                        entry.method=ZipEntry.STORED; entry.size=file.length(); entry.crc=crc.value
                    }
                    zip.putNextEntry(entry); file.inputStream().use { it.transfer(zip) }; zip.closeEntry()
                }
            }
            SEVEN -> SevenZOutputFile(dst).use { archive ->
                files.forEach { (name,file) ->
                    currentCoroutineContext().ensureActive()
                    archive.putArchiveEntry(archive.createArchiveEntry(file,name))
                    file.inputStream().use { input -> input.transfer(object: OutputStream() {
                        override fun write(b: Int) = archive.write(b)
                        override fun write(b: ByteArray, off: Int, len: Int) = archive.write(b,off,len)
                    }) }
                    archive.closeArchiveEntry()
                }
            }
            in FormatRegistry.singleStreams -> {
                require(files.size == 1) { "This format holds one file. Select a TAR container for several files." }
                compress(fmt,dst.outputStream().buffered()).use { out -> files.single().second.inputStream().use { it.transfer(out) } }
            }
            else -> TarArchiveOutputStream(compress(fmt,dst.outputStream().buffered())).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                files.forEach { (name,file) ->
                    tar.putArchiveEntry(TarArchiveEntry(name).also { it.size=file.length() })
                    file.inputStream().use { it.transfer(tar) }; tar.closeArchiveEntry()
                }
            }
        }
    }

    private val sink = object: OutputStream() { override fun write(b: Int) {} ; override fun write(b: ByteArray,off: Int,len: Int) {} }
}
