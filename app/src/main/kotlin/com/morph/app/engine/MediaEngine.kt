package com.morph.app.engine

import com.arthenica.ffmpegkit.*
import com.morph.app.domain.model.*
import com.morph.app.domain.model.Format.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlin.math.*

object MediaEngine {
    suspend fun run(args: List<String>,statistics: ((Statistics)->Unit)?=null) = suspendCancellableCoroutine<Unit> { continuation ->
        val session = FFmpegKit.executeWithArgumentsAsync((listOf("-y","-nostdin","-hide_banner","-loglevel","error","-protocol_whitelist","file,pipe,crypto") + args).toTypedArray(), {
            if (ReturnCode.isSuccess(it.returnCode)) continuation.resume(Unit)
            else continuation.resumeWithException(IllegalArgumentException("The codec could not process this stream. Try another output format or remove custom encoding settings."))
        },null,{ stats -> statistics?.invoke(stats) })
        continuation.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
    }

    suspend fun probe(src: File): MediaInformation = suspendCancellableCoroutine<MediaInformation> { continuation ->
            val session=FFprobeKit.getMediaInformationAsync(src.path) { result ->
                val info=result.mediaInformation
                if(info==null) continuation.resumeWithException(IllegalArgumentException("Unreadable media header")) else continuation.resume(info)
            }
            continuation.invokeOnCancellation {FFmpegKit.cancel(session.sessionId)}
        }

