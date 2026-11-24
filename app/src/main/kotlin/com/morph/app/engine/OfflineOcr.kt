package com.morph.app.engine

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object OfflineOcr {
    suspend fun page(document: PDDocument, index: Int): String {
        val box = document.getPage(index).cropBox
        require(box.width > 0 && box.height > 0) { "The scanned page has invalid dimensions." }
        val scale = minOf(2f,2400f/maxOf(box.width,box.height))
        val bitmap = PDFRenderer(document).renderImage(index,scale)
        try {
            val image = InputImage.fromBitmap(bitmap,0)
            currentCoroutineContext().ensureActive()
            val devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()).use {
                Tasks.await(it.process(image)).text
            }
            if (devanagari.any { it.code in 0x0900..0x097f }) return devanagari
            currentCoroutineContext().ensureActive()
            return TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).use {
                Tasks.await(it.process(image)).text
            }.ifBlank { devanagari }
        } finally {bitmap.recycle()}
    }
}
