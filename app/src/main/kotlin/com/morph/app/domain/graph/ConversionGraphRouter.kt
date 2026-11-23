package com.morph.app.domain.graph

import com.morph.app.domain.model.Format
import java.util.PriorityQueue

enum class Engine { IMAGE, MEDIA, SHEET, DOCUMENT, PDF, SLIDES, ARCHIVE, DATA }

data class ConversionEdge(val from: Format, val to: Format, val engine: Engine, val loss: Int, val speed: Int) {
    init { require(loss >= 0 && speed > 0) }
    val cost: Long get() = loss * 100L + speed
}

class ConversionGraphRouter(val edges: List<ConversionEdge> = FormatRegistry.edges) {
    private val outgoing = edges.groupBy { it.from }

    fun route(source: Format, target: Format): List<ConversionEdge>? {
        if (source == target) return outgoing[source]?.filter { it.to == target }?.minByOrNull { it.cost }?.let(::listOf)
        // A plain-text extraction is a terminal operation unless the goal is a document.
        if (source.category in FormatRegistry.richCategories && target.category !in FormatRegistry.documentDestinations &&
            (source.category != target.category || source in FormatRegistry.archives)) return null
        data class Pending(val format: Format, val cost: Long)
        val queue = PriorityQueue(compareBy<Pending> { it.cost }.thenBy { it.format.ordinal })
        val distance = mutableMapOf(source to 0L)
        val previous = mutableMapOf<Format, ConversionEdge>()
        queue += Pending(source, 0)
        while (queue.isNotEmpty()) {
            val next = queue.remove()
            if (next.cost != distance[next.format]) continue
            if (next.format == target) {
                val path = mutableListOf<ConversionEdge>()
                var cursor = target
                while (cursor != source) {
                    val edge = previous.getValue(cursor)
                    path += edge
                    cursor = edge.from
                }
                return path.asReversed()
            }
            for (edge in outgoing[next.format].orEmpty()) {
                if (edge.to == edge.from) continue
                if (!FormatRegistry.meaningful(source, target, edge)) continue
                val cost = next.cost + edge.cost
                if (cost >= (distance[edge.to] ?: Long.MAX_VALUE)) continue
                distance[edge.to] = cost
                previous[edge.to] = edge
                queue += Pending(edge.to, cost)
            }
        }
        return null
    }

    private fun recommendations(source:Format):List<Format> = when(source) {
        Format.XLS -> listOf(Format.XLSX,Format.PDF,Format.CSV,Format.JSON)
        Format.JSON -> listOf(Format.CSV,Format.XLSX,Format.YAML)
        Format.PDF -> listOf(Format.JPG,Format.PNG,Format.TXT)
        Format.B64 -> listOf(Format.RAW)
        else -> when(source.category) {
            com.morph.app.domain.model.Category.IMAGE -> listOf(Format.JPG,Format.PNG,Format.WEBP,Format.PDF)
            com.morph.app.domain.model.Category.VIDEO -> listOf(Format.MP4,Format.MP3,Format.WEBM,Format.GIF,Format.FRAMES)
            com.morph.app.domain.model.Category.AUDIO -> listOf(Format.MP3,Format.WAV,Format.FLAC)
            com.morph.app.domain.model.Category.SHEET -> listOf(Format.XLSX,Format.PDF,Format.CSV,Format.JSON)
            com.morph.app.domain.model.Category.DOCUMENT,com.morph.app.domain.model.Category.SLIDES -> listOf(Format.PDF,Format.DOCX,Format.PNG,Format.TXT)
            com.morph.app.domain.model.Category.ARCHIVE -> listOf(Format.RAW,Format.ZIP,Format.SEVEN)
            else -> listOf(Format.JSON,Format.YAML,Format.ZIP)
        }
    }

    fun targets(source: Format): List<Format> =
        Format.entries.mapNotNull { goal -> route(source, goal)?.let { goal to it.sumOf(ConversionEdge::cost) } }
            .sortedWith(compareBy<Pair<Format, Long>> { recommendations(source).indexOf(it.first).let { i -> if(i==-1) Int.MAX_VALUE else i } }.thenBy { it.second })
            .map { it.first }
}
