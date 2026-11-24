package com.morph.app.domain.graph

import com.morph.app.domain.model.Format
import com.morph.app.domain.model.Format.*
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import org.apache.poi.poifs.filesystem.POIFSFileSystem

data class FileIdentity(val actual: Format, val extension: Format?, val verified: Boolean,
    val isAnimated: Boolean = false, val isMultiPage: Boolean = false, val isEncrypted: Boolean = false,
    val claimedExtension: String = "") {
    val trueFormat get() = actual
    val containerInfo get() = actual.mime
    val isExtensionMismatch get() = mismatch
    val mismatch: Boolean get() = verified && extension != actual && !(actual == SPREADSHEETML && extension == XML)
}

typealias Detection = FileIdentity
typealias MagicByteDetector = ContentSniffer

object ContentSniffer {
    private const val LIMIT = 65_536

    fun detect(input: InputStream, name: String): Detection {
        val limit = ByteArray(LIMIT)
        var size = 0
        while (size < limit.size) {
            val chunk = input.read(limit, size, limit.size - size)
            if (chunk < 1) break
            size += chunk
        }
        return detect(limit.copyOf(size), name)
    }

    fun detect(file: File, name: String): Detection {
        val first = file.inputStream().use { detect(it, name) }
        val sig=ByteArray(8);file.inputStream().use {it.read(sig)}
        if(sig.contentEquals(byteArrayOf(-48,-49,17,-32,-95,-79,26,-31))) {
            val kind=runCatching {POIFSFileSystem(file,true).use {fs->when {
                fs.root.hasEntry("EncryptedPackage") -> return first.copy(actual=RAW,verified=false,isEncrypted=true)
                fs.root.hasEntry("Workbook") || fs.root.hasEntry("Book") -> XLS
                fs.root.hasEntry("WordDocument") -> DOC
                fs.root.hasEntry("PowerPoint Document") -> PPT
                else -> RAW
            }}}.getOrElse { if (it is IllegalArgumentException) throw it else RAW }
            return first.copy(actual=kind,verified=kind!=RAW)
        }
        if(first.actual in listOf(GZ,BZ,XZ)) {
            val tar=runCatching { file.inputStream().buffered().use { raw ->
                val stream=when(first.actual) {GZ->java.util.zip.GZIPInputStream(raw);BZ->org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(raw);else->org.tukaani.xz.SingleXZInputStream(raw,65536)}
                stream.use { input ->val block=ByteArray(512);java.io.DataInputStream(input).readFully(block);String(block,257,5,Charsets.US_ASCII)=="ustar"}
            }}.getOrDefault(false)
            if(tar) return first.copy(actual=when(first.actual){GZ->TGZ;BZ->TBZ;else->TXZ})
        }
        val actual = when {
            sig[0]==0x50.toByte() && sig[1]==0x4b.toByte() -> runCatching {
                ZipFile(file).use { zip ->
                    when {
                        zip.getEntry("xl/workbook.xml") != null -> XLSX
                        zip.getEntry("word/document.xml") != null -> DOCX
                        zip.getEntry("ppt/presentation.xml") != null -> PPTX
                        zip.getEntry("META-INF/container.xml") != null -> EPUB
                        else -> zip.getEntry("mimetype")?.takeIf { it.size in 1..128 }?.let { entry ->
                            val mime = zip.getInputStream(entry).bufferedReader().use { it.readText() }; listOf(ODS,ODT,EPUB).find { it.mime == mime }
                        } ?: ZIP
                    }
                }
            }.getOrDefault(ZIP)
            first.actual == RAW && file.length() >= 26 -> java.io.RandomAccessFile(file,"r").use { io ->
                val footer = ByteArray(18)
                io.seek(io.length()-footer.size); io.readFully(footer)
                if (footer.toString(Charsets.US_ASCII) == "TRUEVISION-XFILE.\u0000") TGA else RAW
            }
            else -> first.actual
        }
        val encrypted = if (actual == PDF) java.io.RandomAccessFile(file,"r").use { io ->
            val tail = ByteArray(minOf(io.length(),65536L).toInt())
            io.seek(io.length()-tail.size); io.readFully(tail)
            "/Encrypt" in tail.toString(Charsets.ISO_8859_1)
        } else false
        return first.copy(actual = actual, verified=first.verified || actual != RAW, isEncrypted=encrypted, isAnimated = ImageStructure.animated(file,actual), isMultiPage = ImageStructure.multipage(file,actual))
    }

    private fun isoBrand(bytes: ByteArray, raw: String): String {
        val major = raw.substring(8, minOf(12, raw.length))
        val size = java.nio.ByteBuffer.wrap(bytes).int.toLong() and 0xffffffffL
        val compatible = (16 until minOf(size, bytes.size.toLong()).toInt() step 4)
            .filter { it + 4 <= minOf(size, bytes.size.toLong()) }.map { raw.substring(it, it + 4) }
        val brands = listOf(major) + compatible
        return listOf("avif","avis","heic","heix","hevc","hevx","M4A ","M4B ","M4V ","qt  ","mif1","msf1").firstOrNull { it in brands } ?: major
    }

