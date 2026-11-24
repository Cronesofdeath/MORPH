package com.morph.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.morph.app.data.ConversionJob
import com.morph.app.data.decodeOptions
import com.morph.app.data.encode
import com.morph.app.data.preferences
import com.morph.app.domain.model.*
import com.morph.app.domain.model.Format.*
import com.morph.app.engine.*
import com.morph.app.worker.CancelConversionReceiver
import com.morph.app.worker.ConversionWorker
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceStressTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir by lazy { File(context.cacheDir, "stress-" + UUID.randomUUID()).apply { mkdirs() } }

    @After fun cleanup() { dir.deleteRecursively() }

    @Test fun streams256MiBAndKeepsConcatenatedMembers() = runBlocking {
        val payload = 256L * 1024 * 1024
        assertTrue("Emulator needs 600 MiB of free storage", dir.usableSpace > 600L * 1024 * 1024)
        val compressed = File(dir, "stream.gz")
        val block = ByteArray(65_536) { (it % 251).toByte() }
        compressed.outputStream().buffered().use { sink ->
            GZIPOutputStream(sink).use { gz -> repeat((payload / block.size).toInt()) { gz.write(block) } }
        }
        GZIPOutputStream(compressed.appendOutputStream()).use { it.write("LAST".toByteArray()) }
        val output = ArchiveEngine.unpack(compressed, GZ, File(dir, "unpack"), ProOptions()).single().second
        assertEquals(payload + 4, output.length())
        java.io.RandomAccessFile(output, "r").use {
            it.seek(payload - 1)
            assertEquals(block.last().toInt() and 255, it.read())
            val marker = ByteArray(4)
            it.readFully(marker)
            assertArrayEquals("LAST".toByteArray(), marker)
        }
    }

    @Test fun concatenatedBzipAndXzMembersPreserveTheSecondPayload() = runBlocking {
        for (format in listOf(BZ, XZ)) {
            val first = File(dir, "first.txt").apply { writeText("café ") }
            val second = File(dir, "second.txt").apply { writeText("終") }
            val archive = File(dir, "first." + format.extension)
            val tail = File(dir, "second." + format.extension)
            ArchiveEngine.pack(listOf(first.name to first), archive, format, ProOptions())
            ArchiveEngine.pack(listOf(second.name to second), tail, format, ProOptions())
            archive.appendBytes(tail.readBytes())
            val content = ArchiveEngine.unpack(archive, format, File(dir, "unpack-" + format.name), ProOptions()).single().second
            assertEquals("café 終", content.readText())
        }
    }

    @Test fun encryptedZipCanChangePasswordsWithoutDroppingItsUnicodeEntry() = runBlocking {
        val plain = File(dir, "café-終.txt").apply { writeText("payload") }
        val oldZip = File(dir, "old.zip")
        ArchiveEngine.pack(listOf(plain.name to plain), oldZip, ZIP, ProOptions(password = "old-pass"))
        val invalid = runCatching { ArchiveEngine.unpack(oldZip, ZIP, File(dir, "wrong"), ProOptions(inputPassword = "wrong")) }
        assertTrue(invalid.isFailure)
        val folder = File(dir, "rotate").apply { mkdirs() }
        val options = decodeOptions(ProOptions(password = "new-pass", inputPassword = "old-pass").encode())
        val newZip = TargetSizeOrchestrator.convert(oldZip, folder, ZIP, ZIP, options, null) {}
        val result = ArchiveEngine.unpack(newZip, ZIP, File(dir, "correct"), ProOptions(inputPassword = "new-pass")).single()
        assertEquals(plain.name, result.first)
        assertEquals("payload", result.second.readText())
    }

    @Test fun jpegExactTinyGapsAndMultiSegmentPaddingStayDecodable() = runBlocking {
        val bitmap = Bitmap.createBitmap(32, 17, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.MAGENTA)
            val source = File(dir, "source.jpg")
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            for (gap in listOf(1L, 2L, 3L, 4L, 65538L, 140_000L)) {
                val out = source.copyTo(File(dir, "test-$gap.jpg"))
                TargetSizeOrchestrator.pad(out, JPG, source.length() + gap)
                assertEquals(source.length() + gap, out.length())
                val result = BitmapFactory.decodeFile(out.path)
                assertNotNull("Gap $gap", result)
                assertEquals(32, result.width)
                result.recycle()
            }
        } finally { bitmap.recycle() }
    }

    @Test fun panoramaWith144MillionPixelsDecodesAtRequestedSize() = runBlocking {
        val picture = File(dir, "panorama.png")
        val width = 18_000
        val height = 8_000
        val compressed = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(compressed).use { stream ->
            val row = ByteArray(width + 1)
            for (line in 0 until height) {
                row[1] = (line and 255).toByte()
                stream.write(row)
            }
        }
        java.io.DataOutputStream(picture.outputStream()).use { output ->
            fun chunk(type: String, bytes: ByteArray) {
                val tag = type.toByteArray()
                val crc = java.util.zip.CRC32().apply { update(tag); update(bytes) }
                output.writeInt(bytes.size)
                output.write(tag)
                output.write(bytes)
                output.writeInt(crc.value.toInt())
            }
            output.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            val header = java.nio.ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put(byteArrayOf(8, 0, 0, 0, 0))
            chunk("IHDR", header.array())
            chunk("IDAT", compressed.toByteArray())
            chunk("IEND", byteArrayOf())
        }
        val bitmap = ImageEngine.bitmap(picture, PNG, ProOptions(maxWidth = 512))
        try {
            assertEquals(512, bitmap.width)
            assertEquals(227, bitmap.height)
        } finally { bitmap.recycle() }
    }

    @Test fun rotatedJpegKeepsCorrectOrientationAndRespectsBothAxes() = runBlocking {
        val image = File(dir, "rotated.jpg")
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(Color.BLUE)
            canvas.drawRect(0f, 0f, 32f, 32f, android.graphics.Paint().apply { color = Color.RED })
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        } finally { bitmap.recycle() }
        androidx.exifinterface.media.ExifInterface(image).apply {
            setAttribute("Orientation", "6")
            saveAttributes()
        }
        val result = ImageEngine.bitmap(image, JPG, ProOptions(maxWidth = 14, maxHeight = 20))
        try {
            assertEquals(10, result.width)
            assertEquals(20, result.height)
            assertTrue(Color.red(result.getPixel(5, 3)) > 200)
            assertTrue(Color.blue(result.getPixel(5, 16)) > 200)
        } finally { result.recycle() }
    }

    @Test fun exactPdfStillReopensAndAnImpossibleImageBudgetFails() = runBlocking {
        val source = File(dir, "source.png")
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.GREEN)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        val pdf = TargetSizeOrchestrator.convert(source, dir, PNG, PDF, ProOptions(), SizeTarget(12_000, true)) {}
        assertEquals(12_000L, pdf.length())
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(pdf).use { assertEquals(1, it.numberOfPages) }
        assertTrue(runCatching {
            TargetSizeOrchestrator.convert(source, dir, PNG, JPG, ProOptions(), SizeTarget(128)) {}
        }.exceptionOrNull() is TargetTooSmall)
    }

    @Test fun notificationCancellationIsTerminalAndCannotPublish() = runBlocking {
        val app = context.applicationContext as MorphApplication
        val dao = app.database.dao()
        val work = OneTimeWorkRequestBuilder<ConversionWorker>().setInitialDelay(1, TimeUnit.HOURS).build()
        val id = work.id.toString()
        dao.save(ConversionJob(id, "", "Cancelled fixture", "JSON", "JSON", 0, 0, false, "{}", "stress"))
        val manager = WorkManager.getInstance(app)
        withContext(Dispatchers.IO) { manager.enqueue(work).result.get(30, TimeUnit.SECONDS) }
        context.sendBroadcast(Intent(context, CancelConversionReceiver::class.java).setData(Uri.parse("morph:cancel/$id")))
        withTimeout(30_000) {
            while (dao.job(id)?.status != "Cancelled") delay(100)
            while (withContext(Dispatchers.IO) { manager.getWorkInfoById(work.id).get(5, TimeUnit.SECONDS)?.state?.isFinished } != true) delay(100)
        }
        assertEquals(0, dao.updateActive(id, "Running", "late update"))
        assertEquals(0, dao.complete(id, "JSON", 123, "/unpublished", 123, ""))
        assertEquals("Cancelled", dao.job(id)?.status)
    }

    @Test fun mislabeledCameraFixturePasses50KiBAndImpossibleJobCleansUp() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = File(dir, "messy-photo.png")
        instrumentation.context.assets.open(source.name).use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        val sourceFormat = com.morph.app.domain.graph.ContentSniffer.detect(source,source.name)
        assertEquals(JPG, sourceFormat.actual)
        assertTrue(sourceFormat.mismatch)
        val dao = (context.applicationContext as MorphApplication).database.dao()
        val manager = WorkManager.getInstance(context)
        suspend fun convert(budget: Long): ConversionJob {
            val work = OneTimeWorkRequestBuilder<ConversionWorker>().build()
            val id = work.id.toString()
            dao.save(ConversionJob(id, Uri.fromFile(source).toString(), source.name, "PNG", "JPG",
                source.length(), budget, false, "{}", "picker-fixture"))
            manager.enqueue(work)
            return withTimeout(90_000) {
                while (withContext(Dispatchers.IO) {
                    manager.getWorkInfoById(work.id).get(5, TimeUnit.SECONDS)?.state?.isFinished
                } != true) delay(100)
                assertFalse(File(context.cacheDir, "morph_work/$id").exists())
                requireNotNull(dao.job(id))
            }
        }
        val success = convert(50 * 1024)
        assertEquals("Complete", success.status)
        assertEquals("JPG", success.source)
        assertTrue(success.bytes in 1..(50L * 1024))
        assertTrue(File(success.output).isFile)
        val impossible = convert(1)
        assertEquals("Failed", impossible.status)
        assertTrue(impossible.minimum > 1)
        assertFalse(File(context.filesDir, "exports/${impossible.id}").exists())
    }

    @Test fun unavailableAutosaveDestinationKeepsVerifiedVaultFile() = runBlocking {
        Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val app = context.applicationContext as MorphApplication
        val dao = app.database.dao()
        val treeKey = stringPreferencesKey("outputTree")
        val autoKey = booleanPreferencesKey("autoSave")
        val previous = app.preferences.data.first()
        try {
            app.preferences.edit {
                it[treeKey] = "content://com.morph.unavailable/tree/destination"
                it[autoKey] = true
            }
            val input = File(dir, "payload.json").apply { writeText("""{"verified":true}""") }
            val work = OneTimeWorkRequestBuilder<ConversionWorker>().build()
            val id = work.id.toString()
            dao.save(ConversionJob(id, Uri.fromFile(input).toString(), input.name, "JSON", "JSON", input.length(), 0, false, "{}", "stress"))
            WorkManager.getInstance(app).enqueue(work)
            val record = withTimeout(30_000) {
                var current = dao.job(id)!!
                while (current.status in setOf("Queued", "Running") || current.stage == "Verified") {
                    delay(100)
                    current = dao.job(id)!!
                }
                current
            }
            assertEquals("Complete", record.status)
            assertTrue(record.stage.contains("Auto-save failed"))
            assertEquals(input.readText(), File(record.output).readText())
        } finally {
            app.preferences.edit {
                previous[treeKey]?.let { value -> it[treeKey] = value } ?: it.remove(treeKey)
                previous[autoKey]?.let { value -> it[autoKey] = value } ?: it.remove(autoKey)
            }
        }
    }

    private fun File.appendOutputStream() = java.io.FileOutputStream(this, true)
}
