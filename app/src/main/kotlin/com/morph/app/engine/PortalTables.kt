package com.morph.app.engine

import com.morph.app.domain.graph.ContentText
import com.morph.app.domain.model.Format
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File

internal object PortalTables {
    fun read(file: File, format: Format): List<SpreadsheetEngine.Table> {
        require(file.length() <= 8L shl 20) { "This report exceeds the HTML editing limit." }
        val source = ContentText.reader(file).use { it.readText() }
        if (format == Format.SPREADSHEETML) {
            val doc = Jsoup.parse(source, "", Parser.xmlParser())
            return doc.getElementsByTag("Worksheet").mapIndexed { i, sheet ->
                val rows = sheet.getElementsByTag("Row").map { row ->
                    buildList {
                        row.getElementsByTag("Cell").forEach { cell ->
                            val index = cell.attr("ss:Index").toIntOrNull() ?: size + 1
                            require(index in size + 1..16384) { "Unsupported spreadsheet column index." }
                            repeat(index - size - 1) { add("") }
                            add(cell.getElementsByTag("Data").text())
                        }
                    }
                }
                SpreadsheetEngine.Table(sheet.attr("ss:Name").ifBlank { "Sheet${i+1}" }, rows)
            }
        }
        var remaining = 250_000
        return Jsoup.parse(source).select("table").mapIndexed { i, table ->
            val occupied = mutableMapOf<Int,Int>()
            val rows = table.select("tr").filter { it.closest("table") == table }.mapIndexed { r,row ->
                buildList {
                    fun appendCell(value: String) {
                        require(--remaining >= 0 && size < 16384) { "The expanded table exceeds the cell limit." }
                        add(value)
                    }
                    for (cell in row.children().filter { it.tagName() in setOf("td","th") }) {
                        while ((occupied[size] ?: 0) > r) appendCell("")
                        val width = cell.attr("colspan").toIntOrNull() ?: 1
                        val height = cell.attr("rowspan").toIntOrNull() ?: 1
                        require(width in 1..16384 && height in 1..65536) { "Unsupported merged cell span." }
                        repeat(width) { n ->
                            occupied[size] = r + height
                            appendCell(if (n == 0) cell.text() else "")
                        }
                    }
                }
            }
            SpreadsheetEngine.Table("Table ${i+1}", rows)
        }.also { require(it.any { table -> table.rows.any { row -> row.isNotEmpty() } }) { "This HTML file contains no data table." } }
    }
}
