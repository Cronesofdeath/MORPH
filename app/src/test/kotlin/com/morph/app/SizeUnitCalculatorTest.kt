package com.morph.app

import com.morph.app.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class SizeUnitCalculatorTest {
    @Test fun decimalAndBinary() {assertEquals(24500000L,SizeUnitCalculator.bytes("24.5",SizeUnit.MB,false));assertEquals(51200L,SizeUnitCalculator.bytes("50",SizeUnit.KB,true));assertEquals(1073741824L,SizeUnitCalculator.bytes("1",SizeUnit.GB,true));assertEquals(13L,SizeUnitCalculator.bytes("13.99",SizeUnit.B,true))}
    @Test fun invalidAndOverflow() {for(v in listOf("-1","0","1e100","abc")) assertNull(SizeUnitCalculator.bytes(v,SizeUnit.GB,true))}
    @Test fun sharedBudgetIsConserved() {val allocation=SizeUnitCalculator.allocate(1901,listOf(1,2,17));assertEquals(1901L,allocation.sum());assertTrue(allocation[2]>allocation[1])}
}
