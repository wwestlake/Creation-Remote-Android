package com.lagdaemon.creationremote.gimbal

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

/** One durable local file per run; checkpoints and final report replace atomically. */
class OsmoReportStore(private val directory:File) {
    @Synchronized fun save(text:String):File {
        val id=JSONObject(text).getString("session")
        require(Regex("[a-f0-9-]{36}").matches(id))
        check(directory.isDirectory||directory.mkdirs())
        val target=File(directory,"Djehuti-Osmo-$id.json");val pending=File(directory,"$id.pending")
        FileOutputStream(pending).use{it.write(text.toByteArray(Charsets.UTF_8));it.fd.sync()}
        try{Files.move(pending.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}
        catch(_:AtomicMoveNotSupportedException){Files.move(pending.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING)}
        return target
    }
    @Synchronized fun latest():String?=directory.listFiles()?.filter{it.extension=="json"}?.sortedByDescending{it.lastModified()}?.firstNotNullOfOrNull{runCatching{it.readText().also{t->JSONObject(t)}}.getOrNull()}
}
