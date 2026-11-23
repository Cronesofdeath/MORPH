package com.morph.app

import com.morph.app.domain.graph.MagicByteDetector
import com.morph.app.domain.model.Format.*
import java.io.*
import java.util.zip.*
import org.junit.Assert.*
import org.junit.Test

class MagicByteDetectorTest {
    fun b(vararg values:Int)=values.map(Int::toByte).toByteArray()
    @Test fun imageHeadersOverrideWrongExtension() {
        val png=MagicByteDetector.detect(b(137,80,78,71,13,10,26,10),"photo.jpg")
        assertEquals(PNG,png.actual); assertTrue(png.mismatch)
        assertEquals(JPG,MagicByteDetector.detect(b(255,216,255),"test.bin").actual)
    }
    @Test fun binaryContainers() {
        assertEquals(SEVEN,MagicByteDetector.detect(b(55,122,188,175,39,28),"noext").actual)
        assertEquals(PDF,MagicByteDetector.detect("%PDF-1.7".toByteArray(),"document").actual)
        assertEquals(MP4,MagicByteDetector.detect(b(0,0,0,24)+"ftypisom".toByteArray(),"noext").actual)
        val ole=b(208,207,17,224,161,177,26,225)
        assertEquals(XLS,MagicByteDetector.detect(ole+"Workbook".toByteArray(Charsets.UTF_16LE),"book.bin").actual)
        assertEquals(DOC,MagicByteDetector.detect(ole+"WordDocument".toByteArray(Charsets.UTF_16LE),"book.bin").actual)
    }
    @Test fun inspectsRealZipEntries() {
        val file=File.createTempFile("morph-test",".bin")
        try { ZipOutputStream(file.outputStream()).use {it.putNextEntry(ZipEntry("xl/workbook.xml"));it.write("<workbook/>".toByteArray());it.closeEntry()}
            assertEquals(XLSX,MagicByteDetector.detect(file,"sample.zip").actual)
        } finally {file.delete()}
    }
}
