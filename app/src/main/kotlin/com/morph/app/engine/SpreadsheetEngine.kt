package com.morph.app.engine

import com.morph.app.domain.model.*
import com.morph.app.domain.model.Format.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.csv.*
import com.morph.app.domain.graph.ContentText
import java.io.File
import java.util.zip.ZipFile
import org.apache.poi.ss.usermodel.*
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

object SpreadsheetEngine {
    data class Table(val name: String,val rows: List<List<String>>)
    val mapper = ObjectMapper()
    fun escape(text: String) = org.jsoup.nodes.Entities.escape(text)
    fun tables(src: File, fmt: Format, opt: ProOptions): List<Table> {
        require(src.length() <= 32L shl 20) { "Workbook exceeds the in-memory editing limit" }
        if (fmt == HTML || fmt == SPREADSHEETML) return PortalTables.read(src, fmt).filter { opt.sheet.isBlank() || it.name==opt.sheet }
        if (fmt == CSV || fmt == TSV) {
            val csv = CsvMapper().enable(CsvParser.Feature.WRAP_AS_ARRAY)
            val schema = CsvSchema.emptySchema().withColumnSeparator(if(fmt==TSV) '\t' else ContentText.reader(src).use { reader ->
                val sample = CharArray(65536)
                val n = reader.read(sample)
                ContentText.delimiter(String(sample, 0, n.coerceAtLeast(0))) ?: opt.delimiter
            })
            val rows = ContentText.reader(src).use { reader -> csv.readerFor(Array<String>::class.java).with(schema).readValues<Array<String>>(reader).use { it.readAll().map(Array<String>::toList) } }
            return listOf(Table("Sheet1",rows))
        }
        if (fmt == ODS) return ZipFile(src).use { zip ->
            val entry = zip.getEntry("content.xml") ?: error("ODS workbook has no content.xml")
            require(entry.size in 0..(12L shl 20)) { "ODS worksheet XML exceeds the editing memory limit" }
            val xml = zip.getInputStream(entry).use { Jsoup.parse(it,"UTF-8","",Parser.xmlParser()) }
            var cellsLeft = 1_000_000L
            xml.getElementsByTag("table:table")
                .filter { opt.sheet.isBlank() || it.attr("table:name") == opt.sheet }
                .map { sheet ->
                    fun repeated(node: org.jsoup.nodes.Element, attr: String): Int {
                        if (!node.hasAttr(attr)) return 1
                        return node.attr(attr).toIntOrNull()?.takeIf { it > 0 } ?: error("Invalid ODS repeat count")
                    }
                    val rows = buildList {
                        val meaningful = sheet.getElementsByTag("table:table-row").dropLastWhile { it.text().isBlank() }
                        for (row in meaningful) {
                            val values = buildList {
                                val children = row.children().filter { it.tagName() in setOf("table:table-cell", "table:covered-table-cell") }
                                for (cell in children.dropLastWhile { it.text().isBlank() }) {
                                    val columns = repeated(cell, "table:number-columns-repeated")
                                    require(columns <= 16_384 - size) { "ODS worksheet exceeds the column limit" }
                                    repeat(columns) { add(cell.text()) }
                                }
                            }
                            val copies = repeated(row, "table:number-rows-repeated")
                            val expanded = copies.toLong() * values.size.coerceAtLeast(1)
                            require(copies <= 100_000 - size && expanded <= cellsLeft) { "ODS worksheet exceeds the editing cell limit" }
                            cellsLeft -= expanded
                            repeat(copies) { add(values) }
                        }
                    }
                    Table(sheet.attr("table:name"), rows)
                }
        }
        guardWorkbook(src)
        return WorkbookFactory.create(src,null,true).use { book ->
            val formatter = DataFormatter().apply { setUseCachedValuesForFormulaCells(true) }
            val evaluator = book.creationHelper.createFormulaEvaluator()
            book.filter { opt.sheet.isBlank() || it.sheetName == opt.sheet }.map { sheet ->
                Table(sheet.sheetName,sheet.map { row -> (0 until row.lastCellNum.toInt()).map { val cell = row.getCell(it); runCatching { formatter.formatCellValue(cell,evaluator) }.getOrElse { formatter.formatCellValue(cell) } } })
            }
        }
    }
    internal fun guardWorkbook(src: File) {
        require(src.length() <= 32L shl 20) { "Workbook exceeds the editing memory limit." }
        if (src.inputStream().use { it.read() } == 0x50) ZipFile(src).use { zip ->
            var bytes = 0L
            for (entry in zip.entries()) {
                require(entry.size >= 0 && entry.size <= (32L shl 20) - bytes) { "Expanded workbook exceeds the editing memory limit." }
                bytes += entry.size
            }
        }
    }
    fun html(src: File, dst: File, opt: ProOptions) {
        guardWorkbook(src)
        WorkbookFactory.create(src,null,true).use { book ->
            val formatter=DataFormatter().apply { setUseCachedValuesForFormulaCells(true) }
            val evaluator=book.creationHelper.createFormulaEvaluator()
            val sheets=book.filter { opt.sheet.isBlank() || it.sheetName == opt.sheet }
            require(sheets.isNotEmpty()) { "No matching worksheet." }
            fun cssColor(color: org.apache.poi.ss.usermodel.Color?): String? {
                val rgb=when(color) {
                    is org.apache.poi.xssf.usermodel.XSSFColor -> color.rgb
                    is org.apache.poi.hssf.util.HSSFColor -> color.triplet.map { it.toByte() }.toByteArray()
                    else -> null
                }
                return rgb?.joinToString("",prefix="#") { "%02x".format(it) }
            }
            dst.bufferedWriter().use { w ->
                w.append("<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><style>section{overflow:auto}table{border-collapse:collapse}td{white-space:pre-wrap;padding:.25em}</style></head><body>")
                for (sheet in sheets) {
                    w.append("<section><h2>${escape(sheet.sheetName)}</h2><table><colgroup>")
                    val merges=sheet.mergedRegions
                    val columns=maxOf(sheet.maxOfOrNull { it.lastCellNum.toInt() } ?: 0,(merges.maxOfOrNull { it.lastColumn } ?: -1)+1)
                    val end=maxOf(sheet.lastRowNum,merges.maxOfOrNull { it.lastRow } ?: 0)
                    require((end+1L)*columns<=500000 && merges.size<=4096) { "The worksheet is too large for HTML export." }
                    repeat(columns) { w.append("<col style='width:${sheet.getColumnWidth(it)/256.0}ch'>") }
                    w.append("</colgroup>")
                    for (r in 0..end) {
                        val spans=merges.filter { r in it.firstRow..it.lastRow }.sortedBy { it.firstColumn }
                        val row=sheet.getRow(r)
                        var spanIndex=0
                        w.append("<tr>")
                        for(c in 0 until columns) {
                            while(spanIndex<spans.size && spans[spanIndex].lastColumn<c) spanIndex++
                            val span=spans.getOrNull(spanIndex)?.takeIf { c>=it.firstColumn }
                            if(span!=null && (r!=span.firstRow || c!=span.firstColumn)) continue
                            val cell=row?.getCell(c)
                            w.append("<td")
                            if(span!=null) w.append(" rowspan='${span.lastRow-r+1}' colspan='${span.lastColumn-c+1}'")
                            if(cell!=null) {
                                val s=cell.cellStyle
                                val font=book.getFontAt(s.fontIndex)
                                w.append(" style='")
                                if(font.bold) w.append("font-weight:bold;")
                                if(font.italic) w.append("font-style:italic;")
                                if(s.alignment in setOf(HorizontalAlignment.LEFT,HorizontalAlignment.CENTER,HorizontalAlignment.RIGHT)) w.append("text-align:${s.alignment.name.lowercase()};")
                                if(s.fillPattern!=FillPatternType.NO_FILL) cssColor(s.fillForegroundColorColor)?.let { w.append("background-color:$it;") }
                                val color=when(font) {
                                    is org.apache.poi.xssf.usermodel.XSSFFont -> font.xssfColor
                                    is org.apache.poi.hssf.usermodel.HSSFFont -> (book as HSSFWorkbook).customPalette.getColor(font.color)
                                    else -> null
                                }
                                cssColor(color)?.let { w.append("color:$it;") }
                                listOf("top" to s.borderTop,"bottom" to s.borderBottom,"left" to s.borderLeft,"right" to s.borderRight).forEach { (side,border) ->
                                    if (border!=BorderStyle.NONE) w.append("border-$side:1px solid;")
                                }
                                w.append("'")
                            }
                            val value=runCatching { formatter.formatCellValue(cell,evaluator) }.getOrElse { formatter.formatCellValue(cell) }
                            w.append(">${escape(value)}</td>")
                        }
                        w.append("</tr>")
                    }
                    w.append("</table></section>")
                }
                w.append("</body></html>")
            }
        }
    }

