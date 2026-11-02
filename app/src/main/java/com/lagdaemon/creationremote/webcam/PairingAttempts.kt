package com.lagdaemon.creationremote.webcam

/** Worker-owned bounded failover, independent of Android and WebRTC callbacks. */
class PairingAttempts(private val endpoints: List<String>) {
    data class Attempt(val id: Int, val endpoint: String)
    enum class Failure { UNREACHABLE, REFUSED, AUTHENTICATION }
    private var index = 0
    private var current: Attempt? = null
    private val failures = mutableSetOf<Failure>()
    init { require(endpoints.size in 1..16) }
    fun next(): Attempt? {
        current = if (index < endpoints.size) Attempt(++index, endpoints[index - 1]) else null
        return current
    }
    fun isCurrent(attempt: Attempt) = current == attempt
    fun failed(attempt: Attempt, failure: Failure): Boolean {
        if (!isCurrent(attempt)) return false
        failures.add(failure)
        current = null
        return true
    }
    fun failureMessage(): String = when {
        Failure.AUTHENTICATION in failures -> "Failed: Movie authentication could not be verified. Generate a fresh QR code and scan again."
        Failure.REFUSED in failures -> "Failed: Movie connections were refused or blocked. Keep Pair Phone open and check the PC firewall and local network access."
        else -> "Failed: No reachable Movie endpoint. Check that the phone and PC can communicate over the local network; firewall rules or router isolation may prevent access."
    }
}
