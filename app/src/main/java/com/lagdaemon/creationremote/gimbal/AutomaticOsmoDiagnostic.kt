package com.lagdaemon.creationremote.gimbal

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** The initial Run authorizes the whole read-only sequence; no manual stage windows. */
class AutomaticOsmoDiagnostic(private val port:Port,private val clock:()->Long,private val update:()->Unit={}) {
    enum class Phase { IDLE, CHECKING, SEARCHING, CHOOSING, CONNECTING, EXAMINING, COLLECTING, DISCONNECTING, SAVING, RESULT }
    data class Capture(val scan:String?,val inspection:String?)
    interface Port {
        fun scan();fun stopScan();fun connect(candidate:OsmoServiceSession.Candidate):Boolean
        fun discover();fun cleanup():Capture;fun checkpoint(report:String);fun save(session:String,report:String)
    }
    var phase=Phase.IDLE;private set
    var session="";private set
    var outcome="";private set
    var report:String?=null;private set
    var localSaved=false;private set
    var savePending=false;private set
    var candidates=emptyList<OsmoServiceSession.Candidate>();private set
    var deviceFound=false;private set
    var connected=false;private set
    var servicesExamined=false;private set
    var scanReport:String?=null;private set
    private var inspectionReport:String?=null
    private var started=0L
    private var startedUtc=""
    private var deadline=Long.MAX_VALUE
    private var firstStrongAt:Long?=null
    private val events=mutableListOf<JSONObject>()
    val active get()=phase !in setOf(Phase.IDLE,Phase.RESULT)
    val canRun get()=!active&&!savePending&&outcome!="cleanup_failed"
    private fun enter(next:Phase,timeout:Long){phase=next;deadline=clock()+timeout;events.add(JSONObject().put("phase",next.name).put("elapsedMs",clock()-started));if(session.isNotEmpty()&&next!=Phase.RESULT)port.checkpoint(buildReport(false));update()}
    fun start():Boolean {
        if(!canRun)return false
        session=UUID.randomUUID().toString();started=clock();startedUtc=Instant.now().toString();outcome="running";report=null;localSaved=false
        candidates=emptyList();deviceFound=false;connected=false;servicesExamined=false;scanReport=null;inspectionReport=null;firstStrongAt=null;events.clear()
        enter(Phase.CHECKING,60_000);port.scan();return true
    }
    fun scanUpdate(running:Boolean,finished:Boolean,rows:List<OsmoServiceSession.Candidate>,json:String?){
        if(phase !in setOf(Phase.CHECKING,Phase.SEARCHING))return
        if(json!=null)scanReport=json
        if(running&&phase==Phase.CHECKING)enter(Phase.SEARCHING,25_000)
        candidates=rows.filter{it.connectable&&it.om6Hint&&it.vendorService}
        deviceFound=candidates.isNotEmpty()
        if(candidates.isNotEmpty()&&firstStrongAt==null)firstStrongAt=clock()
        if(candidates.size>1){enter(Phase.CHOOSING,60_000);port.stopScan();update();return}
        if(finished){
            if(candidates.size==1){connect(candidates.single());return}
            val parsed=json?.let{JSONObject(it)}
            finish(if(parsed?.optString("classification")=="scanner_unavailable")"scanner_unavailable" else "device_not_found");return
        }
        update()
    }
    fun choose(alias:String){if(phase==Phase.CHOOSING){if(clock()>=deadline)finish("selection_timeout")else candidates.firstOrNull{it.alias==alias}?.let{connect(it)}}}
    private fun connect(candidate:OsmoServiceSession.Candidate){
        enter(Phase.CONNECTING,20_000)
        port.stopScan() // serialized before the connection request
        if(!port.connect(candidate))finish("connection_could_not_start")
    }
    fun inspectionUpdate(stage:OsmoServiceSession.Phase,json:String?){
        if(phase !in setOf(Phase.CONNECTING,Phase.EXAMINING,Phase.COLLECTING,Phase.DISCONNECTING))return
        when(stage){
            OsmoServiceSession.Phase.CONNECTED->{if(phase!=Phase.CONNECTING)return;connected=true;enter(Phase.EXAMINING,65_000);port.discover()}
            OsmoServiceSession.Phase.DISCOVERING->if(phase!=Phase.EXAMINING)enter(Phase.EXAMINING,65_000)
            OsmoServiceSession.Phase.READING->if(phase!=Phase.COLLECTING)enter(Phase.COLLECTING,45_000)
            OsmoServiceSession.Phase.DISCONNECTING->if(phase!=Phase.DISCONNECTING)enter(Phase.DISCONNECTING,5_000)
            OsmoServiceSession.Phase.FINISHED->{
                inspectionReport=json
                val r=json?.let{JSONObject(it)}
                connected=r?.optBoolean("connectionSucceeded")==true;servicesExamined=r?.optBoolean("serviceDiscoveryCompleted")==true
                finish(if(r?.optJSONObject("cleanup")?.optBoolean("closeSucceeded",true)==false)"cleanup_failed" else r?.optString("outcome") ?: "inspection_incomplete")
            }
            else->Unit
        }
    }
    fun cancel(reason:String="cancelled"){if(active&&phase!=Phase.SAVING)finish(reason)}
    fun tick(){
        if(!active)return
        if(clock()<deadline){if(phase==Phase.SEARCHING&&candidates.size==1&&clock()-(firstStrongAt ?: clock())>=3000)connect(candidates.single());return}
        if(phase==Phase.SAVING){outcome="report_save_timeout";phase=Phase.RESULT;update();return}
        finish(when(phase){Phase.CHECKING->"permission_timeout";Phase.SEARCHING->"scan_timeout";Phase.CHOOSING->"selection_timeout";Phase.CONNECTING->"connection_timeout";Phase.EXAMINING->"service_discovery_timeout";else->"inspection_timeout"})
    }
    private fun finish(result:String){
        if(phase in setOf(Phase.SAVING,Phase.RESULT,Phase.IDLE))return
        outcome=result;enter(Phase.SAVING,15_000)
        val capture=port.cleanup() // returns immutable evidence after bounded local cleanup
        if(capture.scan!=null)scanReport=capture.scan
        if(capture.inspection!=null)inspectionReport=capture.inspection
        inspectionReport?.let{val r=JSONObject(it);connected=r.optBoolean("connectionSucceeded");servicesExamined=r.optBoolean("serviceDiscoveryCompleted")}
        report=buildReport(true);savePending=true;port.save(session,report!!)
    }
    fun saved(id:String,success:Boolean){
        if(id!=session||!savePending)return
        savePending=false;localSaved=success
        if(!success)outcome="local_report_save_failed"
        else if(outcome=="report_save_timeout")outcome=report?.let{JSONObject(it).optString("outcome")} ?: outcome
        phase=Phase.RESULT;update()
    }
    private fun buildReport(complete:Boolean)=JSONObject().put("schema","djehuti-automatic-osmo-diagnostic").put("version",1).put("appVersion","0.0.11")
        .put("session",session).put("startedUtc",startedUtc).put("updatedUtc",Instant.now().toString()).put("elapsedMs",clock()-started)
        .put("runFinished",complete).put("outcome",outcome).put("lastPhase",phase.name)
        .put("deviceFound",deviceFound).put("connected",connected).put("servicesExamined",servicesExamined)
        .put("initialRunAuthorizesReadOnlySequence",true).put("candidateEvidence",JSONArray(candidates.map{it.json()}))
        .put("scan",scanReport?.let{JSONObject(it)} ?: JSONObject.NULL).put("inspection",inspectionReport?.let{JSONObject(it)} ?: JSONObject.NULL)
        .put("progress",JSONArray(events)).put("cloudSyncVerified",false).toString(2)
    fun restore(text:String){
        if(phase!=Phase.IDLE)return
        val r=JSONObject(text);if(r.optString("schema")!="djehuti-automatic-osmo-diagnostic")return
        session=r.getString("session");report=text;localSaved=true;deviceFound=r.optBoolean("deviceFound");connected=r.optBoolean("connected");servicesExamined=r.optBoolean("servicesExamined")
        outcome=if(r.optBoolean("runFinished"))r.optString("outcome") else "application_interrupted";phase=Phase.RESULT;update()
    }
}
