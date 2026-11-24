package com.morph.app.engine

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Build
import com.caverock.androidsvg.SVG as AndroidSvg
import com.morph.app.domain.model.*
import com.morph.app.domain.model.Format.*
import java.io.File
import kotlin.math.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object ImageEngine {
    suspend fun bitmap(file: File, format: Format, opt: ProOptions): Bitmap {
        val maxWidth = (opt.maxWidth ?: 4096).coerceIn(1, 4096)
        val maxHeight = (opt.maxHeight ?: 4096).coerceIn(1, 4096)
        fun fit(width: Int, height: Int): Pair<Int, Int> {
            val scale = minOf(1.0, maxWidth.toDouble() / width, maxHeight.toDouble() / height)
            return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
        }
        if (format == SVG) {
            require(file.length() <= 8L*1024*1024) { "SVG exceeds the vector memory budget" }
            val svg = file.inputStream().use { AndroidSvg.getFromInputStream(it) }
            val box = svg.documentViewBox
            val w = svg.documentWidth.takeIf { it > 0 } ?: box?.width() ?: 512f
            val h = svg.documentHeight.takeIf { it > 0 } ?: box?.height() ?: 512f
            val (width, height) = fit(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                svg.renderToCanvas(Canvas(it),RectF(0f,0f,it.width.toFloat(),it.height.toFloat()))
            }
        }
        val decoded = if (Build.VERSION.SDK_INT >= 28) runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val (width, height) = fit(info.size.width, info.size.height)
                decoder.setTargetSize(width, height)
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() else null
        if (decoded != null) return decoded
        val meta = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path,meta)
        if (meta.outWidth > 0 && meta.outHeight > 0) {
            val orientation = runCatching {
                androidx.exifinterface.media.ExifInterface(file).getAttributeInt("Orientation", 1)
            }.getOrDefault(1)
            val swapAxes = orientation in 5..8
            val width = if (swapAxes) meta.outHeight else meta.outWidth
            val height = if (swapAxes) meta.outWidth else meta.outHeight
            val (fitWidth, fitHeight) = fit(width, height)
            var sample = 1
            while (width / (sample * 2L) >= fitWidth && height / (sample * 2L) >= fitHeight) sample *= 2
            val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB) })
            if (raw != null) {
                val transform = Matrix().apply {
                    when (orientation) {
                        2 -> setScale(-1f, 1f)
                        3 -> setRotate(180f)
                        4 -> setScale(1f, -1f)
                        5 -> { setRotate(90f); postScale(-1f, 1f) }
                        6 -> setRotate(90f)
                        7 -> { setRotate(-90f); postScale(-1f, 1f) }
                        8 -> setRotate(-90f)
                    }
                    postScale(fitWidth.toFloat() / (if (swapAxes) raw.height else raw.width),
                        fitHeight.toFloat() / (if (swapAxes) raw.width else raw.height))
                }
                val resized = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, transform, true)
                if (resized !== raw) raw.recycle()
                return resized
            }
        }
        val fallback = File.createTempFile("raster",".png",file.parentFile)
        try {
            MediaEngine.run(listOf("-i",file.path,"-frames:v","1","-vf","scale='min($maxWidth,iw)':'min($maxHeight,ih)':force_original_aspect_ratio=decrease",fallback.path))
            return BitmapFactory.decodeFile(fallback.path) ?: error("Image decoding failed")
        } finally { fallback.delete() }
    }

    suspend fun convert(src: File, dst: File, from: Format, to: Format, opt: ProOptions, limit: Long?, step: suspend (String)->Unit) {
        var image = bitmap(src,from,opt)
        try {
            if (to == HEIC) {
                if (Build.VERSION.SDK_INT < 28) error("HEIC encoding requires Android 9 or later")
                suspend fun heif(q:Int): Long {
                    currentCoroutineContext().ensureActive()
                    androidx.heifwriter.HeifWriter.Builder(dst.path,image.width,image.height,androidx.heifwriter.HeifWriter.INPUT_MODE_BITMAP)
                        .setQuality(q).setMaxImages(1).build().use { writer -> writer.start(); writer.addBitmap(image); writer.stop(30000) }
                    return dst.length()
                }
                if(limit==null) heif(92) else {
                    val quality=TargetSizeConvergence.highestQuality(limit) { heif(it) } ?: throw TargetTooSmall(dst.length())
                    heif(quality)
                }
                return
            }
            if (to == PDF) { pdf(listOf(image),dst,opt); return }
            if (to == JPG && image.hasAlpha()) {
                val flat = Bitmap.createBitmap(image.width,image.height,Bitmap.Config.ARGB_8888)
                Canvas(flat).apply { drawColor(opt.background); drawBitmap(image,0f,0f,null) }
                image.recycle(); image = flat
            }
            @Suppress("DEPRECATION")
            val encoder = when(to) { JPG -> Bitmap.CompressFormat.JPEG; PNG -> Bitmap.CompressFormat.PNG; WEBP -> Bitmap.CompressFormat.WEBP; else -> error("Unsupported bitmap encoder") }
            val metadata=if(opt.keepMetadata && to in listOf(JPG,WEBP)) FileInspector.metadata(src) else emptyMap()
            fun encode(q: Int): Long {
                dst.outputStream().buffered().use { check(image.compress(encoder,q,it)) }
                FileInspector.writeMetadata(dst,metadata)
                return dst.length()
            }
            if (limit == null) { encode(95); return }
            while (true) {
                currentCoroutineContext().ensureActive()
                step("Optimizing ${image.width} × ${image.height}")
                if (to == PNG) {
                    if (encode(100) <= limit) return
                    for (colors in listOf(256,128,64,32,16)) {
                        step("Quantizing to $colors colors")
                        IndexedPng.write(image,dst,colors)
                        if(dst.length()<=limit) return
                    }
                }
                else {
                    val q = TargetSizeConvergence.highestQuality(limit) { currentCoroutineContext().ensureActive(); encode(it) }
                    if (q != null) { encode(q); return }
                    encode(5)
                }
                val scale = max(0.05,min(.88,sqrt(limit.toDouble()/dst.length())*.92))
                if (max(image.width,image.height) <= 32) throw TargetTooSmall(dst.length())
                val smaller = Bitmap.createScaledBitmap(image,max(1,(image.width*scale).toInt()),max(1,(image.height*scale).toInt()),true)
                image.recycle(); image = smaller
            }
        } finally { image.recycle() }
    }

    fun pdf(images: List<Bitmap>, dst: File, opt: ProOptions) {
        PdfDocument().use { doc ->
            for ((index,image) in images.withIndex()) {
                var w = if (opt.pageSize == "Letter") 612 else 595
                var h = if (opt.pageSize == "Letter") 792 else 842
                if (opt.pageSize == "Fit") { w=image.width; h=image.height } else if (opt.landscape) { val swap=w; w=h; h=swap }
                val page = doc.startPage(PdfDocument.PageInfo.Builder(w,h,index+1).create())
                val m = opt.margin.coerceIn(0,min(w,h)/4)
                val scale = min((w-2*m).toFloat()/image.width,(h-2*m).toFloat()/image.height)
                val iw = image.width*scale; val ih = image.height*scale
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawBitmap(image,null,RectF((w-iw)/2,(h-ih)/2,(w+iw)/2,(h+ih)/2),Paint(Paint.FILTER_BITMAP_FLAG))
                doc.finishPage(page)
            }
            dst.outputStream().use(doc::writeTo)
        }
    }
}
