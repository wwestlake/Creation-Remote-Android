package com.lagdaemon.creationremote.gimbal

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.UUID

@SuppressLint("MissingPermission") // Every public operation checks grants; revoked permission failures are caught.
class BleDiagnostics(private val context:Context,private val update:(Snapshot)->Unit) {
    data class DeviceRow(val id:Int,val name:String,val rssi:Int,val advertisement:String)
    data class CharacteristicRow(val id:Int,val service:String,val characteristic:String,val properties:Int,val subscribed:Boolean=false)
    data class Snapshot(val status:String="Idle",val devices:List<DeviceRow> = emptyList(),val characteristics:List<CharacteristicRow> = emptyList(),val services:List<String> = emptyList(),val events:Int=0)
    private val main=Handler(Looper.getMainLooper())
    private val manager=context.getSystemService(BluetoothManager::class.java)
    private var scanner:BluetoothLeScanner?=null
    private var scan:ScanCallback?=null
    private var gatt:BluetoothGatt?=null
    private var selected=0
    private var generation=0
    private var closed=false
    private var snapshot=Snapshot()
    private var eventCount=0
    private val devices=linkedMapOf<String,Pair<Int,BluetoothDevice>>() // RAM only; never journalled.
    private val handles=linkedMapOf<Int,BluetoothGattCharacteristic>()
    private val parsers=mutableMapOf<Int,DumlParser>()
    private var pending:Int?=null
    private var subscriptionAttempt=0
    private val journal=BleJournal()
    private val cccd=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private fun publish(text:String=snapshot.status){snapshot=snapshot.copy(status=text,events=eventCount);update(snapshot)}
    private fun record(kind:String,status:Int?=null,id:Int?=null,bytes:Int?=null,header:DumlParser.Header?=null){
        val c=id?.let{handles[it]};journal.event(kind,selected.takeIf{it>0},status,c?.service?.uuid?.toString(),c?.uuid?.toString(),c?.properties,bytes,header);eventCount++
    }
    private fun ready():Boolean {
        if(closed)return false
        if(requiredPermissions().any{ContextCompat.checkSelfPermission(context,it)!=PackageManager.PERMISSION_GRANTED}){record("permission_denied");publish("Permission required. Allow Nearby devices (or Location on older Android) and retry.");return false}
        try{if(manager?.adapter?.isEnabled!=true){publish("Bluetooth is off or unavailable. Enable Bluetooth and retry.");return false}}
        catch(_:SecurityException){publish("Bluetooth permission was revoked. Allow it again and retry.");return false}
        return true
    }
    fun scan() {
        if(!ready())return
        disconnect(false);stopScan()
        if(Build.VERSION.SDK_INT<31 && context.getSystemService(LocationManager::class.java)?.isProviderEnabled(LocationManager.NETWORK_PROVIDER)!=true && context.getSystemService(LocationManager::class.java)?.isProviderEnabled(LocationManager.GPS_PROVIDER)!=true){publish("Enable Android Location services for BLE scanning on this Android version.");return}
        selected=0;devices.clear();snapshot=snapshot.copy(devices=emptyList(),characteristics=emptyList(),services=emptyList())
        val token=generation
        val callback=object:ScanCallback(){
            override fun onScanResult(type:Int,result:ScanResult){main.post{
                if(closed||scan!==this||token!=generation)return@post
                try{
                    val address=result.device.address
                    val entry=devices[address] ?: if(devices.size<100)(devices.size+1 to result.device).also{devices[address]=it}else return@post
                    val ad=result.scanRecord
                    val name=(ad?.deviceName ?: result.device.name ?: "Unnamed BLE device").take(80)
                    val summary="Services: ${ad?.serviceUuids?.joinToString() ?: "none advertised"}; manufacturer company IDs: ${ad?.manufacturerSpecificData?.let{data->(0 until data.size()).joinToString{data.keyAt(it).toString(16)}} ?: "none"}"
                    val rows=snapshot.devices.filter{it.id!=entry.first}+DeviceRow(entry.first,name,result.rssi,summary)
                    if(snapshot.devices.none{it.id==entry.first}){journal.event("device_seen",entry.first);eventCount++}
                    snapshot=snapshot.copy(devices=rows);publish()
                }catch(_:SecurityException){publish("Bluetooth permission was revoked. Stop and allow it again.")}
            }}
            override fun onScanFailed(code:Int){main.post{if(scan===this){stopScan();record("scan_failed",code);publish("Scan failed ($code). Wait briefly and retry.")}}}
        }
        try{scanner=manager!!.adapter.bluetoothLeScanner;scan=callback;scanner?.startScan(null,ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),callback) ?: error("Scanner unavailable");record("scan_started");publish("Scanning for 15 seconds. Select your Osmo by name; a name is not proof of model identity.");main.postDelayed({if(scan===callback){stopScan();publish("Scan complete. Select the Osmo, or scan again.")}},15000)}
        catch(_:Exception){stopScan();publish("Cannot scan. Check Bluetooth and permissions, then retry.")}
    }
    private fun stopScan(){val previous=scan;scan=null;try{if(previous!=null){scanner?.stopScan(previous);record("scan_stopped")}}catch(_:SecurityException){};scanner=null}
    fun connect(id:Int){
        if(!ready())return
        stopScan();disconnect(false)
        val device=devices.values.firstOrNull{it.first==id}?.second ?: return
        selected=id;val token=generation;record("connect");publish("Connecting to selected BLE device…")
        try{gatt=device.connectGatt(context.applicationContext,false,callback(token),BluetoothDevice.TRANSPORT_LE)
            if(gatt==null)error("No GATT client")
            main.postDelayed({if(token==generation&&snapshot.status.startsWith("Connecting")){record("operation_timeout");disconnect(false);publish("Connection timed out. Close Mimo, keep the gimbal nearby and retry.")}},15000)
        }catch(_:Exception){disconnect(false);publish("GATT connection could not start. Check permissions and Bluetooth.")}
    }
    private fun callback(token:Int)=object:BluetoothGattCallback(){
        private fun current(client:BluetoothGatt,action:()->Unit){main.post{if(!closed&&token==generation&&gatt===client)try{action()}catch(_:Exception){record("gatt_error");disconnect(false);publish("Bluetooth operation failed. Disconnect and retry; no device commands were sent.")}}}
        override fun onConnectionStateChange(client:BluetoothGatt,status:Int,newState:Int)=current(client){
            if(status!=BluetoothGatt.GATT_SUCCESS){record("gatt_error",status);disconnect(false);publish("GATT error $status. Check whether Mimo holds the connection or Android requests pairing.")}
            else if(newState==BluetoothProfile.STATE_CONNECTED){record("connected");publish("Connected. Discovering services…");if(!client.discoverServices())error("Discovery rejected");main.postDelayed({if(token==generation&&snapshot.status.startsWith("Connected. Discovering")){record("operation_timeout");disconnect(false);publish("Service discovery timed out. Retry the connection.")}},10000)}
            else if(newState==BluetoothProfile.STATE_DISCONNECTED){record("disconnected",status);disconnect(false);publish("Disconnected. You can reconnect or export the redacted log.")}
        }
        override fun onServicesDiscovered(client:BluetoothGatt,status:Int)=current(client){
            if(status!=BluetoothGatt.GATT_SUCCESS){record("gatt_error",status);publish("Service discovery failed ($status). Disconnect and retry.");return@current}
            handles.clear();parsers.clear();val rows=mutableListOf<CharacteristicRow>()
            for(service in client.services.take(64)){journal.event("service",selected,service=service.uuid.toString());eventCount++}
            for(service in client.services.take(64))for(c in service.characteristics.take(64)){
                if(handles.size>=256)break
                val id=handles.size+1;handles[id]=c;rows.add(CharacteristicRow(id,service.uuid.toString(),c.uuid.toString(),c.properties));record("inventory",id=id)
            }
            snapshot=snapshot.copy(characteristics=rows,services=client.services.take(64).map{it.uuid.toString()});publish("Ready: services discovered. Subscribe only to an appropriate notification channel. No characteristic values are written or read.")
        }
        override fun onDescriptorWrite(client:BluetoothGatt,descriptor:BluetoothGattDescriptor,status:Int)=current(client){
            val id=pending ?: return@current
            if(handles[id]!==descriptor.characteristic)return@current
            pending=null
            if(status==BluetoothGatt.GATT_SUCCESS){snapshot=snapshot.copy(characteristics=snapshot.characteristics.map{if(it.id==id)it.copy(subscribed=true)else it});record("subscribed",status,id);publish("Subscribed. Received data is inspected in memory; exported payloads are omitted.")}
            else{client.setCharacteristicNotification(descriptor.characteristic,false);record("gatt_error",status,id);publish("Subscription failed ($status). Authentication or OS pairing may be required; no bypass is attempted.")}
        }
        @Deprecated("Legacy callback") override fun onCharacteristicChanged(client:BluetoothGatt,c:BluetoothGattCharacteristic){if(Build.VERSION.SDK_INT<33){val bytes=c.value?.copyOf() ?: return;current(client){received(c,bytes)}}}
        override fun onCharacteristicChanged(client:BluetoothGatt,c:BluetoothGattCharacteristic,value:ByteArray){val copy=value.copyOf();current(client){received(c,copy)}}
    }
    fun subscribe(id:Int){
        if(!ready())return
        val client=gatt ?: return;val c=handles[id] ?: return
        if(pending!=null){publish("A subscription is in progress. Wait for it to finish.");return}
        if(snapshot.characteristics.firstOrNull{it.id==id}?.subscribed==true)return
        val descriptor=c.getDescriptor(cccd) ?: run{publish("This characteristic has no standard notification descriptor.");return}
        val value=if(c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY!=0)BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else if(c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE!=0)BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else return
        try{
            if(!client.setCharacteristicNotification(c,true))error("Local notification rejected")
            pending=id;val request=++subscriptionAttempt;record("subscribe",id=id)
            val accepted=if(Build.VERSION.SDK_INT>=33)client.writeDescriptor(descriptor,value)==BluetoothStatusCodes.SUCCESS else{descriptor.value=value;client.writeDescriptor(descriptor)}
            if(!accepted){pending=null;client.setCharacteristicNotification(c,false);error("Descriptor rejected")}
            val token=generation;main.postDelayed({if(token==generation&&pending==id&&request==subscriptionAttempt){record("operation_timeout");disconnect(false);publish("Notification subscription timed out. Reconnect and retry.")}},10000)
        }catch(_:Exception){pending=null;publish("Unable to enable notifications. Check Bluetooth access and reconnect.")}
    }
    private fun received(c:BluetoothGattCharacteristic,bytes:ByteArray){
        val id=handles.entries.firstOrNull{it.value===c}?.key ?: return
        if(bytes.size>512){record("parse_rejected",id=id,bytes=bytes.size);return}
        val parser=parsers.getOrPut(id){DumlParser()};val before=parser.rejected
        val frames=parser.feed(bytes)
        if(frames.isEmpty())record("notify",id=id,bytes=bytes.size)else for(frame in frames)record("notify",id=id,bytes=bytes.size,header=frame)
        if(before!=parser.rejected)record("parse_rejected",parser.rejected-before,id)
        publish("Receiving notifications: $eventCount events. Valid DUML headers are observations, not proof of OM6 command support.")
    }
    fun disconnect(show:Boolean=true){
        generation++;stopScan();val previous=gatt;gatt=null;pending=null
        try{previous?.disconnect()}catch(_:Exception){};try{previous?.close()}catch(_:Exception){}
        handles.clear();parsers.values.forEach{it.clear()};parsers.clear()
        snapshot=snapshot.copy(characteristics=emptyList(),services=emptyList())
        if(show){record("disconnected");publish("Disconnected. Log retained in memory for export.")}
    }
    fun export():String=journal.export(Build.VERSION.SDK_INT)
    fun close(){if(closed)return;disconnect(false);closed=true;main.removeCallbacksAndMessages(null)}
    companion object {
        fun requiredPermissions():Array<String> = if(Build.VERSION.SDK_INT>=31)arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        fun propertyText(value:Int):String=buildList{if(value and 2!=0)add("read");if(value and 8!=0)add("write");if(value and 4!=0)add("write without response");if(value and 16!=0)add("notify");if(value and 32!=0)add("indicate")}.joinToString().ifEmpty{"no supported flags"}
    }
}
