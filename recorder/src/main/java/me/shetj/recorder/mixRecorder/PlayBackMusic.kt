@file:Suppress("DEPRECATION")

package me.shetj.recorder.mixRecorder

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import me.shetj.player.PlayerListener
import java.util.concurrent.LinkedBlockingDeque
import android.media.AudioAttributes
import android.media.AudioFormat.CHANNEL_OUT_MONO


/**
 * 播放音乐，用来播放PCM
 *
 * 1.支持暂停 pause(),resume() <br>
 * 2.支持循环播放setLoop(boolean isLoop)<br>
 * 3. 支持切换背景音乐 setBackGroundUrl(String path)<br>
 * update 2019年10月11日
 * 添加时间进度记录(注意返回不是在主线程需要自己设置在主线程)
 *
 * TODO seekTo 缺失功能
 *
 */
class PlayBackMusic(private var defaultChannel: Int = CHANNEL_OUT_MONO) {

    private var mAudioDecoder: AudioDecoder? = null
    private val backGroundBytes =
        LinkedBlockingDeque<ByteArray>()//new ArrayDeque<>();// ArrayDeque不是线程安全的
    var isPlayingMusic = false
        private set
    private var mIsRecording = false
    private var mIsLoop = false
    var isIsPause = false
        private set
    private val playHandler: PlayHandler
    private var audioTrack: AudioTrack? = null
    private var volume  = 0.3f
    private var playerListener: PlayerListener?=null

    internal val isPCMDataEos: Boolean
        get() = if (mAudioDecoder == null){
            true
        }else {
            mAudioDecoder!!.isPCMExtractorEOS
        }

    val bufferSize: Int
        get() = if (mAudioDecoder == null){
            AudioDecoder.BUFFER_SIZE
        }else{
            mAudioDecoder!!.bufferSize
        }

