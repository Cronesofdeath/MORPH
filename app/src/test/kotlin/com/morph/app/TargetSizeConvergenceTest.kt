package com.morph.app

import com.morph.app.domain.model.*
import com.morph.app.domain.model.Format.*
import com.morph.app.engine.*
import java.io.*
import java.nio.file.Files
import java.util.zip.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TargetSizeConvergenceTest {
    @Test fun highestQualityUnderCeiling()= runBlocking {
        var attempts=0
        val q=TargetSizeConvergence.highestQuality(135) { attempts++; it*2L }
        assertEquals(67,q);assertTrue(attempts<=8)
        assertNull(TargetSizeConvergence.highestQuality(3) {it.toLong()})
    }
    @Test fun muxAndAudioReserve() {val b=TargetSizeConvergence.videoBudget(10000000,60.0,true);assertEquals(96,b.audio);assertTrue(b.total*1000*60/8 <= 9500000)}
    @Test(expected=TargetTooSmall::class) fun impossibleVideoFloor() {TargetSizeConvergence.videoBudget(500,3600.0,true)}
    @Test fun zipPaddingRemainsReadable()=runBlocking {
        val zip=File.createTempFile("morph-test",".zip")
        try {ZipOutputStream(zip.outputStream()).use {it.putNextEntry(ZipEntry("a.txt"));it.write("example".toByteArray());it.closeEntry()}
            val size=zip.length()+77;TargetSizeOrchestrator.pad(zip,ZIP,size);assertEquals(size,zip.length())
            ZipFile(zip).use {assertEquals("example",it.getInputStream(it.getEntry("a.txt")).reader().readText())}
        } finally {zip.delete()}; Unit
    }
    @Test fun actualConversionHonorsExactBytes()=runBlocking {
        val dir=Files.createTempDirectory("morph-test").toFile()
        try {val input=File(dir,"rows.json").apply { writeText("""[{"name":"Ada","n":2}]""") }
            val out=TargetSizeOrchestrator.convert(input,dir,JSON,JSON,ProOptions(),SizeTarget(97,true)) {}
            assertEquals(97L,out.length()); assertEquals("Ada",SpreadsheetEngine.mapper.readTree(out)[0]["name"].asText())
        } finally {dir.deleteRecursively()}; Unit
    }
}
