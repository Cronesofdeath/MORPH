package com.morph.app.domain.graph

import com.morph.app.domain.model.Category
import com.morph.app.domain.model.Format
import com.morph.app.domain.model.Format.*

object FormatRegistry {
    val videos = Format.entries.filter { it.category == Category.VIDEO && it != FRAMES }
    val audio = Format.entries.filter { it.category == Category.AUDIO }
    val archives = Format.entries.filter { it.category == Category.ARCHIVE }
    val singleStreams = setOf(GZ, BZ, XZ)
    val images = Format.entries.filter { it.category == Category.IMAGE }
    val bitmapOutputs = listOf(JPG, PNG, WEBP)
    val stillOutputs = bitmapOutputs + listOf(HEIC, AVIF, BMP, TIFF, ICO, GIF, TGA)
    val tables = listOf(XLS, XLSX, ODS, CSV, TSV)
    val structured = listOf(JSON, YAML, XML, TOML, INI, PROPERTIES)
    val documents = listOf(DOCX, DOC, RTF, TXT, MD, HTML, EPUB, ODT)
    val richCategories = setOf(Category.SLIDES)
    val documentDestinations = setOf(Category.DOCUMENT, Category.IMAGE, Category.ARCHIVE)
    val exact = setOf(JPG, PNG, PDF, MP4, M4V, MOV, ZIP, JSON)

    val edges: List<ConversionEdge> = buildList {
        fun edge(from: Format, to: Format, engine: Engine, loss: Int = 1, speed: Int = 3) {
            add(ConversionEdge(from, to, engine, loss, speed))
        }
        for (source in images) {
            for (target in stillOutputs) {
                if (source == GIF && target == GIF) continue
                val engine = if (target in bitmapOutputs || target in listOf(PDF, HEIC)) Engine.IMAGE else Engine.MEDIA
                if (source != SVG || engine == Engine.IMAGE) edge(source, target, engine, if (target in listOf(JPG, AVIF, HEIC, GIF)) 2 else 1)
            }
            edge(source, PDF, Engine.IMAGE)
        }
        for (source in videos + GIF) {
            edge(source, FRAMES, Engine.MEDIA, 1, 8)
            for (target in videos + GIF) edge(source, target, Engine.MEDIA, 2, 8)
            if (source != GIF) for (target in audio + bitmapOutputs) edge(source, target, Engine.MEDIA, 3, 5)
        }
        for (source in audio) for (target in audio) edge(source, target, Engine.MEDIA, 1, 6)
        for (target in listOf(XLSX, CSV, TSV, JSON)) edge(HTML, target, Engine.SHEET)
        for (source in tables + SPREADSHEETML) {
            for (target in tables + listOf(HTML, JSON, XML, TXT, SQL)) {
                val richMigration = source in listOf(XLS, XLSX) && target in listOf(XLS, XLSX)
                edge(source, target, Engine.SHEET, if (richMigration) 0 else if (target == TXT) 3 else 1)
            }
        }
        for (source in structured) {
            for (target in structured + listOf(CSV, TSV, XLSX, SQL)) edge(source, target, Engine.DATA, 1)
        }
        for (source in documents) {
            for (target in documents - DOC + listOf(PDF)) {
                if (source == target && source in listOf(DOCX, EPUB, ODT)) continue
                edge(source, target, Engine.DOCUMENT, if (source == target) 0 else if (target in listOf(HTML, PDF)) 1 else 2)
            }
        }
        for (source in listOf(PPT, PPTX)) {
            for (target in listOf(PDF, TXT, HTML, MD)) edge(source, target, Engine.SLIDES, if (target == PDF) 1 else 2)
            if (source == PPTX) edge(source, PPTX, Engine.ARCHIVE, 0)
        }
        for (target in bitmapOutputs) edge(PDF, target, Engine.PDF, 3)
        for (target in listOf(TXT, PDF)) edge(PDF, target, Engine.PDF, 1)
        for (source in listOf(DOCX, EPUB, ODT, XLSX)) edge(source, source, Engine.ARCHIVE, 0, 1)
        for (source in Format.entries) {
            for (target in archives) edge(source, target, Engine.ARCHIVE, 0, 6)
            if (source != B64) edge(source, B64, Engine.DATA, 0, 10)
        }
        edge(B64, RAW, Engine.DATA, 0)
        for (source in archives) edge(source, RAW, Engine.ARCHIVE, 0)
    }

    fun meaningful(source: Format, target: Format, edge: ConversionEdge): Boolean {
        if (edge.from != source && edge.to != target) return false
        if (edge.to != target && edge.to !in setOf(HTML, TXT, PDF)) return false
        if (edge.to != target && edge.to == HTML && target !in documents + PDF) return false
        if (edge.to != target && edge.to == TXT && target !in documents) return false
        if (edge.to != target && edge.to == PDF && target !in bitmapOutputs) return false
        if (edge.to in archives || edge.to in setOf(B64, RAW)) return edge.to == target
        if (source in archives || source == B64 || source == RAW) return false
        if (target in archives || target == B64) return false
        if (source.category == Category.IMAGE && target.category in setOf(Category.VIDEO,Category.AUDIO) && source != GIF) return false
        if (source.category == Category.IMAGE && target.category !in setOf(Category.IMAGE,Category.VIDEO,Category.AUDIO) && target != PDF) return false
        if (edge.to == target) return true
        if (source.category == Category.VIDEO) return false
        if (source.category == Category.AUDIO) return false
        if (source.category == Category.IMAGE) return edge.to in images || edge.to == PDF
        if (source.category == Category.SLIDES) return edge.to == PDF || edge.to == HTML
        if (edge.to in images) return false
        return true
    }
}
