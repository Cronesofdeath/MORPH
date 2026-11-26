package com.morph.app

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.morph.app.domain.model.*
import com.morph.app.domain.model.Format.*
import com.morph.app.engine.*
import kotlinx.coroutines.runBlocking
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class DeviceConversionTest {
    private val dir by lazy { File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,UUID.randomUUID().toString()).apply {mkdirs()} }
    @After fun cleanup() {dir.deleteRecursively()}
    private suspend fun convert(file:File,from:Format,to:Format,budget:SizeTarget?=null)=TargetSizeOrchestrator.convert(file,File(dir,UUID.randomUUID().toString()).apply {mkdirs()},from,to,ProOptions(),budget) {}
    @Test fun workerPublishesVerifiedOutput()=runBlocking {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val app=ctx.applicationContext as MorphApplication
        val source=File(dir,"record.json").apply {writeText("""[{"record":1}]""")}
        val job=androidx.work.OneTimeWorkRequestBuilder<com.morph.app.worker.ConversionWorker>().build()
        val dao=app.database.dao()
        dao.save(com.morph.app.data.ConversionJob(job.id.toString(),android.net.Uri.fromFile(source).toString(),source.name,"JSON","JSON",source.length(),1024,true,"{}","test"))
        androidx.work.WorkManager.getInstance(app).enqueue(job)
        val saved=kotlinx.coroutines.withTimeout(90000) {
            var result=dao.job(job.id.toString())!!
            while(result.status in setOf("Queued","Running")) {kotlinx.coroutines.delay(250);result=dao.job(job.id.toString())!!};result
        }
        assertEquals(saved.stage,"Complete",saved.status);assertEquals(1024L,File(saved.output).length());assertEquals(64,saved.hash.length)
        assertFalse(File(ctx.cacheDir,"morph_work/"+job.id).exists())
    }
    @Test fun twoPassVideoAndFrames()=runBlocking {
        val original=File(dir,"clip.mp4")
        MediaEngine.run(listOf("-f","lavfi","-i","testsrc2=size=160x120:rate=12:duration=2","-f","lavfi","-i","sine=frequency=400:duration=2","-c:v","libx264","-c:a","aac","-shortest",original.path))
        val result=convert(original,MP4,MP4,SizeTarget(28000));assertTrue(result.length() in 1..28000)
        val streams=com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(result.path).mediaInformation.streams
        assertTrue(streams.any {it.type=="audio"});assertTrue(streams.any {it.type=="video"})
        val frames=convert(original,MP4,FRAMES);ZipFile(frames).use {assertTrue(it.size()>=1)}
    }
    @Test fun trimmedGifReadsFiveGigabyteContentUri() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceDir = File(context.filesDir, "exports/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val video = File(sourceDir, "large.mp4")
            MediaEngine.run(listOf("-f", "lavfi", "-i", "testsrc2=size=160x120:rate=12:duration=3",
                "-c:v", "libx264", "-movflags", "+faststart", video.path))
            val fullLength = 5_000_000_000L
            // A sparse MP4 free-space atom exercises 64-bit size and seek without writing 5 GB.
            java.io.RandomAccessFile(video, "rw").use { mp4 ->
                val start = mp4.length()
                mp4.seek(start)
                mp4.writeInt(1)
                mp4.writeBytes("free")
                mp4.writeLong(fullLength - start)
                mp4.setLength(fullLength)
            }
            val uri = com.morph.app.data.StorageManager.uri(context, video)
            val output = File(dir, "trimmed.gif")
            val options = ProOptions(trimStart = 1.0, trimEnd = 2.0, maxWidth = 120)
            val read = context.contentResolver.withLocalInput(uri) { input ->
                assertEquals(fullLength, input.length())
                MediaEngine.convert(input, output, GIF, options, 140_000L) {}
                true
            }
            assertEquals(true, read)
            assertTrue(output.length() in 1L..140_000L)
            assertTrue(com.morph.app.domain.graph.ImageStructure.animated(output, GIF))
            assertEquals(1.0, MediaEngine.probe(output).duration.toDouble(), 0.2)
        } finally {
            sourceDir.deleteRecursively()
        }
    }
    @Test fun batchPreservesOrderAndAllPages()=runBlocking {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val sources=org.json.JSONArray()
        repeat(50) {i->
            val file=File(dir,"page-$i.png");val bitmap=Bitmap.createBitmap(40+i,40,Bitmap.Config.ARGB_8888)
            try {bitmap.eraseColor(Color.rgb(i,120,200));file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {bitmap.recycle()}
            sources.put(org.json.JSONObject().put("uri",android.net.Uri.fromFile(file).toString()).put("name",file.name))
        }
        val folder=File(dir,"batch").apply {mkdirs()}
        val output=BatchEngine.combine(ctx,sources,folder,PDF,ProOptions(pageSize="Fit",margin=0),SizeTarget(2_000_000),{})
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(output).use {doc->
            assertEquals(50,doc.numberOfPages);assertEquals(89f,doc.getPage(49).mediaBox.width,0f)
        }
    }
    @Test fun remuxPreservesBothAudioTracks()=runBlocking {
        val input=File(dir,"three-streams.mkv")
        MediaEngine.run(listOf("-f","lavfi","-i","testsrc2=size=160x120:rate=8:duration=1",
            "-f","lavfi","-i","sine=frequency=330:duration=1","-f","lavfi","-i","sine=frequency=660:duration=1",
            "-map","0:v","-map","1:a","-map","2:a","-c:v","libx264","-pix_fmt","yuv420p","-c:a","aac",input.path))
        val output=File(dir,"output.mp4")
        val stages=mutableListOf<String>()
        MediaEngine.convert(input,output,MP4,ProOptions(),null) { stages+=it }
        assertTrue(stages.toString(),stages.any { "Lossless remux" in it })
        assertEquals(2,MediaEngine.probe(output).streams.count { it.type=="audio" })
        SemanticOutputValidator.verify(output,MP4,ProOptions(),input,MKV)
    }

    @Test fun archiveFamiliesRoundTrip()=runBlocking {
        val sample=File(dir,"note.txt").apply {writeText("A repeatable archive payload\n".repeat(50))}
        for(target in listOf(ZIP,TAR,TGZ,TBZ,TXZ,GZ,BZ,XZ,SEVEN)) {
            val archive=convert(sample,TXT,target);val result=ArchiveEngine.unpack(archive,target,File(dir,"extract-"+target.name),ProOptions())
            assertEquals(target.label,sample.readText(),result.single().second.readText())
        }
    }
    @Test fun audioEncoderMatrix()=runBlocking {
        val source=File(dir,"source.wav")
        MediaEngine.run(listOf("-f","lavfi","-i","sine=frequency=440:duration=1","-c:a","pcm_s16le",source.path))
        val failed=mutableListOf<String>()
        com.morph.app.domain.graph.FormatRegistry.audio.forEach {fmt ->
            runCatching {val result=convert(source,WAV,fmt);check(com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(result.path).mediaInformation.streams.any {it.type=="audio"})}
                .onFailure {failed+=fmt.label+" "+it.message}
        };assertTrue(failed.joinToString("\n"),failed.isEmpty())
    }
    @Test fun videoContainerMatrix()=runBlocking {
        val source=File(dir,"source.mp4")
        MediaEngine.run(listOf("-f","lavfi","-i","testsrc2=size=176x144:rate=10:duration=1","-c:v","libx264",source.path))
        val failed=mutableListOf<String>()
        com.morph.app.domain.graph.FormatRegistry.videos.forEach {fmt->
            runCatching {val out=convert(source,MP4,fmt);check(com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(out.path).mediaInformation.streams.any {it.type=="video"})}
                .onFailure {failed+=fmt.label+" "+it.message}
        };assertTrue(failed.joinToString("\n"),failed.isEmpty())
    }
    @Test fun imageEncoderMatrix()=runBlocking {
        val source=File(dir,"original.png")
        val b=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
        try {b.eraseColor(Color.RED);source.outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {b.recycle()}
        val failed=mutableListOf<String>()
        com.morph.app.domain.graph.FormatRegistry.stillOutputs.filter { it.supportsEncodingOn(android.os.Build.VERSION.SDK_INT) }.forEach {fmt->
            runCatching {val out=convert(source,PNG,fmt);val decoded=ImageEngine.bitmap(out,fmt,ProOptions());check(decoded.width>0);decoded.recycle()}
                .onFailure {failed+=fmt.label+" "+it.message}
        };assertTrue(failed.joinToString("\n"),failed.isEmpty())
    }
    @Test fun editableDocumentsRoundTrip()=runBlocking {
        val sample=File(dir,"notes.txt").apply {writeText("Conversion readback\nSecond paragraph")}
        for(target in listOf(DOCX,ODT,EPUB,RTF,HTML)) {
            val packed=convert(sample,TXT,target);val result=convert(packed,target,TXT)
            assertTrue(target.label,result.readText().contains("Conversion readback"))
        }
    }
    @Test fun nativeMedia()=runBlocking {
        val wav=File(dir,"input.wav")
        MediaEngine.run(listOf("-f","lavfi","-i","sine=frequency=440:duration=1","-c:a","pcm_s16le",wav.path))
        val mp3=convert(wav,WAV,MP3,SizeTarget(24000)); assertTrue(mp3.length() in 1..24000)
    }
    @Test fun imageAndPdf()=runBlocking {
        val source=File(dir,"photo.png")
        val b=Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888)
        try {Canvas(b).drawColor(Color.BLUE);source.outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)} } finally {b.recycle()}
        val jpeg=convert(source,PNG,JPG,SizeTarget(4000,true)); assertEquals(4000L,jpeg.length()); assertEquals(80,BitmapFactory.decodeFile(jpeg.path).width)
        val pdf=convert(source,PNG,PDF);val image=convert(pdf,PDF,PNG);assertNotNull(BitmapFactory.decodeFile(image.path))
    }
    @Test fun workbookRoundTrip()=runBlocking {
        val source=File(dir,"legacy.xls")
        HSSFWorkbook().use {book->
            val s=book.createSheet("Accounts");val row=s.createRow(0);row.createCell(0).setCellValue(2.5);row.createCell(1).cellFormula="A1*2"
            book.createSheet("Notes");source.outputStream().use(book::write)
        }
        val modern=convert(source,XLS,XLSX);val legacy=convert(modern,XLSX,XLS)
        WorkbookFactory.create(legacy,null,true).use {book->assertEquals(2,book.numberOfSheets);assertEquals("A1*2",book.getSheetAt(0).getRow(0).getCell(1).cellFormula)}
    }
    @Test fun archiveAndData()=runBlocking {
        val source=File(dir,"rows.csv").apply {writeText("Name,Value\nAda,7\n")}
        val json=convert(source,CSV,JSON);assertEquals("Ada",SpreadsheetEngine.mapper.readTree(json)[0]["Name"].asText())
        val zip=convert(source,CSV,ZIP); ZipFile(zip).use {assertEquals("Name,Value\nAda,7\n",it.getInputStream(it.entries().nextElement()).reader().readText())}
    }
}
