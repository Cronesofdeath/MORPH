package com.morph.app.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.morph.app.domain.model.Format
import com.morph.app.domain.model.ProOptions
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.hssf.util.HSSFColor
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFFont

internal object SpreadsheetPdf {
    private const val CELL_PADDING = 3f
    private const val HEADER_HEIGHT = 38f
    private const val PAGE_LIMIT = 2000

    private data class PrintedCell(
        val left: Float,
        val right: Float,
        val firstRow: Int,
        val lastRow: Int,
        val fill: Int?,
        val text: StaticLayout?
    )

    suspend fun write(src: File, dst: File, opt: ProOptions, step: suspend (String) -> Unit) {
        SpreadsheetEngine.guardWorkbook(src)
        val paperWidth = if (opt.pageSize == "Letter") 612 else 595
        val paperHeight = if (opt.pageSize == "Letter") 792 else 842
        val width = if (opt.landscape) paperHeight else paperWidth
        val height = if (opt.landscape) paperWidth else paperHeight
        val margin = opt.margin.coerceIn(12, 100).toFloat()
        val printableWidth = width - 2 * margin
        val printableHeight = height - 2 * margin - HEADER_HEIGHT
        val unfiltered = if (opt.pageRange.isBlank()) dst else File(dst.parentFile, "unfiltered-sheet.pdf")
        var pageNumber = 0
        WorkbookFactory.create(src, null, true).use { book ->
            val sheets = book.filter { opt.sheet.isBlank() || it.sheetName == opt.sheet }
            require(sheets.isNotEmpty()) { "No matching worksheet." }
            val formatter = DataFormatter().apply { setUseCachedValuesForFormulaCells(true) }
            val evaluator = book.creationHelper.createFormulaEvaluator()
            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(23, 37, 64)
                typeface = Typeface.DEFAULT_BOLD
                textSize = 13f
            }
            val detail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 8f }
            val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(158, 169, 185)
                style = Paint.Style.STROKE
                strokeWidth = 0.45f
            }
            val fill = Paint()
            PdfDocument().use { pdf ->
                for (sheet in sheets) {
                    val merges = sheet.mergedRegions
                    val columns = maxOf(1, sheet.maxOfOrNull { it.lastCellNum.toInt() } ?: 0,
                        (merges.maxOfOrNull { it.lastColumn } ?: -1) + 1)
                    val rows = maxOf(1, sheet.lastRowNum + 1, (merges.maxOfOrNull { it.lastRow } ?: -1) + 1)
                    require(rows.toLong() * columns <= 500_000 && merges.size <= 4096) {
                        "This worksheet is too large for PDF export."
                    }
                    val colWidths = FloatArray(columns) {
                        (sheet.getColumnWidthInPixels(it) * .75f).coerceIn(30f, printableWidth * .46f)
                    }
                    val prefixCandidate = minOf(3, columns - 1)
                    val prefixWidth = colWidths.take(prefixCandidate).sum()
                    val repeatColumns = if (prefixWidth < printableWidth / 2f) prefixCandidate else 0
                    var start = 0
                    while (start < columns) {
                        currentCoroutineContext().ensureActive()
                        val prefix = if (start > repeatColumns) repeatColumns else 0
                        var used = colWidths.take(prefix).sum()
                        var end = start
                        while (end < columns && used + colWidths[end] <= printableWidth) {
                            used += colWidths[end++]
                        }
                        check(end > start)
                        val ranges = listOf(0 until prefix, start until end).filter { !it.isEmpty() }
                        val rowHeights = FloatArray(rows) { index ->
                            (sheet.getRow(index)?.heightInPoints ?: sheet.defaultRowHeightInPoints).coerceAtLeast(15f)
                        }
                        val cells = ArrayList<PrintedCell>()
                        val mergeStarts = merges.groupBy { it.firstRow }
                        val activeMerges = mutableListOf<org.apache.poi.ss.util.CellRangeAddress>()
                        for (r in 0 until rows) {
                            currentCoroutineContext().ensureActive()
                            activeMerges.removeAll { it.lastRow < r }
                            activeMerges.addAll(mergeStarts[r].orEmpty())
                            val spans = activeMerges.sortedBy { it.firstColumn }
                            val row = sheet.getRow(r)
                            val textTitle = r == 0 && spans.isEmpty() && row?.getCell(0)?.cellType == CellType.STRING &&
                                row.none { it.columnIndex != 0 && it.cellType != CellType.BLANK }
                            var x = 0f
                            for (range in ranges) {
                                var c = range.first
                                var spanIndex = 0
                                while (c <= range.last) {
                                    while (spanIndex < spans.size && spans[spanIndex].lastColumn < c) spanIndex++
                                    val span = spans.getOrNull(spanIndex)?.takeIf { c >= it.firstColumn }
                                    val last = if (textTitle && c == 0) range.last else minOf(span?.lastColumn ?: c, range.last)
                                    var right = x
                                    for (i in c..last) right += colWidths[i]
                                    if (span == null || span.firstRow == r) {
                                        val cell = row?.getCell(span?.firstColumn ?: c)
                                        val continuedMerge = span != null && range.first == start &&
                                            prefix > 0 && span.firstColumn < prefix
                                        val layout = cell?.takeUnless { continuedMerge }?.let { sourceCell ->
                                            val value = runCatching { formatter.formatCellValue(sourceCell, evaluator) }
                                                .getOrElse { formatter.formatCellValue(sourceCell) }
                                            if (value.isBlank()) null else layout(value, sourceCell, book, right - x)
                                        }
                                        if (layout != null) {
                                            rowHeights[r] = maxOf(rowHeights[r], layout.height + 2 * CELL_PADDING)
                                        }
                                        val background = cell?.cellStyle?.takeIf { it.fillPattern != FillPatternType.NO_FILL }
                                            ?.fillForegroundColorColor?.let(::color)
                                        cells += PrintedCell(x, right, r, span?.lastRow ?: r, background, layout)
                                    }
                                    x = right
                                    c = last + 1
                                }
                            }
                        }
                        val offsets = FloatArray(rows + 1)
                        for (r in 0 until rows) offsets[r + 1] = offsets[r] + rowHeights[r]
                        var firstRow = 0
                        while (firstRow < rows) {
                            currentCoroutineContext().ensureActive()
                            var lastRow = firstRow
                            while (lastRow < rows && offsets[lastRow + 1] - offsets[firstRow] <= printableHeight) lastRow++
                            require(lastRow > firstRow) { "A worksheet row is too tall for this paper size. Try a wider page or smaller margins." }
                            require(pageNumber < PAGE_LIMIT) { "The workbook exceeds the PDF page limit." }
                            step("Laying out spreadsheet page ${++pageNumber}")
                            val page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, pageNumber).create())
                            val canvas = page.canvas
                            canvas.drawColor(Color.WHITE)
                            canvas.drawText(sheet.sheetName, margin, margin + 13f, title)
                            val firstColumn = CellReference.convertNumToColString(start)
                            val lastColumn = CellReference.convertNumToColString(end - 1)
                            canvas.drawText("Columns $firstColumn–$lastColumn   |   Rows ${firstRow + 1}–$lastRow",
                                margin, margin + 28f, detail)
                            canvas.save()
                            canvas.translate(margin, margin + HEADER_HEIGHT)
                            canvas.clipRect(0f, 0f, printableWidth, offsets[lastRow] - offsets[firstRow])
                            canvas.translate(0f, -offsets[firstRow])
                            for (cell in cells) {
                                if (cell.lastRow < firstRow || cell.firstRow >= lastRow) continue
                                drawCell(canvas, cell, offsets, grid, fill)
                            }
                            canvas.restore()
                            pdf.finishPage(page)
                            firstRow = lastRow
                        }
                        start = end
                    }
                }
                unfiltered.outputStream().use(pdf::writeTo)
            }
        }
        if (unfiltered != dst) PdfEngine.convert(unfiltered, dst, Format.PDF, opt, null)
    }

    private fun drawCell(canvas: Canvas, cell: PrintedCell, offsets: FloatArray, grid: Paint, fill: Paint) {
        val top = offsets[cell.firstRow]
        val bottom = offsets[cell.lastRow + 1]
        cell.fill?.let {
            fill.color = it
            canvas.drawRect(cell.left, top, cell.right, bottom, fill)
        }
        canvas.drawRect(cell.left, top, cell.right, bottom, grid)
        cell.text?.let {
            canvas.save()
            canvas.translate(cell.left + CELL_PADDING, top + CELL_PADDING)
            it.draw(canvas)
            canvas.restore()
        }
    }

    private fun layout(value: String, cell: Cell, book: Workbook, width: Float): StaticLayout {
        val style = cell.cellStyle
        val font = book.getFontAt(style.fontIndex)
        val type = (if (font.bold) Typeface.BOLD else 0) or (if (font.italic) Typeface.ITALIC else 0)
        val textColor = when (font) {
            is XSSFFont -> color(font.xssfColor)
            else -> (book as? HSSFWorkbook)?.customPalette?.getColor(font.color)?.let(::color)
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor ?: Color.BLACK
            typeface = Typeface.create(Typeface.SANS_SERIF, type)
            textSize = font.fontHeightInPoints.toFloat().coerceIn(8f, 16f)
        }
        val textWidth = (width - 2 * CELL_PADDING).toInt().coerceAtLeast(1)
        if (value.length <= 8 && value.none(Char::isWhitespace)) {
            val measured = paint.measureText(value)
            if (measured > textWidth) paint.textSize = (paint.textSize * textWidth / measured).coerceAtLeast(8f)
        }
        val alignment = when (style.alignment) {
            HorizontalAlignment.CENTER, HorizontalAlignment.CENTER_SELECTION -> Layout.Alignment.ALIGN_CENTER
            HorizontalAlignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        return StaticLayout.Builder.obtain(value, 0, value.length, paint, textWidth)
            .setAlignment(alignment).setIncludePad(false).build()
    }

    private fun color(source: org.apache.poi.ss.usermodel.Color?): Int? = when (source) {
        is XSSFColor -> source.rgb?.takeIf { it.size == 3 }?.let {
            Color.rgb(it[0].toInt() and 255, it[1].toInt() and 255, it[2].toInt() and 255)
        }
        is HSSFColor -> source.triplet.let { Color.rgb(it[0].toInt(), it[1].toInt(), it[2].toInt()) }
        else -> null
    }
}
