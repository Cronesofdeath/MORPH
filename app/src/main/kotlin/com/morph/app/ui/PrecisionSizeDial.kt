package com.morph.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.morph.app.domain.model.SizeUnitCalculator
import kotlin.math.*

@Composable fun PrecisionSizeDial(value:String,change:(String)->Unit) {
    val number=SizeUnitCalculator.parse(value) ?: return
    val tick=when {
        number>=100.toBigDecimal() -> java.math.BigDecimal.ONE
        number>=java.math.BigDecimal.ONE -> java.math.BigDecimal("0.1")
        else -> java.math.BigDecimal("0.01")
    }
    val latest by rememberUpdatedState(number)
    val callback by rememberUpdatedState(change)
    val haptics=LocalHapticFeedback.current
    fun shift(steps:Int) {
        callback((latest+tick*steps.toBigDecimal()).max(tick).stripTrailingZeros().toPlainString())
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    val accent=MaterialTheme.colorScheme.primary
    val ink=MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(40.dp).semantics {
        contentDescription="Fine size adjustment, $value"
        customActions=listOf(CustomAccessibilityAction("Increase"){shift(1);true},CustomAccessibilityAction("Decrease"){shift(-1);true})
    }.pointerInput(tick) {
        var pending=0f
        detectDragGestures(onDragStart={pending=0f}) {event,delta->
            event.consume();pending+=delta.x
            val steps=(pending/8.dp.toPx()).toInt()
            if(steps!=0) {pending-=steps*8.dp.toPx();shift(steps)}
        }
    }) {
        val pitch=8.dp.toPx();val center=size.width/2
        val phase=(number.remainder(tick*5.toBigDecimal())/tick).toFloat()
        val count=ceil(center/pitch).toInt()+1
        for(i in -count..count) {
            val x=center+(i-phase)*pitch;val height=if(i%5==0) size.height*.65f else size.height*.30f
            drawLine(ink,Offset(x,size.height-height),Offset(x,size.height),1.dp.toPx(),StrokeCap.Round)
        }
        drawLine(accent,Offset(center,0f),Offset(center,size.height),2.dp.toPx(),StrokeCap.Round)
    }
}
