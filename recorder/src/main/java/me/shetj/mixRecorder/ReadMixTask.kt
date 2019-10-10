package me.shetj.mixRecorder


import me.shetj.recorder.util.BytesTransUtil

class ReadMixTask(rawData: ByteArray, val readSize: Int) {
    private val rawData: ByteArray = rawData.clone()

    val data: ShortArray
        get() = BytesTransUtil.bytes2Shorts(rawData)

}