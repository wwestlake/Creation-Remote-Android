package com.lagdaemon.creationremote.gimbal

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LiveCandidateTest {
    @Test fun candidateCanHandOffDuringScanOrAfterManualStop(){
        val r=DiscoveryRun{0};assertFalse(r.candidateHandoffAllowed)
        r.start();assertFalse(r.candidateHandoffAllowed);r.beginScan();assertTrue(r.candidateHandoffAllowed)
        r.stop("selected_for_connection");assertTrue(r.candidateHandoffAllowed);assertFalse(r.active)
        r.start();r.beginScan();r.stop("cancelled");assertTrue(r.candidateHandoffAllowed)
    }
    @Test fun failedOrBackgroundedScanCannotHandOff(){
        for(reason in listOf("scan_callback_error","backgrounded","permission_revoked","capacity_limit","closed")){
            val r=DiscoveryRun{0};r.start();r.beginScan();r.stop(reason);assertFalse(reason,r.candidateHandoffAllowed)
        }
    }
    @Test fun newlyAmbiguousAutomaticChoiceIsClearedButExplicitChoiceSurvivesLiveUpdates(){
        assertEquals("device-1",CandidateSelection.update("",listOf("device-1")))
        assertEquals("",CandidateSelection.update("device-1",listOf("device-1","device-2")))
        assertEquals("device-2",CandidateSelection.update("device-2",listOf("device-1","device-2"),true))
        assertEquals("device-2",CandidateSelection.update("device-2",listOf("device-1","device-2","device-3"),true))
        assertEquals("",CandidateSelection.update("device-2",emptyList(),true))
    }
    @Test fun earlyHandoffReportDoesNotFalselyClaimFullScanCompleted(){
        val e=DiscoveryEvidence({100},"osmo_powered_on")
        val r=org.json.JSONObject(e.export("0.0.10",37,"17",org.json.JSONObject(),"selected_for_connection",false,false))
        assertFalse(r.getBoolean("scanComplete"));assertEquals("selected_for_connection",r.getString("termination"))
    }
    @Test fun liveCandidatesAreVisibleAndScanStopPrecedesReturningConnectionHandle(){
        val root=sequenceOf(File("."),File("app")).first{File(it,"src/main/AndroidManifest.xml").exists()}
        val source=File(root,"src/main/java/com/lagdaemon/creationremote/gimbal/BluetoothDiscovery.kt").readText()
        assertTrue(source.contains("candidates=candidateEvidence.values.toList()"))
        val handoff=source.substringAfter("fun takeCandidateForConnection").substringBefore("private val timer")
        assertTrue(handoff.indexOf("finish(\"selected_for_connection\")")<handoff.indexOf("return selected"))
        assertTrue(handoff.contains("stopPermissionRevoked"));assertTrue(handoff.contains("stopRequestException"))
        val ui=File(root,"src/main/java/com/lagdaemon/creationremote/gimbal/BleDiagnosticsScreen.kt").readText()
        val runtime=File(root,"src/main/java/com/lagdaemon/creationremote/gimbal/AutomaticOsmoRuntime.kt").readText()
        assertTrue(runtime.contains("takeCandidateForConnection(candidate.alias)"))
        assertFalse(ui.contains("Text(\"Stop Scan\")"))
        assertTrue(ui.contains("s.choices.withIndex()"))
    }
}
