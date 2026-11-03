package com.lagdaemon.creationremote.gimbal

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Discovery evidence only: capture precedes recognition, identifiers never leave this session. */
class DiscoveryEvidence(private val clock:()->Long, val condition:String, private val classify:(Observation)->Boolean={
    BleDiagnostics.candidateName(it.name.orEmpty())||BleDiagnostics.osmoName(it.name.orEmpty())||it.services.contains(BleDiagnostics.serviceUuid.toString())
}) {
    data class Observation(val name:String?,val services:List<String>,val manufacturers:Map<Int,ByteArray>,val serviceData:Map<String,ByteArray>,val flags:Int?,val rssi:Int,val raw:ByteArray?,val callbackType:Int=1,val legacy:Boolean=true,val connectable:Boolean?=null)
    private data class Device(val alias:String,val first:Long,var last:Long,var count:Long=0,var minRssi:Int=127,var maxRssi:Int=-127,var lastRssi:Int=0,var recognized:Boolean=false,var rejected:Long=0,val variants:MutableMap<String,JSONObject> = linkedMapOf())
    private val secret=ByteArray(32).also{SecureRandom().nextBytes(it)}
    private val mac=Mac.getInstance("HmacSHA256").also{it.init(SecretKeySpec(secret,"HmacSHA256"))}
    private val started=clock()
    val startedUtc=Instant.now().toString()
    val sessionId=UUID.randomUUID().toString()
    private val devices=linkedMapOf<String,Device>() // MACs only in RAM, never exported or logged.
    private val observations=ArrayDeque<JSONObject>()
    var total=0L;private set
    var dropped=0L;private set
    var variantsDropped=0L;private set
    var overflow=false;private set
    val distinct get()=devices.size
    fun aliasFor(address:String):String?=devices[address]?.alias
    val recognized get()=devices.values.count{it.recognized}
    val rows get()=devices.values.map{"${it.alias}: ${it.count} observations, ${it.lastRssi} dBm · ${if(it.recognized)"possible DJI hint" else "not recognized by current rules"}"}
    init{require(condition in conditions)}
    private fun tag(bytes:ByteArray):String {
        val hex="0123456789abcdef"
        return buildString{for(b in mac.doFinal(bytes).take(12)){val n=b.toInt() and 255;append(hex[n ushr 4]);append(hex[n and 15])}}
    }
    private fun nameEvidence(name:String?):JSONObject=JSONObject().put("present",name!=null).put("length",name?.length ?: 0)
        .put("value","redacted").put("sessionTag",name?.let{tag(it.toByteArray())} ?: JSONObject.NULL)
        .put("safeHints",JSONArray(listOf("DJI","OSMO","OM6","MOBILE","GIMBAL").filter{(name ?: "").uppercase(java.util.Locale.ROOT).contains(it)}))
    private fun dataEvidence(data:ByteArray)=JSONObject().put("bytes",data.size).put("sessionTag",tag(data)).put("value","redacted")
    private fun adTypes(raw:ByteArray?):JSONArray {
        val types=JSONArray();if(raw==null)return types
        var offset=0
        while(offset<raw.size){val length=raw[offset].toInt() and 255;if(length==0)break
            if(offset+length>=raw.size){types.put(JSONObject().put("malformedOrTruncated",true));break}
            types.put(JSONObject().put("type",raw[offset+1].toInt() and 255).put("dataBytes",length-1));offset+=length+1
        };return types
    }
    fun observe(address:String,input:Observation):Boolean {
        total++
        val now=clock()-started
        val device=devices[address] ?: if(devices.size<4096)Device("device-${devices.size+1}",now,now).also{devices[address]=it}else{overflow=true;return false}
        device.count++;device.last=now;device.lastRssi=input.rssi;device.minRssi=minOf(device.minRssi,input.rssi);device.maxRssi=maxOf(device.maxRssi,input.rssi)
        // Capture privacy-safe advertisement evidence BEFORE applying the previous Osmo rules.
        val signature=tag((input.raw ?: byteArrayOf())+(input.name ?: "").toByteArray()+input.services.joinToString().toByteArray()+input.manufacturers.values.fold(byteArrayOf()){a,b->a+b}+input.serviceData.values.fold(byteArrayOf()){a,b->a+b})
        val captured=JSONObject().put("name",nameEvidence(input.name)).put("services",JSONArray(input.services.map{UUID.fromString(it).toString()}))
            .put("manufacturerData",JSONArray(input.manufacturers.map{(id,data)->dataEvidence(data).put("companyId",id)}))
            .put("serviceData",JSONArray(input.serviceData.map{(id,data)->dataEvidence(data).put("service",UUID.fromString(id).toString())}))
            .put("flags",input.flags ?: JSONObject.NULL).put("advertisement",input.raw?.let{dataEvidence(it)} ?: JSONObject.NULL)
            .put("advertisingDataTypes",adTypes(input.raw)).put("legacy",input.legacy).put("connectable",input.connectable ?: JSONObject.NULL)
        if(!device.variants.containsKey(signature)){
            if(device.variants.size<64)device.variants[signature]=captured else variantsDropped++
        }
        val event=JSONObject().put("device",device.alias).put("elapsedMs",now).put("rssi",input.rssi).put("variant",signature).put("callbackType",input.callbackType)
        if(observations.size==4096){observations.removeFirst();dropped++};observations.addLast(event)
        val matched=classify(input)
        device.recognized=device.recognized||matched;if(!matched)device.rejected++
        event.put("recognizedByExistingRules",matched)
        return true
    }
    fun classification(scannerFailed:Boolean):String=when {
        scannerFailed->"scanner_unavailable"
        total==0L->"no_advertisements_observed"
        recognized==0->"advertisements_observed_no_dji_match"
        else->"possible_dji_device_observed"
    } // Advertising hints alone can NEVER establish "osmo_identified".
    fun export(appVersion:String,api:Int,androidVersion:String,environment:JSONObject,termination:String,scannerFailed:Boolean,complete:Boolean):String {
        require(termination in terminations)
        return JSONObject().put("schema","djehuti-bluetooth-discovery").put("version",1).put("diagnosticVersion",3)
            .put("appVersion",appVersion).put("androidApi",api).put("androidVersion",androidVersion).put("session",sessionId)
            .put("condition",condition).put("startedUtc",startedUtc).put("endedUtc",Instant.now().toString()).put("elapsedMs",clock()-started)
            .put("scanner",environment).put("scanComplete",complete).put("termination",termination)
            .put("classification",if(complete||scannerFailed)classification(scannerFailed) else "incomplete_scan")
            .put("androidDeliveredAdvertisements",total>0).put("totalAdvertisements",total).put("distinctDevices",distinct).put("possibleDjiDevices",recognized)
            .put("droppedObservationDetails",dropped).put("droppedVariantDetails",variantsDropped).put("deviceCapacityExceeded",overflow)
            .put("devices",JSONArray(devices.values.map{d->JSONObject().put("alias",d.alias).put("firstElapsedMs",d.first).put("lastElapsedMs",d.last)
                .put("firstObservedUtc",Instant.parse(startedUtc).plusMillis(d.first).toString()).put("lastObservedUtc",Instant.parse(startedUtc).plusMillis(d.last).toString())
                .put("observations",d.count).put("recognizedByExistingRules",d.recognized).put("rejectedObservations",d.rejected)
                .put("rssiMin",d.minRssi).put("rssiMax",d.maxRssi).put("rssiLast",d.lastRssi)
                .put("recognitionRule","Previous Osmo/OM name patterns or advertised FFF0; hints only")
                .put("variants",JSONObject(d.variants as Map<*,*>))}))
            .put("observations",JSONArray(observations.toList()))
            .put("privacy","Session aliases and keyed tags only: no MACs, exact names, raw advertisement/manufacturer/service payloads, serials or keys. Payload types, sizes, company IDs, service UUIDs, flags and coarse name hints are retained. Tags deliberately cannot track a device across sessions.")
            .put("limitations",JSONArray(listOf("Reports only advertisements Android delivered; does not prove radio silence or incompatibility.","Android neverForLocation may filter some BLE beacons; vendor/OS policies, radio state and device operating mode can affect delivery.","Names and service hints are not authenticated identity. An on/off difference is only a candidate.","Address rotation can split one physical device into multiple aliases. Structural evidence can be compared across runs, but aliases and content tags cannot.","No pairing, connection, reads, writes, initialization or movement commands are made.")))
            .toString(2)
    }
    companion object {
        val conditions=listOf("nearby_device_check","osmo_powered_off","osmo_powered_on","osmo_normal_pairing_condition")
        val terminations=setOf("duration_completed","permission_denied","permission_timeout","hardware_unavailable","bluetooth_off","location_disabled","scanner_missing","startup_exception","scan_callback_error","permission_revoked","cancelled","backgrounded","capacity_limit","closed")
    }
}
