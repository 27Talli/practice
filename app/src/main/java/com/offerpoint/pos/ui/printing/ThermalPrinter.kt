package com.offerpoint.pos.ui.printing

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.usb.*
import android.os.Build
import androidx.core.content.ContextCompat
import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.ui.receipts.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil

data class PrinterCandidate(val name:String,val key:String)

/** Standard ESC/POS raster commands. Customer-safe ReceiptDocument is the only accepted print input. */
object EscPosRaster {
    fun band(width:Int,height:Int,black:(Int,Int)->Boolean):ByteArray {
        require(width in 8..1024 && width%8==0 && height in 1..128)
        val bytes=width/8
        val output=ByteArrayOutputStream()
        output.write(byteArrayOf(0x1d,0x76,0x30,0x00,bytes.toByte(),(bytes shr 8).toByte(),height.toByte(),(height shr 8).toByte()))
        for(y in 0 until height)for(xByte in 0 until bytes){var value=0;for(bit in 0..7)if(black(xByte*8+bit,y))value=value or (0x80 shr bit);output.write(value)}
        return output.toByteArray()
    }
    fun encode(receipt:ReceiptDocument,paper:ReceiptPaper,cut:Boolean):ByteArray {
        require(paper!=ReceiptPaper.A4){"Choose 58 mm or 80 mm paper for a direct thermal printer."}
        val width=if(paper==ReceiptPaper.ROLL_58)384 else 576
        val plan=ReceiptPaginator.layout(receipt,ReceiptPageSpec.forPaper(paper))
        val scale=width.toFloat()/plan.spec.width
        val output=ByteArrayOutputStream();output.write(byteArrayOf(0x1b,0x40))
        for((index,page) in plan.pages.withIndex()){
            val bottom=maxOf(page.text.maxOfOrNull{it.baseline+it.size} ?: 30f,page.qr?.bottom ?: 0f)+22f
            val height=ceil(bottom*scale).toInt().coerceIn(64,2048)
            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE);val canvas=Canvas(bitmap);canvas.scale(scale,scale)
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK}
                for(line in page.text){paint.textSize=line.size;paint.typeface=if(line.bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT;canvas.drawText(line.text,line.x,line.baseline,paint)}
                page.qr?.let { ReceiptQr.draw(canvas,it) }
                if(plan.pages.size>1){paint.textSize=8f;canvas.drawText("Page ${index+1} of ${plan.pages.size}",plan.spec.left,bottom-4f,paint)}
                val pixels=IntArray(width*height);bitmap.getPixels(pixels,0,width,0,0,width,height)
                for(top in 0 until height step 128){
                    val rows=minOf(128,height-top)
                    output.write(band(width,rows){x,y->val color=pixels[(top+y)*width+x];(Color.red(color)*299+Color.green(color)*587+Color.blue(color)*114)<128000})
                    require(output.size()<=8*1024*1024){"This receipt is too large for direct printing. Use its PDF instead."}
                }
            }finally{bitmap.recycle()}
        }
        output.write(byteArrayOf(0x1b,0x64,0x03)) // Feed three lines. Cutting is explicitly opt-in.
        if(cut)output.write(byteArrayOf(0x1d,0x56,0x00))
        return output.toByteArray()
    }
}

