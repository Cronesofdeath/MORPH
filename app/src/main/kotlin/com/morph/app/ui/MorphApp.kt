package com.morph.app.ui

import android.content.*
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.datastore.preferences.core.*
import androidx.navigation.compose.*
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.ui.draw.clipToBounds
import com.morph.app.*
import com.morph.app.data.*
import com.morph.app.domain.graph.*
import com.morph.app.domain.model.*
import kotlinx.coroutines.*
import java.io.File

@Composable fun MorphApp(model:StudioModel) {
    val context=LocalContext.current
    val prefs by context.preferences.data.collectAsState(initial=emptyPreferences())
    val mode=prefs[stringPreferencesKey("theme")] ?: "System"
    val dark=mode in listOf("Dark","OLED") || mode=="System" && isSystemInDarkTheme()
    val dynamic=prefs[booleanPreferencesKey("dynamic")] ?: false
    val colors=when {
        dynamic && Build.VERSION.SDK_INT>=31 -> if(dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary=Color(0xff72a4ff),primaryContainer=Color(0xff172554),secondary=Color(0xff93c5fd),secondaryContainer=Color(0xff1e3a5f),onSecondaryContainer=Color(0xffdbeafe),tertiary=Color(0xff34d399),surface=Color(0xff16191e),background=Color(0xff0b0d10),outlineVariant=Color(0xff343a44))
        else -> lightColorScheme(primary=Color(0xff2563eb),primaryContainer=Color(0xffdbeafe),secondary=Color(0xff25549a),secondaryContainer=Color(0xffdbeafe),onSecondaryContainer=Color(0xff172554),tertiary=Color(0xff047857),background=Color(0xfff8f9fa),surface=Color.White,outlineVariant=Color(0xffd9dfe6))
    }.let { if(mode=="OLED") it.copy(background=Color.Black,surface=Color.Black) else it }
    MaterialTheme(colorScheme=colors) {
        val scope=rememberCoroutineScope(); val nav=rememberNavController()
        val active by nav.currentBackStackEntryAsState()
        val tabs=listOf("Convert","Queue","Vault","Settings")
        val icons=listOf(Icons.Outlined.Tune,Icons.Outlined.Layers,Icons.Outlined.Inventory2,Icons.Outlined.Settings)
        val route=active?.destination?.route ?: tabs.first()
        val snack=remember { SnackbarHostState() }; val haptic=LocalHapticFeedback.current
        val files by model.files.collectAsState(); val jobs by model.jobs.collectAsState(); val recipes by model.recipes.collectAsState()
        var observed by remember {mutableStateOf<Set<String>?>(null)}
        LaunchedEffect(jobs) {
            val complete=jobs.filter {it.status=="Complete"}.map {it.id}.toSet()
            if(observed!=null && (complete-observed.orEmpty()).isNotEmpty()) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
            };observed=complete
        }
        var options by model.studioOptions
        var destination by rememberSaveable { mutableStateOf("") }
        var sizeValue by rememberSaveable { mutableStateOf("100") }; var unit by rememberSaveable { mutableStateOf("KB") }
        var enabled by rememberSaveable { mutableStateOf(false) }; var exact by rememberSaveable { mutableStateOf(false) }
        var shared by rememberSaveable { mutableStateOf(false) }; var combine by rememberSaveable { mutableStateOf(false) }
        val binary=prefs[booleanPreferencesKey("binary")] ?: true
        fun changeTab(name:String) { nav.navigate(name) { popUpTo(nav.graph.findStartDestination().id) { saveState=true }; launchSingleTop=true; restoreState=true } }
        fun message(text:String) { scope.launch { snack.showSnackbar(text) } }
        fun applyRecipe(r:Recipe) { destination=r.target; options=decodeOptions(r.options); exact=r.exact; enabled=r.budget>0; sizeValue=r.budget.toString(); unit="B"; changeTab("Convert") }
        LaunchedEffect(model) { model.events.collect { snack.showSnackbar(it) } }
        Scaffold(containerColor=colors.background,snackbarHost={SnackbarHost(snack)},bottomBar={
            NavigationBar(containerColor=colors.surface) { tabs.forEachIndexed { i,name ->
                NavigationBarItem(route==name,{changeTab(name)},modifier=Modifier.testTag("tab-$name"),icon={Icon(icons[i],name)},label={Text(name)})
            } }
        }) { inset ->
            Surface(Modifier.fillMaxSize().padding(inset).consumeWindowInsets(inset).clipToBounds(),color=colors.background) {
            NavHost(nav,"Convert",Modifier.fillMaxSize(),enterTransition={EnterTransition.None},exitTransition={ExitTransition.None},popEnterTransition={EnterTransition.None},popExitTransition={ExitTransition.None}) {
                composable("Convert") {
                    val router=remember { ConversionGraphRouter() }
                    val targets=remember(files) {
                        files.firstOrNull()?.let { first -> router.targets(first.format).filter { goal ->
                            goal.supportsEncodingOn(Build.VERSION.SDK_INT) && files.drop(1).all { router.route(it.format,goal)!=null }
                        } }.orEmpty()
                    }
                    val selected=targets.find { it.name==destination } ?: targets.firstOrNull()
                    val canCombine=files.size>1 && (selected in FormatRegistry.archives-FormatRegistry.singleStreams || selected==Format.PDF && files.all {it.format.category==Category.IMAGE})
                    val budget=SizeUnitCalculator.bytes(sizeValue,SizeUnit.valueOf(unit),binary)
                    val view=LocalView.current
                    DisposableEffect(view) {
                        view.setOnDragListener { _,event->
                            when(event.action) {
                                android.view.DragEvent.ACTION_DRAG_STARTED -> event.clipDescription!=null
                                android.view.DragEvent.ACTION_DROP -> {
                                    val permission=(context as? android.app.Activity)?.requestDragAndDropPermissions(event)
                                    val items=(0 until event.clipData.itemCount).map {event.clipData.getItemAt(it)}
                                    val uris=items.mapNotNull {it.uri}
                                    if(uris.isNotEmpty()) model.pick(uris,false,permission) else items.firstOrNull()?.text?.toString()?.let(model::paste)
                                    true
                                }
                                else -> true
                            }
                        }
                        onDispose {view.setOnDragListener(null)}
                    }
                    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if(it.isNotEmpty()) model.pick(it) }
                    val folder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(model::folder) }
                    val notify=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                    var showFormats by remember { mutableStateOf(false) }; var lab by rememberSaveable { mutableStateOf(false) }
                    var recipeName by rememberSaveable { mutableStateOf("") }
                    val importing by model.importing.collectAsState()
                    Page("Convert","Studio workbench") {
                        Surface(onClick={picker.launch(arrayOf("*/*"))},shape=RoundedCornerShape(24.dp),color=Color(0xff2458d8)) {
                            Box(Modifier.fillMaxWidth().height(180.dp)) {
                                DropArt(Modifier.align(Alignment.CenterEnd).fillMaxWidth(.55f).fillMaxHeight())
                                Column(Modifier.align(Alignment.CenterStart).padding(22.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Outlined.AddCircleOutline,"Add",tint=Color.White,modifier=Modifier.size(30.dp))
                                    Text(if(importing) "Reading files…" else "Choose files",color=Color.White,fontSize=26.sp,fontWeight=FontWeight.Bold)
                                    Text("Any format. Exact size.\nZero cloud.",style=mono,color=Color.White)
                                }
                            }
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            OutlinedButton({folder.launch(null)},Modifier.weight(1f)) { Icon(Icons.Outlined.FolderOpen,null); Spacer(Modifier.width(6.dp)); Text("Import folder") }
                            OutlinedButton({
                                val clip=context.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount>0 }?.getItemAt(0)
                                when { clip?.uri!=null -> model.pick(listOf(clip.uri),false); !clip?.text.isNullOrBlank() -> model.paste(clip.text.toString()); else -> message("Clipboard is empty") }
                            },Modifier.weight(1f)) { Icon(Icons.Outlined.ContentPaste,null); Spacer(Modifier.width(6.dp)); Text("Paste") }
                        }
                        if(files.isNotEmpty()) Panel {
                            Label("Source · ${files.size} selected")
                            files.forEach { item -> Row(verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(item.name,fontWeight=FontWeight.SemiBold)
                                    Text(listOf(item.format.label,bytes(item.size,binary),"${item.size} B",item.detail).filter(String::isNotEmpty).joinToString(" · "),style=mono)
                                    if(item.mismatch) Text("Detected ${item.format.label} • routing from the file contents.",color=Color(0xffaa6900),style=mono)
                                }
                                if(files.size>1 && item!=files.first()) IconButton({ val reordered=files.toMutableList(); val at=reordered.indexOf(item); java.util.Collections.swap(reordered,at,at-1); model.files.value=reordered }) {Icon(Icons.Outlined.ArrowUpward,"Move earlier")}
                                IconButton({model.files.value=files-item}) { Icon(Icons.Outlined.Close,"Remove ${item.name}") }
                            } }
                        }
                        files.singleOrNull()?.takeIf { it.mismatch && !enabled && options.permitsCorrection() }?.let { item ->
                            Panel {
                                Label("Lossless Format Correction")
                                Text("Save a byte-identical .${item.format.extension} copy. Original metadata is preserved and both SHA-256 hashes are checked.")
                                Button({ scope.launch {
                                    model.enqueue(item.format,0,false,options,false)
                                    changeTab("Queue")
                                } }) { Text("Save corrected copy") }
                            }
                        }
                        Panel {
                            Label("Output format")
                            if(selected==null) Text("Select a source to see compatible formats.",color=colors.onSurfaceVariant)
                            else {
                                OutlinedButton({showFormats=true},Modifier.fillMaxWidth()) { Text("${selected.label}  ·  Change format") }
                                val path=router.route(files.first().format,selected).orEmpty()
                                PipelineFlowCanvas(listOf(files.first().format.label)+path.map { it.to.label })
                                val warnings = (files.flatMap { ConversionLossAnalyzer.advisories(it.format, selected,it.animated,it.multiPage) } + if(enabled && selected==Format.PDF) listOf("A tight PDF budget can rasterize pages and remove selectable text.") else emptyList()).distinct()
                                if (warnings.isNotEmpty()) Surface(color=Color(0xffffedc2),contentColor=Color(0xff553600),shape=RoundedCornerShape(14.dp)) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text("Before converting",fontWeight=FontWeight.Bold)
                                        warnings.forEach { Text(it) }
                                    }
                                }
                                if(canCombine) Toggle("Combine into one file",combine) {combine=it}
                            }
                        }
                        Panel {
                            Toggle("Exact size target",enabled) { enabled=it }
                            AnimatedVisibility(enabled) { Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(sizeValue,{sizeValue=it},label={Text("Maximum size")},singleLine=true,modifier=Modifier.fillMaxWidth())
                                PrecisionSizeDial(sizeValue) {sizeValue=it}
                                Choices(SizeUnit.entries.map { it.name },unit) { unit=it }
                                Choices(listOf("IEC 1024","SI 1000"),if(binary) "IEC 1024" else "SI 1000") { scope.launch { context.preferences.edit { p->p[booleanPreferencesKey("binary")]=it=="IEC 1024" } } }
                                if(budget!=null && files.sumOf {it.size}>0L) {
                                    val sourceSize=if(shared) files.sumOf {it.size} else files.maxOf {it.size}
                                    val fraction=(budget.toDouble()/sourceSize.coerceAtLeast(1)).toFloat().coerceIn(0f,1f)
                                    val displayed by androidx.compose.animation.core.animateFloatAsState(fraction,androidx.compose.animation.core.spring(dampingRatio=.75f,stiffness=androidx.compose.animation.core.Spring.StiffnessMediumLow),label="Size budget")
                                    LinearProgressIndicator(progress={displayed},modifier=Modifier.fillMaxWidth())
                                    Text("Estimated quality · "+when {fraction>=1f -> "Full budget"; fraction>.7f->"Crisp";fraction>.35f->"Balanced";fraction>.1f->"Aggressive";else->"Extreme"},style=mono)
                                }
                                Text(budget?.let { "${java.text.NumberFormat.getInstance().format(it)} bytes" } ?: "Enter a positive size",style=mono)
                                val presets=listOf("Exam 50 KB" to ("50" to "KB"),"100 KB" to ("100" to "KB"),"200 KB" to ("200" to "KB"),"Discord 9.8 MB" to ("9.8" to "MB"),"Email 24.5 MB" to ("24.5" to "MB"),"WhatsApp 15.8 MB" to ("15.8" to "MB"),"WhatsApp Doc 99 MB" to ("99" to "MB"),"GitHub 24.5 MB" to ("24.5" to "MB"))
                                Choices(presets.map { it.first },"") { name->val v=presets.first { it.first==name }.second; sizeValue=v.first; unit=v.second }
                                if(files.size>1) Toggle("Share total budget",shared) {shared=it}
                                if(selected!=null && budget!=null && budget<com.morph.app.engine.TargetSizeOrchestrator.minimumHeader(selected)) {
                                    val floor=com.morph.app.engine.TargetSizeOrchestrator.minimumHeader(selected)
                                    Text("This target is below the format’s header allowance of $floor bytes.",color=colors.error)
                                    TextButton({sizeValue=floor.toString();unit="B"}) {Text("Use header allowance")}
                                }
                                if(selected in FormatRegistry.exact) Toggle("Exact-byte lock",exact) {exact=it}
                                else if(selected!=null) Text("Exact-byte padding is unavailable for ${selected.label}. The maximum size still applies.",style=mono)
                            } }
                        }
                        Panel {
                            Row(Modifier.fillMaxWidth().clickable { lab=!lab }.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Tune,null)
                                Column(Modifier.weight(1f).padding(horizontal=12.dp)) {
                                    Text(if(lab) "Close Pro Lab" else "Open Pro Lab",fontWeight=FontWeight.SemiBold)
                                    Text(if(options==ProOptions()) "Default studio settings" else "Custom overrides active",style=mono)
                                }
                                Icon(if(lab) Icons.Outlined.ExpandLess else Icons.Outlined.ChevronRight,null)
                            }
                            if(lab) {
                                ProControls(selected, files.any { it.format in setOf(Format.ZIP, Format.SEVEN) }, options) { options=it }
                                OutlinedTextField(recipeName,{recipeName=it},label={Text("Recipe name")},singleLine=true)
                                TextButton({scope.launch { model.dao.recipe(Recipe(recipeName.trim(),selected!!.name,if(enabled) budget ?: 0 else 0,exact,options.copy(password="", inputPassword="").encode())); message("Recipe saved") }},enabled=recipeName.isNotBlank() && selected!=null) {Text("Save recipe")}
                            }
                        }
                        Button({
                            scope.launch { try {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                model.enqueue(selected!!,if(enabled) budget!! else 0,exact && selected in FormatRegistry.exact,options,shared,combine && canCombine)
                                changeTab("Queue")
                                if(Build.VERSION.SDK_INT>=33) notify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } catch(e:Exception) { message(e.message ?: "Could not queue files") } }
                        },enabled=selected!=null && (!enabled || budget!=null) && !importing,modifier=Modifier.fillMaxWidth().height(54.dp)) { Text(if(files.size>1) "Convert ${files.size} files" else "Convert & compress") }
                    }
                    if(showFormats) FormatPickerSheet(targets,{selectedFormat->destination=selectedFormat.name; showFormats=false},{showFormats=false})
                }
                composable("Queue") { JobsPage({changeTab("Convert")},jobs,model,binary,::message) }
                composable("Vault") { Vault(jobs,recipes,model,binary,::applyRecipe,::message) }
                composable("Settings") { Settings(mode,dynamic,binary) }
            }
            }
        }
    }
}
@Composable fun Page(title:String,subtitle:String,content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=18.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Surface(shape=RoundedCornerShape(7.dp),color=Blue) { Text("M",Modifier.padding(horizontal=7.dp,vertical=2.dp),fontWeight=FontWeight.Black,color=Color.White) }
            Spacer(Modifier.width(7.dp)); Text("MORPH",style=mono.copy(fontWeight=FontWeight.Bold,letterSpacing=2.sp))
        }
        Column { Text(title,fontSize=38.sp,fontWeight=FontWeight.Bold,letterSpacing=(-1.8).sp); Text(subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        content(); Spacer(Modifier.height(24.dp))
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun FormatPickerSheet(items:List<Format>,select:(Format)->Unit,close:()->Unit) {
    var query by remember { mutableStateOf("") }; var category by remember { mutableStateOf("All") }
    val haptic=LocalHapticFeedback.current
    ModalBottomSheet(onDismissRequest=close) { Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Output format",style=MaterialTheme.typography.headlineSmall)
        OutlinedTextField(query,{query=it},label={Text("Search formats")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        Choices(listOf("All")+items.map { it.category.label }.distinct(),category) {category=it}
        Column(Modifier.verticalScroll(rememberScrollState())) {
            items.filter { (category=="All" || it.category.label==category) && (it.label.contains(query,true) || it.mime.contains(query,true)) }.forEach { fmt ->
                TextButton({haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress);select(fmt)},Modifier.fillMaxWidth()) {
                    Text(fmt.label,Modifier.weight(1f),style=mono); Text(fmt.category.label)
                }
            }
        }
    } }
}
@Composable fun ProControls(fmt:Format?, hasArchiveInput:Boolean, opt:ProOptions,set:(ProOptions)->Unit) {
    @Composable fun field(title:String,value:String,change:(String)->Unit) { OutlinedTextField(value,change,label={Text(title)},singleLine=true,modifier=Modifier.fillMaxWidth()) }
    field("Filename template",opt.filename) {set(opt.copy(filename=it))}
    if(fmt in listOf(Format.JSON,Format.XML,Format.TXT,Format.SQL)) field("Worksheet name (for workbook input)",opt.sheet) {set(opt.copy(sheet=it))}
    if (hasArchiveInput) {
        OutlinedTextField(opt.inputPassword, { set(opt.copy(inputPassword = it)) }, label = { Text("Input archive password") },
            singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    }
    when(fmt?.category) {
        Category.IMAGE,Category.VIDEO -> {
            field("Maximum height in pixels",opt.maxHeight?.toString().orEmpty()) {set(opt.copy(maxHeight=it.toIntOrNull()?.coerceIn(1,8192)))}
            if(fmt.category==Category.IMAGE) {
                if(fmt in listOf(Format.JPG,Format.WEBP)) Toggle("Keep EXIF and GPS",opt.keepMetadata) {set(opt.copy(keepMetadata=it))}
                field("Maximum width in pixels",opt.maxWidth?.toString().orEmpty()) {set(opt.copy(maxWidth=it.toIntOrNull()?.coerceIn(1,8192)))}
                field("Render DPI",opt.dpi.toString()) {set(opt.copy(dpi=it.toIntOrNull()?.coerceIn(36,600) ?: 150))}
            }
            if(fmt.category==Category.VIDEO) {
                Choices(listOf("H.264","H.265"),opt.videoCodec.ifEmpty { "H.264" }) {set(opt.copy(videoCodec=it))}
                field("Frames per second",opt.fps?.toString().orEmpty()) {set(opt.copy(fps=it.toIntOrNull()?.coerceIn(1,120)))}
                Toggle("Mute audio",opt.mute) {set(opt.copy(mute=it))}
                field("Trim start (seconds)",opt.trimStart?.toString().orEmpty()) {set(opt.copy(trimStart=it.toDoubleOrNull()?.coerceAtLeast(0.0)))}
                field("Trim end (seconds)",opt.trimEnd?.toString().orEmpty()) {set(opt.copy(trimEnd=it.toDoubleOrNull()?.coerceAtLeast(0.0)))}
                Toggle("Optimize for playback",opt.fastStart) {set(opt.copy(fastStart=it))}
            }
        }
        Category.AUDIO -> { Choices(listOf("Mono","Stereo"),if(opt.channels==1) "Mono" else "Stereo") {set(opt.copy(channels=if(it=="Mono") 1 else 2))}
            field("Sample rate (Hz)",opt.sampleRate?.toString().orEmpty()) {set(opt.copy(sampleRate=it.toIntOrNull()?.coerceIn(8000,96000)))}
            Toggle("Normalize loudness",opt.normalize) {set(opt.copy(normalize=it))} }
        Category.SHEET -> {
            field("Sheet name (blank for all)",opt.sheet) {set(opt.copy(sheet=it))}
            if(fmt==Format.CSV) Choices(listOf("Comma","Semicolon","Tab"),when(opt.delimiter){';'->"Semicolon";'\t'->"Tab";else->"Comma"}) {set(opt.copy(delimiter=when(it){"Semicolon"->';';"Tab"->'\t';else->','}))}
        }
        Category.ARCHIVE -> if(fmt==Format.ZIP) {
            OutlinedTextField(opt.password,{set(opt.copy(password=it))},label={Text("Output ZIP password")},singleLine=true,visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            Choices(listOf("Store","Balanced","Ultra"),when(opt.compression){0->"Store";9->"Ultra";else->"Balanced"}) {set(opt.copy(compression=when(it){"Store"->0;"Balanced"->5;else->9}))}
        }
        else -> Unit
    }
    if(fmt==Format.PDF) { Choices(listOf("A4","Letter","Fit"),opt.pageSize) {set(opt.copy(pageSize=it))}; Toggle("Landscape",opt.landscape) {set(opt.copy(landscape=it))}
        field("Pages (for example 1-5, 8)",opt.pageRange) {set(opt.copy(pageRange=it))}
        field("Page margin (points)",opt.margin.toString()) {set(opt.copy(margin=it.toIntOrNull()?.coerceIn(0,100) ?: 36))} }
    Toggle("Compute SHA-256",opt.sha256) {set(opt.copy(sha256=it))}
}
