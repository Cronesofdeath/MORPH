package com.morph.app.engine

import android.graphics.*
import android.graphics.pdf.PdfDocument
import com.morph.app.domain.model.*
import com.morph.app.domain.model.Format.*
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hslf.usermodel.HSLFSlideShow
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.commonmark.parser.Parser as MarkdownParser
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.ext.gfm.tables.TablesExtension
import java.io.File
import java.util.zip.*

object DocumentEngine {
    private fun esc(s: String)=SpreadsheetEngine.escape(s)
    private fun packageFile(file: File, mime: String?, entries: Map<String,String>) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            if (mime!=null) {
                val bytes=mime.toByteArray()
                zip.putNextEntry(ZipEntry("mimetype").apply { method=ZipEntry.STORED; size=bytes.size.toLong(); crc=CRC32().apply { update(bytes) }.value })
                zip.write(bytes); zip.closeEntry()
            }
            entries.forEach { (path,s) -> zip.putNextEntry(ZipEntry(path)); zip.write(s.toByteArray()); zip.closeEntry() }
        }
    }
    fun odf(dst: File,sheet: Boolean,body: String) {
        val tag=if(sheet) "spreadsheet" else "text"; val mime=if(sheet) ODS.mime else ODT.mime
        packageFile(dst,mime,mapOf("content.xml" to """<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" office:version="1.2"><office:body><office:$tag>$body</office:$tag></office:body></office:document-content>""",
            "META-INF/manifest.xml" to """<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0"><manifest:file-entry manifest:full-path="/" manifest:media-type="$mime"/><manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/></manifest:manifest>"""))
    }
    private fun ZipFile.xml(path: String): org.jsoup.nodes.Document {
        val entry=getEntry(path) ?: error("Missing package part $path")
        require(entry.size in 0..(10L shl 20)) { "Document part exceeds memory budget" }
        return getInputStream(entry).use { Jsoup.parse(it,"UTF-8","",Parser.xmlParser()) }
    }
    private fun paragraphs(src: File,fmt: Format): List<String> {
        require(src.length() <= 64L shl 20) { "Document exceeds the editing memory budget" }
        return when(fmt) {
            DOCX,ODT,PPTX,EPUB -> ZipFile(src).use { zip ->
                when(fmt) {
                    DOCX -> zip.xml("word/document.xml").getElementsByTag("w:p").map { p -> p.getElementsByTag("w:t").joinToString("") { it.wholeText() } }
                    ODT -> zip.xml("content.xml").getElementsByTag("text:p").map { it.text() }
                    PPTX -> zip.entries().asSequence().map { it.name }.filter { it.matches(Regex("ppt/slides/slide[0-9]+\\.xml")) }.sortedBy { it.filter(Char::isDigit).toInt() }.map { name ->
                        val i=name.filter(Char::isDigit); val notes="ppt/notesSlides/notesSlide$i.xml"
                        "Slide $i\n"+(zip.xml(name).getElementsByTag("a:t") + if(zip.getEntry(notes)!=null) zip.xml(notes).getElementsByTag("a:t") else emptyList()).joinToString("\n") { it.text() }
                    }.toList()
                    else -> {
                        val path=zip.xml("META-INF/container.xml").getElementsByTag("rootfile").first()!!.attr("full-path")
                        val opf=zip.xml(path)
                        val items=opf.getElementsByTag("item").associate { it.id() to it.attr("href") }
                        opf.getElementsByTag("itemref").map { item ->
                            val chapter=java.net.URI(path).resolve(items.getValue(item.attr("idref"))).path
                            zip.xml(chapter).text()
                        }
                    }
                }
            }
            DOC -> src.inputStream().use { HWPFDocument(it).use { doc -> doc.documentText.split('\r') } }
            PPT -> src.inputStream().use { HSLFSlideShow(it).use { deck -> deck.slides.mapIndexed { i,s ->
                "Slide ${i+1}\n"+s.shapes.filterIsInstance<org.apache.poi.hslf.usermodel.HSLFTextShape>().joinToString("\n") { it.text.orEmpty() }
            } } }
            HTML -> Jsoup.parse(src,"UTF-8").body().wholeText().lines()
            MD -> {
                val extensions=listOf(TablesExtension.create())
                val html=HtmlRenderer.builder().extensions(extensions).build().render(MarkdownParser.builder().extensions(extensions).build().parse(src.readText()))
                Jsoup.parse(html).body().wholeText().lines()
            }
            RTF -> RtfReader.text(src.readText(Charsets.ISO_8859_1)).lines()
            else -> src.readLines()
        }
    }
    fun convert(src: File,dst: File,from: Format,to: Format,opt: ProOptions) {
        if(from==PPTX && to==PDF) {LayoutEngine.slides(src,dst,opt);return}
        if(from==HTML && to==PDF && LayoutEngine.tablePdf(src,dst,opt)) return
        if(from==MD && to==HTML) {
            val extensions=listOf(TablesExtension.create())
            val tree=src.reader().use {MarkdownParser.builder().extensions(extensions).build().parseReader(it)}
            dst.bufferedWriter().use { html->html.append("<!doctype html><html><meta charset=utf-8><body>");HtmlRenderer.builder().escapeHtml(true).extensions(extensions).build().render(tree,html);html.append("</body></html>") };return
        }
        val lines=paragraphs(src,from)
        val body=lines.joinToString("") { "<p>${esc(it)}</p>" }
        when(to) {
            PDF -> textPdf(lines,dst,opt,from in setOf(PPT,PPTX))
            HTML -> dst.writeText("<!doctype html><html><meta charset=utf-8><body>$body</body></html>")
            TXT,MD -> dst.bufferedWriter().use { w -> lines.forEach(w::appendLine) }
            RTF -> dst.writeText("{\\rtf1\\ansi "+lines.joinToString("\\par\n") { line -> line.map { c -> when(c) { '\\','{','}' -> "\\$c"; else -> if(c.code<128) c.toString() else "\\u${c.code.toShort()}?" } }.joinToString("") }+"}")
            ODT -> odf(dst,false,lines.joinToString("") { "<text:p>${esc(it)}</text:p>" })
            DOCX -> packageFile(dst,null,mapOf("[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
                "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
                "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>"""+lines.joinToString("") { "<w:p><w:r><w:t xml:space=\"preserve\">${esc(it)}</w:t></w:r></w:p>" }+"</w:body></w:document>"))
            EPUB -> packageFile(dst,EPUB.mime,mapOf("META-INF/container.xml" to """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                "content.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:${java.util.UUID.randomUUID()}</dc:identifier><dc:title>${esc(src.nameWithoutExtension)}</dc:title><dc:language>und</dc:language><meta property="dcterms:modified">${java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)}</meta></metadata><manifest><item id="c" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="c"/></spine></package>""",
                "chapter.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Document</title></head><body>$body</body></html>""",
                "nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="chapter.xhtml">Document</a></li></ol></nav></body></html>"""))
            else -> error("Unsupported document output")
        }
    }
    private fun textPdf(lines: List<String>,dst: File,opt: ProOptions,slides: Boolean) {
        val portrait=if(opt.pageSize=="Letter") 612 to 792 else 595 to 842
        val w=if(opt.landscape || slides) portrait.second else portrait.first
        val h=if(opt.landscape || slides) portrait.first else portrait.second
        val margin=opt.margin.coerceIn(8,100)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize=12f; color=Color.BLACK }
        PdfDocument().use { pdf ->
            var index=0
            fun page()=pdf.startPage(PdfDocument.PageInfo.Builder(w,h,++index).create())
            var page=page(); var y=margin+14f
            lines.forEachIndexed { i,para ->
                if(slides && i>0) { pdf.finishPage(page); page=page(); y=margin+14f }
                for (line in para.lines()) {
                    var offset=0
                    do {
                        if(y>h-margin) { pdf.finishPage(page); page=page(); y=margin+14f }
                        val count=paint.breakText(line,offset,line.length,true,(w-2*margin).toFloat(),null).coerceAtLeast(1)
                        val end=minOf(line.length,offset+count)
                        page.canvas.drawText(line,offset,end,margin.toFloat(),y,paint); offset=end; y+=16f
                    } while(offset<line.length)
                }
            }
            pdf.finishPage(page); dst.outputStream().use(pdf::writeTo)
        }
    }
}