    suspend fun convert(src: File, dst: File, fmt: Format, opt: ProOptions, limit: Long?, step: suspend (String) -> Unit) {
        val media = probe(src)
        val start = opt.trimStart ?: 0.0
        val duration = min(opt.trimEnd ?: Double.MAX_VALUE, media.duration?.toDoubleOrNull() ?: Double.MAX_VALUE) - start
        suspend fun encode(args:List<String>,label:String) = kotlinx.coroutines.coroutineScope {
            val stats=kotlinx.coroutines.channels.Channel<Statistics>(kotlinx.coroutines.channels.Channel.CONFLATED)
            val monitor=launch {
                var last=0L
                for(s in stats) {
                    val now=android.os.SystemClock.elapsedRealtime()
                    if(now-last<1000) continue
                    last=now
                    val elapsed=s.time/1000
                    val progress=if(duration in 0.001..1e8) " · ${(elapsed/duration*100).toInt().coerceIn(0,100)}%" else ""
                    val eta=if(s.speed>0 && duration in 0.001..1e8) " · ${((duration-elapsed)/s.speed).toLong().coerceAtLeast(0)}s left" else ""
                    step("$label$progress · "+java.lang.String.format(java.util.Locale.ROOT,"%.1f×",s.speed)+eta)
                }
            }
            try {run(args){stats.trySend(it)}} finally {stats.close();monitor.join()}
        }
        val active = media.streams.filter { it.type == "video" || it.type == "audio" || it.type == "subtitle" }
        val remux = opt.permitsCorrection() && limit == null && when (fmt) {
            MP4, MOV, M4V -> active.any { it.type == "video" } && media.streams.all { stream ->
                when(stream.type) {
                    "video" -> stream.codec == "h264" && stream.format == "yuv420p"
                    "audio" -> stream.codec in setOf("aac","mp3","alac")
                    "subtitle" -> stream.codec == "mov_text"
                    else -> false
                }
            }
            MKV -> active.any { it.type == "video" } && active.size == media.streams.size
            M4A, AAC -> active.size == 1 && active.single().codec == "aac" && media.streams.size == 1
            else -> false
        }
        if (remux) {
            step("Lossless remux — zero re-encoding")
            val args = mutableListOf("-i",src.path,"-map","0","-c","copy")
            if (!opt.keepMetadata) args += listOf("-map_metadata","-1")
            if (fmt in setOf(MP4,M4V,MOV,M4A)) args += listOf("-movflags","+faststart")
            if (fmt == M4V) args += listOf("-f","mp4")
            run(args + dst.path)
            return
        }
        val input = buildList {
            if (start > 0) addAll(listOf("-ss",start.toString()))
            addAll(listOf("-i",src.path))
            opt.trimEnd?.let { require(it > start); addAll(listOf("-t",(it-start).toString())) }
            if (!opt.keepMetadata) addAll(listOf("-map_metadata","-1"))
        }
        if (fmt == FRAMES) {
            val frames=File(dst.parentFile,"frames").apply {mkdirs()}
            val fps=opt.fps ?: 1
            require(duration.isFinite() && duration*fps<=10000) {"Trim the clip or lower the frame rate to export at most 10,000 frames."}
            run(input+listOf("-vf","fps=$fps","-q:v","3",File(frames,"frame-%06d.jpg").path))
            val entries=frames.listFiles().orEmpty().sortedBy {it.name}.map {it.name to it}
            require(entries.isNotEmpty()) {"No frames found in this clip"}
            ArchiveEngine.pack(entries,dst,ZIP,opt);return
        }
        if (fmt.category == Category.IMAGE) {
            if (fmt == GIF) {
                val palette = File(dst.parentFile,"palette.png")
                val gifInput = buildList {
                    addAll(listOf("-threads", "2", "-filter_threads", "2", "-filter_complex_threads", "2"))
                    if (start > 0) addAll(listOf("-ss", start.toString()))
                    // Limit the input so palettegen receives EOF at the end of the selected clip.
                    opt.trimEnd?.let { addAll(listOf("-t", (it - start).toString())) }
                    addAll(listOf("-i", src.path))
                }
                var fps = (opt.fps ?: 15).coerceAtLeast(1)
                var width = (opt.maxWidth ?: 640).coerceAtLeast(1)
                repeat(if (limit == null) 1 else 3) {
                    if (dst.exists()) check(dst.delete()) { "Could not discard the previous GIF attempt." }
                    val filter = "fps=$fps,scale='min($width,iw)':-1:flags=lanczos"
                    step("Building GIF palette")
                    encode(gifInput + listOf("-map", "0:v:0", "-vf", "$filter,palettegen", "-frames:v", "1", palette.path), "Building GIF palette")
                    step("Encoding GIF")
                    val metadata = if (opt.keepMetadata) emptyList() else listOf("-map_metadata", "-1")
                    encode(gifInput + listOf("-i", palette.path, "-filter_complex", "[0:v:0]$filter[x];[x][1:v:0]paletteuse[out]", "-map", "[out]", "-loop", "0") + metadata + dst.path, "Encoding GIF")
                    if (limit == null || dst.length() <= limit) return
                    val factor = sqrt(limit.toDouble() / dst.length()).coerceIn(.25, .85)
                    fps = minOf(fps, (fps * sqrt(factor)).toInt().coerceAtLeast(2))
                    width = minOf(width, (width * factor).toInt().coerceAtLeast(64))
                }
                throw TargetTooSmall(dst.length())
            } else {
                val flags = when(fmt) {
                    AVIF -> listOf("-c:v","libaom-av1","-still-picture","1","-cpu-used","6")
                    ICO -> listOf("-vf","scale=256:256:force_original_aspect_ratio=decrease,pad=256:256:(ow-iw)/2:(oh-ih)/2")
                    else -> emptyList()
                }
                run(input + flags + listOf("-frames:v","1",dst.path))
            }
            return
        }
        val hasAudio = !opt.mute && media.streams.any { it.type == "audio" }
        if (fmt.category == Category.AUDIO) {
            val codec = mapOf(MP3 to "libmp3lame", WAV to "pcm_s16le", AAC to "aac", M4A to "aac", FLAC to "flac",
                OGG to "libvorbis", OPUS to "libopus", WMA to "wmav2", AIFF to "pcm_s16be", AMR to "libopencore_amrnb", AC3 to "ac3").getValue(fmt)
            var bitrate = limit?.let { (it * 8.0 * .97 / duration / 1000).toInt().coerceIn(8,320) } ?: 192
            repeat(4) {
                val channels=when {fmt==AMR -> 1; opt.channels!=null -> opt.channels; fmt in listOf(WAV,AIFF) && limit!=null && limit*8.0*.97/duration<44100*32 -> 1;bitrate<48 -> 1;else -> 2}
                val preferred=opt.sampleRate ?: when {
                    fmt==AMR -> 8000
                    fmt in listOf(WAV,AIFF) && limit!=null -> listOf(48000,44100,22050,16000,8000).firstOrNull {r->r*channels*16.0*duration/8+128<=limit}
                        ?: throw TargetTooSmall(ceil(8000*channels*16.0*duration/8+128).toLong())
                    fmt==AC3 -> if(bitrate<80) 32000 else 48000
                    fmt==OPUS -> when {bitrate<20->8000;bitrate<40->16000;else->48000}
                    bitrate<20 -> 8000;bitrate<40 -> 16000;bitrate<64 -> 22050;else->44100
                }
                val rate = when (fmt) {
                    AMR -> 8000
                    OPUS -> 48000
                    AC3 -> listOf(32000,44100,48000).minBy { abs(it-preferred) }
                    MP3 -> listOf(8000,11025,12000,16000,22050,24000,32000,44100,48000).minBy { abs(it-preferred) }
                    else -> preferred
                }
                val valid=when(fmt) {
                    MP3 -> listOf(8,16,24,32,40,48,56,64,80,96,112,128,160,192,224,256,320)
                    AC3 -> listOf(32,40,48,56,64,80,96,112,128,160,192,224,256,320)
                    else -> listOf(bitrate)
                }
                val selected=valid.lastOrNull {it<=bitrate} ?: valid.first()
                val args = mutableListOf("-vn","-c:a",codec,"-ar",rate.toString(),"-ac",channels.toString())
                if (fmt !in listOf(WAV,AIFF,FLAC)) args += listOf("-b:a",if(fmt == AMR) "12.2k" else "${selected}k")
                if (opt.normalize) args += listOf("-af","loudnorm")
                encode(input + args + dst.path,"Encoding audio")
                if (limit == null || dst.length() <= limit) return
                bitrate = (bitrate * limit.toDouble() / dst.length() * .95).toInt().coerceAtLeast(8)
            }
            throw TargetTooSmall(dst.length())
        }
        val codec = when(fmt) {
            WEBM -> "libvpx-vp9"; AVI -> "mpeg4"; WMV -> "wmv2"; FLV -> "flv"; GPP -> "mpeg4"
            else -> if (opt.videoCodec == "H.265") "libx265" else "libx264"
        }
        require(media.streams.count { it.type == "video" } == 1) { "This file contains several video streams. Choose MKV without overrides to preserve them." }
        val subtitles = media.streams.filter { it.type == "subtitle" }
        val mp4Text = fmt in setOf(MP4,M4V,MOV) && subtitles.all { it.codec in setOf("subrip","mov_text","webvtt","text") }
        require(subtitles.isEmpty() || fmt == MKV || mp4Text) { "This subtitle format cannot be preserved. Choose MKV without overrides." }
        val audioCodec = when(fmt) { WEBM -> "libopus"; WMV -> "wmav2"; AVI,FLV -> "libmp3lame"; else -> "aac" }
        val budget = limit?.let { TargetSizeConvergence.videoBudget(it,duration,hasAudio) }
        var video = budget?.video ?: 0
        val stream = media.streams.firstOrNull { it.type == "video" } ?: error("No video stream")
        repeat(3) {
            val ratio=stream.width?.toDouble()?.div((stream.height ?: 1L).coerceAtLeast(1L)) ?: (16.0/9)
            val nativeFps=stream.averageFrameRate?.split('/')?.let { v->v[0].toDoubleOrNull()?.div(v.getOrNull(1)?.toDoubleOrNull() ?: 1.0) }?.takeIf {it.isFinite() && it>0} ?: 30.0
            val fps=minOf(nativeFps,opt.fps?.toDouble() ?: if(budget==null) 60.0 else if(video<180) 15.0 else if(video<800) 24.0 else 30.0)
            val cap=minOf(opt.maxHeight ?: 2160,stream.height?.toInt() ?: 720)
            val height=if(budget==null) cap else (listOf(cap,2160,1080,720,480,360,240,144).filter {it<=cap}.firstOrNull { video*1000.0/(it*it*ratio*fps)>=.075 } ?: minOf(cap,144))
            val args = mutableListOf("-map","0:v:0","-c:v",codec,"-pix_fmt","yuv420p","-vf","scale=-2:${height/2*2}")
            if(opt.fps!=null || budget!=null) args+=listOf("-r",fps.toString())
            if (budget != null) {
                args += listOf("-b:v","${video}k","-maxrate","${video*2}k","-bufsize","${video*3}k")
                val log = File(dst.parentFile,"passlog").path
                if (codec == "libx265") {
                    // x265 owns its two-pass statistics file.
                    val escaped = log.replace("\\","/").replace(":","\\:")
                    step("Encoding pass 1 of 2")
                    run(input + args + listOf("-x265-params","pass=1:stats=$escaped","-an","-f","null","/dev/null"))
                    args += listOf("-x265-params","pass=2:stats=$escaped")
                } else {
                    args += listOf("-passlogfile",log)
                    step("Encoding pass 1 of 2")
                    run(input + args + listOf("-pass","1","-an","-f","null","/dev/null"))
                    args += listOf("-pass","2")
                }
                step("Encoding pass 2 of 2")
            }
            if (hasAudio) args += listOf("-map","0:a?","-c:a",audioCodec,"-b:a","${budget?.audio ?: 128}k","-ac",if ((budget?.audio ?: 128) <= 48) "1" else "2") else args += "-an"
            if (subtitles.isNotEmpty()) args += listOf("-map","0:s?","-c:s",if (fmt == MKV) "copy" else "mov_text")
            if (fmt in listOf(MP4,M4V,MOV) && opt.fastStart) args += listOf("-movflags","+faststart")
            if (fmt == M4V) args += listOf("-f","mp4")
            encode(input + args + dst.path,if(budget!=null) "Encoding pass 2 of 2" else "Encoding video")
            if (limit == null || dst.length() <= limit) return
            video = (video * limit.toDouble() / dst.length() * .96).toInt().coerceAtLeast(12)
        }
        throw TargetTooSmall(dst.length())
    }
}
