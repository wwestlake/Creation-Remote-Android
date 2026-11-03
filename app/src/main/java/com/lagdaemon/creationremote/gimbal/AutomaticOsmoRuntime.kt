package com.lagdaemon.creationremote.gimbal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors

/** Android orchestration and durable storage; all Bluetooth callbacks stay on main. */
class AutomaticOsmoRuntime(private val context:Context,private val update:(Snapshot)->Unit) {
    data class Snapshot(val phase:AutomaticOsmoDiagnostic.Phase=AutomaticOsmoDiagnostic.Phase.IDLE,val status:String="Ready to run the complete read-only diagnostic.",val permissionRequest:Int=0,val choices:List<OsmoServiceSession.Candidate> = emptyList(),val deviceFound:Boolean=false,val connected:Boolean=false,val examined:Boolean=false,val saved:Boolean=false,val savePending:Boolean=false,val report:String?=null,val copyStatus:String="",val copyBusy:Boolean=false,val canRun:Boolean=true){
        val active get()=phase !in setOf(AutomaticOsmoDiagnostic.Phase.IDLE,AutomaticOsmoDiagnostic.Phase.RESULT)
    }
    private val main=Handler(Looper.getMainLooper())
    private val writer=Executors.newSingleThreadExecutor()
    private val providerWriter=Executors.newSingleThreadExecutor()
    private val store=OsmoReportStore(File(context.filesDir,"osmo-reports"))
    private val preferences=context.getSharedPreferences("osmo-report-destination",Context.MODE_PRIVATE)
    private var scanSnapshot=BluetoothDiscovery.Snapshot()
    private var inspectionSnapshot=AndroidOsmoInspection.Snapshot()
    @Volatile private var closed=false
    private val activeOutput=java.util.concurrent.atomic.AtomicReference<java.io.OutputStream?>()
    private var providerCancellation:android.os.CancellationSignal?=null
    private var copying=false
    private var copyStatus=""
    private var coordinator:AutomaticOsmoDiagnostic?=null
    private val scanner=BluetoothDiscovery(context){s->
        scanSnapshot=s;coordinator?.scanUpdate(s.phase==DiscoveryRun.Phase.SCANNING,s.phase==DiscoveryRun.Phase.FINISHED,s.candidates,s.report);publish()
    }
    private val inspector=AndroidOsmoInspection(context){s->inspectionSnapshot=s;coordinator?.inspectionUpdate(s.phase,s.report);publish()}
    private val port=object:AutomaticOsmoDiagnostic.Port {
        override fun scan(){inspector.reset();scanner.start("osmo_powered_on")}
        override fun stopScan(){scanner.cancel()}
        override fun connect(candidate:OsmoServiceSession.Candidate):Boolean{
            val device=scanner.takeCandidateForConnection(candidate.alias) ?: return false
            return inspector.connect(true,candidate,device)
        }
        override fun discover(){inspector.discover(true,true)} // initial Run is explicit authorization
        override fun cleanup():AutomaticOsmoDiagnostic.Capture{
            scanner.cancel();inspector.background()
            return AutomaticOsmoDiagnostic.Capture(scanSnapshot.report,inspectionSnapshot.report)
        }
        override fun checkpoint(report:String){writer.execute{runCatching{store.save(report)}}}
        override fun save(session:String,report:String){writer.execute{
            val success=runCatching{store.save(report)}.isSuccess
            main.post{if(!closed){coordinator?.saved(session,success);if(success)copyToRemembered(report)}}
        }}
    }
    private val tick=object:Runnable{override fun run(){
        if(closed)return
        coordinator?.tick();publish();if(coordinator?.active==true)main.postDelayed(this,250)
    }}
    init{
        coordinator=AutomaticOsmoDiagnostic(port,{SystemClock.elapsedRealtime()}){publish()}
        writer.execute{val latest=runCatching{store.latest()}.getOrNull();if(latest!=null)main.post{if(!closed)coordinator?.restore(latest)}}
    }
    fun start(){if(closed||copying)return;copyStatus="";if(coordinator?.start()==true){main.removeCallbacks(tick);main.postDelayed(tick,250)}}
    fun permissionResult(){scanner.permissionsResolved()}
    fun choose(alias:String){coordinator?.choose(alias)}
    fun cancel(){coordinator?.cancel()}
    fun background(){
        if(coordinator?.phase==AutomaticOsmoDiagnostic.Phase.CHECKING&&scanSnapshot.phase==DiscoveryRun.Phase.PERMISSION)return
        if(inspector.awaitingAuthorization())return // OS confirmation is bounded by existing stage timeouts.
        coordinator?.cancel("application_interrupted")
    }
    fun hasDestination()=preferences.getString("uri",null)!=null
    fun saveToDestination(){coordinator?.report?.let{copyToRemembered(it)}}
    fun authorizeDestination(uri:Uri){
        var persisted=false
        try{context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_WRITE_URI_PERMISSION);persisted=true}catch(_:SecurityException){}
        if(persisted)preferences.edit().putString("uri",uri.toString()).apply() else preferences.edit().remove("uri").apply()
        coordinator?.report?.let{copyDocument(uri,it,persisted)}
    }
    private fun copyToRemembered(text:String){
        val saved=preferences.getString("uri",null) ?: return
        copyDocument(Uri.parse(saved),text,true)
    }
    private fun copyDocument(uri:Uri,text:String,remembered:Boolean){
        if(copying||closed)return
        copying=true;copyStatus="Local report is safe. Saving a copy to the selected document…";publish()
        val signal=android.os.CancellationSignal();providerCancellation=signal
        val expired=java.util.concurrent.atomic.AtomicBoolean(false)
        val timeout=Runnable{expired.set(true);signal.cancel();runCatching{activeOutput.getAndSet(null)?.close()};copying=false;copyStatus="Local report is saved. The selected provider did not confirm a copy in time; cloud synchronization is unverified.";publish()}
        main.postDelayed(timeout,15_000)
        providerWriter.execute{
            val success=try{
                val descriptor=context.contentResolver.openFileDescriptor(uri,"wt",signal) ?: error("No output")
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use{stream->activeOutput.set(stream);if(expired.get()||closed)error("Expired");stream.write(text.toByteArray(Charsets.UTF_8));stream.flush()}
                true
            }catch(_:SecurityException){preferences.edit().remove("uri").apply();false}catch(_:Exception){false}
            finally{activeOutput.set(null)}
            main.post{main.removeCallbacks(timeout);if(!closed&&!expired.get()){
                copying=false;copyStatus=if(success)"Report copy written to the selected document. ${if(remembered)"Future reports will update this same file automatically; local history is retained." else "This provider did not grant reusable access; choose the destination again next time."} Cloud synchronization is not verified."
                else "Local report is saved. Could not write the selected document; use Choose Report Destination to authorize another file."
                publish()
            }}
        }
    }
    fun close(){
        if(closed)return
        coordinator?.cancel("application_interrupted");scanner.close();inspector.close();closed=true;main.removeCallbacksAndMessages(null)
        providerCancellation?.cancel();runCatching{activeOutput.getAndSet(null)?.close()}
        writer.shutdown();providerWriter.shutdownNow()
    }
    private fun publish(){
        if(closed)return
        val c=coordinator ?: return
        val status=when(c.phase){
            AutomaticOsmoDiagnostic.Phase.IDLE->"Ready. Run automatically checks permissions, finds the Osmo, connects, examines services, reads only approved standard information, disconnects and saves a report."
            AutomaticOsmoDiagnostic.Phase.CHECKING->"Checking Bluetooth and Android permissions…"
            AutomaticOsmoDiagnostic.Phase.SEARCHING->"Looking for your Osmo… ${if(c.deviceFound)"Osmo found; checking that the candidate is unique…" else ""}"
            AutomaticOsmoDiagnostic.Phase.CHOOSING->"More than one likely Osmo is nearby. Select yours once below; the remaining steps are automatic. If unsure, cancel."
            AutomaticOsmoDiagnostic.Phase.CONNECTING->"Osmo found. Connecting…"
            AutomaticOsmoDiagnostic.Phase.EXAMINING->"Connected. Examining communication services…"
            AutomaticOsmoDiagnostic.Phase.COLLECTING->"Collecting approved read-only diagnostic information…"
            AutomaticOsmoDiagnostic.Phase.DISCONNECTING->"Disconnecting and releasing Bluetooth resources…"
            AutomaticOsmoDiagnostic.Phase.SAVING->"Saving the diagnostic report on this phone…"
            AutomaticOsmoDiagnostic.Phase.RESULT->when(c.outcome){
                "connection_and_service_discovery_succeeded"->"Osmo Diagnostic Complete"
                "device_not_found"->"Osmo Diagnostic — No sufficiently confident Osmo candidate found. The report preserves what Android observed."
                "scanner_unavailable","permission_timeout"->"Osmo Diagnostic — Bluetooth scanning or permission was unavailable. The report records the details."
                "discovery_succeeded_connection_failed","connection_timeout","connection_could_not_start","gatt_unavailable_or_inconclusive"->"Osmo Diagnostic — Connection Failed. Android could not establish the diagnostic connection; this does not prove Bluetooth is unsupported."
                "connection_succeeded_service_discovery_failed","service_discovery_timeout"->"Osmo Diagnostic — Connected, but Android could not finish service discovery."
                "user_authorization_or_pairing_required"->"Osmo Diagnostic — Android authorization was required. No security bypass was attempted."
                "local_report_save_failed","report_save_timeout"->"Osmo Diagnostic — Local report saving failed or timed out. Keep the app open and use Save Report to Osmo Research to preserve the available report."
                "cleanup_failed"->"Osmo Diagnostic — Android resource release could not be confirmed. Do not run again; close the application. The saved report records the cleanup failure."
                else->"Osmo Diagnostic — Interrupted or incomplete. Available evidence has been collected."
            }
        }
        update(Snapshot(c.phase,status,scanSnapshot.permissionRequest,if(c.phase==AutomaticOsmoDiagnostic.Phase.CHOOSING)c.candidates else emptyList(),c.deviceFound,c.connected,c.servicesExamined,c.localSaved,c.savePending,c.report,copyStatus,copying,c.canRun&&!copying))
    }
}
