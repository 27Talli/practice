package com.offerpoint.pos

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.ui.printing.EscPosRaster
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThermalReceiptTest {
    private fun document(lines:Int)=ReceiptDocument(1,1,"OP-S-000001","Offer Point","Shop No. 8, Mardan","27 Oct 2026, 12:00 PKT",false,
        List(lines){ReceiptLine("Lipstick with a longer product name $it","Rs. 123.45")})
    @Test fun paginatedReceiptIsEncodedAsBoundedRasterBandsForBothRollWidths() {
        for((paper,width) in listOf(ReceiptPaper.ROLL_58 to 384,ReceiptPaper.ROLL_80 to 576)) {
            val bytes=EscPosRaster.encode(document(100),paper,false)
            assertEquals(0x1b,bytes[0].toInt());assertEquals(0x40,bytes[1].toInt())
            var index=2;var bands=0;var ink=false
            while(index<bytes.size-3){
                assertEquals(0x1d,bytes[index].toInt());assertEquals(0x76,bytes[index+1].toInt());assertEquals(0x30,bytes[index+2].toInt())
                fun u(offset:Int)=bytes[index+offset].toInt() and 255
                val stride=u(4)+256*u(5);val rows=u(6)+256*u(7)
                assertEquals(width/8,stride);assertTrue(rows in 1..128)
                val end=index+8+stride*rows;assertTrue(end<=bytes.size-3)
                ink=ink||(index+8 until end).any{bytes[it].toInt()!=0};index=end;bands++
            }
            assertEquals(bytes.size-3,index);assertTrue(bands>10);assertTrue(ink)
            assertArrayEquals(byteArrayOf(0x1b,0x64,3),bytes.takeLast(3).toByteArray())
        }
    }
    @Test fun cutterIsExplicitAndA4CannotBeSentAsDirectThermalRaster() {
        val bytes=EscPosRaster.encode(document(1),ReceiptPaper.ROLL_58,true)
        assertArrayEquals(byteArrayOf(0x1d,0x56,0),bytes.takeLast(3).toByteArray())
        try{EscPosRaster.encode(document(1),ReceiptPaper.A4,false);fail("A4 must be rejected")}catch(_:IllegalArgumentException){}
    }
}
