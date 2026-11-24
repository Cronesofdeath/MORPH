package com.morph.app

import com.morph.app.domain.model.*
import com.morph.app.domain.model.Format.*
import com.morph.app.engine.*
import java.io.File
import java.nio.file.Files
import org.junit.*
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking

class CompressionAndValidatorTest {
    @Test fun rejectsDamagedOutputBeforeCompletion() = runBlocking {
        val folder=Files.createTempDirectory("morph-invalid-output").toFile()
        try {
            val out=File(folder,"output")
            for ((fmt,data) in listOf(JSON to "{",TXT to "  ",ZIP to "broken")) {
                out.writeText(data)
                assertTrue(fmt.label,runCatching { SemanticOutputValidator.verify(out,fmt,ProOptions(),null,fmt) }.isFailure)
            }
        } finally {folder.deleteRecursively()}
    }
}
