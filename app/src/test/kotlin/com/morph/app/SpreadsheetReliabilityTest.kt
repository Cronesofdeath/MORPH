package com.morph.app

import com.morph.app.domain.model.*
import com.morph.app.domain.model.Format.*
import com.morph.app.engine.*
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.util.CellRangeAddress
import org.jsoup.Jsoup
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files

class SpreadsheetReliabilityTest {
    @Test fun styledDatesAndMergedRegions() {
        val folder = Files.createTempDirectory("morph-sheets").toFile()
        try {
            val source=File(folder,"book")
            HSSFWorkbook().use { wb ->
                val sheet=wb.createSheet("Expenses")
                val row=sheet.createRow(0)
                row.createCell(0).apply {
                    setCellValue(46350.0)
                    cellStyle=wb.createCellStyle().apply { dataFormat=wb.createDataFormat().getFormat("yyyy-mm-dd") }
                }
                sheet.addMergedRegion(CellRangeAddress(0,1,0,1))
                sheet.createRow(2).createCell(0).cellFormula="2+3"
                wb.createSheet("IDs").createRow(0).createCell(0).setCellValue("000123")
                source.outputStream().use(wb::write)
            }
            val html=File(folder,"out.html")
            SpreadsheetEngine.html(source,html,ProOptions())
            val doc=Jsoup.parse(html,"UTF-8")
            assertEquals(2,doc.select("section").size)
            assertTrue(doc.select("td[rowspan=2][colspan=2]").text().matches(Regex("""\d{4}-\d{2}-\d{2}""")))
            assertTrue(doc.select("td").any { it.text()=="5" })
            assertTrue(doc.text().contains("000123"))
        } finally { folder.deleteRecursively() }
    }
}
