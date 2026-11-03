package com.lagdaemon.creationremote.gimbal

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DiscoveryEvidenceTest {
    private fun observed(name:String?="Unrelated private phone",services:List<String> = emptyList(),rssi:Int=-50)=DiscoveryEvidence.Observation(name,services,mapOf(76 to "private serial bytes".toByteArray()),mapOf("0000180a-0000-1000-8000-00805f9b34fb" to "secret payload".toByteArray()),6,rssi,byteArrayOf(2,1,6,0))
    private fun report(e:DiscoveryEvidence,failed:Boolean=false,complete:Boolean=true)=JSONObject(e.export("0.0.8",37,"17",JSONObject(),if(failed)"scan_callback_error" else "duration_completed",failed,complete))
    @Test fun rejectedAdvertisementsAreCapturedBeforeRecognition(){
        var now=0L
        lateinit var evidence:DiscoveryEvidence
        evidence=DiscoveryEvidence({now},"osmo_powered_on"){
            assertEquals(1,evidence.distinct);assertEquals(1L,evidence.total)
            assertEquals(1,report(evidence).getJSONArray("observations").length());false
        }
        assertTrue(evidence.observe("AA:BB:CC:DD:EE:FF",observed()))
        val r=report(evidence);assertEquals("advertisements_observed_no_dji_match",r.getString("classification"))
        assertTrue(r.getBoolean("androidDeliveredAdvertisements"));assertEquals(1,r.getJSONArray("devices").length())
        assertFalse(r.getJSONArray("devices").getJSONObject(0).getBoolean("recognizedByExistingRules"))
    }
    @Test fun repeatsCorrelateAndPreserveTimesRssiCountsAndChangedAdvertisements(){
        var now=0L;val e=DiscoveryEvidence({now},"osmo_powered_off")
        e.observe("private-address-1",observed());now=2000;e.observe("private-address-1",observed(rssi=-40));now=3000;e.observe("private-address-1",observed(name="Other advertisement"));e.observe("private-address-2",observed())
        val r=report(e);assertEquals(2,r.getInt("distinctDevices"));assertEquals(4,r.getInt("totalAdvertisements"))
        val d=r.getJSONArray("devices").getJSONObject(0);assertEquals(3,d.getInt("observations"));assertEquals(0,d.getLong("firstElapsedMs"));assertEquals(3000,d.getLong("lastElapsedMs"));assertEquals(-40,d.getInt("rssiMax"));assertEquals(2,d.getJSONObject("variants").length())
    }
    @Test fun exportsProtectExactNamesAddressesAndPayloadsButRetainProtocolEvidence(){
        val e=DiscoveryEvidence({0},"osmo_powered_on");e.observe("AA:BB:CC:DD:EE:FF",observed("DJI OM6 serial123"))
        val r=report(e);val text=r.toString()
        for(privateValue in listOf("AA:BB:CC:DD:EE:FF","serial123","private serial bytes","secret payload"))assertFalse(text.contains(privateValue))
        val variants=r.getJSONArray("devices").getJSONObject(0).getJSONObject("variants");val v=variants.getJSONObject(variants.keys().next())
        assertEquals(76,v.getJSONArray("manufacturerData").getJSONObject(0).getInt("companyId"));assertEquals(6,v.getInt("flags"))
        assertEquals(1,v.getJSONArray("advertisingDataTypes").length());assertTrue(v.getJSONObject("name").getJSONArray("safeHints").toString().contains("DJI"))
        assertEquals("possible_dji_device_observed",r.getString("classification"));assertNotEquals("osmo_identified",r.getString("classification"))
    }
    @Test fun contentTagsCannotTrackDevicesAcrossSessions(){
        val a=DiscoveryEvidence({0},"osmo_powered_off");val b=DiscoveryEvidence({0},"osmo_powered_on")
        a.observe("same-private-address",observed());b.observe("same-private-address",observed())
        fun variant(e:DiscoveryEvidence)=report(e).getJSONArray("devices").getJSONObject(0).getJSONObject("variants").keys().next()
        assertNotEquals(variant(a),variant(b));assertNotEquals(a.sessionId,b.sessionId)
    }
    @Test fun noAdvertisementsAndScannerFailureAreDistinctAndUncertaintyIsPreserved(){
        val e=DiscoveryEvidence({0},"nearby_device_check")
        assertEquals("no_advertisements_observed",report(e).getString("classification"))
        assertEquals("scanner_unavailable",report(e,failed=true).getString("classification"))
        assertEquals("incomplete_scan",report(e,complete=false).getString("classification"))
        e.observe("x",observed());assertEquals("scanner_unavailable",report(e,failed=true).getString("classification"));assertEquals(1,report(e,failed=true).getInt("distinctDevices"))
    }
    @Test fun serviceHintIsRecordedWithoutNameAndMalformedAdDoesNotAbortCapture(){
        val e=DiscoveryEvidence({0},"osmo_normal_pairing_condition")
        e.observe("x",observed(null,listOf(BleDiagnostics.serviceUuid.toString())).copy(raw=byteArrayOf(99,1)))
        val r=report(e);assertEquals(1,r.getInt("possibleDjiDevices"));assertEquals(1,r.getInt("totalAdvertisements"))
    }
    @Test fun boundedDetailDoesNotLoseTotalsOrDeviceAggregates(){
        val e=DiscoveryEvidence({0},"nearby_device_check");repeat(4100){e.observe("one",observed())}
        val r=report(e);assertEquals(4100,r.getInt("totalAdvertisements"));assertEquals(4100,r.getJSONArray("devices").getJSONObject(0).getInt("observations"));assertEquals(4096,r.getJSONArray("observations").length());assertEquals(4,r.getInt("droppedObservationDetails"))
    }
    @Test fun scanLifecycleBoundsPermissionScanningAndRestarts(){
        var now=0L;val run=DiscoveryRun{now};assertTrue(run.start());assertFalse(run.start());now=60000;assertEquals("permission_timeout",run.due());assertTrue(run.stop("permission_timeout"));assertFalse(run.beginScan())
        assertTrue(run.start());assertTrue(run.beginScan());now+=19999;assertNull(run.due());now++;assertEquals("duration_completed",run.due());assertTrue(run.stop("duration_completed"));assertFalse(run.stop("cancelled"));assertTrue(run.start())
        assertTrue(run.stop("cancelled"));assertFalse(run.beginScan())
    }
    @Test fun discoveryRouteCannotConnectPairOrWriteAndUsesUnfilteredScan(){
        val root=sequenceOf(File("."),File("app")).first{File(it,"src/main/AndroidManifest.xml").exists()}
        val source=File(root,"src/main/java/com/lagdaemon/creationremote/gimbal/BluetoothDiscovery.kt").readText()
        for(forbidden in listOf("connectGatt(","createBond(","writeDescriptor(","writeCharacteristic(","readCharacteristic(","BluetoothSocket"))assertFalse(forbidden,source.contains(forbidden))
        assertTrue(source.contains("startScan(null,settings,cb)"));assertFalse(source.contains("if(!nameHint"));assertTrue(source.contains("evidence?.observe("))
        val ui=File(root,"src/main/java/com/lagdaemon/creationremote/gimbal/BleDiagnosticsScreen.kt").readText()
        assertFalse(ui.contains("val diagnostic=BleDiagnostics("));assertTrue(ui.contains("val diagnostic=BluetoothDiscovery("));assertTrue(ui.contains("Export Diagnostic Report"))
    }
}
