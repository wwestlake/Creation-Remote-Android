package com.lagdaemon.creationremote.gimbal

/** Preserve an explicit choice as live RSSI/counts change; never pick among several aliases. */
object CandidateSelection {
    fun update(selected:String,aliases:List<String>,explicit:Boolean=false):String=when{
        explicit&&selected in aliases->selected
        aliases.size==1->aliases.single()
        else->""
    }
}
