package com.lagdaemon.creationremote.gimbal

import org.json.JSONArray
import org.json.JSONObject

/** Whitelist-based export: never accepts names, MACs, arbitrary strings or payload bytes. */
class BleJournal {
    private val started=System.nanoTime()
    private val inventory=linkedMapOf<String,JSONObject>()
    private val events=ArrayDeque<JSONObject>()
    var dropped=0;private set
    fun event(kind:String,device:Int?=null,status:Int?=null,service:String?=null,characteristic:String?=null,
              properties:Int?=null,byteCount:Int?=null,header:DumlParser.Header?=null) {
        require(kind in setOf("service","scan_started","scan_stopped","scan_failed","device_seen","connect","connected","disconnected","gatt_error","inventory","subscribe","subscribed","notify","parse_rejected","operation_timeout","permission_denied"))
        val row=JSONObject().put("event",kind).put("elapsedMs",(System.nanoTime()-started)/1000000)
        device?.let{row.put("device",it)};status?.let{row.put("status",it)}
        service?.let{row.put("service",java.util.UUID.fromString(it).toString())}
        characteristic?.let{row.put("characteristic",java.util.UUID.fromString(it).toString())}
        properties?.let{row.put("properties",it)};byteCount?.let{row.put("bytes",it)}
        header?.let{row.put("duml",JSONObject().put("length",it.length).put("version",it.version)
            .put("sender",it.sender).put("receiver",it.receiver).put("sequence",it.sequence).put("flags",it.flags)
            .put("commandSet",it.commandSet).put("command",it.command).put("payloadBytes",it.payloadBytes))}
        if(kind in setOf("service","inventory")&&inventory.size<320)inventory["$service/$characteristic"]=row
        if(events.size>=1000){events.removeFirst();dropped++};events.addLast(row)
    }
    fun export(api:Int):String=JSONObject().put("schema","djehuti-ble-diagnostic").put("version",1)
        .put("androidApi",api).put("om6CompatibilityVerified",false).put("droppedEvents",dropped)
        .put("dataPolicy","Session aliases only. No MACs, names, raw advertisements, raw notifications, payloads, serials, keys or pairing material. DUML fields are framing observations, not OM6 capability claims.")
        .put("inventory",JSONArray(inventory.values.toList())).put("events",JSONArray(events.toList())).toString(2)
}
