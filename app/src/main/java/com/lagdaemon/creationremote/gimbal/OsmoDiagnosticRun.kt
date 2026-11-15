package com.lagdaemon.creationremote.gimbal

/** Deterministic receive-only workflow. Android owns I/O; the run owns transitions/deadlines. */
class OsmoDiagnosticRun(private val clock:()->Long) {
    enum class Phase { IDLE, PERMISSION, SEARCHING, CHOOSING, CONNECTING, EXAMINING, SUBSCRIBING, MONITORING, PREPARING, COMPLETE, FAILED, CANCELLED }
    data class Candidate(val id:Int,val osmoName:Boolean,val vendorService:Boolean,val nameHint:Boolean=osmoName)
    sealed interface Action {
        data object RequestPermission:Action
        data object Scan:Action
        data class Connect(val id:Int):Action
        data class Subscribe(val id:Int):Action
        data object Monitor:Action
        data object Finish:Action
    }
    var phase=Phase.IDLE; private set
    var reason="idle"; private set
    var deadline=Long.MAX_VALUE; private set
    var selected:Int?=null; private set
    var choices=emptyList<Candidate>(); private set
    private val channels=ArrayDeque<Int>()
    private var pending:Int?=null
    private var overall=Long.MAX_VALUE
    var subscriptionFailures=0; private set
    val active get()=phase !in setOf(Phase.IDLE,Phase.COMPLETE,Phase.FAILED,Phase.CANCELLED)
    val terminal get()=phase in setOf(Phase.COMPLETE,Phase.FAILED,Phase.CANCELLED)
    private fun enter(next:Phase,ms:Long=Long.MAX_VALUE){phase=next;deadline=if(ms==Long.MAX_VALUE)ms else clock()+ms}
    fun start():List<Action>{
        if(active)return emptyList()
        selected=null;choices=emptyList();channels.clear();pending=null;subscriptionFailures=0;reason="running"
        overall=clock()+180_000;enter(Phase.PERMISSION,60_000);return listOf(Action.RequestPermission)
    }
    fun permission(granted:Boolean):List<Action>{
        if(phase!=Phase.PERMISSION)return emptyList()
        if(!granted)return fail("permission_denied")
        enter(Phase.SEARCHING,15_000);return listOf(Action.Scan)
    }
    fun scanned(candidates:List<Candidate>):List<Action>{
        if(phase!=Phase.SEARCHING)return emptyList()
        choices=candidates.filter{it.nameHint||it.vendorService}
        if(choices.isEmpty())return fail("no_candidate")
        // A service alone is only a hint. Never silently choose it, or a first/strongest result.
        if(choices.size==1&&choices.single().osmoName)return chooseInternal(choices.single().id)
        enter(Phase.CHOOSING,60_000);return emptyList()
    }
    fun choose(id:Int):List<Action> = if(phase==Phase.CHOOSING&&choices.any{it.id==id})chooseInternal(id)else emptyList()
    private fun chooseInternal(id:Int):List<Action>{selected=id;enter(Phase.CONNECTING,15_000);return listOf(Action.Connect(id))}
    fun connected():List<Action>{if(phase!=Phase.CONNECTING)return emptyList();enter(Phase.EXAMINING,10_000);return emptyList()}
    fun discovered(recognizedChannels:List<Int>):List<Action>{
        if(phase!=Phase.EXAMINING)return emptyList()
        channels.addAll(recognizedChannels);return nextChannel()
    }
    private fun nextChannel():List<Action>{
        pending=channels.removeFirstOrNull()
        val id=pending
        if(id!=null){enter(Phase.SUBSCRIBING,10_000);return listOf(Action.Subscribe(id))}
        enter(Phase.MONITORING,30_000);return listOf(Action.Monitor)
    }
    fun subscribed(id:Int,success:Boolean):List<Action>{
        if(phase!=Phase.SUBSCRIBING||pending!=id)return emptyList()
        if(!success)subscriptionFailures++
        return nextChannel()
    }
    fun tick():List<Action>{
        if(!active||phase==Phase.PREPARING)return emptyList()
        if(clock()>=overall)return fail("session_timeout")
        if(clock()<deadline)return emptyList()
        return when(phase){
            Phase.MONITORING->{reason="monitoring_complete";enter(Phase.PREPARING);listOf(Action.Finish)}
            else->fail(when(phase){Phase.PERMISSION->"permission_timeout";Phase.CHOOSING->"selection_timeout";Phase.CONNECTING->"connection_timeout";Phase.EXAMINING->"discovery_timeout";Phase.SUBSCRIBING->"subscription_timeout";else->"scan_timeout"})
        }
    }
    fun fail(code:String):List<Action>{if(!active)return emptyList();reason=code;enter(Phase.PREPARING);return listOf(Action.Finish)}
    fun cancel():List<Action> = fail("cancelled")
    fun prepared(){if(phase==Phase.PREPARING)enter(if(reason=="cancelled")Phase.CANCELLED else if(reason=="monitoring_complete")Phase.COMPLETE else Phase.FAILED)}
}
