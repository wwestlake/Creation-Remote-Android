package com.lagdaemon.creationremote.gimbal

/** Bounded scan-only lifecycle. There is deliberately no connection transition. */
class DiscoveryRun(private val clock:()->Long) {
    enum class Phase { IDLE, PERMISSION, SCANNING, FINISHED }
    var phase=Phase.IDLE;private set
    var deadline=0L;private set
    var scanStarted=0L;private set
    var termination="";private set
    val active get()=phase==Phase.PERMISSION||phase==Phase.SCANNING
    val candidateHandoffAllowed get()=phase==Phase.SCANNING||(phase==Phase.FINISHED&&termination in setOf("duration_completed","cancelled","selected_for_connection"))
    fun start():Boolean{if(active)return false;phase=Phase.PERMISSION;termination="";deadline=clock()+60_000;return true}
    fun beginScan():Boolean{if(phase!=Phase.PERMISSION)return false;phase=Phase.SCANNING;scanStarted=clock();deadline=clock()+20_000;return true}
    fun stop(reason:String):Boolean{require(reason in DiscoveryEvidence.terminations);if(!active)return false;termination=reason;phase=Phase.FINISHED;return true}
    fun due():String?=if(!active||clock()<deadline)null else if(phase==Phase.PERMISSION)"permission_timeout" else "duration_completed"
}
