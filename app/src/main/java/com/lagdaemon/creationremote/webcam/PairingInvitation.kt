package com.lagdaemon.creationremote.webcam

import java.util.Base64
import org.json.JSONObject
import java.net.URI
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One-use out-of-band invitation; never persisted or logged. */
class PairingInvitation(text: String) {
    private val json = JSONObject(text.trim())
    val session: String = json.getString("session")
    val endpoint: String = json.getString("endpoint")
    private val secret = decode(json.getString("secret"))
    init {
        require(json.getString("protocol") == PROTOCOL) { "Unsupported invitation version" }
        require(session.matches(Regex("[0-9a-f]{32}")) && secret.size == 32) { "Invalid invitation" }
        val uri = URI(endpoint)
        require(uri.scheme == "ws" && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path == "/signal") { "Invalid LAN endpoint" }
        val octets = uri.host?.split('.')?.map { it.toIntOrNull() ?: -1 } ?: emptyList()
        require(octets.size == 4 && octets.all { it in 0..255 } &&
            (octets[0] == 10 || (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 172 && octets[1] in 16..31))) { "Pairing requires a private LAN IPv4 address" }
        require(uri.port in 1..65535) { "Invalid LAN port" }
    }
    private fun key(direction: String): ByteArray {
        fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256")); doFinal(data)
        }
        val extracted = hmac(session.toByteArray(Charsets.UTF_8), secret)
        return hmac(extracted, "$PROTOCOL:$direction".toByteArray(Charsets.UTF_8) + byteArrayOf(1))
    }
    fun encryptOffer(sdp: String): String {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key("offer"), "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(session.toByteArray(Charsets.UTF_8))
        val data = JSONObject().put("type", "offer").put("sdp", sdp).toString().toByteArray(Charsets.UTF_8)
        return JSONObject().put("session", session).put("nonce", encode(nonce))
            .put("ciphertext", encode(cipher.doFinal(data))).toString()
    }
    fun decryptAnswer(text: String): String {
        require(text.length <= 131072) { "Answer too large" }
        val value = JSONObject(text)
        require(value.getString("session") == session) { "Wrong connection generation" }
        val nonce = decode(value.getString("nonce")); require(nonce.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key("answer"), "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(session.toByteArray(Charsets.UTF_8))
        val answer = JSONObject(String(cipher.doFinal(decode(value.getString("ciphertext"))), Charsets.UTF_8))
        require(answer.getString("type") == "answer")
        return answer.getString("sdp")
    }
    companion object {
        const val PROTOCOL = "djehuti-webcam-feasibility-v1"
        private fun decode(value: String) = Base64.getUrlDecoder().decode(value)
        private fun encode(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }
}