    private class PlayHandler(private val playBackMusic: PlayBackMusic) :
        Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                PROCESS_STOP, PROCESS_ERROR -> playBackMusic.release()
                PROCESS_REPLAY -> playBackMusic.restartMusic()
            }
        }
    }

    init {
        playHandler = PlayHandler(this)
    }

    /**
     * 设置或者切换背景音乐
     * @param path
     * @return
     */
    fun setBackGroundUrl(path: String): PlayBackMusic {
        if (isIsPause) {
            releaseDecoder()
            initDecoder(path)
        } else {
            isIsPause = true
            releaseDecoder()
            initDecoder(path)
            isIsPause = false
        }
        return this
    }

    fun setBackGroundPlayListener(playerListener: PlayerListener){
        this.playerListener = playerListener
    }

    private fun initDecoder(path: String) {
        mAudioDecoder = AudioDecoder()
        mAudioDecoder?.setMp3FilePath(path)
    }

    private fun releaseDecoder() {
        if (mAudioDecoder != null) {
            mAudioDecoder?.release()
            mAudioDecoder = null
        }
    }

    /**
     * mIsRecording 标识外部是否正在录音，只有开始录音
     * @param enable
     * @return
     */
    fun setNeedRecodeDataEnable(enable: Boolean): PlayBackMusic {
        mIsRecording = enable
        return this
    }

    /**
     * 是否循环播放
     * @param isLoop 是否循环
     */
    fun setLoop(isLoop: Boolean) {
        mIsLoop = isLoop
    }

    /**
     * 开始播放
     * @return
     */
    fun startPlayBackMusic(): PlayBackMusic {
        if (mAudioDecoder == null) {
            throw NullPointerException("AudioDecoder no null, please set setBackGroundUrl")
        }
        //开始加载音乐数据
        initPCMData()
        isPlayingMusic = true
        PlayNeedMixAudioTask(object : BackGroundFrameListener {
            override fun onFrameArrive(bytes: ByteArray) {
                addBackGroundBytes(bytes)
            }
        }).start()
        playerListener?.onStart("",0)
        return this
    }


    fun getBackGroundBytes(): ByteArray? {
        if (backGroundBytes.isEmpty()) {
            return null
        }
        return backGroundBytes.poll()
    }

    fun hasFrameBytes(): Boolean {
        return !backGroundBytes.isEmpty()
    }

    fun frameBytesSize(): Int {
        return backGroundBytes.size
    }

    /**
     * 暂停播放
     * @return
     */
    fun stop() {
        isPlayingMusic = false
        playerListener?.onStop()
    }

    fun resume() {
        if (isPlayingMusic) {
            isIsPause = false
            playerListener?.onResume()
        }
    }

    fun pause() {
        if (isPlayingMusic) {
            isIsPause = true
            playerListener?.onPause()
        }
    }


    fun release(): PlayBackMusic {
        isPlayingMusic = false
        isIsPause = false
        mAudioDecoder?.release()
        backGroundBytes.clear()
        return this
    }

    /**
     * 这样的方式控制同步 需要添加到队列时判断同时在播放和录制
     */
    private fun addBackGroundBytes(bytes: ByteArray) {
        if (isPlayingMusic && mIsRecording) {
            backGroundBytes.add(bytes) // what if out of memory?
        }
    }

    /**
     * 重新开始播放
     */
    private fun restartMusic() {
        //等于 mAudioDecoder.isPCMExtractorEOS()  = true 表示已经结束了
        //如果是循环模式要重新开始解码
        if (isPCMDataEos && mIsLoop) {
            //重新开始播放mp3 -> pcm
            initPCMData()
        }
        Log.i("PlayBackMusic","restartMusic")
    }

    /**
     * 解析 mp3 --> pcm
     */
    private fun initPCMData() {
        mAudioDecoder!!.startPcmExtractor()
    }

    /**
     * 虽然可以新建多个 AsyncTask的子类的实例，但是AsyncTask的内部Handler和ThreadPoolExecutor都是static的，
     * 这么定义的变 量属于类的，是进程范围内共享的，所以AsyncTask控制着进程范围内所有的子类实例，
     * 而且该类的所有实例都共用一个线程池和Handler
     * 这里新开一个线程
     * 自己解析出来 pcm data
     */
    private inner class PlayNeedMixAudioTask internal constructor(private val listener: BackGroundFrameListener?) :
        Thread() {
        override fun run() {
            try {
                if (audioTrack == null) {
                    audioTrack = initAudioTrack()
                    setVolume(volume)
                }
                // 开始播放
                audioTrack!!.play()
                //音乐实际开始会慢一点
                repeat(10) {
                    listener?.onFrameArrive(ByteArray(2048))
                }
                while (isPlayingMusic) {
                    if (!isIsPause) {
                        val pcm = mAudioDecoder!!.pcmData
                        val temp = pcm?.bufferBytes
                        if (pcm == null || temp == null){
                            if (mIsLoop) {
                                playHandler.sendEmptyMessage(PROCESS_REPLAY)
                                sleep(100)
                            }
                            continue
                        }
                        audioTrack!!.write(temp, 0, temp.size)
                        if (mAudioDecoder!= null && mAudioDecoder!!.mediaFormat !=null) {
                            playerListener?.onProgress(
                                (pcm.time / 1000).toInt(),
                                (mAudioDecoder!!.mediaFormat!!.getLong(MediaFormat.KEY_DURATION) / 1000).toInt()
                            )
                        }
                        listener?.onFrameArrive(temp)
                    } else {
                        //如果是暂停
                        try {
                            //防止死循环ANR
                            sleep(500)
                        } catch (e: InterruptedException) {
                            e.printStackTrace()
                        }
                    }
                }
                playerListener?.onStop()
                audioTrack!!.stop()
                audioTrack!!.flush()
                audioTrack!!.release()
                audioTrack = null
            } catch (e: Exception) {
                Log.e("mp3Recorder", "error:" + e.message)
                playerListener?.onError(e)
            } finally {
                isPlayingMusic = false
                isIsPause = false
                playerListener?.onCompletion()
            }
        }
    }

    private fun initAudioTrack(): AudioTrack {
        val bufferSize = AudioTrack.getMinBufferSize(
            mSampleRate,
            defaultChannel, mAudioEncoding
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(mAudioEncoding)
                        .setSampleRate(mSampleRate)
                        .setChannelMask(defaultChannel)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build()
        } else {
            return  AudioTrack(AudioManager.STREAM_MUSIC,
                mSampleRate, defaultChannel, mAudioEncoding, bufferSize,
                AudioTrack.MODE_STREAM
            )
        }
    }


    fun setVolume(volume: Float) {
        this.volume = volume
        if (audioTrack != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                audioTrack!!.setVolume(volume)
            }else{
                audioTrack!!.setStereoVolume(volume,volume)
            }
        }
    }

    internal interface BackGroundFrameListener {
        fun onFrameArrive(bytes: ByteArray)
    }

    companion object {
        private val PROCESS_STOP = 3
        private val PROCESS_ERROR = 4
        private val PROCESS_REPLAY = 5
        private val mSampleRate = 44100
        private val mAudioEncoding = AudioFormat.ENCODING_PCM_16BIT//一个采样点16比特-2个字节
    }
}
