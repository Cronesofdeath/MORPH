package com.morph.app

import com.morph.app.data.StorageManager
import com.morph.app.domain.model.*
import com.morph.app.domain.graph.MagicByteDetector
import com.morph.app.domain.model.Format.*
import com.morph.app.engine.*
import java.io.*
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RobustnessTest {
    @Test fun sizeFieldRejectsUnsupportedScientificNotationAndOverflow() {
        for (input in listOf("1e700000000", "1e-700000000", "9".repeat(100_000), "9223372036854775808")) {
            assertNull(SizeUnitCalculator.bytes(input, SizeUnit.B, false))
        }
        assertEquals(Long.MAX_VALUE, SizeUnitCalculator.bytes("9223372036854775807", SizeUnit.B, true))
        assertEquals(512L, SizeUnitCalculator.bytes(".5", SizeUnit.KB, true))
    }

    @Test fun sharedBudgetDoesNotOverflowAtLongMax() {
        val weights = listOf(Long.MAX_VALUE, Long.MAX_VALUE, 0L, Long.MAX_VALUE, 0L)
        val result = SizeUnitCalculator.allocate(Long.MAX_VALUE, weights)
        assertEquals(Long.MAX_VALUE, result.sum())
        assertTrue(result.all { it > 0 })
        assertTrue(result[0] - result[1] in 0..1)
    }

    @Test fun longUnicodeFilenamesStayWithinFilesystemLimit() {
        val result = StorageManager.safe("🚀".repeat(150) + ".jpg")
        assertEquals("🚀".repeat(55), result)
        assertTrue(("$result.tar.gz").toByteArray().size <= 255)
        assertEquals("file", StorageManager.safe("..."))
    }

    @Test fun base64AcrossBuffersAndStrictEndOfInput() = runBlocking {
        val data = ByteArray(180_031) { (it * 13).toByte() }
        val encoded = Base64.getMimeEncoder().encode(data)
        val decoded = ByteArrayOutputStream()
        decodeBase64(encoded.inputStream(), decoded)
        assertArrayEquals(data, decoded.toByteArray())
        for (bad in listOf("YQ==Yg==", "YQ=", "Y@Q==", "Y", "YR==")) {
            assertTrue(runCatching { decodeBase64(bad.byteInputStream(), ByteArrayOutputStream()) }.isFailure)
        }
        val noPadding = ByteArrayOutputStream()
        decodeBase64("YQ".byteInputStream(), noPadding)
        assertEquals("a", noPadding.toString("UTF-8"))
    }

    @Test fun pageRangesRejectAmbiguousSyntax() {
        assertEquals(listOf(2, 1, 3), PdfEngine.pages("3,2-4", 10))
        for (range in listOf("1-2-3", "1,", "0", "4-2", "2147483648")) {
            assertThrows(IllegalArgumentException::class.java) { PdfEngine.pages(range, 10) }
        }
    }

    @Test fun mislabeledGzipIsNotAssumedToContainTar() = fixture { dir ->
        val input = File(dir, "records.tar.gz")
        java.util.zip.GZIPOutputStream(input.outputStream()).use { it.write("a single text stream".toByteArray()) }
        val detection = MagicByteDetector.detect(input, input.name)
        assertEquals(GZ, detection.actual)
        assertTrue(detection.mismatch)
    }

    @Test fun detectorDistinguishesPlainTextAndUnlabeledScalarArrays() {
        assertEquals(TXT, MagicByteDetector.detect("BMW service notes".toByteArray(), "notes.txt").actual)
        assertEquals(B64, MagicByteDetector.detect("BMAA".toByteArray(), "record.b64").actual)
        assertEquals(JSON, MagicByteDetector.detect("[19, false, null]".toByteArray(), "data").actual)
    }

    @Test fun duplicateBlankAndUnicodeCsvHeadersKeepEveryValue() = fixture { dir ->
        val input = File(dir, "source.csv").apply {
            writeText("\uFEFFName,Name,,Name_2\r\n\"Ada, Jr.\",\"line 1\nline 2\",値,A,B\r\n")
        }
        val output = File(dir, "out.json")
        SpreadsheetEngine.export(SpreadsheetEngine.tables(input, CSV, ProOptions()), output, JSON, ProOptions())
        val row = DataEngine.read(output, JSON)[0]
        assertEquals("Ada, Jr.", row["Name"].asText())
        assertEquals("line 1\nline 2", row["Name_2"].asText())
        assertEquals("値", row["Column3"].asText())
        assertEquals("A", row["Name_2_2"].asText())
        assertEquals("B", row["Column5"].asText())
    }

    @Test fun odsExpandedRowsColumnsAndInteriorBlanksStayAligned() = fixture { dir ->
        val input = File(dir, "source.ods")
        val xml = """<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"><table:table table:name="Data"><table:table-row table:number-rows-repeated="2"><table:table-cell table:number-columns-repeated="2"><text:p>é</text:p></table:table-cell><table:table-cell table:number-columns-repeated="3"/><table:table-cell><text:p>end</text:p></table:table-cell></table:table-row><table:table-row table:number-rows-repeated="1048574"><table:table-cell/></table:table-row></table:table></office:document-content>"""
        ZipOutputStream(input.outputStream()).use {
            it.putNextEntry(ZipEntry("content.xml"))
            it.write(xml.toByteArray())
        }
        val rows = SpreadsheetEngine.tables(input, ODS, ProOptions()).single().rows
        assertEquals(List(2) { listOf("é", "é", "", "", "", "end") }, rows)
    }

    @Test fun structuredInputRejectsSilentLossAndKeepsDecimalPrecision() = fixture { dir ->
        val input = File(dir, "source.json")
        for (invalid in listOf("""{"x":1,"x":2}""", """{"x":1} {"y":2}""", "[".repeat(129) + "0" + "]".repeat(129))) {
            input.writeText(invalid)
            assertTrue(runCatching { DataEngine.read(input, JSON) }.isFailure)
        }
        input.writeText("""{"value":1.234567890123456789}""")
        assertEquals("1.234567890123456789", DataEngine.read(input, JSON)["value"].decimalValue().toPlainString())
        input.writeText("""<doc><entry>café</entry><entry>豆</entry></doc>""")
        val node = DataEngine.read(input, XML)
        assertEquals(listOf("café", "豆"), node["entry"].map { it.asText() })
    }

    private fun fixture(test: (File) -> Unit) {
        val dir = Files.createTempDirectory("morph-robustness").toFile()
        try { test(dir) } finally { dir.deleteRecursively() }
    }
}
