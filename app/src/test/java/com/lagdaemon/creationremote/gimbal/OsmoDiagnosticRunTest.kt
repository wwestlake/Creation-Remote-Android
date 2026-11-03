package com.lagdaemon.creationremote.gimbal

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class OsmoDiagnosticRunTest {
    private class Fixture {
        var now=0L
        val run=OsmoDiagnosticRun{now}
        fun connect(){run.start();run.permission(true);run.scanned(listOf(OsmoDiagnosticRun.Candidate(1,true,false)));run.connected()}
    }
    @Test fun normalRunAutomaticallyConnectsSubscribesAndMonitorsThirtySeconds(){
        val f=Fixture();val r=f.run
        assertEquals(listOf(OsmoDiagnosticRun.Action.RequestPermission),r.start())
        assertTrue(r.start().isEmpty())
        assertEquals(listOf(OsmoDiagnosticRun.Action.Scan),r.permission(true))
        assertEquals(listOf(OsmoDiagnosticRun.Action.Connect(4)),r.scanned(listOf(OsmoDiagnosticRun.Candidate(4,true,true))))
        r.connected();assertEquals(OsmoDiagnosticRun.Phase.EXAMINING,r.phase)
        assertEquals(listOf(OsmoDiagnosticRun.Action.Subscribe(8)),r.discovered(listOf(8,9)))
        assertTrue(r.subscribed(42,true).isEmpty())
        assertEquals(listOf(OsmoDiagnosticRun.Action.Subscribe(9)),r.subscribed(8,true))
        assertEquals(listOf(OsmoDiagnosticRun.Action.Monitor),r.subscribed(9,true))
        f.now=29_999;assertTrue(r.tick().isEmpty());f.now=30_000
        assertEquals(listOf(OsmoDiagnosticRun.Action.Finish),r.tick());r.prepared()
        assertEquals(OsmoDiagnosticRun.Phase.COMPLETE,r.phase);assertEquals("monitoring_complete",r.reason)
        assertTrue(r.connected().isEmpty());assertTrue(r.tick().isEmpty())
    }
    @Test fun ambiguousOrServiceOnlyEvidenceRequiresSelection(){
        for(candidates in listOf(listOf(OsmoDiagnosticRun.Candidate(1,false,true)),listOf(OsmoDiagnosticRun.Candidate(1,false,false,true)),listOf(OsmoDiagnosticRun.Candidate(1,true,true),OsmoDiagnosticRun.Candidate(2,true,false)))){
            val f=Fixture();f.run.start();f.run.permission(true)
            assertTrue(f.run.scanned(candidates).isEmpty());assertEquals(OsmoDiagnosticRun.Phase.CHOOSING,f.run.phase)
            assertTrue(f.run.choose(999).isEmpty());assertEquals(listOf(OsmoDiagnosticRun.Action.Connect(1)),f.run.choose(1))
        }
    }
    @Test fun unknownDevicesAreNeverSilentlyConnected(){
        val f=Fixture();f.run.start();f.run.permission(true)
        assertEquals(listOf(OsmoDiagnosticRun.Action.Finish),f.run.scanned(listOf(OsmoDiagnosticRun.Candidate(1,false,false))))
        assertEquals("no_candidate",f.run.reason);f.run.prepared();assertEquals(OsmoDiagnosticRun.Phase.FAILED,f.run.phase)
    }
    @Test fun permissionDenialAndPermissionTimeoutProduceReports(){
        val f=Fixture();f.run.start();assertEquals(listOf(OsmoDiagnosticRun.Action.Finish),f.run.permission(false));assertEquals("permission_denied",f.run.reason)
        val g=Fixture();g.run.start();g.now=60_000;assertEquals(listOf(OsmoDiagnosticRun.Action.Finish),g.run.tick());assertEquals("permission_timeout",g.run.reason)
    }
    @Test fun connectionDiscoveryAndSubscriptionHaveBoundedTimeouts(){
        for(phase in listOf("connection_timeout","discovery_timeout","subscription_timeout")){
            val f=Fixture();val r=f.run;r.start();r.permission(true);r.scanned(listOf(OsmoDiagnosticRun.Candidate(1,true,false)))
            if(phase!="connection_timeout")r.connected()
            if(phase=="subscription_timeout")r.discovered(listOf(7))
            f.now=r.deadline;assertEquals(listOf(OsmoDiagnosticRun.Action.Finish),r.tick());assertEquals(phase,r.reason)
        }
    }
    @Test fun cancellationAtEveryActiveStageIgnoresLateCallbacksAndAllowsRestart(){
        for(stage in 0..6){
            val f=Fixture();val r=f.run;r.start()
            if(stage>0)r.permission(true)
            if(stage>1)r.scanned(listOf(OsmoDiagnosticRun.Candidate(1,true,false)))
            if(stage>2)r.connected()
            if(stage>3)r.discovered(listOf(1))
            if(stage>4)r.subscribed(1,true)
            assertEquals(listOf(OsmoDiagnosticRun.Action.Finish),r.cancel());r.prepared()
            assertEquals(OsmoDiagnosticRun.Phase.CANCELLED,r.phase)
            assertTrue(r.permission(true).isEmpty());assertTrue(r.connected().isEmpty());assertTrue(r.discovered(listOf(1)).isEmpty());assertTrue(r.subscribed(1,true).isEmpty())
            assertEquals(listOf(OsmoDiagnosticRun.Action.RequestPermission),r.start())
        }
    }
    @Test fun unrecognizedServicesAndSubscriptionFailuresStillCollectEvidence(){
        val f=Fixture();f.connect();assertEquals(listOf(OsmoDiagnosticRun.Action.Monitor),f.run.discovered(emptyList()))
        val g=Fixture();g.connect();g.run.discovered(listOf(1));assertEquals(listOf(OsmoDiagnosticRun.Action.Monitor),g.run.subscribed(1,false));assertEquals(1,g.run.subscriptionFailures)
    }
    @Test fun selectionAndWholeSessionAreBounded(){
        val f=Fixture();f.run.start();f.run.permission(true);f.run.scanned(listOf(OsmoDiagnosticRun.Candidate(1,false,true)))
        f.now=f.run.deadline;f.run.tick();assertEquals("selection_timeout",f.run.reason)
        val g=Fixture();g.connect();g.run.discovered((1..30).toList());g.now=180_000;g.run.tick();assertEquals("session_timeout",g.run.reason)
    }
    @Test fun namesAndChannelsAreHintsAndUnknownWritesNeverSelected(){
        assertTrue(BleDiagnostics.osmoName("Osmo Mobile 6"));assertTrue(BleDiagnostics.osmoName("OM6-1234"))
        assertFalse(BleDiagnostics.osmoName("Cosmopolitan"));assertFalse(BleDiagnostics.osmoName("DJI drone"))
        assertFalse(BleDiagnostics.osmoName("Osmo Pocket"));assertTrue(BleDiagnostics.candidateName("Osmo Pocket"))
        assertTrue(BleDiagnostics.osmoName("OsmoMobile6"));assertFalse(BleDiagnostics.osmoName("OM4"))
        val fff4=java.util.UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
        assertTrue(BleDiagnostics.recognizedChannel(BleDiagnostics.serviceUuid,fff4,16))
        assertTrue(BleDiagnostics.recognizedChannel(BleDiagnostics.serviceUuid,fff4,32))
        assertFalse(BleDiagnostics.recognizedChannel(BleDiagnostics.serviceUuid,fff4,8))
        assertFalse(BleDiagnostics.recognizedChannel(java.util.UUID.randomUUID(),fff4,16))
    }
    @Test fun completeReportRetainsInventoryCountsAndErrorsAfterEventRollover(){
        var now=0L;val j=BleJournal({now},{"2026-11-03T12:00:00Z"})
        val uuid=BleDiagnostics.serviceUuid.toString()
        repeat(400){j.event("inventory",service=uuid,characteristic=uuid,instance=it,serviceInstance=it/2)}
        j.candidate(1,true,true,-42);j.event("subscribe",1);j.event("subscription_failed",1,status=5)
        repeat(1200){j.event("notify",1,byteCount=20)};j.event("frame_checksum_failure",status=3)
        now=30_000;j.finish("monitoring_complete",listOf("no_notifications_observed"))
        val report=JSONObject(j.export(35,"0.0.7","15",true,true))
        assertEquals(400,report.getJSONArray("inventory").length());assertEquals(1200,report.getJSONObject("counts").getInt("notify"))
        assertEquals(199,report.getJSONArray("inventory").getJSONObject(399).getInt("serviceInstance"))
        assertEquals(24000,report.getJSONArray("notificationTotals").getJSONObject(0).getInt("bytes"))
        assertEquals(3,report.getJSONObject("parserTotals").getInt("frame_checksum_failure"))
        assertEquals(2,report.getJSONArray("subscriptionResults").length());assertEquals(30_000,report.getJSONObject("result").getInt("durationMs"))
        assertEquals("0.0.7",report.getString("appVersion"));assertEquals("device-1",report.getJSONArray("candidates").getJSONObject(0).getString("alias"))
        assertFalse(report.toString().contains("Osmo Mobile 6"));assertFalse(report.toString().contains("AA:BB"))
    }
    @Test fun parserDistinguishesChecksumFailures(){
        val packet="551504a902044700400414c20100002000050feb63".chunked(2).map{it.toInt(16).toByte()}.toByteArray()
        val badHeader=packet.copyOf().also{it[3]=(it[3].toInt() xor 1).toByte()}
        val badFrame=packet.copyOf().also{it[15]=(it[15].toInt() xor 1).toByte()}
        val parser=DumlParser();parser.feed(badHeader+badFrame+packet)
        assertTrue(parser.headerChecksumFailures>0);assertTrue(parser.frameChecksumFailures>0)
    }
}
