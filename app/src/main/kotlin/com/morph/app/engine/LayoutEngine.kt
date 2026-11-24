package com.morph.app.engine

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.text.*
import com.morph.app.domain.model.ProOptions
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipFile

object LayoutEngine {
    fun slides(src:File,dst:File,opt:ProOptions) {
        ZipFile(src).use { zip ->
            fun xml(path:String):org.jsoup.nodes.Document {
                val entry=zip.getEntry(path) ?: error("Missing slide part")
                require(entry.size in 0..(12L shl 20))
                return zip.getInputStream(entry).use { Jsoup.parse(it,"UTF-8","",Parser.xmlParser()) }
            }
            fun rels(path:String):Map<String,String> {
                val part=File(path);val relPath=path.substringBeforeLast('/')+"/_rels/"+part.name+".rels"
                if(zip.getEntry(relPath)==null) return emptyMap()
                return xml(relPath).getElementsByTag("Relationship").filter {it.attr("TargetMode")!="External"}.associate {
                    it.attr("Id") to java.net.URI(path).resolve(it.attr("Target")).normalize().path.trimStart('/')
                }
            }
            val presentation=xml("ppt/presentation.xml");val size=presentation.getElementsByTag("p:sldSz").first()
            val w=((size?.attr("cx")?.toDoubleOrNull() ?: 9144000.0)/12700).toInt().coerceIn(72,4096)
            val h=((size?.attr("cy")?.toDoubleOrNull() ?: 6858000.0)/12700).toInt().coerceIn(72,4096)
            val paths=rels("ppt/presentation.xml")
            val slides=presentation.getElementsByTag("p:sldId").mapNotNull {paths[it.attr("r:id")]}
            require(slides.isNotEmpty())
            PdfDocument().use {doc->
                for((number,i) in PdfEngine.pages(opt.pageRange,slides.size).withIndex()) {
                    val path=slides[i];val slide=xml(path);val relationships=rels(path)
                    val page=doc.startPage(PdfDocument.PageInfo.Builder(w,h,number+1).create());val canvas=page.canvas
                    fun color(element:Element?,fallback:Int):Int=element?.getElementsByTag("a:srgbClr")?.first()?.attr("val")?.takeIf {it.matches(Regex("[0-9A-Fa-f]{6}"))}?.let {Color.parseColor("#$it")} ?: fallback
                    canvas.drawColor(color(slide.getElementsByTag("p:bg").first(),Color.WHITE))
                    val tree=slide.getElementsByTag("p:spTree").first()
                    for(shape in tree?.children().orEmpty()) {
                        val title=shape.getElementsByTag("p:ph").first()?.attr("type") in listOf("title","ctrTitle")
                        val trans=shape.getElementsByTag("a:xfrm").first()
                        fun emu(tag:String,key:String,default:Float)=trans?.getElementsByTag(tag)?.first()?.attr(key)?.toFloatOrNull()?.div(12700f) ?: default
                        val left=emu("a:off","x",w*.06f);val top=emu("a:off","y",if(title) h*.06f else h*.25f)
                        val width=emu("a:ext","cx",w*.88f);val height=emu("a:ext","cy",if(title) h*.2f else h*.65f)
                        if(width<=0 || height<=0) continue
                        val rect=RectF(left,top,left+width,top+height)
                        val props=shape.getElementsByTag("p:spPr").first()
                        props?.children()?.find {it.tagName()=="a:solidFill"}?.let {canvas.drawRect(rect,Paint().apply {this.color=color(it,Color.TRANSPARENT)})}
                        val id=shape.getElementsByTag("a:blip").first()?.attr("r:embed")
                        relationships[id]?.let {resource->zip.getEntry(resource)?.let {entry->
                            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};zip.getInputStream(entry).use {BitmapFactory.decodeStream(it,null,bounds)}
                            var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2400) sample*=2
                            zip.getInputStream(entry).use {BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply {inSampleSize=sample})}?.let {b->canvas.drawBitmap(b,null,rect,Paint(Paint.FILTER_BITMAP_FLAG));b.recycle()}
                        }}
                        canvas.save();canvas.clipRect(rect);canvas.translate(left+4,top+2)
                        for(para in shape.getElementsByTag("a:p")) {
                            val text=para.getElementsByTag("a:t").joinToString("") {it.wholeText()}
                            val run=para.getElementsByTag("a:rPr").first()
                            val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {textSize=(run?.attr("sz")?.toFloatOrNull()?.div(100) ?: if(title) 30f else 18f).coerceIn(5f,160f);isFakeBoldText=run?.attr("b")=="1";this.color=color(run,Color.BLACK)}
                            val layout=StaticLayout.Builder.obtain(text,0,text.length,paint,(width-8).toInt().coerceAtLeast(1)).setIncludePad(false).build()
                            layout.draw(canvas);canvas.translate(0f,layout.height.toFloat())
                        };canvas.restore()
                    };doc.finishPage(page)
                };dst.outputStream().use(doc::writeTo)
            }
        }
    }
}
