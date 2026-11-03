package com.lagdaemon.creationremote.gimbal

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Typed, whitelist-only evidence. No arbitrary device strings or payload bytes accepted. */
class BleJournal(private val clock:()->Long={System.nanoTime()/1_000_000}, private val wall:()->String={Instant.now().toString()}) {
    private val started=clock()
    private val startedUtc=wall()
    private val inventory=mutableListOf<JSONObject>()
    private val candidates=linkedMapOf<Int,JSONObject>()
    private val events=ArrayDeque<JSONObject>()
    private val counts=linkedMapOf<String,Long>()
    private val notificationTotals=linkedMapOf<String,LongArray>()
    private val parserTotals=linkedMapOf<String,Long>()
    private val subscriptions=mutableListOf<JSONObject>()
    private var final:JSONObject?=null
    var dropped=0;private set
    val notifications get()=counts["notify"] ?: 0L
    val bytes get()=notificationTotals.values.sumOf{it[1]}
    fun candidate(id:Int,osmoName:Boolean,vendorService:Boolean,rssi:Int,nameHint:Boolean=osmoName){
        candidates[id]=JSONObject().put("alias","device-$id").put("om6NameHint",osmoName).put("osmoNameHint",nameHint).put("vendorServiceHint",vendorService).put("rssi",rssi)
    }
    fun event(kind:String,device:Int?=null,status:Int?=null,service:String?=null,characteristic:String?=null,
              properties:Int?=null,byteCount:Int?=null,header:DumlParser.Header?=null,instance:Int?=null,serviceInstance:Int?=null) {
        require(kind in kinds)
        val row=JSONObject().put("event",kind).put("elapsedMs",clock()-started)
        device?.let{row.put("device","device-$it")};status?.let{row.put("status",it)}
        service?.let{row.put("service",UUID.fromString(it).toString())}
        characteristic?.let{row.put("characteristic",UUID.fromString(it).toString())}
        properties?.let{row.put("properties",it)};byteCount?.let{row.put("bytes",it)};instance?.let{row.put("instance",it)}
        serviceInstance?.let{row.put("serviceInstance",it)}
        header?.let{row.put("duml",JSONObject().put("length",it.length).put("version",it.version)
            .put("sender",it.sender).put("receiver",it.receiver).put("sequence",it.sequence).put("flags",it.flags)
            .put("commandSet",it.commandSet).put("command",it.command).put("payloadBytes",it.payloadBytes))}
        counts[kind]=(counts[kind] ?: 0)+1
        if(kind in setOf("parse_rejected","header_checksum_failure","frame_checksum_failure","invalid_header","discarded_bytes"))parserTotals[kind]=(parserTotals[kind] ?: 0)+(status ?: 1)
        if(kind=="notify"){
            val totals=notificationTotals.getOrPut("$service/$characteristic/$instance"){longArrayOf(0,0)}
            totals[0]++;totals[1]+=byteCount ?: 0
        }
        if(kind in setOf("service","inventory"))inventory.add(row)
        if(kind in setOf("subscribe","subscribed","subscription_failed"))subscriptions.add(row)
        if(events.size>=1000){events.removeFirst();dropped++};events.addLast(row)
    }
    fun finish(outcome:String,questions:List<String>){
        require(outcome in outcomes);require(questions.all{it in questionsAllowed})
        final=JSONObject().put("outcome",outcome).put("endedUtc",wall()).put("durationMs",clock()-started)
            .put("unresolvedQuestions",JSONArray(questions))
    }
    fun export(api:Int,appVersion:String="unknown",androidVersion:String="unknown",bluetoothAvailable:Boolean=false,bleSupported:Boolean=false):String =
        JSONObject().put("schema","djehuti-ble-diagnostic").put("version",2).put("diagnosticVersion",2)
            .put("appVersion",appVersion).put("androidApi",api).put("androidVersion",androidVersion)
            .put("bluetoothAvailable",bluetoothAvailable).put("bleSupported",bleSupported).put("startedUtc",startedUtc)
            .put("om6CompatibilityVerified",false).put("droppedEvents",dropped)
            .put("dataPolicy","Session aliases only. No MACs, names, raw advertisements, raw notifications, payloads, serials, keys or pairing material. Header observations do not prove OM6 compatibility.")
            .put("candidates",JSONArray(candidates.values.toList())).put("inventory",JSONArray(inventory))
            .put("subscriptionResults",JSONArray(subscriptions)).put("counts",JSONObject(counts as Map<*,*>))
            .put("parserTotals",JSONObject(parserTotals as Map<*,*>))
            .put("notificationTotals",JSONArray(notificationTotals.map{(key,value)->JSONObject().put("channel",key).put("notifications",value[0]).put("bytes",value[1])}))
            .put("events",JSONArray(events.toList())).put("result",final ?: JSONObject().put("outcome","unfinished")).toString(2)
    companion object {
        private val kinds=setOf("service","scan_started","scan_stopped","scan_failed","device_seen","connect","connected","disconnected","local_close","discovery_complete","gatt_error","inventory","subscribe","subscribed","subscription_failed","notify","frame","parse_rejected","header_checksum_failure","frame_checksum_failure","invalid_header","discarded_bytes","operation_timeout","permission_denied","bluetooth_unavailable","location_required","cancelled","monitor_started","monitor_finished","report_prepared")
        val outcomes=setOf("monitoring_complete","permission_denied","permission_timeout","no_candidate","selection_timeout","connection_timeout","discovery_timeout","subscription_timeout","session_timeout","scan_timeout","scan_failed","connect_failed","discovery_failed","disconnected","bluetooth_unavailable","location_required","gatt_error","cancelled")
        val questionsAllowed=setOf("model_identity_not_verified","notification_channel_unrecognized","no_notifications_observed","subscription_failed","physical_compatibility_unverified")
    }
}
