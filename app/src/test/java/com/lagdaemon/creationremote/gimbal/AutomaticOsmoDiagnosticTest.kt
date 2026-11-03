package com.lagdaemon.creationremote.gimbal

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AutomaticOsmoDiagnosticTest {
    private class Fake:AutomaticOsmoDiagnostic.Port {
        val calls=mutableListOf<String>();val checkpoints=mutableListOf<String>()
        var scanJson:String?=null;var inspectionJson:String?=null;var saved:String?=null;var connectAccepted=true
        override fun scan(){calls.add("scan")}
        override fun stopScan(){calls.add("stop")}
        override fun connect(candidate:OsmoServiceSession.Candidate):Boolean{calls.add("connect:${candidate.alias}");return connectAccepted}
        override fun discover(){calls.add("discover")}
        override fun cleanup():AutomaticOsmoDiagnostic.Capture{calls.add("cleanup");return AutomaticOsmoDiagnostic.Capture(scanJson,inspectionJson)}
        override fun checkpoint(report:String){checkpoints.add(report)}
        override fun save(session:String,report:String){calls.add("save");saved=report}
    }
    private class Fixture {
        var now=0L;val p=Fake();val run=AutomaticOsmoDiagnostic(p,{now})
        val candidate=OsmoServiceSession.Candidate("device-2",true,true,true,true,-30,20,"OM6 private suffix")
        fun searching(){run.start();run.scanUpdate(true,false,listOf(candidate),null)}
        fun connecting(){searching();now=3000;run.tick()}
        fun inspecting(){connecting();run.inspectionUpdate(OsmoServiceSession.Phase.CONNECTED,null)}
        fun finished(connected:Boolean=true,examined:Boolean=true,outcome:String="connection_and_service_discovery_succeeded"){
            p.inspectionJson=JSONObject().put("connectionSucceeded",connected).put("serviceDiscoveryCompleted",examined).put("outcome",outcome).put("cleanup",JSONObject().put("closeCalled",true)).toString()
            run.inspectionUpdate(OsmoServiceSession.Phase.FINISHED,p.inspectionJson)
        }
    }
    @Test fun oneRunAutomaticallyConnectsDiscoversCleansAndSavesBeforeShowingResult(){
        val f=Fixture();f.searching();assertEquals(listOf("scan"),f.p.calls);f.now=3000;f.run.tick()
        assertEquals(listOf("scan","stop","connect:device-2"),f.p.calls)
        f.run.inspectionUpdate(OsmoServiceSession.Phase.CONNECTED,null);assertEquals("discover",f.p.calls.last())
        f.finished();assertEquals(listOf("cleanup","save"),f.p.calls.takeLast(2));assertEquals(AutomaticOsmoDiagnostic.Phase.SAVING,f.run.phase);assertFalse(f.run.localSaved)
        val r=JSONObject(f.p.saved!!);assertTrue(r.getBoolean("runFinished"));assertTrue(r.getBoolean("servicesExamined"));assertFalse(r.toString().contains("private suffix"))
        f.run.saved(f.run.session,true);assertEquals(AutomaticOsmoDiagnostic.Phase.RESULT,f.run.phase);assertTrue(f.run.localSaved)
    }
    @Test fun missingDeviceAndRejectedPermissionStillGenerateReports(){
        for(classification in listOf("no_advertisements_observed","scanner_unavailable")){
            val f=Fixture();f.run.start();f.p.scanJson=JSONObject().put("classification",classification).put("termination","permission_denied").toString()
            f.run.scanUpdate(false,true,emptyList(),f.p.scanJson);assertNotNull(f.p.saved);assertFalse(f.p.calls.any{it.startsWith("connect")})
            assertEquals(if(classification=="scanner_unavailable")"scanner_unavailable" else "device_not_found",f.run.outcome)
        }
    }
    @Test fun multipleStrongCandidatesPauseForOneSelectionOnly(){
        val f=Fixture();f.run.start();f.run.scanUpdate(true,false,listOf(f.candidate,f.candidate.copy(alias="device-3")),null)
        assertEquals(AutomaticOsmoDiagnostic.Phase.CHOOSING,f.run.phase);assertEquals(listOf("scan","stop"),f.p.calls)
        f.run.choose("unknown");assertEquals(2,f.p.calls.size);f.run.choose("device-3");f.run.choose("device-2")
        assertEquals(1,f.p.calls.count{it.startsWith("connect")});assertEquals("connect:device-3",f.p.calls.last())
    }
    @Test fun weakOrUnrelatedEvidenceNeverAutoConnects(){
        val f=Fixture();f.run.start();f.run.scanUpdate(true,false,listOf(f.candidate.copy(om6Hint=false)),null);f.now=4000;f.run.tick()
        assertFalse(f.p.calls.any{it.startsWith("connect")});f.run.scanUpdate(false,true,listOf(f.candidate.copy(vendorService=false)),null);assertEquals("device_not_found",f.run.outcome)
    }
    @Test fun connectionAndDiscoveryFailuresRemainDistinct(){
        val f=Fixture();f.connecting();f.finished(false,false,"discovery_succeeded_connection_failed");assertFalse(f.run.connected);assertFalse(f.run.servicesExamined);assertNotNull(f.p.saved)
        val g=Fixture();g.inspecting();g.finished(true,false,"connection_succeeded_service_discovery_failed");assertTrue(g.run.connected);assertFalse(g.run.servicesExamined);assertNotNull(g.p.saved)
    }
    @Test fun allActiveStagesHaveTimeoutsAndCaptureEvidence(){
        for(stage in 0..5){
            val f=Fixture()
            when(stage){0->f.run.start();1->{f.run.start();f.run.scanUpdate(true,false,emptyList(),null)};2->{f.run.start();f.run.scanUpdate(true,false,listOf(f.candidate,f.candidate.copy(alias="other")),null)};3->f.connecting();4->f.inspecting();else->{f.inspecting();f.run.inspectionUpdate(OsmoServiceSession.Phase.READING,null)}}
            f.now+=100000;f.run.tick();assertEquals(AutomaticOsmoDiagnostic.Phase.SAVING,f.run.phase);assertNotNull(f.p.saved);assertTrue(f.run.outcome.endsWith("timeout"))
        }
    }
    @Test fun cancellationAndInterruptionCleanUpAndAutomaticallySave(){
        for(reason in listOf("cancelled","application_interrupted")){
            val f=Fixture();f.inspecting();f.run.cancel(reason);assertEquals(reason,f.run.outcome);assertEquals(listOf("cleanup","save"),f.p.calls.takeLast(2));assertNotNull(f.p.saved)
            f.run.inspectionUpdate(OsmoServiceSession.Phase.CONNECTED,null);assertEquals(AutomaticOsmoDiagnostic.Phase.SAVING,f.run.phase)
        }
    }
    @Test fun onlyOneRunAndNoDuplicateAutomaticDiscoveries(){
        val f=Fixture();f.connecting();assertFalse(f.run.start());f.run.inspectionUpdate(OsmoServiceSession.Phase.CONNECTED,null)
        // Android normally publishes DISCOVERING synchronously; do not rediscover on another CONNECTED event.
        f.run.inspectionUpdate(OsmoServiceSession.Phase.CONNECTED,null)
        assertEquals(1,f.p.calls.count{it=="discover"})
        f.run.inspectionUpdate(OsmoServiceSession.Phase.DISCOVERING,null)
        assertEquals(1,f.p.calls.count{it=="connect:device-2"})
        f.finished();assertFalse(f.run.start());f.run.saved("stale-session",true);assertFalse(f.run.localSaved)
        f.run.saved(f.run.session,true);assertTrue(f.run.start())
    }
    @Test fun failedOrSlowLocalStorageNeverClaimsSaved(){
        val f=Fixture();f.inspecting();f.finished();f.run.saved(f.run.session,false);assertFalse(f.run.localSaved);assertEquals("local_report_save_failed",f.run.outcome)
        val g=Fixture();g.inspecting();g.finished();g.now+=15000;g.run.tick();assertEquals(AutomaticOsmoDiagnostic.Phase.RESULT,g.run.phase);assertFalse(g.run.localSaved);assertFalse(g.run.start());g.run.saved(g.run.session,true);assertTrue(g.run.localSaved)
    }
    @Test fun checkpointsRecoverInterruptedOperationAndRetainCompletedReports(){
        val f=Fixture();f.inspecting();val checkpoint=f.p.checkpoints.last();val recovered=Fixture();recovered.run.restore(checkpoint)
        assertEquals("application_interrupted",recovered.run.outcome);assertTrue(recovered.run.localSaved)
        f.finished();val completed=Fixture();completed.run.restore(f.p.saved!!);assertTrue(completed.run.servicesExamined)
    }
    @Test fun atomicLocalStoreRetainsRunHistoryAndRejectsUnsafePaths(){
        val dir=Files.createTempDirectory("osmo-report-test-").toFile()
        try{
            val store=OsmoReportStore(dir);val f=Fixture();f.inspecting();f.finished();store.save(f.p.saved!!)
            val second=Fixture();second.run.start();second.run.cancel();store.save(second.p.saved!!)
            assertEquals(2,dir.listFiles()!!.count{it.extension=="json"});assertNotNull(store.latest())
            val copy=JSONObject(f.p.saved!!).put("runFinished",false).toString();store.save(copy);assertEquals(2,dir.listFiles()!!.count{it.extension=="json"})
            try{store.save(JSONObject().put("session","../../escape").toString());fail("Unsafe session accepted")}catch(_:IllegalArgumentException){}
        }finally{dir.listFiles()?.forEach{it.delete()};dir.delete()}
    }
    @Test fun normalUiHasOneRunAndNoManualStageControlsAndUsesDurableDocumentPermission(){
        val root=sequenceOf(File("."),File("app")).first{File(it,"src/main/AndroidManifest.xml").exists()}
        val ui=File(root,"src/main/java/com/lagdaemon/creationremote/gimbal/BleDiagnosticsScreen.kt").readText()
        for(label in listOf("Scan for Osmo","Connect to Osmo","Discover Services","Stop Scan"))assertFalse(label,ui.contains("Text(\"$label\")"))
        assertTrue(ui.contains("RUN OSMO DIAGNOSTIC"));assertTrue(ui.contains("Cancel Diagnostic"));assertTrue(ui.contains("Save Report to Osmo Research"))
        val runtime=File(root,"src/main/java/com/lagdaemon/creationremote/gimbal/AutomaticOsmoRuntime.kt").readText()
        assertTrue(runtime.contains("takePersistableUriPermission"));assertTrue(runtime.contains("store.save(report)"));assertTrue(runtime.contains("inspector.discover(true,true)"));assertTrue(runtime.contains("writer.shutdown()"))
    }
    @Test fun staleCandidatesAndCleanupFailureDoNotStartAnotherConnection(){
        val f=Fixture();f.searching();f.now=30000;f.run.tick();assertEquals("scan_timeout",f.run.outcome);assertFalse(f.p.calls.any{it.startsWith("connect")})
        val g=Fixture();g.inspecting();g.p.inspectionJson=JSONObject().put("connectionSucceeded",true).put("serviceDiscoveryCompleted",true).put("outcome","connection_and_service_discovery_succeeded").put("cleanup",JSONObject().put("closeSucceeded",false)).toString()
        g.run.inspectionUpdate(OsmoServiceSession.Phase.FINISHED,g.p.inspectionJson);g.run.saved(g.run.session,true);assertEquals("cleanup_failed",g.run.outcome);assertFalse(g.run.start())
    }
}

