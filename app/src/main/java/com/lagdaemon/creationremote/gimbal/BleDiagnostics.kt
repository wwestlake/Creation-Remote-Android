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
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.UUID

@SuppressLint("MissingPermission") // Grants checked before operations; revocations are caught.
class BleDiagnostics(private val context:Context,private val update:(Snapshot)->Unit) {
    data class DeviceRow(val id:Int,val name:String,val rssi:Int)
    data class Snapshot(val phase:OsmoDiagnosticRun.Phase=OsmoDiagnosticRun.Phase.IDLE,val status:String="Power on your Osmo and close DJI Mimo, then start diagnostics.",val devices:List<DeviceRow> = emptyList(),val report:String?=null,val permissionRequest:Int=0) {
        val active get()=phase !in setOf(OsmoDiagnosticRun.Phase.IDLE,OsmoDiagnosticRun.Phase.COMPLETE,OsmoDiagnosticRun.Phase.FAILED,OsmoDiagnosticRun.Phase.CANCELLED)
    }
    private val main=Handler(Looper.getMainLooper())
    private val manager=context.getSystemService(BluetoothManager::class.java)
    private val run=OsmoDiagnosticRun{SystemClock.elapsedRealtime()}
    private var scanner:BluetoothLeScanner?=null
    private var scan:ScanCallback?=null
    private var gatt:BluetoothGatt?=null
    private var generation=0
    private var closed=false
    private var snapshot=Snapshot()
    private val devices=linkedMapOf<String,Pair<Int,BluetoothDevice>>() // Addresses/names never enter report.
    private val rows=linkedMapOf<Int,DeviceRow>()
    private val candidates=linkedMapOf<Int,OsmoDiagnosticRun.Candidate>()
    private val handles=linkedMapOf<Int,BluetoothGattCharacteristic>()
    private val parsers=mutableMapOf<Int,DumlParser>()
    private var pending:Int?=null
    private var recognized=0
    private var journal=BleJournal({SystemClock.elapsedRealtime()})
    private val cccd=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val tick=object:Runnable {override fun run(){if(!closed&&run.active){if(run.phase==OsmoDiagnosticRun.Phase.SEARCHING&&SystemClock.elapsedRealtime()>=run.deadline){stopScan();perform(run.scanned(candidates.values.toList()))}else perform(run.tick());if(run.active)main.postDelayed(this,250)}}}
    private fun record(kind:String,status:Int?=null,id:Int?=null,bytes:Int?=null,header:DumlParser.Header?=null){
        val c=id?.let{handles[it]};journal.event(kind,run.selected,status,c?.service?.uuid?.toString(),c?.uuid?.toString(),c?.properties,bytes,header,id,c?.service?.instanceId)
    }
    private fun publish(){
        snapshot=snapshot.copy(phase=run.phase,devices=if(run.phase==OsmoDiagnosticRun.Phase.CHOOSING)run.choices.mapNotNull{rows[it.id]}else emptyList(),status=when(run.phase){
            OsmoDiagnosticRun.Phase.IDLE->"Power on your Osmo and close DJI Mimo, then start diagnostics."
            OsmoDiagnosticRun.Phase.PERMISSION->"Waiting for Android permission"
            OsmoDiagnosticRun.Phase.SEARCHING->"Searching for Osmo"
            OsmoDiagnosticRun.Phase.CHOOSING->"Please choose your Osmo below. We could not identify one device confidently. Names are hints; choose only your gimbal."
            OsmoDiagnosticRun.Phase.CONNECTING->"Connecting"
            OsmoDiagnosticRun.Phase.EXAMINING->"Examining Bluetooth services"
            OsmoDiagnosticRun.Phase.SUBSCRIBING->"Preparing communication monitoring"
            OsmoDiagnosticRun.Phase.MONITORING->"Monitoring communication — ${((run.deadline-SystemClock.elapsedRealtime()).coerceAtLeast(0)+999)/1000} seconds remaining"
            OsmoDiagnosticRun.Phase.PREPARING->"Preparing diagnostic report"
            OsmoDiagnosticRun.Phase.COMPLETE->when{
                recognized==0->"Osmo Diagnostics Complete. Device connected and Bluetooth services examined. Its notification channel was not recognized. Report ready for engineering review; compatibility is still undetermined."
                run.subscriptionFailures>0->"Osmo Diagnostics Complete. Device connected and services examined, but a communication subscription failed. Report ready for review."
                journal.notifications==0L->"Osmo Diagnostics Complete. Connected and monitored for 30 seconds. No notifications arrived. This does not mean the device is unsupported. Report ready."
                else->"Osmo Diagnostics Complete. Device connected, Bluetooth services examined, and communication monitored for 30 seconds. Diagnostic report ready."
            }
            OsmoDiagnosticRun.Phase.CANCELLED->"Diagnostics cancelled. Evidence collected so far is ready to share."
            OsmoDiagnosticRun.Phase.FAILED->failureText(run.reason)+" Evidence collected so far is ready to share."
        });update(snapshot)
    }
    fun start(){
        if(closed||run.active)return
        cleanup();journal=BleJournal({SystemClock.elapsedRealtime()});devices.clear();rows.clear();candidates.clear();recognized=0
        snapshot=snapshot.copy(report=null)
        perform(run.start());main.removeCallbacks(tick);main.postDelayed(tick,250)
    }
    private fun perform(actions:List<OsmoDiagnosticRun.Action>){
        publish()
        for(action in actions)when(action){
            OsmoDiagnosticRun.Action.RequestPermission->{snapshot=snapshot.copy(permissionRequest=snapshot.permissionRequest+1);update(snapshot)}
            OsmoDiagnosticRun.Action.Scan->scan()
            is OsmoDiagnosticRun.Action.Connect->connect(action.id)
            is OsmoDiagnosticRun.Action.Subscribe->subscribe(action.id)
            OsmoDiagnosticRun.Action.Monitor->{record("monitor_started");publish()}
            OsmoDiagnosticRun.Action.Finish->{
                if(run.reason.endsWith("timeout"))record("operation_timeout")
                if(run.reason=="subscription_timeout")pending?.let{record("subscription_failed",-3,it)}
                if(run.reason=="monitoring_complete")record("monitor_finished")
                if(run.reason=="cancelled")record("cancelled")
                cleanup();val questions=buildList{
                    add("model_identity_not_verified");add("physical_compatibility_unverified")
                    if(recognized==0)add("notification_channel_unrecognized")
                    if(journal.notifications==0L)add("no_notifications_observed")
                    if(run.subscriptionFailures>0||run.reason=="subscription_timeout")add("subscription_failed")
                }
                journal.finish(run.reason,questions);record("report_prepared")
                val version=context.packageManager.getPackageInfo(context.packageName,0).versionName ?: "unknown"
                snapshot=snapshot.copy(report=journal.export(Build.VERSION.SDK_INT,version,Build.VERSION.RELEASE,manager?.adapter!=null,context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)))
                run.prepared();publish();main.removeCallbacks(tick)
            }
        }
    }
    fun permissionsResolved(){
        if(run.phase!=OsmoDiagnosticRun.Phase.PERMISSION)return
        if(requiredPermissions().any{ContextCompat.checkSelfPermission(context,it)!=PackageManager.PERMISSION_GRANTED}){record("permission_denied");perform(run.permission(false));return}
        try{
            if(manager?.adapter?.isEnabled!=true||!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)){record("bluetooth_unavailable");fail("bluetooth_unavailable");return}
            if(Build.VERSION.SDK_INT<31){val location=context.getSystemService(LocationManager::class.java);if(location?.isProviderEnabled(LocationManager.NETWORK_PROVIDER)!=true&&location?.isProviderEnabled(LocationManager.GPS_PROVIDER)!=true){record("location_required");fail("location_required");return}}
            perform(run.permission(true))
        }catch(_:SecurityException){record("permission_denied");fail("permission_denied")}
    }
    fun choose(id:Int){perform(run.choose(id))}
    private fun fail(code:String){perform(run.fail(code))}
    private fun scan(){
        val token=generation
        val callback=object:ScanCallback(){
            override fun onScanResult(type:Int,result:ScanResult){main.post{
                if(closed||scan!==this||token!=generation||run.phase!=OsmoDiagnosticRun.Phase.SEARCHING)return@post
                try{
                    val ad=result.scanRecord;val name=(ad?.deviceName ?: result.device.name ?: "Unnamed nearby device").take(80)
                    val named=osmoName(name);val nameHint=candidateName(name);val vendor=ad?.serviceUuids?.any{it.uuid==serviceUuid}==true
                    if(!nameHint&&!vendor)return@post
                    val entry=devices[result.device.address] ?: if(devices.size<100)(devices.size+1 to result.device).also{devices[result.device.address]=it; journal.event("device_seen",it.first)}else return@post
                    rows[entry.first]=DeviceRow(entry.first,name,result.rssi)
                    val previous=candidates[entry.first]
                    val candidate=OsmoDiagnosticRun.Candidate(entry.first,named||previous?.osmoName==true,vendor||previous?.vendorService==true,nameHint||previous?.nameHint==true)
                    candidates[entry.first]=candidate;journal.candidate(entry.first,candidate.osmoName,candidate.vendorService,result.rssi,candidate.nameHint)
                }catch(_:SecurityException){record("permission_denied");fail("permission_denied")}
            }}
            override fun onScanFailed(code:Int){main.post{if(scan===this&&token==generation){record("scan_failed",code);fail("scan_failed")}}}
        }
        try{scanner=manager!!.adapter.bluetoothLeScanner;scan=callback;scanner?.startScan(null,ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),callback) ?: error("Scanner unavailable");record("scan_started")}
        catch(_:SecurityException){record("permission_denied");fail("permission_denied")}
        catch(_:Exception){record("scan_failed");fail("scan_failed")}
    }
    private fun stopScan(){val previous=scan;scan=null;try{if(previous!=null){scanner?.stopScan(previous);record("scan_stopped")}}catch(_:SecurityException){};scanner=null}
    private fun connect(id:Int){
        stopScan();val device=devices.values.firstOrNull{it.first==id}?.second ?: run{fail("connect_failed");return}
        val token=generation;record("connect")
        try{gatt=device.connectGatt(context.applicationContext,false,callback(token),BluetoothDevice.TRANSPORT_LE);if(gatt==null)fail("connect_failed")}
        catch(_:SecurityException){record("permission_denied");fail("permission_denied")}
        catch(_:Exception){fail("connect_failed")}
    }
    private fun callback(token:Int)=object:BluetoothGattCallback(){
        private fun current(client:BluetoothGatt,action:()->Unit){main.post{if(!closed&&token==generation&&gatt===client&&run.active)try{action()}catch(_:SecurityException){record("permission_denied");fail("permission_denied")}catch(_:Exception){record("gatt_error");fail("gatt_error")}}}
        override fun onConnectionStateChange(client:BluetoothGatt,status:Int,newState:Int)=current(client){
            if(status!=BluetoothGatt.GATT_SUCCESS){record("gatt_error",status);fail("gatt_error")}
            else if(newState==BluetoothProfile.STATE_CONNECTED&&run.phase==OsmoDiagnosticRun.Phase.CONNECTING){record("connected");perform(run.connected());if(!client.discoverServices())fail("discovery_failed")}
            else if(newState==BluetoothProfile.STATE_DISCONNECTED){record("disconnected",status);fail("disconnected")}
        }
        override fun onServicesDiscovered(client:BluetoothGatt,status:Int)=current(client){
            if(run.phase!=OsmoDiagnosticRun.Phase.EXAMINING)return@current
            if(status!=BluetoothGatt.GATT_SUCCESS){record("gatt_error",status);fail("discovery_failed");return@current}
            for(service in client.services){journal.event("service",run.selected,service=service.uuid.toString(),instance=service.instanceId)
                for(c in service.characteristics){val id=handles.size+1;handles[id]=c;record("inventory",id=id)}
            }
            record("discovery_complete",status)
            val channels=handles.filter{(_,c)->recognizedChannel(c.service.uuid,c.uuid,c.properties)}.keys.toList()
            recognized=channels.size;perform(run.discovered(channels))
        }
        override fun onDescriptorWrite(client:BluetoothGatt,descriptor:BluetoothGattDescriptor,status:Int)=current(client){
            val id=pending ?: return@current
            if(descriptor.uuid!=cccd||handles[id]!==descriptor.characteristic)return@current
            pending=null;record(if(status==BluetoothGatt.GATT_SUCCESS)"subscribed" else "subscription_failed",status,id)
            if(status!=BluetoothGatt.GATT_SUCCESS)client.setCharacteristicNotification(descriptor.characteristic,false)
            perform(run.subscribed(id,status==BluetoothGatt.GATT_SUCCESS))
        }
        @Deprecated("Legacy callback") override fun onCharacteristicChanged(client:BluetoothGatt,c:BluetoothGattCharacteristic){if(Build.VERSION.SDK_INT<33){val bytes=c.value?.copyOf() ?: return;current(client){received(c,bytes)}}}
        override fun onCharacteristicChanged(client:BluetoothGatt,c:BluetoothGattCharacteristic,value:ByteArray){val copy=value.copyOf();current(client){received(c,copy)}}
    }
    private fun subscribe(id:Int){
        val client=gatt ?: return fail("disconnected");val c=handles[id] ?: return fail("gatt_error")
        record("subscribe",id=id)
        val descriptor=c.getDescriptor(cccd)
        if(descriptor==null){record("subscription_failed",-1,id);perform(run.subscribed(id,false));return}
        val value=if(c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY!=0)BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        try{
            if(!client.setCharacteristicNotification(c,true))error("Local notification rejected")
            pending=id
            val accepted=if(Build.VERSION.SDK_INT>=33)client.writeDescriptor(descriptor,value)==BluetoothStatusCodes.SUCCESS else{descriptor.value=value;client.writeDescriptor(descriptor)}
            if(!accepted)error("Descriptor rejected")
        }catch(_:SecurityException){record("permission_denied");fail("permission_denied")}
        catch(_:Exception){pending=null;try{client.setCharacteristicNotification(c,false)}catch(_:Exception){};record("subscription_failed",-2,id);perform(run.subscribed(id,false))}
    }
    private fun received(c:BluetoothGattCharacteristic,bytes:ByteArray){
        val id=handles.entries.firstOrNull{it.value===c}?.key ?: return
        record("notify",id=id,bytes=bytes.size)
        if(bytes.size>512){record("parse_rejected",1,id);return}
        val parser=parsers.getOrPut(id){DumlParser()}
        val before=listOf(parser.rejected,parser.headerChecksumFailures,parser.frameChecksumFailures,parser.invalidHeaders,parser.discarded)
        for(frame in parser.feed(bytes))record("frame",id=id,header=frame)
        listOf("parse_rejected","header_checksum_failure","frame_checksum_failure","invalid_header","discarded_bytes").zip(listOf(parser.rejected,parser.headerChecksumFailures,parser.frameChecksumFailures,parser.invalidHeaders,parser.discarded)).forEachIndexed{index,(kind,total)->if(total>before[index])record(kind,total-before[index],id)}
    }
    private fun cleanup(){
        generation++;stopScan();val previous=gatt;gatt=null;pending=null
        try{previous?.disconnect()}catch(_:Exception){};try{previous?.close()}catch(_:Exception){}
        if(previous!=null)record("local_close")
        handles.clear();parsers.values.forEach{it.clear()};parsers.clear()
    }
    fun cancel(){perform(run.cancel())}
    fun background(){if(run.active&&run.phase!=OsmoDiagnosticRun.Phase.PERMISSION)cancel()}
    fun close(){if(closed)return;cancel();cleanup();closed=true;main.removeCallbacksAndMessages(null)}
    companion object {
        val serviceUuid:UUID=UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        private val notifyUuid=UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
        fun osmoName(name:String):Boolean=Regex("(?i)(^|[^a-z0-9])(osmo[ _-]*mobile[ _-]*6|om[ _-]*6)([^a-z0-9]|$)").containsMatchIn(name)
        fun candidateName(name:String):Boolean=Regex("(?i)(^|[^a-z0-9])(osmo|osmo[ _-]*mobile[ _-]*[2-7]|om[ _-]*(?:[2-7]|se))([^a-z0-9]|$)").containsMatchIn(name)
        fun recognizedChannel(service:UUID,characteristic:UUID,properties:Int)=service==serviceUuid&&characteristic==notifyUuid&&properties and (16 or 32)!=0
        fun requiredPermissions():Array<String> = if(Build.VERSION.SDK_INT>=31)arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        private fun failureText(reason:String)=when(reason){
            "permission_denied","permission_timeout"->"Android permission was not granted. Allow Nearby devices (or Location on older Android) in Settings and try again."
            "bluetooth_unavailable"->"Bluetooth is off or unavailable. Enable Bluetooth and try again."
            "location_required"->"This Android version needs Location services enabled to search nearby devices. Enable Location and try again."
            "no_candidate"->"No likely Osmo was found. Power on the gimbal, keep it nearby, close DJI Mimo, and try again."
            "selection_timeout"->"No device was selected. Start again when your gimbal is ready."
            "scan_failed"->"Android could not search for nearby devices. Check Bluetooth, wait a few seconds, and try again."
            "connection_timeout","connect_failed","gatt_error"->"Could not connect to the gimbal. Keep it nearby and close DJI Mimo. If Android asks to pair, confirm only your gimbal, then retry."
            "discovery_failed","discovery_timeout"->"Connected, but could not finish examining the device. Close DJI Mimo and retry."
            "subscription_timeout"->"The device did not confirm communication monitoring. Close DJI Mimo and retry."
            "disconnected"->"The device disconnected before diagnostics finished. Keep it powered on and nearby, then retry."
            else->"Diagnostics could not finish in time. Check Bluetooth, keep the gimbal nearby, and retry."
        }
    }
}
