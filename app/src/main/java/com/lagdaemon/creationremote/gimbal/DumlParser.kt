package com.lagdaemon.creationremote.gimbal

/** Receive-only DUML v1 framing. A valid frame says nothing about OM6 command support.
 * Independent bitwise implementation; see docs/OSMO_BLE_INVESTIGATION.md for sources.
 */
class DumlParser {
    data class Header(val length:Int,val version:Int,val sender:Int,val receiver:Int,val sequence:Int,
                      val flags:Int,val commandSet:Int,val command:Int,val payloadBytes:Int)
    private val buffer=ArrayList<Int>()
    var rejected=0;private set
    var discarded=0;private set
    fun clear(){buffer.clear()}
    fun feed(input:ByteArray):List<Header> {
        val result=mutableListOf<Header>()
        for(byte in input){
            buffer.add(byte.toInt() and 255)
            while(buffer.isNotEmpty()){
                if(buffer[0]!=0x55){buffer.removeAt(0);discarded++;continue}
                if(buffer.size<4)break
                val length=buffer[1] or ((buffer[2] and 3) shl 8)
                val version=buffer[2] shr 2
                if(length !in 13..1023 || version!=1 || crc(buffer.take(3),0x77,0x8c)!=buffer[3]){
                    buffer.removeAt(0);rejected++;continue
                }
                if(buffer.size<length)break
                val data=buffer.take(length)
                if(crc(data.dropLast(2),0x3692,0x8408)!=(data[length-2] or (data[length-1] shl 8))){
                    buffer.removeAt(0);rejected++;continue
                }
                result.add(Header(length,version,data[4],data[5],data[6] or (data[7] shl 8),data[8],data[9],data[10],length-13))
                repeat(length){buffer.removeAt(0)}
            }
        }
        return result
    }
    companion object {
        private fun crc(data:List<Int>,initial:Int,polynomial:Int):Int {
            var value=initial
            for(byte in data){value=value xor byte;repeat(8){value=(value ushr 1) xor if(value and 1!=0)polynomial else 0}}
            return value
        }
    }
}
