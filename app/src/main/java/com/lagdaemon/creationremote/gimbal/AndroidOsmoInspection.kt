package com.lagdaemon.creationremote.gimbal

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Android adapter for the fake-testable inspection session; deliberately no write APIs. */
@SuppressLint("MissingPermission")
class AndroidOsmoInspection(private val context:Context,private val update:(Snapshot)->Unit) {
    data class Snapshot(val phase:OsmoServiceSession.Phase=OsmoServiceSession.Phase.IDLE,val status:String="Scan, then select Connect to Osmo.",val report:String?=null,val inventory:List<String> = emptyList()){
        val active get()=phase !in setOf(OsmoServiceSession.Phase.IDLE,OsmoServiceSession.Phase.FINISHED)
    }
    private val main=Handler(Looper.getMainLooper())
    private val manager=context.getSystemService(BluetoothManager::class.java)
    private var session:OsmoServiceSession?=null
    private var client:BluetoothGatt?=null
    private var device:BluetoothDevice?=null
    private var generation=0
    private var closed=false
    private val handles=linkedMapOf<Int,BluetoothGattCharacteristic>()
    private val timer=object:Runnable{override fun run(){
        val s=session ?: return
        if(closed||!s.active)return
        try{if(manager?.adapter?.isEnabled!=true)s.radioOff();device?.let{s.bond(it.bondState)}}catch(_:SecurityException){s.permissionLost()}
        s.tick();publish();if(s.active)main.postDelayed(this,250)
    }}
    private val port=object:OsmoServiceSession.Port {
        private fun request(action:()->Boolean):OsmoServiceSession.Request=try{
            if(manager?.adapter?.isEnabled!=true)OsmoServiceSession.Request.RADIO_OFF else if(action())OsmoServiceSession.Request.ACCEPTED else OsmoServiceSession.Request.REJECTED
        }catch(_:SecurityException){OsmoServiceSession.Request.PERMISSION_REQUIRED}catch(_:Exception){OsmoServiceSession.Request.REJECTED}
        override fun connect()=request{
            val d=device ?: return@request false
            val token=++generation
            client=d.connectGatt(context.applicationContext,false,callback(token),BluetoothDevice.TRANSPORT_LE)
            client!=null
        }
        override fun discover()=request{client?.discoverServices()==true}
        override fun read(key:Int)=request{
            val c=handles[key] ?: return@request false
            // Independent adapter guard: never trust a caller to authorize a proprietary read.
            if(!OsmoServiceSession.safeRead(c.service.uuid.toString(),c.uuid.toString())||c.properties and BluetoothGattCharacteristic.PROPERTY_READ==0)return@request false
            client?.readCharacteristic(c)==true
        }
        override fun disconnect():Boolean=try{val c=client;if(c==null)false else{c.disconnect();true}}catch(_:Exception){false}
        override fun close():Boolean{
            generation++;val c=client;handles.clear()
            return try{c?.close();client=null;true}catch(_:Exception){false}
        }
    }
    fun connect(explicit:Boolean,candidate:OsmoServiceSession.Candidate,selected:BluetoothDevice):Boolean{
        if(closed||!explicit||session?.active==true||client!=null)return false
        device=selected;handles.clear()
        val version=context.packageManager.getPackageInfo(context.packageName,0).versionName ?: "unknown"
        val s=OsmoServiceSession(port,{SystemClock.elapsedRealtime()},version,Build.VERSION.SDK_INT,Build.VERSION.RELEASE)
        session=s
        val bond=try{selected.bondState}catch(_:SecurityException){null}
        val connection=try{manager?.getConnectionState(selected,BluetoothProfile.GATT)}catch(_:Exception){null}
        val enabled=try{manager?.adapter?.isEnabled}catch(_:Exception){null}
        val accepted=s.start(explicit,candidate,bond,connection,enabled)
        publish();main.removeCallbacks(timer);if(s.active)main.postDelayed(timer,250)
        return accepted
    }
    fun discover(explicit:Boolean,reads:Boolean){session?.discover(explicit,reads);publish()}
    fun disconnect(){session?.end("cancelled");publish()}
    fun awaitingAuthorization():Boolean=try{device?.bondState==BluetoothDevice.BOND_BONDING}catch(_:Exception){false}
    fun background(){session?.dispose("backgrounded");publish()}
    fun reset(){if(session?.active!=true){session=null;publish()}}
    fun close(){if(closed)return;session?.dispose();if(client!=null)port.close();closed=true;main.removeCallbacksAndMessages(null)}
    private fun callback(token:Int)=object:BluetoothGattCallback(){
        private fun current(gatt:BluetoothGatt,block:()->Unit){main.post{
            if(!closed&&token==generation&&client===gatt&&session?.active==true){
                try{block()}catch(_:SecurityException){session?.permissionLost()}catch(_:Exception){session?.end("android_callback_error")};publish()
            }
        }}
        override fun onConnectionStateChange(gatt:BluetoothGatt,status:Int,newState:Int)=current(gatt){session?.connection(status,newState)}
        override fun onServicesDiscovered(gatt:BluetoothGatt,status:Int)=current(gatt){
            if(session?.phase!=OsmoServiceSession.Phase.DISCOVERING)return@current
            val inventory=if(status==BluetoothGatt.GATT_SUCCESS)gatt.services.map{s->
                OsmoServiceSession.Service(s.uuid.toString(),s.instanceId,s.type,s.characteristics.map{c->
                    val id=handles.size+1;handles[id]=c
                    OsmoServiceSession.Characteristic(id,c.uuid.toString(),c.instanceId,c.properties,c.permissions,c.descriptors.map{d->OsmoServiceSession.Descriptor(d.uuid.toString(),d.permissions)})
                })
            }else emptyList()
            session?.discovered(status,inventory)
        }
        @Deprecated("Legacy callback") override fun onCharacteristicRead(gatt:BluetoothGatt,c:BluetoothGattCharacteristic,status:Int){if(Build.VERSION.SDK_INT<33){val bytes=c.value?.copyOf() ?: byteArrayOf();current(gatt){read(c,status,bytes)}}}
        override fun onCharacteristicRead(gatt:BluetoothGatt,c:BluetoothGattCharacteristic,value:ByteArray,status:Int){val bytes=value.copyOf();current(gatt){read(c,status,bytes)}}
        private fun read(c:BluetoothGattCharacteristic,status:Int,value:ByteArray){val id=handles.entries.firstOrNull{it.value===c}?.key ?: return;session?.read(id,status,value)}
    }
    private fun publish(){
        val s=session
        val status=when(s?.phase){
            null,OsmoServiceSession.Phase.IDLE->"Scan, then select Connect to Osmo."
            OsmoServiceSession.Phase.CONNECTING->"Connecting to selected Osmo candidate…"
            OsmoServiceSession.Phase.CONNECTED->"Connected. Select Discover Services within 30 seconds."
            OsmoServiceSession.Phase.DISCOVERING->"Discovering services…"
            OsmoServiceSession.Phase.READING->"Reading only the standard information you authorized…"
            OsmoServiceSession.Phase.DISCONNECTING->"Disconnecting and releasing resources…"
            OsmoServiceSession.Phase.FINISHED->when{
                !s.closeSucceeded->"Android resource cleanup failed. Do not start another connection. Restore Bluetooth permission if needed and exit the app. The report records that release was not confirmed."
                s.discoveryComplete->"Services discovered. Connection closed. Report includes metadata, any opted-in read results, and cleanup status. No motor capability is established."
                s.reason=="permission_required"->"Android authorization is required. Allow its normal request if shown; no pairing bypass was attempted. Connection closed; report ready."
                s.reason in setOf("cancelled","application_closed","awaiting_discovery_timeout")->"Disconnected. Diagnostic session incomplete; collected evidence is ready to export."
                s.connected->"Connected, but service inspection could not finish. Another Android service, device security or a device limitation may be involved; the report records the actual result. Connection closed."
                else->"Connection failed or GATT was unavailable. Keep the gimbal disconnected from the existing phone session and nearby, then retry manually. This does not rule out other Bluetooth interfaces. Report ready."
            }
        }
        val inventory=s?.services?.flatMap{service->listOf("${if(service.uuid==OsmoServiceSession.uuid(0x1812))"Standard input-device service" else if(service.uuid==OsmoServiceSession.uuid(0xfff0))"Proprietary candidate service" else "Service"}: ${service.uuid}")+service.characteristics.map{c->"  ${c.uuid}: ${OsmoServiceSession.operations(c.properties).joinToString()}; ${c.descriptors.size} descriptors"}} ?: emptyList()
        update(Snapshot(s?.phase ?: OsmoServiceSession.Phase.IDLE,status,s?.report,inventory))
    }
}
