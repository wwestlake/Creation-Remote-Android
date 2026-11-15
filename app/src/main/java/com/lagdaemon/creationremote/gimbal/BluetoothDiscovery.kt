package com.lagdaemon.creationremote.gimbal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** Foreground scanner only. No GATT, pairing, connection, or command APIs. */
@SuppressLint("MissingPermission")
class BluetoothDiscovery(private val context:Context,private val update:(Snapshot)->Unit) {
    data class Snapshot(val phase:DiscoveryRun.Phase=DiscoveryRun.Phase.IDLE,val status:String="Ready. Scan first; connecting requires a separate action.",val elapsedSeconds:Long=0,val distinct:Int=0,val advertisements:Long=0,val possible:Int=0,val rows:List<String> = emptyList(),val report:String?=null,val permissionRequest:Int=0,val candidates:List<OsmoServiceSession.Candidate> = emptyList(),val canConnect:Boolean=false){val active get()=phase==DiscoveryRun.Phase.PERMISSION||phase==DiscoveryRun.Phase.SCANNING}
    private val main=Handler(Looper.getMainLooper())
    private val manager=context.getSystemService(BluetoothManager::class.java)
    private val run=DiscoveryRun{SystemClock.elapsedRealtime()}
    private var evidence:DiscoveryEvidence?=null
    private var environment=JSONObject()
    private var snapshot=Snapshot()
    private var scanner:BluetoothLeScanner?=null
    private var callback:ScanCallback?=null
    private var generation=0
    private var closed=false
    private val candidateDevices=linkedMapOf<String,BluetoothDevice>()
    private val candidateEvidence=linkedMapOf<String,OsmoServiceSession.Candidate>()
    fun takeCandidateForConnection(alias:String):BluetoothDevice?{
        if(!run.candidateHandoffAllowed)return null
        val selected=candidateDevices[alias] ?: return null
        if(run.phase==DiscoveryRun.Phase.SCANNING)finish("selected_for_connection")
        if(environment.optBoolean("stopPermissionRevoked")||environment.optBoolean("stopRequestException"))return null
        return selected
    }
    private val timer=object:Runnable{override fun run(){
        if(closed||!run.active)return
        val due=run.due()
        if(due!=null){finish(due);return}
        if(run.phase==DiscoveryRun.Phase.SCANNING)try{if(manager?.adapter?.isEnabled!=true){finish("bluetooth_off",true);return}}catch(_:SecurityException){finish("permission_revoked",true);return}
        publish();main.postDelayed(this,250)
    }}
    fun start(condition:String){
        if(closed||!run.start())return
        generation++;environment=JSONObject();evidence=DiscoveryEvidence({SystemClock.elapsedRealtime()},condition)
        candidateDevices.clear();candidateEvidence.clear()
        environment.put("requiredPermissions",org.json.JSONArray(BleDiagnostics.requiredPermissions().toList()))
            .put("applicationScanFilters",0).put("identificationAppliedAfterCapture",true).put("neverForLocation",true)
            .put("mode","LOW_LATENCY").put("callbackType","ALL_MATCHES").put("reportDelayMs",0).put("durationLimitMs",20000)
            .put("startApi","startScan(filters=null, settings, callback); void return, no positive radio-start acknowledgement")
            .put("startupRequestAccepted",false).put("scanErrorCode",JSONObject.NULL)
            .put("bluetoothHardwareAvailable",manager?.adapter!=null).put("bleSupported",context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
        snapshot=snapshot.copy(report=null,elapsedSeconds=0,permissionRequest=snapshot.permissionRequest+1)
        publish();main.removeCallbacks(timer);main.postDelayed(timer,250)
    }
    fun permissionsResolved(){
        if(run.phase!=DiscoveryRun.Phase.PERMISSION)return
        val grants=JSONObject();for(p in BleDiagnostics.requiredPermissions())grants.put(p,ContextCompat.checkSelfPermission(context,p)==PackageManager.PERMISSION_GRANTED)
        environment.put("permissionGrants",grants)
        environment.put("fineLocationGranted",ContextCompat.checkSelfPermission(context,android.Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)
        if(BleDiagnostics.requiredPermissions().any{!grants.getBoolean(it)}){finish("permission_denied",true);return}
        try{
            val adapter=manager?.adapter
            environment.put("bluetoothEnabled",adapter?.isEnabled==true)
            if(adapter==null||!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)){finish("hardware_unavailable",true);return}
            if(!adapter.isEnabled){finish("bluetooth_off",true);return}
            val location=context.getSystemService(LocationManager::class.java)
            val locationEnabled=location?.isProviderEnabled(LocationManager.NETWORK_PROVIDER)==true||location?.isProviderEnabled(LocationManager.GPS_PROVIDER)==true
            environment.put("locationServicesEnabled",locationEnabled).put("locationServicesRequiredForThisScan",Build.VERSION.SDK_INT<31)
            if(Build.VERSION.SDK_INT<31&&!locationEnabled){finish("location_disabled",true);return}
            environment.put("extendedAdvertisingSupported",adapter.isLeExtendedAdvertisingSupported)
            scanner=adapter.bluetoothLeScanner
            if(scanner==null){finish("scanner_missing",true);return}
            if(!run.beginScan())return
            val token=generation
            val cb=object:ScanCallback(){
                override fun onScanResult(type:Int,result:ScanResult){accept(token,this,type,result)}
                override fun onBatchScanResults(results:MutableList<ScanResult>){for(result in results)accept(token,this,1,result)}
                override fun onScanFailed(errorCode:Int){main.post{if(token==generation&&callback===this&&run.active){environment.put("scanErrorCode",errorCode);finish("scan_callback_error",true)}}}
            }
            callback=cb
            val extended=adapter.isLeExtendedAdvertisingSupported
            val settings=ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).setReportDelay(0).setLegacy(!extended).apply{if(extended)setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)}.build()
            environment.put("legacyOnly",!extended)
            scanner!!.startScan(null,settings,cb)
            environment.put("startupRequestAccepted",true).put("scanStartElapsedMs",SystemClock.elapsedRealtime()-run.scanStarted)
            publish()
        }catch(_:SecurityException){finish("permission_revoked",true)}catch(_:Exception){finish("startup_exception",true)}
    }
    private fun accept(token:Int,source:ScanCallback,type:Int,result:ScanResult){main.post{
        if(closed||token!=generation||callback!==source||run.phase!=DiscoveryRun.Phase.SCANNING)return@post
        try{
            val ad=result.scanRecord
            val manufacturers=linkedMapOf<Int,ByteArray>();ad?.manufacturerSpecificData?.let{data->for(i in 0 until data.size())manufacturers[data.keyAt(i)]=data.valueAt(i).copyOf()}
            // Do not query a cached/bonded device name: the advertised name is the evidence.
            val observation=DiscoveryEvidence.Observation(ad?.deviceName,ad?.serviceUuids?.map{it.uuid.toString()} ?: emptyList(),manufacturers,
                ad?.serviceData?.map{it.key.uuid.toString() to it.value.copyOf()}?.toMap() ?: emptyMap(),ad?.advertiseFlags?.takeIf{it>=0},result.rssi,ad?.bytes?.copyOf(),type,result.isLegacy,result.isConnectable)
            if(evidence?.observe(result.device.address,observation)==false)finish("capacity_limit")
            val named=BleDiagnostics.osmoName(observation.name.orEmpty())
            val vendor=observation.services.contains(OsmoServiceSession.uuid(0xfff0))
            val alias=evidence?.aliasFor(result.device.address)
            if(alias!=null&&(named||vendor)&&result.isConnectable){
                val old=candidateEvidence[alias]
                candidateDevices[alias]=result.device
                candidateEvidence[alias]=OsmoServiceSession.Candidate(alias,named||old?.om6Hint==true,vendor||old?.vendorService==true,observation.services.contains(OsmoServiceSession.uuid(0x1812))||old?.hidService==true,true,result.rssi,(old?.observations ?: 0)+1,observation.name?.take(80) ?: old?.localName.orEmpty())
            }
            // Live totals are published by the bounded timer, not once per radio callback.
        }catch(_:SecurityException){finish("permission_revoked",true)}catch(_:Exception){environment.put("observationProcessingError",true);finish("startup_exception",true)}
    }}
    private fun finish(reason:String,failed:Boolean=reason=="permission_timeout"){
        if(!run.stop(reason))return
        generation++;val previous=callback;callback=null
        environment.put("scanDurationMs",if(environment.optBoolean("startupRequestAccepted"))SystemClock.elapsedRealtime()-run.scanStarted else 0)
        environment.put("stopRequested",previous!=null)
        try{if(previous!=null)scanner?.stopScan(previous)}catch(_:SecurityException){environment.put("stopPermissionRevoked",true)}catch(_:Exception){environment.put("stopRequestException",true)}
        scanner=null;main.removeCallbacks(timer)
        val e=evidence ?: return
        val version=context.packageManager.getPackageInfo(context.packageName,0).versionName ?: "unknown"
        val report=e.export(version,Build.VERSION.SDK_INT,Build.VERSION.RELEASE,environment,reason,failed,reason=="duration_completed")
        snapshot=snapshot.copy(report=report,elapsedSeconds=environment.optLong("scanDurationMs")/1000,status=when{
            failed->"Scanner unavailable. ${errorText(reason)} Report ready to export."
            reason in setOf("selected_for_connection","cancelled")&&candidateEvidence.isNotEmpty()->"Scan stopped. Osmo candidate detected and retained. Select Connect to Osmo to continue, or export the scan report."
            reason!="duration_completed"->"Scan stopped before completion (${if(reason=="capacity_limit")"observation capacity reached" else "interrupted"}). Collected evidence is ready to export."
            e.total==0L->"No advertisements observed. Android accepted the scan request but delivered no advertisements. This does not establish that the Osmo is not advertising."
            e.recognized==0->"Advertisements observed, no DJI match. Bluetooth traffic was received, but our existing identification rules recognized none."
            else->"Possible DJI device observed. Advertising hints merit investigation; they do not establish the device's identity."
        });publish()
    }
    private fun publish(){
        val e=evidence
        snapshot=snapshot.copy(phase=run.phase,distinct=e?.distinct ?: 0,advertisements=e?.total ?: 0,possible=e?.recognized ?: 0,rows=e?.rows ?: emptyList(),candidates=candidateEvidence.values.toList(),canConnect=run.candidateHandoffAllowed&&candidateEvidence.isNotEmpty()&&!environment.optBoolean("stopPermissionRevoked")&&!environment.optBoolean("stopRequestException"),
            elapsedSeconds=if(run.phase==DiscoveryRun.Phase.SCANNING)((SystemClock.elapsedRealtime()-run.scanStarted)/1000)else snapshot.elapsedSeconds,
            status=when(run.phase){DiscoveryRun.Phase.PERMISSION->"Waiting for Android permission";DiscoveryRun.Phase.SCANNING->if(candidateEvidence.isNotEmpty())"Device discovered. Select Connect to Osmo now; scanning will stop first. Nothing connects automatically." else "Scanning nearby Bluetooth advertisements — no connections will be attempted";else->snapshot.status})
        update(snapshot)
    }
    fun cancel(){finish("cancelled")}
    fun background(){if(run.phase==DiscoveryRun.Phase.SCANNING)finish("backgrounded")}
    fun close(){if(closed)return;finish("closed");closed=true;main.removeCallbacksAndMessages(null)}
    private fun errorText(reason:String)=when(reason){
        "permission_denied","permission_timeout","permission_revoked"->"Android did not provide the required Bluetooth permission. Start again and allow the permission request. If Android no longer prompts, permission may need to be restored in app settings."
        "bluetooth_off"->"Bluetooth is switched off. Turn it on and retry."
        "hardware_unavailable","scanner_missing"->"Android did not provide an available Bluetooth Low Energy scanner."
        "location_disabled"->"This Android version requires Location services enabled for discovery."
        "scan_callback_error"->"Android reported a scan error (${environment.optInt("scanErrorCode")}). Wait briefly, then retry."
        else->"Android could not run the scan reliably. Check Bluetooth and retry."
    }
}
