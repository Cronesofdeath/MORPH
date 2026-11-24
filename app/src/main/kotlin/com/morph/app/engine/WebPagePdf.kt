package com.morph.app.engine

import android.graphics.pdf.PdfDocument
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import com.morph.app.MorphApplication
import com.morph.app.domain.model.ProOptions
import java.io.File
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import org.jsoup.Jsoup

internal object WebPagePdf {
    @Suppress("DEPRECATION")
    suspend fun write(source: String, dst: File, options: ProOptions) {
        val html = Jsoup.parse(source)
        html.select("script,iframe,object,embed,link,base,meta[http-equiv]").remove()
        html.head().prependElement("meta").attr("http-equiv","Content-Security-Policy")
            .attr("content","default-src 'none'; img-src data:; style-src 'unsafe-inline'")
        val markup = html.outerHtml()
        val paper = if (options.pageSize == "Letter") 612 to 792 else 595 to 842
        val (width,height) = if (options.landscape) paper.second to paper.first else paper
        val margin = options.margin.coerceIn(0,100)
        val pageWidth = width - 2*margin
        val pageHeight = height - 2*margin
        withContext(Dispatchers.Main.immediate) {
            val view = WebView(MorphApplication.instance)
            try {
                view.settings.apply { javaScriptEnabled=false; allowFileAccess=false; allowContentAccess=false; blockNetworkLoads=true }
                view.setInitialScale(100)
                val w = View.MeasureSpec.makeMeasureSpec(pageWidth,View.MeasureSpec.EXACTLY)
                view.measure(w, View.MeasureSpec.makeMeasureSpec(pageHeight, View.MeasureSpec.EXACTLY))
                view.layout(0,0,pageWidth,pageHeight)
                withTimeoutOrNull(30000) {
                    suspendCancellableCoroutine { done ->
                        view.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView,url: String) { if (done.isActive) done.resume(Unit) }
                        }
                        view.loadDataWithBaseURL(null,markup,"text/html","UTF-8",null)
                    }
                    while (view.contentHeight <= 0) delay(25)
                    true
                } ?: error("The report layout timed out. Try a smaller report.")
                val picture = view.capturePicture()
                require(picture.width > 0 && picture.height > 0) { "The report has no printable layout." }
                val scale = pageWidth.toFloat()/picture.width
                val pages = kotlin.math.ceil(picture.height*scale/pageHeight).toInt()
                require(pages in 1..2000) { "The report exceeds the printable page limit." }
                withContext(Dispatchers.IO) {
                    PdfDocument().use { doc ->
                        for ((n,index) in PdfEngine.pages(options.pageRange,pages).withIndex()) {
                            currentCoroutineContext().ensureActive()
                            val page=doc.startPage(PdfDocument.PageInfo.Builder(width,height,n+1).create())
                            page.canvas.apply {
                                drawColor(android.graphics.Color.WHITE)
                                clipRect(margin,margin,width-margin,height-margin)
                                translate(margin.toFloat(),(margin-index*pageHeight).toFloat())
                                scale(scale,scale)
                                drawPicture(picture)
                            }
                            doc.finishPage(page)
                        }
                        dst.outputStream().use(doc::writeTo)
                    }
                }
            } finally {view.stopLoading(); view.destroy()}
        }
    }
}
