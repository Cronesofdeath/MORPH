package com.morph.app

import com.morph.app.domain.graph.*
import com.morph.app.domain.model.Format.*
import com.morph.app.engine.SpreadsheetEngine
import com.morph.app.domain.model.ProOptions
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ContentSnifferAndRoutingTest {
    @Test fun portalAndBinaryDisguises() {
        val pairs = listOf(
            Triple("RIFF0000WEBP".toByteArray(),"x.jpg",WEBP),
            Triple("ID3".toByteArray(),"x.wav",MP3),
            Triple("{\\rtf1 test}".toByteArray(),"x.doc",RTF),
            Triple("\uFEFF <!-- comment --><table><tr><td>₹".toByteArray(Charsets.UTF_16LE),"x.xls",HTML),
            Triple("id;name\r\n001;Coffee".toByteArray(),"x.xls",CSV),
            Triple("<?xml version=\"1.0\"?><Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\"/>".toByteArray(),"x.xls",SPREADSHEETML),
            Triple("{\"flag\":true}".toByteArray(),"x.txt",JSON),
            Triple(byteArrayOf(0,0,0,24)+"ftypmif1".toByteArray()+ByteArray(4)+"avifmif1".toByteArray(),"x.jpg",AVIF)
        )
        pairs.forEach { (data,name,format) ->
            val identity = MagicByteDetector.detect(data,name)
            assertEquals(name, format, identity.actual)
            assertTrue(name, identity.mismatch)
        }
    }

    @Test fun correctedCopyRetainsAllBytes() = kotlinx.coroutines.runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("morph-correction").toFile()
        try {
            val input = File(dir,"source.jpg").apply { writeBytes(ByteArray(270000) { (it % 223).toByte() }) }
            val output = com.morph.app.engine.FileInspector.correctedCopy(input,File(dir,"corrected.webp"))
            assertArrayEquals(input.readBytes(),output.readBytes())
        } finally {dir.deleteRecursively()}
    }

    @Test fun graphExcludesPseudoVectorAndLongRoutes() {
        val graph = ConversionGraphRouter()
        assertNull(graph.route(PNG,SVG))
        assertEquals(Engine.SHEET,graph.route(HTML,XLSX)!!.single().engine)
        for (from in com.morph.app.domain.model.Format.entries) for (to in graph.targets(from))
            assertTrue(graph.route(from,to)!!.size <= 2)
    }

    @Test fun portalSpanAndPrecision() {
        val file = File.createTempFile("portal",".xls")
        try {
            file.writeText("<table><tr><th rowspan=2>ID</th><td colspan=2>Top</td></tr><tr><td>0012</td><td>₹</td></tr></table>")
            val rows = SpreadsheetEngine.tables(file,HTML,ProOptions()).single().rows
            assertEquals(listOf("","0012","₹"),rows[1])
        } finally {file.delete()}
    }
}
