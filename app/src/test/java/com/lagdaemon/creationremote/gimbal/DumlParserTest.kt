package com.lagdaemon.creationremote.gimbal

import org.junit.Assert.*
import org.junit.Test

class DumlParserTest {
    // Published receive-only fixture from alkersan/om-research gimbal.py CRC examples.
    // MIT attribution and license retained in docs/third-party/om-research-LICENSE.txt.
    private val sample="551504a902044700400414c20100002000050feb63".chunked(2).map{it.toInt(16).toByte()}.toByteArray()
    @Test fun publishedFixtureChecksumsAndFields(){
        val parser=DumlParser();val h=parser.feed(sample).single()
        assertEquals(21,h.length);assertEquals(1,h.version);assertEquals(2,h.sender);assertEquals(4,h.receiver)
        assertEquals(0x47,h.sequence);assertEquals(0x40,h.flags);assertEquals(4,h.commandSet);assertEquals(0x14,h.command);assertEquals(8,h.payloadBytes)
        assertEquals(0,parser.rejected)
    }
    @Test fun boundariesFragmentationAndCoalescing(){
        val parser=DumlParser();assertTrue(parser.feed(sample.copyOfRange(0,9)).isEmpty())
        assertEquals(2,parser.feed(sample.copyOfRange(9,sample.size)+sample).size)
        val bytewise=DumlParser();assertEquals(1,sample.sumOf{bytewise.feed(byteArrayOf(it)).size})
    }
    @Test fun corruptHeaderAndPacketAreRejectedAndResynchronize(){
        for(offset in listOf(3,13,19)){
            val bad=sample.copyOf();bad[offset]=(bad[offset].toInt() xor 1).toByte()
            val parser=DumlParser();assertEquals(1,parser.feed(bad+sample).size);assertTrue(parser.rejected>0)
        }
    }
    @Test fun malformedLengthAndNoiseStayBounded(){
        val parser=DumlParser();assertTrue(parser.feed(ByteArray(20000){0x55}).isEmpty())
        assertEquals(1,parser.feed(byteArrayOf(0x55,0,4,0)+sample).size)
        parser.feed(sample.copyOfRange(0,7));parser.clear();assertEquals(1,parser.feed(sample).size)
    }
    @Test fun exportContainsOnlySafeFramingMetadata(){
        val journal=BleJournal();val header=DumlParser().feed(sample).single()
        journal.event("notify",1,service="0000fff0-0000-1000-8000-00805f9b34fb",characteristic="0000fff4-0000-1000-8000-00805f9b34fb",byteCount=sample.size,header=header)
        val result=org.json.JSONObject(journal.export(35));val row=result.getJSONArray("events").getJSONObject(0)
        assertFalse(row.has("payload"));assertFalse(row.has("address"));assertFalse(row.has("name"));assertFalse(row.has("raw"))
        assertEquals(0x14,row.getJSONObject("duml").getInt("command"))
        try{journal.event("sensitive arbitrary content");fail("Unrestricted log accepted")}catch(_:IllegalArgumentException){}
        try{journal.event("notify",service="AA:BB:CC:DD:EE:FF");fail("Address accepted as UUID")}catch(_:IllegalArgumentException){}
    }
    @Test fun journalIsBounded(){
        val journal=BleJournal();journal.event("service",service="0000fff0-0000-1000-8000-00805f9b34fb");repeat(1200){journal.event("notify",byteCount=20)}
        val result=org.json.JSONObject(journal.export(35));assertEquals(1000,result.getJSONArray("events").length());assertEquals(201,result.getInt("droppedEvents"));assertEquals(1,result.getJSONArray("inventory").length())
    }
}
