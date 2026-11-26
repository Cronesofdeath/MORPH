package com.morph.app.data

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import androidx.core.content.FileProvider
import androidx.work.*
import com.morph.app.domain.model.*
import com.morph.app.engine.transfer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID
import org.json.JSONObject

val Context.preferences by preferencesDataStore("morph")

const val SAVING_OUTPUT = "Verified · Saving to output folder…"
const val SAVED_OUTPUT = "Verified · Saved to output folder"

@Entity data class ConversionJob(@PrimaryKey val id:String, val uri:String,val name:String,val source:String,val target:String,
    val original:Long,val budget:Long,val exact:Boolean,val options:String,val batch:String,
    val status:String="Queued",val stage:String="",val output:String="",val bytes:Long=0,val hash:String="",val minimum:Long=0,
    val created:Long=System.currentTimeMillis())
@Entity data class Recipe(@PrimaryKey val name:String,val target:String,val budget:Long,val exact:Boolean,val options:String)

@Dao interface MorphDao {
    @Query("SELECT * FROM ConversionJob ORDER BY created DESC") fun jobs(): Flow<List<ConversionJob>>
    @Query("SELECT * FROM ConversionJob WHERE id=:id") suspend fun job(id:String): ConversionJob?
    @Query("UPDATE ConversionJob SET status = :status, stage = :stage, minimum = :minimum WHERE id = :id AND status IN ('Queued', 'Running')")
    suspend fun updateActive(id: String, status: String, stage: String, minimum: Long = 0): Int
    @Query("UPDATE ConversionJob SET status = 'Complete', source = :source, stage = :stage, original = :original, output = :output, bytes = :bytes, hash = :hash WHERE id = :id AND status = 'Running'")
    suspend fun complete(id: String, source: String, original: Long, output: String, bytes: Long, hash: String, stage: String = "Verified"): Int
    @Query("UPDATE ConversionJob SET stage = :stage WHERE id = :id AND status = 'Complete'")
    suspend fun updateCompletedStage(id: String, stage: String)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun save(job:ConversionJob)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun recipe(recipe:Recipe)
    @Query("SELECT * FROM Recipe ORDER BY name") fun recipes(): Flow<List<Recipe>>
}
@Database(entities=[ConversionJob::class,Recipe::class],version=1)
abstract class MorphDatabase:RoomDatabase() { abstract fun dao():MorphDao }

data class Selection(val uri:String,val name:String,val size:Long,val format:Format,val mismatch:Boolean=false,val detail:String="",val animated:Boolean=false,val multiPage:Boolean=false)
fun ProOptions.encode()=JSONObject().apply {
    put("filename",filename); put("width",maxWidth); put("height",maxHeight); put("dpi",dpi); put("keep",keepMetadata)
    put("codec",videoCodec); put("fps",fps); put("mute",mute); put("start",trimStart); put("end",trimEnd)
    put("fast",fastStart); put("rate",sampleRate); put("channels",channels); put("norm",normalize); put("sheet",sheet)
    put("delimiter",delimiter.toString()); put("page",pageSize); put("landscape",landscape); put("range",pageRange)
    put("level",compression); put("sealedPassword",JobSecret.seal(password)); put("sealedInputPassword", JobSecret.seal(inputPassword))
    put("hash",sha256); put("margin",margin)
}.toString()
fun decodeOptions(value:String):ProOptions = JSONObject(value.ifBlank { "{}" }).let {
    val password = if (it.has("sealedPassword")) JobSecret.open(it.optString("sealedPassword")) else it.optString("password")
    val inputPassword = if (it.has("sealedInputPassword")) JobSecret.open(it.optString("sealedInputPassword")) else password
    ProOptions(filename=it.optString("filename","{name}_morph.{ext}"),maxWidth=it.optInt("width").takeIf { n->n>0 },maxHeight=it.optInt("height").takeIf { n->n>0 },
        dpi=it.optInt("dpi",150),keepMetadata=it.optBoolean("keep"),videoCodec=it.optString("codec"),fps=it.optInt("fps").takeIf { n->n>0 },
        mute=it.optBoolean("mute"),trimStart=it.optDouble("start").takeIf(Double::isFinite),trimEnd=it.optDouble("end").takeIf(Double::isFinite),
        fastStart=it.optBoolean("fast",true),sampleRate=it.optInt("rate").takeIf { n->n>0 },channels=it.optInt("channels").takeIf { n->n>0 },
        normalize=it.optBoolean("norm"),sheet=it.optString("sheet"),delimiter=it.optString("delimiter",",").firstOrNull() ?: ',',
        pageSize=it.optString("page","A4"),landscape=it.optBoolean("landscape"),pageRange=it.optString("range"),compression=it.optInt("level",9),
        password=password,sha256=it.optBoolean("hash",true),margin=it.optInt("margin",36),inputPassword=inputPassword)
}

object StorageManager {
    private val invalidFilename = Regex("""[\p{Cntrl}:*?"<>|]""")

    fun safe(name: String): String {
        val clean = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(invalidFilename, "_").trim('.', ' ')
        val result = StringBuilder()
        var position = 0
        var bytes = 0
        while (position < clean.length) {
            val codePoint = clean.codePointAt(position)
            val width = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (bytes + width > 220) break
            result.appendCodePoint(codePoint)
            bytes += width
            position += Character.charCount(codePoint)
        }
        return result.toString().trimEnd('.', ' ').ifBlank { "file" }
    }
    fun uri(context:Context,file: File):Uri = FileProvider.getUriForFile(context,context.packageName+".files",file)
    suspend fun save(context:Context,file:File):Uri {
        if (Build.VERSION.SDK_INT < 29) throw IllegalArgumentException("Choose a destination with the file picker")
        val resolver=context.contentResolver
        val prefs=context.preferences.data.first()
        val tree=prefs[androidx.datastore.preferences.core.stringPreferencesKey("outputTree")]
        if(!tree.isNullOrBlank()) {
            val folder=Uri.parse(tree)
            val document=android.provider.DocumentsContract.buildDocumentUriUsingTree(folder,android.provider.DocumentsContract.getTreeDocumentId(folder))
            val created=android.provider.DocumentsContract.createDocument(resolver,document,Format.named(file.name)?.mime ?: "application/octet-stream",file.name) ?: error("Cannot create file in the output folder")
            try {resolver.openOutputStream(created)!!.use {out->file.inputStream().use {it.transfer(out)}};return created}
            catch(e:Throwable) {android.provider.DocumentsContract.deleteDocument(resolver,created);throw e}
        }
        val values=ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME,file.name); put(MediaStore.Downloads.MIME_TYPE,Format.named(file.name)?.mime ?: "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH,"Download/MORPH"); put(MediaStore.Downloads.IS_PENDING,1) }
        val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values) ?: error("Cannot create download")
        try {
            resolver.openOutputStream(uri)!!.use { out->file.inputStream().use { it.transfer(out) } }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING,0); resolver.update(uri,values,null,null); return uri
        } catch(e:Throwable) { resolver.delete(uri,null,null); throw e }
    }
}
