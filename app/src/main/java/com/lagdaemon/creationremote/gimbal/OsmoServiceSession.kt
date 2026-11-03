package com.lagdaemon.creationremote.gimbal

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Read-only diagnostic lifecycle, independent of Android for fake-transport testing. */
class OsmoServiceSession(private val port:Port,private val clock:()->Long,private val appVersion:String,private val api:Int,private val androidVersion:String) {
    enum class Phase { IDLE, CONNECTING, CONNECTED, DISCOVERING, READING, DISCONNECTING, FINISHED }
    enum class Request { ACCEPTED, REJECTED, PERMISSION_REQUIRED, RADIO_OFF }
    interface Port { fun connect():Request;fun discover():Request;fun read(key:Int):Request;fun disconnect():Boolean;fun close():Boolean }
    data class Descriptor(val uuid:String,val permissions:Int)
    data class Characteristic(val key:Int,val uuid:String,val instance:Int,val properties:Int,val permissions:Int,val descriptors:List<Descriptor>)
    data class Service(val uuid:String,val instance:Int,val type:Int,val characteristics:List<Characteristic>)
    data class Candidate(val alias:String,val om6Hint:Boolean,val vendorService:Boolean,val hidService:Boolean,val connectable:Boolean,val rssi:Int,val observations:Long,val localName:String="") {
        fun json()=JSONObject().put("alias",alias).put("om6NameHint",om6Hint).put("advertisedFFF0",vendorService).put("advertised1812",hidService).put("advertisedConnectable",connectable).put("rssi",rssi).put("observations",observations).put("identityVerified",false)
    }
    var phase=Phase.IDLE;private set
    var reason="not_started";private set
    var report:String?=null;private set
    var services=emptyList<Service>();private set
    var connected=false;private set
    var discoveryStarted=false;private set
    var discoveryComplete=false;private set
    var readOptIn=false;private set
    var readsPlanned=0;private set
    private var deadline=Long.MAX_VALUE
    private var overall=Long.MAX_VALUE
    private var started=0L
    private var startedUtc=""
    private var candidate:Candidate?=null
    private var initialBond:Int?=null
    private var initialConnection:Int?=null
    private var currentBond:Int?=null
    private var initialBluetooth:Boolean?=null
    private var discoveryAccepted=false
    private var closeCalled=false
    var closeSucceeded=false;private set
    private var eventsDropped=0
    private var disconnectRequested=false
    private var disconnectConfirmed=false
    private var authorizationRequired=false
    private val events=mutableListOf<JSONObject>()
    private val reads=mutableListOf<JSONObject>()
    private val queue=ArrayDeque<Pair<Service,Characteristic>>()
    private var pending:Pair<Service,Characteristic>?=null
    val active get()=phase !in setOf(Phase.IDLE,Phase.FINISHED)
    private fun event(kind:String,status:Int?=null){if(events.size==1000){events.removeAt(0);eventsDropped++};events.add(JSONObject().put("event",kind).put("elapsedMs",clock()-started).apply{status?.let{put("androidStatus",it)}})}
    private fun enter(next:Phase,timeout:Long){phase=next;deadline=clock()+timeout}
    fun start(explicit:Boolean,selected:Candidate,bond:Int?,connection:Int?,bluetooth:Boolean?):Boolean {
        if(!explicit||active||phase==Phase.FINISHED||!selected.connectable||(!selected.om6Hint&&!selected.vendorService))return false
        candidate=selected;initialBond=bond;currentBond=bond;initialConnection=connection;initialBluetooth=bluetooth;started=clock();startedUtc=Instant.now().toString();overall=started+90_000
        enter(Phase.CONNECTING,15_000);event("connect_requested")
        handle(port.connect(),"connect_request_rejected");return true
    }
    private fun handle(result:Request,rejected:String){when(result){
        Request.ACCEPTED->event("request_accepted")
        Request.REJECTED->end(rejected)
        Request.PERMISSION_REQUIRED->{authorizationRequired=true;end("permission_required")}
        Request.RADIO_OFF->end("bluetooth_disabled")
    }}
    fun connection(status:Int,newState:Int){
        if(!active)return
        event("connection_state_$newState",status)
        if(status in setOf(5,15)){authorizationRequired=true}
        if(newState==0){disconnectConfirmed=true
            if(phase==Phase.DISCONNECTING)finalizeReport()else{reason=if(status==0)"remote_disconnected" else "connection_error";finalizeReport()};return
        }
        if(phase==Phase.DISCONNECTING)return
        if(status!=0){end("connection_error");return}
        if(newState==2&&phase==Phase.CONNECTING){connected=true;event("connected");enter(Phase.CONNECTED,30_000)}
    }
    fun bond(state:Int){if(active&&currentBond!=state){currentBond=state;event("bond_state_observed",state)}}
    fun discover(explicit:Boolean,allowStandardReads:Boolean){
        if(!explicit||phase!=Phase.CONNECTED)return
        readOptIn=allowStandardReads;discoveryStarted=true;enter(Phase.DISCOVERING,10_000);event("service_discovery_requested")
        val result=port.discover();discoveryAccepted=result==Request.ACCEPTED;handle(result,"discovery_request_rejected")
    }
    fun discovered(status:Int,inventory:List<Service>){
        if(phase!=Phase.DISCOVERING)return
        event("service_discovery_result",status)
        if(status!=0){if(status in setOf(5,15))authorizationRequired=true;end("service_discovery_failed");return}
        services=inventory;discoveryComplete=true
        if(readOptIn){for(s in services)for(c in s.characteristics)if(safeRead(s.uuid,c.uuid)&&c.properties and 2!=0)queue.addLast(s to c)}
        readsPlanned=minOf(queue.size,8)
        while(queue.size>8)queue.removeLast()
        nextRead()
    }
    private fun nextRead(){
        pending=queue.removeFirstOrNull()
        val p=pending
        if(p==null){end("inspection_complete");return}
        enter(Phase.READING,5_000);event("standard_read_requested")
        val result=port.read(p.second.key)
        if(result!=Request.ACCEPTED){
            reads.add(readRow(p).put("result",result.name.lowercase()))
            if(result==Request.PERMISSION_REQUIRED)authorizationRequired=true
            end("read_request_rejected")
        }
    }
    private fun readRow(p:Pair<Service,Characteristic>)=JSONObject().put("service",p.first.uuid).put("serviceInstance",p.first.instance).put("characteristic",p.second.uuid).put("key",p.second.key).put("elapsedMs",clock()-started)
    fun read(key:Int,status:Int,value:ByteArray){
        val p=pending ?: return
        if(phase!=Phase.READING||p.second.key!=key)return
        event("standard_read_result",status)
        val row=readRow(p).put("androidStatus",status)
        if(status==0){val decoded=decode(p.first.uuid,p.second.uuid,value);row.put("result",if(decoded!=null)"success" else "unexpected_standard_format");if(decoded!=null)row.put("value",decoded)}
        else{row.put("result",if(status in setOf(5,15))"authorization_required" else "read_failed");if(status in setOf(5,15))authorizationRequired=true}
        reads.add(row);pending=null;nextRead()
    }
    fun tick(){
        if(!active)return
        if(phase==Phase.DISCONNECTING){if(clock()>=deadline){event("disconnect_ack_timeout");finalizeReport()};return}
        if(clock()>=overall){end("session_timeout");return}
        if(clock()>=deadline){if(phase==Phase.READING)pending?.let{reads.add(readRow(it).put("result","timeout"))};end(when(phase){Phase.CONNECTING->"connection_timeout";Phase.CONNECTED->"awaiting_discovery_timeout";Phase.DISCOVERING->"service_discovery_timeout";else->"read_timeout"})}
    }
    fun end(termination:String){
        if(!active||phase==Phase.DISCONNECTING)return
        reason=termination;event("ending_$termination");pending=null;queue.clear()
        enter(Phase.DISCONNECTING,3_000);disconnectRequested=true
        if(!port.disconnect()){event("disconnect_request_failed");finalizeReport()}
    }
    fun radioOff(){end("bluetooth_disabled")}
    fun permissionLost(){authorizationRequired=true;end("permission_required")}
    fun dispose(reason:String="application_closed"){if(active){end(reason);if(active)finalizeReport()}}
    private fun finalizeReport(){
        if(phase==Phase.FINISHED)return
        closeCalled=true;closeSucceeded=port.close();event("gatt_closed")
        phase=Phase.FINISHED
        val outcome=when{
            authorizationRequired->"user_authorization_or_pairing_required"
            reason in setOf("cancelled","backgrounded","application_closed","awaiting_discovery_timeout","bluetooth_disabled","session_timeout")->"diagnostic_session_incomplete"
            discoveryComplete->"connection_and_service_discovery_succeeded"
            connected&&discoveryStarted->"connection_succeeded_service_discovery_failed"
            !connected&&reason in setOf("connect_request_rejected")->"gatt_unavailable_or_inconclusive"
            !connected->"discovery_succeeded_connection_failed"
            else->"gatt_unavailable_or_inconclusive"
        }
        report=JSONObject().put("schema","djehuti-osmo-service-inspection").put("version",1).put("appVersion",appVersion).put("androidApi",api).put("androidVersion",androidVersion)
            .put("session",UUID.randomUUID().toString()).put("startedUtc",startedUtc).put("endedUtc",Instant.now().toString()).put("elapsedMs",clock()-started)
            .put("candidate",candidate?.json()).put("initialBondState",initialBond ?: JSONObject.NULL).put("lastObservedBondState",currentBond ?: JSONObject.NULL).put("initialGattConnectionState",initialConnection ?: JSONObject.NULL).put("initialBluetoothEnabled",initialBluetooth ?: JSONObject.NULL)
            .put("connectionSucceeded",connected).put("serviceDiscoveryRequested",discoveryStarted).put("serviceDiscoveryRequestAccepted",discoveryAccepted).put("serviceDiscoveryCompleted",discoveryComplete)
            .put("standardReadsOptedIn",readOptIn).put("standardReadsPlanned",readsPlanned).put("readResults",JSONArray(reads))
            .put("inventory",inventoryJson(services)).put("events",JSONArray(events)).put("droppedEvents",eventsDropped).put("outcome",outcome).put("termination",reason)
            .put("cleanup",JSONObject().put("disconnectRequested",disconnectRequested).put("disconnectCallbackObserved",disconnectConfirmed).put("closeCalled",closeCalled).put("closeSucceeded",closeSucceeded))
            .put("interfaceAssessment",JSONObject().put("fff0Present",services.any{it.uuid==uuid(0xfff0)}).put("hidServicePresent",services.any{it.uuid==uuid(0x1812)}).put("hidReportMapPresent",services.any{it.uuid==uuid(0x1812)&&it.characteristics.any{c->c.uuid==uuid(0x2a4b)}}).put("hidReportCharacteristicPresent",services.any{it.uuid==uuid(0x1812)&&it.characteristics.any{c->c.uuid==uuid(0x2a4d)}}))
            .put("limits","GATT results do not establish motor control, live HID input behavior, or Classic Bluetooth availability. Remote disconnect reasons beyond Android status are unknown. No bond changes, writes, descriptor reads/writes, notification subscriptions or proprietary packets. No MACs, exact names, serial numbers, pairing material or arbitrary value bytes exported.")
            .toString(2)
    }
    companion object {
        fun uuid(value:Int)="%08x-0000-1000-8000-00805f9b34fb".format(value)
        fun safeRead(service:String,characteristic:String)=listOf(0x1800 to 0x2a01,0x180f to 0x2a19,0x1812 to 0x2a4a).any{service==uuid(it.first)&&characteristic==uuid(it.second)}
        fun decode(service:String,characteristic:String,value:ByteArray):JSONObject? {
            if(!safeRead(service,characteristic))return null
            fun byte(n:Int)=value[n].toInt() and 255
            return when{
                characteristic==uuid(0x2a19)&&value.size==1&&byte(0)<=100->JSONObject().put("batteryPercent",byte(0))
                characteristic==uuid(0x2a01)&&value.size==2->JSONObject().put("appearance",byte(0) or (byte(1) shl 8))
                characteristic==uuid(0x2a4a)&&value.size==4->JSONObject().put("bcdHid",byte(0) or (byte(1) shl 8)).put("countryCode",byte(2)).put("flags",byte(3))
                else->null
            }
        }
        fun operations(properties:Int)=listOf(1 to "broadcast",2 to "read",4 to "write_without_response",8 to "write",16 to "notify",32 to "indicate",64 to "signed_write",128 to "extended_properties").filter{properties and it.first!=0}.map{it.second}
        fun inventoryJson(services:List<Service>)=JSONArray(services.map{s->JSONObject().put("uuid",s.uuid).put("instance",s.instance).put("type",s.type).put("characteristics",JSONArray(s.characteristics.map{c->JSONObject().put("uuid",c.uuid).put("instance",c.instance).put("key",c.key).put("properties",c.properties).put("operations",JSONArray(operations(c.properties))).put("permissions",c.permissions).put("descriptors",JSONArray(c.descriptors.map{d->JSONObject().put("uuid",d.uuid).put("permissions",d.permissions)}))}))})
    }
}