    fun detect(bytes: ByteArray, name: String): Detection {
        val ext = Format.named(name)
        fun header(vararg values: Int) = values.indices.all { it < bytes.size && bytes[it].toInt() and 255 == values[it] }
        val raw = bytes.toString(Charsets.ISO_8859_1)
        val text = ContentText.decode(bytes).replaceFirst(Regex("""(?s)^\s*(?:<!--.*?-->\s*)+"""), "").trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val actual = when {
            header(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> PNG
            header(0xff, 0xd8, 0xff) -> JPG
            header(0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c) -> SEVEN
            header(0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1) -> when {
                "Workbook".toByteArray(Charsets.UTF_16LE).toString(Charsets.ISO_8859_1) in raw -> XLS
                "WordDocument".toByteArray(Charsets.UTF_16LE).toString(Charsets.ISO_8859_1) in raw -> DOC
                "PowerPoint Document".toByteArray(Charsets.UTF_16LE).toString(Charsets.ISO_8859_1) in raw -> PPT
                else -> null
            }
            header(0x50, 0x4b, 3, 4) || header(0x50, 0x4b, 5, 6) -> when {
                "xl/" in raw -> XLSX
                "word/" in raw -> DOCX
                "ppt/" in raw -> PPTX
                "application/epub+zip" in raw -> EPUB
                "application/vnd.oasis.opendocument.spreadsheet" in raw -> ODS
                "application/vnd.oasis.opendocument.text" in raw -> ODT
                else -> ZIP
            }
            raw.startsWith("%PDF-") -> PDF
            raw.startsWith("GIF8") -> GIF
            bytes.size >= 26 && raw.startsWith("BM") &&
                (bytes[14].toInt() and 255) in setOf(12, 16, 40, 52, 56, 64, 108, 124) &&
                bytes[15] == 0.toByte() && bytes[16] == 0.toByte() && bytes[17] == 0.toByte() -> BMP
            raw.startsWith("II*\u0000") || raw.startsWith("MM\u0000*") -> TIFF
            header(0, 0, 1, 0) -> ICO
            raw.startsWith("RIFF") -> when (raw.drop(8).take(4)) {
                "WEBP" -> WEBP; "WAVE" -> WAV; "AVI " -> AVI; else -> null
            }
            raw.drop(4).startsWith("ftyp") -> when (val brand = isoBrand(bytes,raw)) {
                "avif", "avis" -> AVIF
                "heic", "heix", "hevc", "hevx", "mif1", "msf1" -> HEIC
                "M4A ", "M4B " -> M4A
                "M4V " -> M4V
                "qt  " -> MOV
                else -> if (brand.startsWith("3gp")) GPP else MP4
            }
            header(0x1a, 0x45, 0xdf, 0xa3) -> if ("\u0042\u0082\u0084webm" in raw.take(4096)) WEBM else MKV
            header(0x30, 0x26, 0xb2, 0x75, 0x8e, 0x66) -> if ("\u00c0\u00ef\u0019\u00bc" in raw) WMV else WMA
            raw.startsWith("FLV") -> FLV
            raw.startsWith("fLaC") -> FLAC
            raw.startsWith("OggS") -> if ("OpusHead" in raw.take(128)) OPUS else OGG
            bytes.size > 376 && bytes[0] == 0x47.toByte() && bytes[188] == bytes[0] && bytes[376] == bytes[0] -> TS
            bytes.size > 2 && bytes[0] == (-1).toByte() && (bytes[1].toInt() and 0xf6) == 0xf0 -> AAC
            bytes.size > 2 && !header(0xff,0xfe) && bytes[0] == (-1).toByte() && (bytes[1].toInt() and 0xe0) == 0xe0 && (bytes[1].toInt() and 6) != 0 -> MP3
            raw.startsWith("ID3") -> MP3
            raw.startsWith("#!AMR") -> AMR
            raw.startsWith("FORM") && raw.drop(8).take(4) in setOf("AIFF", "AIFC") -> AIFF
            header(0x0b, 0x77) -> AC3
            header(0x1f, 0x8b) -> GZ
            raw.startsWith("BZh") -> BZ
            header(0xfd, 0x37, 0x7a, 0x58, 0x5a, 0) -> XZ
            raw.drop(257).startsWith("ustar") -> TAR
            text.startsWith("{\\rtf") -> RTF
            text.startsWith("<") && text.contains("urn:schemas-microsoft-com:office:spreadsheet",true) && !text.contains("<html",true) -> SPREADSHEETML
            text.contains("MIME-Version:",true) && text.contains("multipart/related",true) -> MHTML
            text.startsWith("<svg", true) || text.startsWith("<?xml") && "<svg" in text -> SVG
            text.startsWith("<!doctype html", true) || Regex("""^<(html|head|body|table|style|meta)(?:\s|>)""",RegexOption.IGNORE_CASE).containsMatchIn(text) -> HTML
            text.startsWith("<?xml") || Regex("""^<[A-Za-z_][\w:.-]*(?:\s|/?>)""").containsMatchIn(text) -> XML
            text.startsWith("{") && text.drop(1).trimStart().let { it.startsWith("\"") || it.startsWith("}") } -> JSON
            text.startsWith("[") && text.drop(1).trimStart().let {
                it.firstOrNull() in "{[\"]-0123456789".toSet() ||
                    listOf("true", "false", "null").any { token -> it.startsWith(token) }
            } -> JSON
            else -> ContentText.classify(text)
        }
        return Detection(actual ?: RAW, ext, actual != null, claimedExtension=name.substringAfterLast('.',""))
    }
}
