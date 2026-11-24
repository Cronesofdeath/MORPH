package com.morph.app.domain.graph

import com.morph.app.domain.model.Category
import com.morph.app.domain.model.Format
import com.morph.app.domain.model.Format.*

object ConversionLossAnalyzer {
    fun advisories(from: Format, to: Format, animated: Boolean, multiPage: Boolean): List<String> = buildList {
        if (animated && to.category == Category.IMAGE && to !in setOf(GIF,WEBP))
            add("Only the first animation frame will be exported.")
        if (multiPage && from == TIFF && to.category == Category.IMAGE) add("Only the first image of a multipage TIFF is exported.")
        if (from.category == Category.IMAGE && to in setOf(JPG, BMP)) add("Transparency will be flattened.")
        if (from in FormatRegistry.tables && to !in setOf(XLS, XLSX))
            add("Formulas become displayed values. Choose a worksheet for single-table formats.")
        if (from.category in setOf(Category.DOCUMENT, Category.SLIDES) && to !in FormatRegistry.archives)
            add("This conversion may reconstruct text and layout. Embedded objects and desktop styling may change.")
        if (from.category == Category.VIDEO && to.category == Category.VIDEO) add("Incompatible subtitle tracks require MKV. Re-encoding may reduce HDR precision.")
        if (from in FormatRegistry.structured && to in setOf(CSV, TSV, SQL, XLSX)) add("Nested fields become table columns.")
    }
}
