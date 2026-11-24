package com.morph.app

import com.morph.app.domain.graph.*
import com.morph.app.domain.model.*
import com.morph.app.domain.model.Format.*
import com.morph.app.engine.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class ModalityReliabilityTest {
    private val dir=Files.createTempDirectory("morph-modality").toFile()
    @After fun removeFiles() {dir.deleteRecursively()}

    @Test fun animatedGifRequiresTwoImageBlocks() {
        val frame=java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==")
        val gif=File(dir,"data")
        gif.writeBytes(frame)
        assertFalse(ImageStructure.animated(gif,GIF))
        val start=frame.indexOf(0x2c.toByte())
        gif.writeBytes(frame.copyOf(frame.size-1)+frame.copyOfRange(start,frame.size))
        assertTrue(ContentSniffer.detect(gif,"misnamed.jpg").isAnimated)
        assertTrue(ConversionLossAnalyzer.advisories(GIF,JPG,true,false).any { "frame" in it })
    }

    @Test fun archiveRetainsDirectoryAndUnicodePath() = runBlocking {
        val folder=File(dir,"marker").apply { createNewFile() }
        val content=File(dir,"record").apply { writeText("0007") }
        val entries=listOf("reports/empty/" to folder,"reports/日本語.txt" to content)
        for(format in listOf(ZIP,TAR,SEVEN)) {
            val archive=File(dir,format.name)
            ArchiveEngine.pack(entries,archive,format,ProOptions())
            val files=ArchiveEngine.unpack(archive,format,File(dir,"extract-"+format.name),ProOptions())
            assertEquals(entries.map { it.first },files.map { it.first })
            assertEquals("0007",files.last().second.readText())
        }
    }

    @Test fun nestedTableRetainsValuesAndRejectsAmbiguousPaths() {
        val input=File(dir,"source")
        val out=File(dir,"out.csv")
        input.writeText("""[{"id":"0007","info":{"enabled":true,"number":9223372036854775807}}]""")
        DataEngine.write(DataEngine.read(input,JSON),out,CSV,ProOptions())
        assertEquals("id,info.enabled,info.number",out.readLines()[0])
        assertEquals("0007,true,9223372036854775807",out.readLines()[1])
        input.writeText("""[{"a.b":1,"a":{"b":2}}]""")
        assertTrue(runCatching { DataEngine.write(DataEngine.read(input,JSON),out,CSV,ProOptions()) }.isFailure)
    }

    @Test fun structuredExportsRetainBooleanNullAndLargeIdentifiers() {
        val input = File(dir, "typed.json").apply {
            writeText("""[{"id":"0007","flag":true,"missing":null,"large":9223372036854775807,"price":12.25}]""")
        }
        val records = DataEngine.read(input, JSON)
        val sql = File(dir, "typed.sql")
        DataEngine.write(records, sql, SQL, ProOptions())
        assertTrue(sql.readText().contains("('0007', TRUE, NULL, 9223372036854775807, 12.25)"))
        val workbook = File(dir, "typed.xlsx")
        DataEngine.write(records, workbook, XLSX, ProOptions())
        org.apache.poi.ss.usermodel.WorkbookFactory.create(workbook, null, true).use { book ->
            val row = book.getSheetAt(0).getRow(1)
            assertEquals("0007", row.getCell(0).stringCellValue)
            assertTrue(row.getCell(1).booleanCellValue)
            assertNull(row.getCell(2))
            assertEquals("9223372036854775807", row.getCell(3).stringCellValue)
            assertEquals(12.25, row.getCell(4).numericCellValue, 0.0)
        }
    }
}
