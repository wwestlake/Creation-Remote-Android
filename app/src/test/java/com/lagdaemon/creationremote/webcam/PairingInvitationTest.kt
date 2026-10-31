package com.lagdaemon.creationremote.webcam

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PairingInvitationTest {
    private val secret=ByteArray(32){it.toByte()}
    private val session="0123456789abcdef0123456789abcdef"
    private fun input(endpoint:String="ws://192.168.0.89:8765/signal")=JSONObject()
        .put("protocol",PairingInvitation.PROTOCOL).put("session",session)
        .put("secret",Base64.getUrlEncoder().withoutPadding().encodeToString(secret)).put("endpoint",endpoint).toString()
    @Test fun privateEndpointOnly() {
        for(endpoint in listOf("ws://8.8.8.8:8765/signal","ws://localhost:8765/signal","ws://192.168.0.89:8765/signal?token=x","http://192.168.0.89:8765/signal")) {
            try{PairingInvitation(input(endpoint));fail("Accepted $endpoint")}catch(_:IllegalArgumentException){}
        }
        assertEquals(session,PairingInvitation(input()).session)
    }
    @Test fun directionalAuthenticatedOfferAndAnswer() {
        fun hmac(key:ByteArray,data:ByteArray)=Mac.getInstance("HmacSHA256").run{init(SecretKeySpec(key,"HmacSHA256"));doFinal(data)}
        fun key(direction:String)=hmac(hmac(session.toByteArray(),secret),(PairingInvitation.PROTOCOL+":"+direction).toByteArray()+byteArrayOf(1))
        val invitation=PairingInvitation(input());val envelope=JSONObject(invitation.encryptOffer("test-sdp"))
        val decoder=Base64.getUrlDecoder();val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(key("offer"),"AES"),GCMParameterSpec(128,decoder.decode(envelope.getString("nonce"))))
        cipher.updateAAD(session.toByteArray());assertEquals("test-sdp",JSONObject(String(cipher.doFinal(decoder.decode(envelope.getString("ciphertext"))))).getString("sdp"))
        val nonce=ByteArray(12){(it+10).toByte()};cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key("answer"),"AES"),GCMParameterSpec(128,nonce));cipher.updateAAD(session.toByteArray())
        val answer=JSONObject().put("session",session).put("nonce",Base64.getUrlEncoder().withoutPadding().encodeToString(nonce))
            .put("ciphertext",Base64.getUrlEncoder().withoutPadding().encodeToString(cipher.doFinal("{\"type\":\"answer\",\"sdp\":\"answer-sdp\"}".toByteArray())))
        assertEquals("answer-sdp",invitation.decryptAnswer(answer.toString()))
        answer.put("session","ffffffffffffffffffffffffffffffff")
        try{invitation.decryptAnswer(answer.toString());fail("Wrong generation accepted")}catch(_:IllegalArgumentException){}
    }
}
