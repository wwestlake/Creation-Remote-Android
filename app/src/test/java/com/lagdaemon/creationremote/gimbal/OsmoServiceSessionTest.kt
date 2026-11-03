package com.lagdaemon.creationremote.gimbal

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OsmoServiceSessionTest {
    private class Fake:OsmoServiceSession.Port {
        val calls=mutableListOf<String>()
        var connectResult=OsmoServiceSession.Request.ACCEPTED
        var discoverResult=OsmoServiceSession.Request.ACCEPTED
        var readResult=OsmoServiceSession.Request.ACCEPTED
        var closeResult=true
        override fun connect():OsmoServiceSession.Request{calls.add("connect");return connectResult}
        override fun discover():OsmoServiceSession.Request{calls.add("discover");return discoverResult}
        override fun read(key:Int):OsmoServiceSession.Request{calls.add("read:$key");return readResult}
        override fun disconnect():Boolean{calls.add("disconnect");return true}
        override fun close():Boolean{calls.add("close");return closeResult}
    }
    private class Fixture {
        var now=0L;val port=Fake();val s=OsmoServiceSession(port,{now},"0.0.9",37,"17")
        val candidate=OsmoServiceSession.Candidate("device-2",true,true,true,true,-30,715)
        fun start(){assertTrue(s.start(true,candidate,12,0,true))}
        fun connected(){start();s.connection(0,2)}
        fun finish(){s.connection(0,0)}
        fun report()=JSONObject(s.report!!)
    }
    private fun characteristic(key:Int,uuid:Int,properties:Int=2)=OsmoServiceSession.Characteristic(key,OsmoServiceSession.uuid(uuid),key,properties,1,listOf(OsmoServiceSession.Descriptor(OsmoServiceSession.uuid(0x2902),17)))
    private fun service(uuid:Int,vararg c:OsmoServiceSession.Characteristic)=OsmoServiceSession.Service(OsmoServiceSession.uuid(uuid),1,0,c.toList())
    @Test fun explicitCandidateAndDiscoveryAuthorizationAreRequired(){
        val f=Fixture();assertFalse(f.s.start(false,f.candidate,12,0,true));assertFalse(f.s.start(true,f.candidate.copy(connectable=false),12,0,true));assertFalse(f.s.start(true,f.candidate.copy(om6Hint=false,vendorService=false),12,0,true));assertTrue(f.port.calls.isEmpty())
        f.connected();assertFalse(f.s.start(true,f.candidate,12,0,true));f.s.discover(false,false);assertEquals(listOf("connect"),f.port.calls)
        f.s.discover(true,false);assertEquals(listOf("connect","discover"),f.port.calls)
    }
    @Test fun fakeDeviceEnumerationPreservesAllMetadataAndDisconnectsAutomatically(){
        val f=Fixture();f.connected();f.s.discover(true,false)
        val inventory=listOf(service(0xfff0,characteristic(1,0xfff4,16),characteristic(2,0xfff5,12)),service(0x1812,characteristic(3,0x2a4b,2),characteristic(4,0x2a4d,18)))
        f.s.discovered(0,inventory);assertEquals(OsmoServiceSession.Phase.DISCONNECTING,f.s.phase);assertFalse(f.port.calls.any{it.startsWith("read")});f.finish()
        val r=f.report();assertEquals("connection_and_service_discovery_succeeded",r.getString("outcome"));assertTrue(r.getBoolean("serviceDiscoveryCompleted"))
        val c=r.getJSONArray("inventory").getJSONObject(0).getJSONArray("characteristics").getJSONObject(0)
        assertEquals(16,c.getInt("properties"));assertEquals(17,c.getJSONArray("descriptors").getJSONObject(0).getInt("permissions"));assertEquals(OsmoServiceSession.uuid(0x2902),c.getJSONArray("descriptors").getJSONObject(0).getString("uuid"))
        assertTrue(r.getJSONObject("interfaceAssessment").getBoolean("hidReportMapPresent"));assertTrue(r.getJSONObject("cleanup").getBoolean("closeSucceeded"));assertEquals(1,f.port.calls.count{it=="close"})
        assertEquals("device-2",r.getJSONObject("candidate").getString("alias"));assertFalse(r.toString().contains("address"))
    }
    @Test fun optionalReadsUseOnlyThreeDocumentedServiceCharacteristicPairs(){
        val f=Fixture();f.connected();f.s.discover(true,true)
        f.s.discovered(0,listOf(service(0x180f,characteristic(1,0x2a19)),service(0x1800,characteristic(2,0x2a01),characteristic(4,0x2a00)),service(0x1812,characteristic(3,0x2a4a),characteristic(5,0x2a4d)),service(0xfff0,characteristic(6,0xfff4))))
        f.s.read(999,0,"private serial".toByteArray());assertEquals(OsmoServiceSession.Phase.READING,f.s.phase)
        f.s.read(1,0,byteArrayOf(90));f.s.read(2,0,byteArrayOf(0x40,0));f.s.read(3,0,byteArrayOf(0x11,1,0,3));f.finish()
        assertEquals(listOf("read:1","read:2","read:3"),f.port.calls.filter{it.startsWith("read")})
        val results=f.report().getJSONArray("readResults");assertEquals(3,results.length());assertEquals(90,results.getJSONObject(0).getJSONObject("value").getInt("batteryPercent"));assertFalse(f.s.report!!.contains("private serial"))
    }
    @Test fun unsafeOrNonReadableCharacteristicsNeverCauseReads(){
        val f=Fixture();f.connected();f.s.discover(true,true);f.s.discovered(0,listOf(service(0x180f,characteristic(1,0x2a19,16)),service(0xfff0,characteristic(2,0x2a19)),service(0x1812,characteristic(3,0x2a4b))))
        assertFalse(f.port.calls.any{it.startsWith("read")});f.finish();assertEquals(0,f.report().getInt("standardReadsPlanned"))
        assertNull(OsmoServiceSession.decode(OsmoServiceSession.uuid(0x180f),OsmoServiceSession.uuid(0x2a19),byteArrayOf(127)))
    }
    @Test fun connectionTimeoutHasNoAutomaticRetryAndCannotReportDiscoverySuccess(){
        val f=Fixture();f.start();f.now=15000;f.s.tick();assertEquals(OsmoServiceSession.Phase.DISCONNECTING,f.s.phase);f.now+=3000;f.s.tick()
        val r=f.report();assertEquals("discovery_succeeded_connection_failed",r.getString("outcome"));assertFalse(r.getBoolean("serviceDiscoveryCompleted"));assertFalse(r.getJSONObject("cleanup").getBoolean("disconnectCallbackObserved"));assertTrue(r.getJSONObject("cleanup").getBoolean("closeCalled"));f.s.tick();assertEquals(1,f.port.calls.count{it=="connect"})
    }
    @Test fun connectionFailureAndGattUnavailableAreReportedWithoutClaimingBluetoothImpossible(){
        val f=Fixture();f.start();f.s.connection(133,0);assertEquals("discovery_succeeded_connection_failed",f.report().getString("outcome"))
        val g=Fixture();g.port.connectResult=OsmoServiceSession.Request.REJECTED;g.start();g.now=3000;g.s.tick();assertEquals("gatt_unavailable_or_inconclusive",g.report().getString("outcome"))
    }
    @Test fun serviceFailureAndServiceTimeoutKeepConnectionEvidence(){
        val f=Fixture();f.connected();f.s.discover(true,false);f.s.discovered(133,emptyList());f.finish();assertTrue(f.report().getBoolean("connectionSucceeded"));assertEquals("connection_succeeded_service_discovery_failed",f.report().getString("outcome"))
        val g=Fixture();g.connected();g.s.discover(true,false);g.now=10000;g.s.tick();g.now+=3000;g.s.tick();assertEquals("service_discovery_timeout",g.report().getString("termination"))
    }
    @Test fun readAuthorizationAndBondObservationsAreRecordedWithoutBypass(){
        val f=Fixture();f.connected();f.s.bond(11);f.s.discover(true,true);f.s.discovered(0,listOf(service(0x180f,characteristic(1,0x2a19))));f.s.read(1,5,byteArrayOf());f.s.bond(12);f.finish()
        assertEquals("user_authorization_or_pairing_required",f.report().getString("outcome"));assertEquals(12,f.report().getInt("lastObservedBondState"));assertEquals(5,f.report().getJSONArray("readResults").getJSONObject(0).getInt("androidStatus"))
    }
    @Test fun cancellationsBluetoothOffAndBackgroundDisposeReleaseResources(){
        for(reason in listOf("cancelled","backgrounded","bluetooth_disabled")){
            val f=Fixture();f.connected();if(reason=="backgrounded")f.s.dispose(reason)else{f.s.end(reason);f.now=3000;f.s.tick()}
            assertEquals(OsmoServiceSession.Phase.FINISHED,f.s.phase);assertEquals("diagnostic_session_incomplete",f.report().getString("outcome"));assertTrue(f.report().getJSONObject("cleanup").getBoolean("closeCalled"))
            f.s.connection(0,2);assertEquals(OsmoServiceSession.Phase.FINISHED,f.s.phase)
        }
    }
    @Test fun readAndConnectedIdleTimeoutsAreBounded(){
        val f=Fixture();f.connected();f.s.discover(true,true);f.s.discovered(0,listOf(service(0x180f,characteristic(1,0x2a19))));f.now=5000;f.s.tick();f.now+=3000;f.s.tick();assertEquals("timeout",f.report().getJSONArray("readResults").getJSONObject(0).getString("result"))
        val g=Fixture();g.connected();g.now=30000;g.s.tick();g.now+=3000;g.s.tick();assertEquals("diagnostic_session_incomplete",g.report().getString("outcome"));assertFalse(g.report().getBoolean("serviceDiscoveryRequested"))
    }
    @Test fun cleanupFailureIsNotHidden(){
        val f=Fixture();f.port.closeResult=false;f.connected();f.s.dispose();assertFalse(f.report().getJSONObject("cleanup").getBoolean("closeSucceeded"))
    }
    @Test fun androidInspectionHasNoWriteBondOrSubscriptionCalls(){
        val root=sequenceOf(File("."),File("app")).first{File(it,"src/main/AndroidManifest.xml").exists()}
        val code=File(root,"src/main/java/com/lagdaemon/creationremote/gimbal/AndroidOsmoInspection.kt").readText()
        for(forbidden in listOf("writeCharacteristic(","writeDescriptor(","readDescriptor(","setCharacteristicNotification(","createBond(","removeBond(","setPin("))assertFalse(forbidden,code.contains(forbidden))
        assertTrue(code.contains("safeRead(c.service.uuid.toString(),c.uuid.toString())"));assertTrue(code.contains("!explicit"));assertTrue(code.contains("token==generation"))
    }
}