    fun migrate(src: File,dst: File,to: Format,opt: ProOptions) {
        guardWorkbook(src)
        WorkbookFactory.create(src,null,true).use { old -> (if(to == XLS) HSSFWorkbook() else XSSFWorkbook() as Workbook).use { new ->
            val styles = mutableMapOf<Short,CellStyle>()
            val selected=old.filter { opt.sheet.isBlank() || it.sheetName == opt.sheet }
            selected.forEach {new.createSheet(it.sheetName)}
            fun colorIndex(color:org.apache.poi.ss.usermodel.Color?,fallback:Short):Short {
                val rgb=when(color) {
                    is org.apache.poi.xssf.usermodel.XSSFColor -> color.rgb
                    is org.apache.poi.hssf.util.HSSFColor -> color.triplet.map {it.toByte()}.toByteArray()
                    else -> null
                }
                if(new is HSSFWorkbook && rgb!=null && rgb.size==3) {
                    val palette=new.customPalette
                    return (palette.findColor(rgb[0],rgb[1],rgb[2]) ?: runCatching {palette.addColor(rgb[0],rgb[1],rgb[2])}.getOrNull()
                        ?: palette.findSimilarColor(rgb[0].toInt() and 255,rgb[1].toInt() and 255,rgb[2].toInt() and 255)).index
                }
                return fallback
            }
            selected.forEach { sheet ->
                val copy = new.getSheet(sheet.sheetName)
                var columns=0
                for (row in sheet) {
                    val next = copy.createRow(row.rowNum); next.height=row.height
                    columns=maxOf(columns,row.lastCellNum.toInt())
                    for (cell in row) {
                        val dest = next.createCell(cell.columnIndex)
                        when(cell.cellType) {
                            CellType.NUMERIC -> dest.setCellValue(cell.numericCellValue)
                            CellType.STRING -> dest.setCellValue(cell.stringCellValue)
                            CellType.BOOLEAN -> dest.setCellValue(cell.booleanCellValue)
                            CellType.FORMULA -> dest.cellFormula=cell.cellFormula
                            CellType.ERROR -> dest.setCellErrorValue(cell.errorCellValue)
                            else -> dest.setBlank()
                        }
                        val st = cell.cellStyle
                        dest.cellStyle = styles.getOrPut(st.index) { new.createCellStyle().apply {
                            dataFormat=new.createDataFormat().getFormat(st.dataFormatString ?: "General")
                            alignment=st.alignment; verticalAlignment=st.verticalAlignment; wrapText=st.wrapText
                            fillForegroundColor=colorIndex(st.fillForegroundColorColor,st.fillForegroundColor); fillPattern=st.fillPattern
                            if(this is org.apache.poi.xssf.usermodel.XSSFCellStyle && st.fillForegroundColorColor is org.apache.poi.hssf.util.HSSFColor) {
                                val rgb=(st.fillForegroundColorColor as org.apache.poi.hssf.util.HSSFColor).triplet.map {it.toByte()}.toByteArray()
                                setFillForegroundColor(org.apache.poi.xssf.usermodel.XSSFColor(rgb,org.apache.poi.xssf.usermodel.DefaultIndexedColorMap()))
                            }
                            val font=old.getFontAt(st.fontIndex)
                            setFont(new.createFont().apply { bold=font.bold; italic=font.italic; fontHeight=font.fontHeight; fontName=font.fontName; color=font.color })
                        } }
                    }
                }
                repeat(columns) { copy.setColumnWidth(it,sheet.getColumnWidth(it)); copy.setColumnHidden(it,sheet.isColumnHidden(it)) }
                sheet.mergedRegions.forEach(copy::addMergedRegion)
            }
            require(new.numberOfSheets>0) { "No matching sheet" }
            dst.outputStream().use(new::write)
        } }
    }
    private fun setTableCell(cell: Cell, value: String) {
        when {
            value == "true" || value == "false" -> cell.setCellValue(value.toBoolean())
            Regex("""-?(?:0|[1-9]\d*)(?:\.\d+)?""").matches(value) && value.count(Char::isDigit) <= 15 &&
                value.toDoubleOrNull()?.let { it.isFinite() && java.math.BigDecimal.valueOf(it).compareTo(value.toBigDecimal()) == 0 } == true -> cell.setCellValue(value.toDouble())
            else -> cell.setCellValue(value)
        }
    }
    fun export(tables: List<Table>,dst: File,to: Format,opt: ProOptions) {
        val table=tables.firstOrNull() ?: error("No matching sheets")
        val rows=table.rows
        if (tables.size > 1 && to in setOf(CSV,TSV,TXT,SQL,JSON,XML)) {
            require(opt.sheet.isNotBlank()) { "This output holds one table. Select a worksheet in Pro Lab or export all sheets to XLSX or HTML." }
        }
        when(to) {
            XLS,XLSX -> (if(to==XLS) HSSFWorkbook() else XSSFWorkbook() as Workbook).use { book ->
                tables.forEach { tab -> val sheet=book.createSheet(org.apache.poi.ss.util.WorkbookUtil.createSafeSheetName(tab.name))
                    tab.rows.forEachIndexed { i,values -> val row=sheet.createRow(i); values.forEachIndexed { j,v -> setTableCell(row.createCell(j),v) } }
                }; dst.outputStream().use(book::write)
            }
            CSV,TSV,TXT -> dst.bufferedWriter().use { writer ->
                val sep=if(to==CSV) opt.delimiter else '\t'
                rows.forEach { row -> writer.appendLine(row.joinToString(sep.toString()) { v ->
                    if (v.any { it in charArrayOf(sep,'\n','\r','"') }) "\"" + v.replace("\"","\"\"") + "\"" else v
                }) }
            }
            HTML -> dst.bufferedWriter().use { w ->
                w.append("<!doctype html><html><meta charset=utf-8><body>")
                tables.forEach { tab -> w.append("<h2>${escape(tab.name)}</h2><table border=1>")
                    tab.rows.forEach { row -> w.append("<tr>"); row.forEach { w.append("<td>${escape(it)}</td>") }; w.append("</tr>") }; w.append("</table>")
                }; w.append("</body></html>")
            }
            JSON,XML,SQL -> {
                val header = rows.firstOrNull().orEmpty()
                val used = mutableSetOf<String>()
                val keys = List(rows.maxOfOrNull { it.size } ?: 0) { index ->
                    val base = header.getOrNull(index)?.takeUnless(String::isBlank) ?: "Column${index + 1}"
                    var key = base
                    var suffix = 2
                    while (!used.add(key)) key = "${base}_${suffix++}"
                    key
                }
                val records=rows.drop(1).map { row -> row.indices.associate { keys[it] to row[it] } }
                DataEngine.write(mapper.valueToTree(records),dst,to,opt)
            }
            ODS -> DocumentEngine.odf(dst,true,tables.joinToString("") { tab ->
                "<table:table table:name=\"${escape(tab.name)}\">"+tab.rows.joinToString("") { row -> "<table:table-row>"+row.joinToString("") { "<table:table-cell office:value-type=\"string\"><text:p>${escape(it)}</text:p></table:table-cell>" }+"</table:table-row>" }+"</table:table>"
            })
            else -> error("Unknown table export")
        }
    }
}
