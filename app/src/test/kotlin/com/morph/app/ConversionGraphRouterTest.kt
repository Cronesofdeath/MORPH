package com.morph.app

import com.morph.app.domain.graph.*
import com.morph.app.domain.model.Format.*
import org.junit.Assert.*
import org.junit.Test

class ConversionGraphRouterTest {
    private val graph=ConversionGraphRouter()
    @Test fun spreadsheetMigrationIsDirect() {assertEquals(listOf(XLSX),graph.route(XLS,XLSX)!!.map {it.to})}
    @Test fun spreadsheetToPdfUsesNativePagination() {
        for (source in listOf(XLS, XLSX)) {
            val edge = graph.route(source, PDF)!!.single()
            assertEquals(Engine.SHEET, edge.engine)
            assertEquals(PDF, edge.to)
        }
    }
    @Test fun heicCanReachPdf() {val path=graph.route(HEIC,PDF)!!; assertEquals(PDF,path.last().to); assertEquals(HEIC,path.first().from)}
    @Test fun jsonToWorkbookIsDirect() {assertEquals(listOf(XLSX),graph.route(JSON,XLSX)!!.map {it.to})}
    @Test fun unsupportedRouteIsAbsent() {assertNull(graph.route(MP3,XLSX))}
    @Test fun lossOutweighsExtraHop() { val graph=ConversionGraphRouter(listOf(ConversionEdge(XLS,PDF,Engine.DOCUMENT,4,1),ConversionEdge(XLS,HTML,Engine.SHEET,1,4),ConversionEdge(HTML,PDF,Engine.DOCUMENT,1,4)))
        assertEquals(2,graph.route(XLS,PDF)!!.size) }
}
