package com.morph.app

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import com.morph.app.domain.model.*
import com.morph.app.domain.model.Format.*
import com.morph.app.engine.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class DocumentPdfReliabilityTest {
    private val folder by lazy { File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,java.util.UUID.randomUUID().toString()).apply { mkdirs() } }
    @After fun clean() {folder.deleteRecursively()}

    @Test fun unicodeTextAndStyledHtmlPrint() = runBlocking {
        val message = "₹  नमस्ते  café  “MORPH”"
        for (format in listOf(TXT,MD,HTML)) {
            val input = File(folder,format.name).apply { writeText(if (format == HTML) "<p>$message</p>" else message) }
            val pdf=File(folder,format.name+".pdf")
            DocumentEngine.convert(input,pdf,format,PDF,ProOptions())
            PDDocument.load(pdf).use { assertTrue(it.numberOfPages > 0) }
        }
    }

    @Test fun wideWorkbookPrintsTheRightmostColumnAndLastRow() = runBlocking {
        val workbook = File(folder, "wide.xlsx")
        XSSFWorkbook().use { book ->
            val sheet = book.createSheet("Attendance")
            for (r in 0 until 80) {
                val row = sheet.createRow(r)
                row.createCell(0).setCellValue("Row ${r + 1}")
                row.createCell(67).setCellValue(if (r == 79) "EndMarker" else "Present")
            }
            sheet.setColumnWidth(0, 14 * 256)
            sheet.setColumnWidth(67, 18 * 256)
            workbook.outputStream().use(book::write)
        }

        val output = TargetSizeOrchestrator.convert(workbook, folder, XLSX, PDF, ProOptions(), null) {}
        PDDocument.load(output).use { pdf ->
            val content = PDFTextStripper().getText(pdf)
            assertTrue(pdf.numberOfPages > 2)
            assertTrue(content.contains("BP"))
            assertTrue(content.contains("Row 80"))
            assertTrue(content.contains("EndMarker"))
        }
    }

    @Test fun scannedTextRunsBundledRecognition() = runBlocking {
        val scan = Bitmap.createBitmap(1400,400,Bitmap.Config.ARGB_8888)
        val pdf = File(folder,"scan.pdf")
        try {
            Canvas(scan).apply {
                drawColor(Color.WHITE)
                drawText("MORPH OFFLINE TEST",60f,210f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=90f })
            }
            ImageEngine.pdf(listOf(scan),pdf,ProOptions(pageSize="Fit",margin=0))
        } finally { scan.recycle() }
        val output=File(folder,"scan.txt")
        val events=mutableListOf<String>()
        PdfEngine.convert(pdf,output,TXT,ProOptions(),null) { events += it }
        assertTrue(events.any { "offline OCR" in it })
        assertTrue(output.readText(),output.readText().contains("OFFLINE",true))
    }
}
