package com.morph.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import kotlin.math.*
import java.util.Locale

val Blue=Color(0xff2563eb)
val mono=TextStyle(fontFamily=FontFamily.Monospace,fontFeatureSettings="tnum",fontSize=12.sp)
fun bytes(n:Long,binary:Boolean=true):String {
    val base=if(binary) 1024.0 else 1000.0
    val power=(ln(n.coerceAtLeast(1).toDouble())/ln(base)).toInt().coerceIn(0,3)
    val unit=(if(binary) listOf("B","KiB","MiB","GiB") else listOf("B","KB","MB","GB"))[power]
    return if(power==0) "$n B" else String.format(Locale.ROOT,"%.2f %s",n/base.pow(power),unit)
}
@Composable fun Label(value:String) { Text(value.uppercase(),style=mono.copy(letterSpacing=1.5.sp,fontSize=10.sp,fontWeight=FontWeight.Bold),color=MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable fun Panel(content:@Composable ColumnScope.()->Unit) {
    Surface(modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}
@Composable fun Toggle(title:String,value:Boolean,set:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall); Switch(value,set)
    }
}
@Composable fun Choices(items:List<String>,chosen:String,select:(String)->Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        items.forEach { FilterChip(it==chosen,{select(it)},label={Text(it)}) }
    }
}
@Composable fun DropArt(modifier:Modifier=Modifier) {
    Canvas(modifier) {
        val w=size.width; val h=size.height
        val grid=Color.White.copy(alpha=.14f)
        for(i in 0..16) for(j in 0..9) drawCircle(grid,1.dp.toPx(),Offset(w*i/16,h*j/9))
        fun card(dx:Float,dy:Float,angle:Float,fill:Color) {
            val center=Offset(w*.61f+dx,h*.49f+dy)
            rotate(angle,center) {
                val a=Offset(center.x-w*.19f,center.y-h*.29f); val s=Size(w*.38f,h*.58f)
                drawRoundRect(fill,a,s,CornerRadius(5.dp.toPx()))
                repeat(4) { k -> val y=a.y+s.height*(.38f+k*.12f)
                    drawLine(Color(0xff193d9c),Offset(a.x+s.width*.15f,y),Offset(a.x+s.width*(if(k==3) .55f else .8f),y),2.dp.toPx()) }
            }
        }
        card(-w*.13f,-h*.02f,-19f,Color(0xff88adff)); card(-w*.05f,-h*.015f,-5f,Color(0xffc8d9ff)); card(w*.04f,h*.025f,12f,Color.White)
    }
}
@Composable fun PipelineFlowCanvas(path:List<String>) {
    val accent=MaterialTheme.colorScheme.primary
    val transition=rememberInfiniteTransition(label="Pipeline motion")
    val phase by transition.animateFloat(0f,1f,infiniteRepeatable(tween(1700,easing=LinearEasing)),label="Data flow")
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        path.forEachIndexed { i,label ->
            if(i>0) Canvas(Modifier.weight(1f).height(22.dp)) {
                val w=size.width; val h=size.height
                val line=Path().apply { moveTo(0f,h*.75f); cubicTo(w*.55f,h*.75f,w*.45f,h*.25f,w,h*.25f) }
                drawPath(line,accent.copy(alpha=.35f),style=Stroke(2.dp.toPx()))
                val travel=androidx.compose.ui.graphics.PathMeasure().apply {setPath(line,false)}
                drawCircle(accent,2.8.dp.toPx(),travel.getPosition(phase*travel.length))
            }
            Text(label,style=mono.copy(fontWeight=FontWeight.Bold),modifier=Modifier.padding(4.dp))
        }
    }
}