class ThermalPrinter(context:Context){
    private val context=context.applicationContext
    private val usb get()=context.getSystemService(Context.USB_SERVICE) as? UsbManager
    fun bluetoothPermissionGranted()=Build.VERSION.SDK_INT<31||ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
    @SuppressLint("MissingPermission")
    fun pairedBluetooth():List<PrinterCandidate>{
        require(bluetoothPermissionGranted()){ "Allow Nearby devices access to list paired Bluetooth printers." }
        val adapter=(context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: error("Bluetooth is unavailable.")
        require(adapter.isEnabled){"Turn on Bluetooth and pair your printer in Android settings."}
        return adapter.bondedDevices.map{PrinterCandidate(it.name ?: "Paired device",it.address)}.sortedBy{it.name}
    }
    fun attachedUsb():List<PrinterCandidate> = usb?.deviceList?.values.orEmpty().filter{printerInterface(it)!=null}.map{PrinterCandidate(it.productName ?: "USB printer ${it.vendorId}:${it.productId}","${it.vendorId}:${it.productId}")}
    private fun printerInterface(device:UsbDevice):Pair<UsbInterface,UsbEndpoint>?{
        for(i in 0 until device.interfaceCount){val iface=device.getInterface(i);if(iface.interfaceClass!=UsbConstants.USB_CLASS_PRINTER)continue
            for(e in 0 until iface.endpointCount){val endpoint=iface.getEndpoint(e);if(endpoint.direction==UsbConstants.USB_DIR_OUT&&endpoint.type==UsbConstants.USB_ENDPOINT_XFER_BULK)return iface to endpoint}}
        return null
    }
    suspend fun send(receipt:ReceiptDocument,settings:ReceiptSettings){
        val bytes=withContext(Dispatchers.Default){EscPosRaster.encode(receipt,settings.paper,settings.cutPaper)}
        when(settings.printerMode){PrinterMode.ANDROID->error("Choose a saved Bluetooth or USB printer first.");PrinterMode.BLUETOOTH->bluetooth(settings.printerKey,bytes);PrinterMode.USB->usb(settings.printerKey,bytes)}
    }
    @SuppressLint("MissingPermission")
    private suspend fun bluetooth(address:String,bytes:ByteArray)=withTimeout(15_000){
        require(bluetoothPermissionGranted()){ "Allow Nearby devices access in printer settings first." }
        val adapter=(context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: error("Bluetooth is unavailable.")
        require(adapter.isEnabled){"Bluetooth is off."}
        val device=adapter.bondedDevices.singleOrNull{it.address==address} ?: error("The saved printer is not paired on this phone. Select it again in receipt settings.")
        val socket=device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"))
        try {
            suspendCancellableCoroutine<Unit>{continuation->
                continuation.invokeOnCancellation{runCatching{socket.close()}}
                val worker=CoroutineScope(Dispatchers.IO).launch{try{socket.connect();socket.outputStream.write(bytes);socket.outputStream.flush();if(continuation.isActive)continuation.resume(Unit)}catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(IllegalStateException("Printer transfer stopped. Check the paper before retrying; part of the receipt may have printed.",e))}}
                worker.invokeOnCompletion{if(it is CancellationException)runCatching{socket.close()}}
            }
        }finally{runCatching{socket.close()}}
    }
    private suspend fun usb(key:String,bytes:ByteArray)=withTimeout(30_000){
        val manager=usb ?: error("USB host printing is unavailable.")
        val candidates=manager.deviceList.values.filter{"${it.vendorId}:${it.productId}"==key&&printerInterface(it)!=null}
        require(candidates.size==1){"Connect exactly one matching USB printer, then select it in receipt settings."}
        val device=candidates.single();permission(manager,device)
        withContext(Dispatchers.IO){
            val (iface,endpoint)=requireNotNull(printerInterface(device))
            val connection=manager.openDevice(device) ?: error("The USB printer could not be opened.")
            try{
                require(connection.claimInterface(iface,true)){"The USB printer is busy in another app."}
                var offset=0
                while(offset<bytes.size){currentCoroutineContext().ensureActive();val count=minOf(1024,bytes.size-offset);val sent=connection.bulkTransfer(endpoint,bytes,offset,count,2000)
                    check(sent>0){"USB transfer stopped. Check the printer before retrying; part of the receipt may have printed."};offset+=sent}
                connection.releaseInterface(iface)
            }finally{connection.close()}
        }
    }
    private suspend fun permission(manager:UsbManager,device:UsbDevice){
        if(manager.hasPermission(device))return
        withTimeout(60_000){suspendCancellableCoroutine<Unit>{continuation->
            val action="${context.packageName}.USB_PRINTER_${UUID.randomUUID()}"
            lateinit var receiver:BroadcastReceiver
            fun unregister(){runCatching{context.unregisterReceiver(receiver)}}
            receiver=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){if(intent.action!=action)return;unregister();if(!continuation.isActive)return
                if(manager.hasPermission(device))continuation.resume(Unit)else continuation.resumeWithException(IllegalStateException("USB printer access was denied."))}}
            if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,IntentFilter(action),Context.RECEIVER_NOT_EXPORTED)else{@Suppress("DEPRECATION") context.registerReceiver(receiver,IntentFilter(action))}
            continuation.invokeOnCancellation{unregister()}
            try{manager.requestPermission(device,PendingIntent.getBroadcast(context,0,Intent(action).setPackage(context.packageName),PendingIntent.FLAG_IMMUTABLE))}catch(e:Exception){unregister();if(continuation.isActive)continuation.resumeWithException(e)}
        }}
    }
}
