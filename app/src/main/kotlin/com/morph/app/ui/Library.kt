package com.morph.app.ui

import android.content.*
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.datastore.preferences.core.*
import coil3.compose.AsyncImage
import com.morph.app.StudioModel
import com.morph.app.data.*
import com.morph.app.domain.model.*
import com.morph.app.domain.graph.*
import com.morph.app.engine.*
import kotlinx.coroutines.*
import java.io.File

private val dismissedQueueJobs = stringSetPreferencesKey("dismissedQueueJobs")
private val clearedVaultJobs = stringSetPreferencesKey("clearedVaultJobs")
private val finishedJobStatuses = setOf("Complete", "Failed", "Cancelled")

@Composable fun JobsPage(choose:()->Unit,jobs:List<ConversionJob>,model:StudioModel,binary:Boolean,message:(String)->Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences by context.preferences.data.collectAsState(initial = emptyPreferences())
    val dismissed = preferences[dismissedQueueJobs].orEmpty()
    val visibleJobs = jobs.filter { it.id !in dismissed || it.status !in finishedJobStatuses }
    val finishedIds = visibleJobs.filter { it.status in finishedJobStatuses }.map { it.id }

    fun dismiss(ids: Collection<String>) {
        scope.launch {
            context.preferences.edit { current ->
                current[dismissedQueueJobs] = current[dismissedQueueJobs].orEmpty() + ids
            }
        }
    }

    Page("Queue", "Live conversions") {
        if (finishedIds.isNotEmpty()) {
            OutlinedButton({ dismiss(finishedIds) }) { Text("Clear finished") }
        }
        if(visibleJobs.isEmpty()) Column(Modifier.fillMaxWidth().heightIn(min=320.dp),horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
            Icon(Icons.Outlined.Layers,null,Modifier.size(42.dp))
            Text("Your queue is empty",fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=12.dp))
            Button(choose) { Text("Choose files") }
        }
        visibleJobs.groupBy {it.batch}.forEach { (_,group)->
            if(group.size>1 && group.all {it.status=="Complete"}) OutlinedButton({scope.launch {model.bundle(group)}}) {Text("Bundle ${group.size} outputs as ZIP")}
            group.forEach { job ->
                key(job.id) {
                    val remove: (() -> Unit)? = if (job.status in finishedJobStatuses) {
                        { dismiss(listOf(job.id)) }
                    } else null
                    JobCard(job, model, binary, message, remove)
                }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun JobCard(job:ConversionJob,model:StudioModel,binary:Boolean,message:(String)->Unit,remove:(()->Unit)?=null) {
    val ctx=LocalContext.current; val scope=rememberCoroutineScope()
    val file=File(job.output); var inspect by remember { mutableStateOf(false) }
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Format.named(file.name)?.mime ?: "application/octet-stream")) { uri ->
        if(uri!=null) scope.launch { runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.transfer(out) } } } }.onSuccess { message("File saved") }.onFailure { message(it.message ?: "Save failed") } }
    }
    fun launch(send:Boolean) {
        runCatching { val uri=StorageManager.uri(ctx,file); val mime=Format.named(file.name)?.mime ?: "application/octet-stream"
            val intent=if(send) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri) else Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); intent.clipData=ClipData.newRawUri(file.name,uri)
            ctx.startActivity(if(send) Intent.createChooser(intent,"Share output") else intent)
        }.onFailure {message("No compatible app could open this file")}
    }
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(job.status+"  ·  "+job.target)
                Text(job.name,fontWeight=FontWeight.Bold)
            }
            if (remove != null) TextButton(remove) { Text("Remove") }
        }
        if(job.status=="Running") LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(job.stage.ifBlank { "Waiting for a worker" },style=mono)
        if(job.status=="Complete") {
            Text(bytes(job.original,binary)+" → "+bytes(job.bytes,binary),style=mono)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton({launch(false)}) {Text("Open")}; TextButton({launch(true)}) {Text("Share")}
                TextButton({if(Build.VERSION.SDK_INT<29) create.launch(file.name) else scope.launch {
                    runCatching { withContext(Dispatchers.IO) { StorageManager.save(ctx,file) } }.onSuccess {message("Saved to output folder")}.onFailure {message(it.message ?: "Save failed")}
                }}) {Text("Save")}; TextButton({inspect=true}) {Text("Inspect")}; TextButton({scope.launch { model.rerun(job) }}) {Text("Re-run")}
            }
        } else if(job.status in setOf("Queued","Running")) TextButton({model.cancel(job)}) {Text("Cancel")}
        else if(job.minimum>0) Button({scope.launch { model.rerun(job,job.minimum) }}) {Text("Use ${bytes(job.minimum,binary)}")}
        else TextButton({scope.launch {model.rerun(job)}}) { Text("Try again") }
    }
    if(inspect) AlertDialog(onDismissRequest={inspect=false},title={Text("Before / after")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(job.source+" → "+job.target,style=mono)
            Text("${job.original} B → ${job.bytes} B",style=mono)
            if(Format.named(file.name)?.category==Category.IMAGE && Format.valueOf(job.source).category==Category.IMAGE) {
                var fraction by remember {mutableFloatStateOf(.5f)}
                Box(Modifier.fillMaxWidth().height(180.dp).clipToBounds()) {
                    AsyncImage(file,"Converted",Modifier.fillMaxSize())
                    AsyncImage(Uri.parse(job.uri),"Original",Modifier.fillMaxSize().drawWithContent {clipRect(right=size.width*fraction) {this@drawWithContent.drawContent()} })
                }
                val state=remember { SliderState(.5f) }
                Slider(state=state,onValueChange={state.value=it;fraction=it},onValueChangeFinished={})
            }
            if(Format.named(file.name) in listOf(Format.TXT,Format.JSON,Format.CSV,Format.TSV,Format.HTML,Format.XML,Format.MD) && !job.uri.startsWith("batch:")) {
                val pair by produceState<Pair<String,String>?>(null,job.id) {
                    value=withContext(Dispatchers.IO) {
                        fun sample(input:java.io.InputStream?):String=input?.bufferedReader()?.use {reader->val buffer=CharArray(1600);val n=reader.read(buffer);if(n>0) String(buffer,0,n) else ""}.orEmpty()
                        runCatching {sample(ctx.contentResolver.openInputStream(Uri.parse(job.uri))) to sample(file.inputStream())}.getOrNull()
                    }
                }
                pair?.let {(before,after)->Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.weight(1f)) {Label("Original");Text(before,style=mono,maxLines=12)}
                    Column(Modifier.weight(1f)) {Label("Converted");Text(after,style=mono,maxLines=12)}
                }}
            }
            if(job.hash.isNotBlank()) {Label("SHA-256"); Text(job.hash,style=mono)}
            if(!job.uri.startsWith("batch:")) {
                val audit by produceState("",job.id) {value=withContext(Dispatchers.IO) {
                    val before=Uri.parse(job.uri)
                    val details=listOf(FileInspector.detail(ctx,before,Format.valueOf(job.source)),FileInspector.detail(ctx,Uri.fromFile(file),Format.named(file.name) ?: Format.RAW))
                    val hash=if(job.hash.isNotEmpty()) runCatching {ctx.contentResolver.openInputStream(before)?.use {FileInspector.hash(it)}}.getOrNull() else null
                    listOfNotNull(details.first().takeIf(String::isNotBlank)?.let {"Original · $it"},details.last().takeIf(String::isNotBlank)?.let {"Converted · $it"},hash?.let {"Original SHA-256\n$it"}).joinToString("\n")
                }}
                if(audit.isNotBlank()) Text(audit,style=mono)
            }
            Text(file.name)
        }
    },confirmButton={TextButton({inspect=false}) {Text("Done")}})
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Vault(jobs:List<ConversionJob>,recipes:List<Recipe>,model:StudioModel,binary:Boolean,apply:(Recipe)->Unit,message:(String)->Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences by context.preferences.data.collectAsState(initial = emptyPreferences())
    val clearedJobs = preferences[clearedVaultJobs].orEmpty()
    val history = jobs.filter { it.status == "Complete" && it.id !in clearedJobs }
    var tab by rememberSaveable { mutableStateOf("History") }; var search by rememberSaveable {mutableStateOf("")}
    Page("Vault","Outputs and recipes") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("History","Recipes").forEachIndexed { i,name ->
                SegmentedButton(tab==name,{tab=name},SegmentedButtonDefaults.itemShape(i,2)) {Text(name)}
            }
        }
        if(tab=="History") {
            OutlinedTextField(search,{search=it},shape=RoundedCornerShape(18.dp),leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon={if(search.isNotEmpty()) IconButton({search=""}) {Icon(Icons.Outlined.Close,"Clear search")}},label={Text("Search files")},singleLine=true,modifier=Modifier.fillMaxWidth())
            if (history.isNotEmpty()) {
                OutlinedButton({
                    val ids = history.map { it.id }
                    scope.launch {
                        context.preferences.edit { current ->
                            current[clearedVaultJobs] = current[clearedVaultJobs].orEmpty() + ids
                        }
                        message("History cleared. Saved files and recipes are kept.")
                    }
                }) { Text("Clear history") }
            }
            val hits = history.filter { it.name.contains(search, true) }
            if(hits.isEmpty()) Column(Modifier.fillMaxWidth().heightIn(min=240.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                Icon(Icons.Outlined.Inventory2,null,Modifier.size(42.dp))
                Text(if(search.isBlank()) "Completed files appear here." else "No matching files.",Modifier.padding(top=12.dp))
            }
            hits.forEach { key(it.id) {JobCard(it,model,binary,message)} }
        } else {
            val defaults=listOf(Recipe("Legacy Excel to XLSX","XLSX",0,false,"{}"),Recipe("Exam portal photo","JPG",50*1024,false,"{}"),Recipe("Discord video","MP4",(9.8*1024*1024).toLong(),false,"{}"),Recipe("HEIC to JPEG","JPG",0,false,"{}"))
            (recipes+defaults).distinctBy {it.name}.forEach { r -> Panel { Label(r.target); Text(r.name,fontWeight=FontWeight.Bold)
                TextButton({apply(r)}) {Text("Use recipe")} } }
        }
    }
}
@Composable fun Settings(mode:String,dynamic:Boolean,binary:Boolean) {
    val ctx=LocalContext.current; val scope=rememberCoroutineScope()
    var search by rememberSaveable {mutableStateOf("")}; var source by rememberSaveable {mutableStateOf(Format.XLS)}
    var health by rememberSaveable {mutableStateOf("")}
    val graph=remember {ConversionGraphRouter()}
    val prefs by ctx.preferences.data.collectAsState(initial=emptyPreferences())
    val tree=prefs[stringPreferencesKey("outputTree")].orEmpty()
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri->if(uri!=null) scope.launch {
        ctx.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        ctx.preferences.edit {it[stringPreferencesKey("outputTree")]=uri.toString()}
    }}
    var storage by remember {mutableStateOf(emptyList<Pair<String,Long>>())}
    var refresh by remember {mutableIntStateOf(0)}
    LaunchedEffect(refresh,binary) {storage=withContext(Dispatchers.IO) {
        fun total(file:File)=file.walkTopDown().filter {it.isFile}.sumOf {it.length()}
        listOf("Saved outputs" to total(File(ctx.filesDir,"exports")),"Imported copies" to total(File(ctx.filesDir,"imports")),"Working files" to total(File(ctx.cacheDir,"morph_work")))
    } }
    Page("Settings","Appearance and diagnostics") {
        Panel {
            Label("About MORPH")
            val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            Text("Version $version",style=mono)
            Text("MORPH processes files on-device. The app has no Internet permission and includes no MORPH telemetry.")
            Text("GPL-3.0 source. Bundled libraries include FFmpegKit, Apache POI, PDFBox and Google ML Kit, which has its own terms.")
            val links = listOf(
                "Source and GPL-3.0 license" to "https://github.com/Cronesofdeath/MORPH",
                "Third-party licenses" to "https://github.com/Cronesofdeath/MORPH/blob/main/THIRD_PARTY_NOTICES.md",
                "Privacy policy" to "https://github.com/Cronesofdeath/MORPH/blob/main/PRIVACY.md"
            )
            links.forEach { (label,url) ->
                TextButton({
                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }
                    catch (_: ActivityNotFoundException) { scope.launch { android.widget.Toast.makeText(ctx,url,android.widget.Toast.LENGTH_LONG).show() } }
                }) { Text(label) }
            }
        }
        Panel {Label("Appearance")
            Choices(listOf("System","Light","Dark","OLED"),mode) { choice -> scope.launch {ctx.preferences.edit {it[stringPreferencesKey("theme")]=choice}}}
            if(Build.VERSION.SDK_INT>=31) Toggle("Dynamic color",dynamic) {v->scope.launch {ctx.preferences.edit {it[booleanPreferencesKey("dynamic")]=v}}}
            Toggle("Binary size units (IEC)",binary) { v->scope.launch {ctx.preferences.edit {it[booleanPreferencesKey("binary")]=v}} }
        }
        Panel {Label("Storage")
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                ListItem(headlineContent={Text("Output folder")},supportingContent={Text(if(tree.isEmpty()) "Downloads/MORPH" else Uri.decode(tree.substringAfterLast('/')))},leadingContent={Icon(Icons.Outlined.FolderOpen,null)},trailingContent={Icon(Icons.Outlined.ChevronRight,null)},modifier=Modifier.weight(1f).clickable {picker.launch(null)})
                if(tree.isNotEmpty()) TextButton({scope.launch {ctx.preferences.edit {it.remove(stringPreferencesKey("outputTree"))}}}) {Text("Reset")}}
            Toggle("Save completed files automatically",prefs[booleanPreferencesKey("autoSave")] ?: (Build.VERSION.SDK_INT>=29)) {v->scope.launch {ctx.preferences.edit {it[booleanPreferencesKey("autoSave")]=v}}}
            storage.forEach { (name,size) -> Row(Modifier.fillMaxWidth()) { Text(name,Modifier.weight(1f)); Text(bytes(size,binary),style=mono) } }
            val total=storage.sumOf { it.second }
            Row(Modifier.fillMaxWidth().height(5.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                storage.forEachIndexed { i, item ->
                    if(item.second>0 && total>0) Spacer(Modifier.weight((item.second.toDouble()/total).toFloat()).fillMaxHeight().background(listOf(MaterialTheme.colorScheme.primary,MaterialTheme.colorScheme.secondary,MaterialTheme.colorScheme.tertiary)[i]))
                }
            }
            val age=prefs[intPreferencesKey("cacheDays")] ?: 7
            Label("Clear unused imports after")
            Choices(listOf("1 day","7 days","30 days"),if(age==1) "1 day" else "$age days") {v->scope.launch {ctx.preferences.edit {it[intPreferencesKey("cacheDays")]=v.substringBefore(' ').toInt()}}}
            ListItem(headlineContent={Text("Clear thumbnail cache")},supportingContent={Text("Remove cached file previews")},leadingContent={Icon(Icons.Outlined.CleaningServices,null)},trailingContent={Icon(Icons.Outlined.ChevronRight,null)},modifier=Modifier.fillMaxWidth().clickable { scope.launch {withContext(Dispatchers.IO) {coil3.SingletonImageLoader.get(ctx).diskCache?.clear()}; refresh++} })
        }
        Panel {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Label("Engine health"); Text(if(health.isBlank()) "Unchecked" else "Checked",style=mono) }
            Button({scope.launch {health=withContext(Dispatchers.IO) {
                listOf("FFmpeg" to { com.arthenica.ffmpegkit.FFmpegKitConfig.getFFmpegVersion() },
                    "PDFBox" to { com.tom_roush.pdfbox.pdmodel.PDDocument().use { "Ready" } },
                    "Excel" to { org.apache.poi.hssf.usermodel.HSSFWorkbook().use {it.createSheet("Check"); "Ready"} }).joinToString("\n") { (name,check) -> "$name  ·  "+runCatching(check).getOrElse {it.message ?: "Unavailable"} }
            } }}) {Text("Check engines")}
            if(health.isNotEmpty()) Text(health,style=mono)
        }
        Panel {Label("Supported format graph explorer")
            OutlinedTextField(search,{search=it},label={Text("Find a source format")},singleLine=true)
            val formats=Format.entries.filter {it.label.contains(search,true)}
            Choices(formats.map {it.label},source.label) {s->source=formats.first {it.label==s}}
            Text("From ${source.label}",fontWeight=FontWeight.Bold)
            Text(graph.targets(source).filter { it.supportsEncodingOn(Build.VERSION.SDK_INT) }.joinToString("  ·  "){it.label},style=mono)
        }
    }
}
