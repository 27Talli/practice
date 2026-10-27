package com.offerpoint.pos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.offerpoint.pos.domain.model.ReceiptPaper
import com.offerpoint.pos.ui.printing.EscPosRaster
import com.offerpoint.pos.ui.receipts.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil
import kotlin.math.floor

@RunWith(AndroidJUnit4::class)
class ReceiptQrTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun decode(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,bitmap.height,pixels)))).text
    }

    @Test fun saleAndRefundQrsSurvivePdfRenderingOnEveryPaperSize() {
        for (reference in listOf("OP-S-000001", "OP-R-000001")) for (paper in ReceiptPaper.entries) {
            val receipt = ReceiptAndroidFixtures.receipt(10).copy(receiptNumber=reference)
            val plan = ReceiptPaginator.layout(receipt, ReceiptPageSpec.forPaper(paper))
            val qr = requireNotNull(plan.pages.last().qr)
            assertEquals(reference,qr.reference)
            assertEquals(1,plan.pages.count{it.qr!=null})
            assertTrue(qr.x >= plan.spec.left && qr.x+qr.side <= plan.spec.width-plan.spec.right)
            assertTrue(qr.bottom < plan.spec.height-plan.spec.bottom-22f)
            val file = ReceiptPdfExporter.write(context,receipt,paper)
            try {
                PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                    renderer.openPage(renderer.pageCount-1).use { page ->
                        val scale=3
                        val bitmap=Bitmap.createBitmap(page.width*scale,page.height*scale,Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val crop=Bitmap.createBitmap(bitmap,floor(qr.x*scale).toInt(),floor(qr.top*scale).toInt(),ceil(qr.side*scale).toInt(),ceil(qr.side*scale).toInt())
                            try{assertEquals(reference,decode(crop))}finally{crop.recycle()}
                        }finally{bitmap.recycle()}
                    }
                }
            }finally{file.delete()}
        }
    }

    @Test fun qrIsReadableFromTheActualEscPosRasterBytesAtBothPrinterWidths() {
        val receipt=ReceiptAndroidFixtures.receipt(1)
        for ((paper,width) in listOf(ReceiptPaper.ROLL_58 to 384,ReceiptPaper.ROLL_80 to 576)) {
            val bytes=EscPosRaster.encode(receipt,paper,false)
            val bands=mutableListOf<Pair<Int,ByteArray>>()
            var offset=2
            while(offset<bytes.size-3){
                fun unsigned(index:Int)=bytes[offset+index].toInt() and 255
                val stride=unsigned(4)+256*unsigned(5);val rows=unsigned(6)+256*unsigned(7)
                assertEquals(width/8,stride)
                val end=offset+8+stride*rows
                bands += rows to bytes.copyOfRange(offset+8,end);offset=end
            }
            val height=bands.sumOf{it.first}
            val pixels=IntArray(width*height){Color.WHITE}
            var top=0
            for((rows,data)in bands){for(y in 0 until rows)for(x in 0 until width)if((data[y*(width/8)+x/8].toInt() and (0x80 shr (x%8)))!=0)pixels[(top+y)*width+x]=Color.BLACK;top+=rows}
            val bitmap=Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
            try{assertEquals(receipt.receiptNumber,decode(bitmap))}finally{bitmap.recycle()}
        }
    }

    @Test fun aLongReceiptGetsOneUnsplitQrAfterItsLastLine() {
        val receipt=ReceiptAndroidFixtures.receipt(90)
        val plan=ReceiptPaginator.layout(receipt,ReceiptPageSpec.forPaper(ReceiptPaper.ROLL_58))
        assertTrue(plan.pages.size>1)
        assertTrue(plan.pages.dropLast(1).all{it.qr==null})
        val qr=requireNotNull(plan.pages.last().qr)
        assertTrue(plan.pages.last().text.all{it.baseline<qr.top})
        assertEquals(receipt.receiptNumber,qr.reference)
    }
}
