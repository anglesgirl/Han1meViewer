package com.yenaly.han1meviewer.ui.view.video

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import org.chromium.net.CronetEngine
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import cn.jzvd.JZMediaInterface
import cn.jzvd.JZMediaSystem
import cn.jzvd.Jzvd
import com.yenaly.han1meviewer.BuildConfig
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.USER_AGENT
import com.yenaly.han1meviewer.logic.network.HProxySelector
import com.yenaly.han1meviewer.logic.network.ech.EchDoh
import com.yenaly.han1meviewer.logic.network.ech.EchTrace
import com.yenaly.han1meviewer.util.AnimeShaders
import com.yenaly.han1meviewer.util.AnimeShaders.getCert
import com.yenaly.han1meviewer.ui.component.GlobalToasts
import `is`.xyz.mpv.MPVLib
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.absoluteValue

/**
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2024/04/21 021 16:57
 */
sealed interface HMediaKernel {
    enum class Type(val clazz: Class<out JZMediaInterface>) {
        MediaPlayer(SystemMediaKernel::class.java),
        ExoPlayer(ExoMediaKernel::class.java),
        MpvPlayer(MpvMediaKernel::class.java);

        companion object {
            fun fromString(name: String): Type {
                return when (name) {
                    MediaPlayer.name -> MediaPlayer
                    ExoPlayer.name -> ExoPlayer
                    MpvPlayer.name -> MpvPlayer
                    else -> ExoPlayer
                }
            }
        }
    }
}

class ExoMediaKernel(jzvd: Jzvd) : JZMediaInterface(jzvd), Player.Listener, HMediaKernel {
    private var isActuallyPlaying = false
    private var lastBufferedPercent = -1
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            isActuallyPlaying = isPlaying
        }
        override fun onIsLoadingChanged(isLoading: Boolean) {
            val per = _exoPlayer?.bufferedPercentage ?: return
            if (per != lastBufferedPercent) {
                lastBufferedPercent = per
                jzvd.setBufferProgress(per)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            // 这里也可以加上更新，确保播放器状态变更时刷新 UI
            val per = _exoPlayer?.bufferedPercentage ?: return
            jzvd.setBufferProgress(per)
        }
    }

    // ---- 诊断日志（diag-log）：每个点同时写 logcat + EchTrace 环形缓冲 ----
    private fun diagLog(msg: String) {
        Log.i("HY-DIAG", msg)
        EchTrace.event(msg)
    }

    private fun hostAndPathPrefix(rawUrl: String): String {
        return try {
            val u = Uri.parse(rawUrl)
            val path = (u.path ?: "").take(80)
            "host=${u.host} pathPrefix=$path"
        } catch (e: Exception) {
            "host=? (parse failed: ${e.javaClass.simpleName})"
        }
    }

    // 本项目 Media3 的 LoadEventInfo 没有 loadEventId 字段，耗时改按 URI 键统计
    //（分片 URI 各不相同；同一 URI 重试时取最近一次开始时间，诊断够用）。
    private val diagLoadStartMs = ConcurrentHashMap<String, Long>()

    // V3：逐请求诊断（分片全覆盖）。只记 host + path 前缀，不记 query，避免 token 外泄。
    private val diagAnalyticsListener = object : AnalyticsListener {
        override fun onLoadStarted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            diagLoadStartMs[loadEventInfo.uri.toString()] = eventTime.realtimeMs
            diagLog("[V3] loadStarted ${hostAndPathPrefix(loadEventInfo.uri.toString())} dataType=${mediaLoadData.dataType}")
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            val start = diagLoadStartMs.remove(loadEventInfo.uri.toString())
            val cost = if (start != null) "${eventTime.realtimeMs - start}ms" else "?"
            diagLog("[V3] loadCompleted ${hostAndPathPrefix(loadEventInfo.uri.toString())} cost=$cost bytes=${loadEventInfo.bytesLoaded}")
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            diagLoadStartMs.remove(loadEventInfo.uri.toString())
            val extra = if (error is HttpDataSource.InvalidResponseCodeException) {
                "responseCode=${error.responseCode} responseMessage=${error.responseMessage} dataSpecHost=${error.dataSpec?.uri?.host}"
            } else {
                "cause=${error.javaClass.simpleName}: ${error.message?.take(200)}"
            }
            diagLog("[V3] loadError ${hostAndPathPrefix(loadEventInfo.uri.toString())} wasCanceled=$wasCanceled $extra")
        }
    }

    companion object {
        const val TAG = "ExoMediaKernel"
    }

    private var _exoPlayer: ExoPlayer? = null

    /**
     * 尽量少用，用了之后容易出bug
     */
    private val exoPlayer get() = _exoPlayer!!

    private var callback: Runnable? = null
    private var prevSeek = 0L

    var videoRealWidth: Int = 0
        private set
    var videoRealHeight: Int = 0
        private set
    @OptIn(UnstableApi::class)
    override fun prepare() {
        diagLog("[V0] prepare entry hasExistingPlayer=${_exoPlayer != null}")
        if (_exoPlayer != null) {
            Log.w(TAG, "prepare called, but player already exists.")
            diagLog("[V0] prepare skipped, player already exists")
            return // 防止误调用
        }
        Log.i(TAG, "prepare")
        val context = jzvd.context

        release()
        mMediaHandlerThread = HandlerThread("JZVD")
        mMediaHandlerThread.start()
        mMediaHandler = Handler(mMediaHandlerThread.looper)
        handler = Handler(Looper.getMainLooper())
        mMediaHandler?.post {
            // ---- 诊断 V1：建播放器前的上下文 ----
            val diagDs = jzvd.jzDataSource
            val diagCurrUrl = diagDs.currentUrl.toString()
            val diagNetDesc = try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                when {
                    caps == null -> "unknown(no caps)"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                        val op = try { tm.networkOperatorName?.takeIf { it.isNotBlank() } ?: "?" } catch (e: Exception) { "?" }
                        "移动(运营商=$op)"
                    }
                    else -> "other"
                }
            } catch (e: Exception) {
                "?(${e.javaClass.simpleName})"
            }
            val diagUrlKeys = try { diagDs.urlsMap.keys.map { it.toString() } } catch (e: Exception) { listOf("?(${e.javaClass.simpleName})") }
            val diagResKey = diagUrlKeys.getOrNull(diagDs.currentUrlIndex) ?: "unknown"
            val diagHeaderDesc = try {
                val hm = diagDs.headerMap
                if (hm.isEmpty()) "空" else "keys=${hm.keys} (values omitted)"
            } catch (e: Exception) {
                "?(${e.javaClass.simpleName})"
            }
            diagLog("[V1] prepare net=$diagNetDesc url=${hostAndPathPrefix(diagCurrUrl)} isM3u8=${diagCurrUrl.contains(".m3u8")} resKey=$diagResKey urlIndex=${diagDs.currentUrlIndex} urlsMapKeys=$diagUrlKeys")
            diagLog("[V1] cdn useBackupMediaCdn=${Preferences.useBackupMediaCdn} mediaCdnRegion=${Preferences.mediaCdnRegion} mediaCdnNodeIps=${Preferences.mediaCdnNodeIps}")
            diagLog("[V1] headerMap=$diagHeaderDesc")
            // ---- 诊断 V2：DNS 对照（后台线程，不阻塞播放） ----
            val diagHost = try { Uri.parse(diagCurrUrl).host } catch (e: Exception) { null }
            if (diagHost != null) {
                Thread {
                    try {
                        val sysAddrs = try {
                            InetAddress.getAllByName(diagHost).map { it.hostAddress ?: "?" }
                        } catch (e: Exception) {
                            listOf("FAIL:${e.javaClass.simpleName}")
                        }
                        val dohAddrs = try {
                            EchDoh.resolve(diagHost).map { it.hostAddress ?: "?" }
                        } catch (e: Exception) {
                            listOf("FAIL:${e.javaClass.simpleName}")
                        }
                        val nodeIps = Preferences.mediaCdnNodeIps
                        val pinned = nodeIps.isNotEmpty() && dohAddrs.toSet() == nodeIps.toSet()
                        diagLog("[V2] dns host=$diagHost system=$sysAddrs echDoh=$dohAddrs pinned=$pinned (钉住只对 echTransport 生效，Cronet 不用)")
                    } catch (e: Exception) {
                        diagLog("[V2] dns failed host=$diagHost cause=${e.javaClass.simpleName}")
                    }
                }.start()
            }
            val videoTrackSelectionFactory = AdaptiveTrackSelection.Factory()
            val trackSelector = DefaultTrackSelector(context, videoTrackSelectionFactory)

            val loadControl: LoadControl = DefaultLoadControl.Builder()
                // .setBufferDurationsMs(360000, 600000, 1000, 5000)
                // .setPrioritizeTimeOverSizeThresholds(false)
                // .setTargetBufferBytes(C.LENGTH_UNSET)
                .build()


            val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()
            // 2. Create the player
            val renderersFactory = DefaultRenderersFactory(context)
            _exoPlayer = ExoPlayer.Builder(context, renderersFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setBandwidthMeter(bandwidthMeter)
                .build().apply {
                    addListener(playerListener)
                }

            // Produces DataSource instances through which media data is loaded.
            // Use Cronet (Chrome's network stack) for TLS fingerprint parity with Chrome,
            // bypassing DPI that filters by ClientHello fingerprint (e.g. Fujian Mobile).
            // cronet-bundled ships the native provider inside the APK, so Cronet works on
            // devices without Google Play Services; if no provider is available anyway,
            // fall back to the platform HTTP stack instead of crashing playback.
            var diagDataSourceKind = "Cronet"
            val httpDataSourceFactory = try {
                val cronetEngine = CronetEngine.Builder(context)
                    .enableHttp2(true)
                    .enableQuic(true)
                    .build()
                CronetDataSource.Factory(
                    cronetEngine,
                    java.util.concurrent.Executors.newSingleThreadExecutor()
                )
            } catch (e: Exception) {
                Log.w(TAG, "Cronet unavailable, using platform HTTP", e)
                diagDataSourceKind = "DefaultHttpDataSource"
                diagLog("[V1] dataSourceFactory fallback cause=${e.javaClass.simpleName}: ${e.message?.take(200)}")
                DefaultHttpDataSource.Factory()
            }.setDefaultRequestProperties(jzvd.jzDataSource.headerMap)
            diagLog("[V1] dataSourceFactory=$diagDataSourceKind player path: no ECH / no DoH / plain SNI ($diagDataSourceKind)")
            val dataSourceFactory = DefaultDataSource.Factory(
                context,
                httpDataSourceFactory
            )

            val currUrl = jzvd.jzDataSource.currentUrl.toString()
            val videoSource = if (currUrl.contains(".m3u8")) {
                HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(currUrl))
            } else {
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(currUrl))
            }

            Log.i(TAG, "URL Link = $currUrl")

            exoPlayer.addListener(this)
            // ---- 诊断 V3：逐请求 AnalyticsListener ----
            exoPlayer.addAnalyticsListener(diagAnalyticsListener)

            val isLoop = jzvd.jzDataSource.looping
            if (isLoop) {
                exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
            } else {
                exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
            }
            exoPlayer.setMediaSource(videoSource)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
//            callback = OnBufferingUpdate()

            val surfaceTexture = jzvd.textureView?.surfaceTexture
            if (surfaceTexture == null) {
                Log.e(TAG, "❌ surfaceTexture is null, video surface not set")
            }
            surfaceTexture?.let { exoPlayer.setVideoSurface(Surface(it)) }
        }
    }

    override fun start() {
        diagLog("[V0] start entry hasPlayer=${_exoPlayer != null} hasHandler=${mMediaHandler != null}")
        mMediaHandler?.post {
            _exoPlayer?.playWhenReady = true
        }
    }

    fun setLooping(looping: Boolean) {
        mMediaHandler?.post {
            _exoPlayer?.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        val realWidth = videoSize.width * videoSize.pixelWidthHeightRatio
        val realHeight = videoSize.height
        videoRealWidth = realWidth.toInt()
        videoRealHeight = realHeight
        Log.i("JZVD-onVideoSizeChanged","realWidth:$realWidth,realHeight:$realHeight")
        handler.post {
            jzvd.onVideoSizeChanged(realWidth.toInt(), realHeight)
        }
        val ratio = realWidth / realHeight // > 1 橫屏， < 1 竖屏
        if (ratio > 1) {
            Jzvd.FULLSCREEN_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            Jzvd.FULLSCREEN_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    override fun onRenderedFirstFrame() {
        Log.i(TAG, "onRenderedFirstFrame")
    }

    override fun pause() {
        mMediaHandler?.post {
            _exoPlayer?.playWhenReady = false
        }
    }

    override fun isPlaying(): Boolean {
        return runOnPlayerThread{
         //   _exoPlayer?.playWhenReady ?: false
            _exoPlayer?.playWhenReady
        } == true
      //  return isActuallyPlaying
    }

    override fun seekTo(time: Long) {
        mMediaHandler?.post {
            if (time != prevSeek) {
                _exoPlayer?.let { exoPlayer ->
                    if (time >= exoPlayer.bufferedPosition) {
                        jzvd.onStatePreparingPlaying()
                    }
                    exoPlayer.seekTo(time)
                    exoPlayer.playWhenReady = true
                    prevSeek = time
                    jzvd.seekToInAdvance = time
                }
            }
        }
    }

    override fun release() {
        if (mMediaHandler != null && mMediaHandlerThread != null && _exoPlayer != null) { //不知道有没有妖孽
            val tmpHandlerThread = mMediaHandlerThread
            val tmpMediaPlayer = exoPlayer
            SAVED_SURFACE = null
            mMediaHandler?.post {
                tmpMediaPlayer.release() //release就不能放到主线程里，界面会卡顿
                tmpHandlerThread.quit()
                _exoPlayer = null
            }
        }
    }



    // 在类里加一个工具方法，用来在线程里同步访问ExoPlayer

    inline fun <T> runOnPlayerThread(crossinline block: () -> T?): T? {
        if (Looper.myLooper() == mMediaHandler?.looper) {
            return block()
        }

        val result = AtomicReference<T?>()
        val latch = CountDownLatch(1)

        mMediaHandler?.post {
            try {
                result.set(block())
            } finally {
                latch.countDown()
            }
        }

        val finished = latch.await(300, TimeUnit.MILLISECONDS)
        return if (finished) result.get() else null
    }



    override fun getCurrentPosition(): Long {
        return runOnPlayerThread {
            _exoPlayer?.currentPosition
        } ?: 0L
    }


    override fun getDuration(): Long {
        return runOnPlayerThread {
            _exoPlayer?.duration
        } ?: 0L
    }



    override fun setVolume(leftVolume: Float, rightVolume: Float) {
        mMediaHandler?.post {
            _exoPlayer?.volume = (leftVolume + rightVolume) / 2
        }
    }

    override fun setSpeed(speed: Float) {
        mMediaHandler?.post {
            val playbackParams = PlaybackParameters(speed, 1.0F)
            _exoPlayer?.playbackParameters = playbackParams
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        Log.i(TAG, "onTimelineChanged")
    }

    override fun onIsLoadingChanged(isLoading: Boolean) {
        Log.i(TAG, "onIsLoadingChanged")
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        Log.i(TAG, "onPlayWhenReadyChanged: $playWhenReady, reason: $reason, " +
                "playbackState=${_exoPlayer?.playbackState}")
        if (playWhenReady && _exoPlayer?.playbackState == Player.STATE_READY) {
            handler?.post {
                Log.i(TAG,"onPlayWhenReadyChanged_ready")
                jzvd.onStatePlaying()
            }
        }
        Log.i(TAG,"onPlayWhenReadyChanged")
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        Log.i(TAG, "onPlaybackStateChanged: $playbackState")
        handler?.post {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    jzvd.onStatePreparingPlaying()
                    callback?.let(handler::post)
                }

                Player.STATE_READY -> {

                    runOnPlayerThread {
                        Log.i(TAG, "STATE_READY, playWhenReady=${_exoPlayer?.playWhenReady}")
                        if (_exoPlayer?.playWhenReady == true){
                            jzvd.onStatePlaying()
                        }
                    }
                }

                Player.STATE_ENDED -> {
                    jzvd.onCompletion()
                }

                else -> {
                    Log.i(TAG, "onPlaybackStateChanged: $playbackState")
                }
            }
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        Log.e(TAG, "onPlayerError: $error")
        // ---- 诊断 V4：不再只留 1000/1000，记真实错误码与 cause 链 ----
        diagLog("[V4] onPlayerError errorCode=${error.errorCode} errorCodeName=${error.errorCodeName}")
        var diagCause: Throwable? = error.cause
        var diagDepth = 0
        while (diagCause != null && diagDepth < 10) {
            diagLog("[V4] cause[$diagDepth] ${diagCause.javaClass.simpleName}: ${diagCause.message?.take(300)}")
            if (diagCause is HttpDataSource.InvalidResponseCodeException) {
                diagLog("[V4] InvalidResponseCode responseCode=${diagCause.responseCode} responseMessage=${diagCause.responseMessage} dataSpecHost=${diagCause.dataSpec?.uri?.host}")
            }
            diagCause = diagCause.cause
            diagDepth++
        }
        handler?.post { jzvd.onError(1000, 1000) }
    }

//    override fun onPositionDiscontinuity(
//        oldPosition: Player.PositionInfo,
//        newPosition: Player.PositionInfo,
//        reason: Int,
//    ) {
//        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
//            handler?.post { jzvd.onSeekComplete() }
//        }
//    }
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            handler?.post {
                jzvd.onSeekComplete()
                runOnPlayerThread {
                    if (_exoPlayer?.playWhenReady==true) {
                        jzvd.onStatePlaying()
                    }
                }

            }
        }
    }

    override fun setSurface(surface: Surface?) {
        Log.e(TAG, "setSurface: $surface")
        _exoPlayer?.setVideoSurface(surface)
    }

//    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
//        if (SAVED_SURFACE == null) {
//            SAVED_SURFACE = surface
//            prepare()
//        } else {
//            jzvd.textureView.setSurfaceTexture(SAVED_SURFACE)
//        }
//    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (SAVED_SURFACE == null) {
            SAVED_SURFACE = surface
            Log.d(TAG, "onSurfaceTextureAvailable: $SAVED_SURFACE $width $height")
            if (_exoPlayer == null) {
                prepare()
            } else {
                _exoPlayer?.setVideoSurface(Surface(surface))
            }
        } else {
            jzvd.textureView.setSurfaceTexture(SAVED_SURFACE)
        }
    }


    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = false

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit


//    private inner class OnBufferingUpdate : Runnable {
//        override fun run() {
//            _exoPlayer?.bufferedPercentage?.let { per ->
//                handler.post {
//                    jzvd.setBufferProgress(per)
//                }
//                if (per < 100) {
//                    handler.postDelayed(this, 300)
//                } else {
//                    handler.removeCallbacks(this)
//                }
//                return
//            }
//            handler.removeCallbacks(this)
//        }
//    }
}


class SystemMediaKernel(jzvd: Jzvd) : JZMediaSystem(jzvd), HMediaKernel {
    // #issue-26: 有的手機長按快進會報錯，合理懷疑是不是因爲沒有加 post
    // #issue-28: 有的平板长按快进也会报错，结果是 IllegalArgumentException，很奇怪，两次 try-catch 处理试试。
    val videoRealWidth: Int get() = mediaPlayer?.videoWidth ?: 0
    val videoRealHeight: Int get() = mediaPlayer?.videoHeight ?: 0
    override fun setSpeed(speed: Float) {
        mMediaHandler?.post {
            try {
                val pp = mediaPlayer.playbackParams
                pp.speed = speed.absoluteValue
                mediaPlayer.playbackParams = pp
            } catch (_: IllegalArgumentException) {
                try {
                    val opp = PlaybackParams().setSpeed(speed.absoluteValue)
                    mediaPlayer.playbackParams = opp
                } catch (e: IllegalArgumentException) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onVideoSizeChanged(mediaPlayer: MediaPlayer?, width: Int, height: Int) {
        super.onVideoSizeChanged(mediaPlayer, width, height)
        val ratio = width.toFloat() / height // > 1 橫屏， < 1 竖屏
        if (ratio > 1) {
            Jzvd.FULLSCREEN_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            Jzvd.FULLSCREEN_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    // #issue-139: 部分机型暂停报错，没判空导致的
    override fun pause() {
        mMediaHandler?.post {
            mediaPlayer?.pause()
        }
    }

    // #issue-crashlytics-c8636c4bb0b8516675cbeb9e8776bf0b:
    // 有些机器到这里可能会报空指针异常，所以加了个判断，但是不知道为什么会报空指针异常
    override fun isPlaying(): Boolean {
        return mediaPlayer?.isPlaying == true
    }

    fun setLooping(looping: Boolean) {
        mMediaHandler?.post {
            mediaPlayer?.isLooping = looping
        }
    }
}

class MpvMediaKernel(jzvd: Jzvd) : JZMediaInterface(jzvd) {
    companion object {
        const val TAG = "MpvMediaKernel"
    }
    val videoRealWidth: Int
        get() = MPVLib.getPropertyInt("video-params/w") ?: 0

    val videoRealHeight: Int
        get() = MPVLib.getPropertyInt("video-params/h") ?: 0

    private var mpvTimePos: Double = 0.0
    private var mpvCacheDuration: Double = 0.0
    private var mpvDuration: Double = 0.0
    private var currentPfd: ParcelFileDescriptor? = null
    private var detachFd: Int? = null
    private var pfdFilePath = false
    private var mpvLoadStartMs = 0L
    private val certFile = getCert(jzvd.context)
    val defaultSpeed = Preferences.playerSpeed
    private val mpvOptions: Map<String, String>
        get() = buildMap {
            // 视频输出驱动：GPU 渲染（支持 GLSL 滤镜/Anime4K/插帧）
            if (Preferences.enableGPUNextRenderer){
                put("vo", "gpu-next")
            } else {
                put("vo", "gpu")
            }

            // 预设模式：fast（性能优先，画质略低；可改为 gpu-hq 追求高画质）
            put("profile", when (Preferences.mpvProfile) {
                "gpu-hq" -> "gpu-hq"
                "fast" -> "fast"
                else -> "default"
            })

            // 解码方式：选择合适的解码器（mediacodec/mediacodec-copy）
            put("hwdec", when (Preferences.mpvHwdec) {
                "Auto" -> "auto"
                "HW" -> "mediacodec-copy"
                "HW+" -> "mediacodec"
                "Vulkan" -> "vulkan-copy"
                "vulkan+" -> "vulkan"
                "SW" -> "no"
                else -> "auto"
            })

            // 日志等级：fatal → error → warn → info → status → verbose → debug → trace
            put("msg-level", "all=" + if (BuildConfig.DEBUG) "debug" else "warn")

            // 插帧设置
            if (Preferences.mpvInterpolation) {
                put("interpolation", "yes")      // 启用插帧
                put("tscale", "oversample")      // 时间插值算法
                put("video-sync", "display-resample") // 同步刷新率
            }

            // 缓存与性能
            put("cache", "yes")  // 启用解复用缓存
            put("cache-secs", Preferences.mpvCacheSecs.toString())   // 预缓存秒数
            put("vd-lavc-threads", Runtime.getRuntime().availableProcessors().toString())  // 视频解码线程数：设为 CPU 核心数，提升多核利用率

            if (Preferences.mpvFramedrop) {
                put("framedrop", "vo")  // GPU 繁忙时允许丢帧（保持音画同步，避免卡顿）
            } else {
                put("framedrop", "no")
            }

            put("deband", if (Preferences.mpvDeband) "yes" else "no")  // 去色带

            put("cache-pause", "no")  // 缓存时是否暂停播放

            put("network-timeout", Preferences.mpvNetworkTimeout.toString())  // 请求超时

            put("tls-ca-file", certFile)  // 为播放器指定根证书文件，解决 tls-verify 如果为yes播放失败的问题

            put("tls-verify", if (Preferences.mpvTlsVerify) "no" else "yes")  // 是否证书验证 yes、no

            // 单独为MPV播放器配置代理，因为它不走ProxySelector，也不支持socks代理，沟槽的非原生实现
            val proxyIp = Preferences.proxyIp
            val proxyPort = Preferences.proxyPort
            if (proxyIp.isNotBlank() && proxyPort != -1) {
                val proxyUrl = "${proxyIp}:${proxyPort}"
                if (Preferences.proxyType == HProxySelector.TYPE_HTTP) {
                    put("http-proxy", "http://$proxyUrl")
                }
            }
            put("user-agent", USER_AGENT)
        }


    private val observedProperties = listOf(
        "time-pos" to MPVLib.mpvFormat.MPV_FORMAT_DOUBLE, // 当前播放时间（秒，带小数）
        "duration" to MPVLib.mpvFormat.MPV_FORMAT_DOUBLE, // 视频总时长（秒）
        "pause" to MPVLib.mpvFormat.MPV_FORMAT_FLAG,      // 是否暂停（true/false）
        "playback-active" to MPVLib.mpvFormat.MPV_FORMAT_FLAG, // 播放是否处于活动状态
        "video-params/w" to MPVLib.mpvFormat.MPV_FORMAT_INT64, // 视频宽度（像素）
        "video-params/h" to MPVLib.mpvFormat.MPV_FORMAT_INT64, // 视频高度（像素）
        "demuxer-cache-duration" to MPVLib.mpvFormat.MPV_FORMAT_DOUBLE
    )
    // ---- 诊断日志（diag-log）：与 Exo 内核同格式 ----
    private fun diagLog(msg: String) {
        Log.i("HY-DIAG", msg)
        EchTrace.event(msg)
    }

    fun parseCustomMpvParams(): LinkedHashMap<String, String> {
        val rawInput = Preferences.customMpvParams
        val map = linkedMapOf<String, String>()

        rawInput.split(";").forEach { entry ->
            val trimmedEntry = entry.trim()
            if (trimmedEntry.isEmpty() || trimmedEntry.startsWith("#")) return@forEach

            val parts = trimmedEntry.split(",", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    map[key] = value
                }
            }
        }
        return map
    }

    fun init() {
        mpvOptions.forEach { (key, value) ->
            Log.i("MPV_config","$key,$value")
            MPVLib.setOptionString(key, value)
        }
        diagLog("[M0] mpv net opts ua=${mpvOptions["user-agent"]} timeout=${mpvOptions["network-timeout"]} tlsVerify=${mpvOptions["tls-verify"]} proxy=${mpvOptions["http-proxy"] ?: "none"}")
        try {
            val customParams = parseCustomMpvParams()
            customParams.forEach { (key, value) ->
                Log.i("MPV_config","custom: $key,$value")
                MPVLib.setOptionString(key, value)
            }
        } catch (e: Exception){
            GlobalToasts.show(e.message.orEmpty(), level = GlobalToasts.ToastLevel.ERROR)
        }

        observedProperties.forEach { (name, type) ->
            MPVLib.observeProperty(name, type)
        }
        MPVLib.addObserver(mpvEventObserver)
    }

    fun prepareUri(context: Context, uri: Uri): String? {
        return when (uri.scheme) {
            "http", "https" -> {
                uri.toString()
            }
            "file", "content" -> {
                try {
                    currentPfd = context.contentResolver.openFileDescriptor(uri, "r")
                    detachFd = currentPfd?.detachFd()
                    Log.d(TAG, "Detached FD = $detachFd")
                    pfdFilePath = true
                    if (detachFd != null) {
                        "fd://$detachFd"
                    } else null
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            else -> {
                null
            }
        }
    }

    fun releaseCurrentPfd(from: String? = null) {
        if (!pfdFilePath) return
        try {
            currentPfd?.let {
                Log.i(TAG, "Closing currentPfd: $it")
                it.close()
            }

            detachFd?.let { fd ->
                Log.i(TAG, "Closing detachFd: $fd")
                try {
                    ParcelFileDescriptor.adoptFd(fd).close()
                } catch (e: Exception) {
                    Log.w(TAG, "detachFd $fd already closed or invalid", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing PFD", e)
        } finally {
            currentPfd = null
            detachFd = null
            handler?.postDelayed({
                Log.i(TAG, "${from ?: "releaseCurrentPfd"} completed. PFD cleared.")
            }, 200)
        }
    }


    override fun prepare() {
        init()
        handler = Handler(Looper.getMainLooper())

        val url = jzvd.jzDataSource.currentUrl.toString()
        if (url.isEmpty()) {
            Log.e(TAG, "视频链接为空")
            diagLog("[M0] prepare aborted, empty url")
            return
        }

        Log.e(TAG, "URL Link = $url")
        val urlHost = try { Uri.parse(url).host } catch (e: Exception) { "?" }
        val urlSuffix = url.substringAfterLast('/').take(20)
        diagLog("[M0] prepare host=$urlHost suffix=$urlSuffix")
        MPVLib.setOptionString("force-window", "yes")

        val uri = url.toUri()
        val path = prepareUri(jzvd.context, uri)
        if (path != null) {
            mpvLoadStartMs = System.currentTimeMillis()
            diagLog("[M0] loadfile ${if (path.startsWith("fd://")) "fd" else "host=$urlHost"}")
            MPVLib.command(arrayOf("loadfile", path))
            val surfaceTexture = jzvd.textureView?.surfaceTexture
            surfaceTexture?.let { MPVLib.attachSurface(Surface(it)) }
        } else {
            diagLog("[M0] loadfile skipped, prepareUri null scheme=${uri.scheme}")
        }
    }

    override fun start() {
        diagLog("[M0] start pause=false")
        MPVLib.setPropertyBoolean("pause", false)
    }

    fun setLooping(looping: Boolean) {
        MPVLib.setPropertyString("loop-file", if (looping) "inf" else "no")
    }

    override fun pause() {
        MPVLib.setPropertyBoolean("pause", true)
    }

    override fun isPlaying(): Boolean {
        val pause = MPVLib.getPropertyBoolean("pause")
        return !pause
    }

    override fun seekTo(time: Long) {
        Log.i("seekTo","${ (time / 1000.0)}")
        MPVLib.command(arrayOf("seek", (time / 1000.0).toString(), "absolute", "exact"))
    }

    override fun release() {
        clearSuperResolution()
        MPVLib.setPropertyBoolean("pause", true)
        MPVLib.command(arrayOf("loadfile", "", "replace"))
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setOptionString("force-window", "no")
        MPVLib.detachSurface()
        MPVLib.removeObserver(mpvEventObserver)
        handler?.postDelayed({
            releaseCurrentPfd("jzvd-release")
        }, 200)
        SAVED_SURFACE = null
    }

    override fun getCurrentPosition(): Long {
        val timePosSeconds = MPVLib.getPropertyDouble("time-pos")
        return (timePosSeconds ?: 0.0).toLong() * 1000
    }

    override fun getDuration(): Long {
        val durationSeconds = MPVLib.getPropertyDouble("duration")
        return (durationSeconds ?: 0.0).toLong() * 1000
    }

    override fun setVolume(leftVolume: Float, rightVolume: Float) {
        val volume = (leftVolume + rightVolume) / 2 * 100
        MPVLib.setPropertyDouble("volume", volume.toDouble())
    }

    override fun setSpeed(speed: Float) {
        MPVLib.setPropertyDouble("speed", speed.toDouble())
    }

    override fun setSurface(surface: Surface?) {
        MPVLib.attachSurface(surface)
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        if (SAVED_SURFACE == null) {
            SAVED_SURFACE = surfaceTexture
            prepare()
        } else {
            jzvd.textureView.setSurfaceTexture(SAVED_SURFACE)
        }
    }

    fun updateSurFaceSize(width: Int, height: Int) {
        Log.d(TAG, "updateSurFaceSize ${width}x${height}")
        MPVLib.setPropertyString("android-surface-size", "${width}x${height}")
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceTextureSizeChanged ${width}x${height}")
        updateSurFaceSize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean = false

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}


    private fun clearSuperResolution() {
        MPVLib.command(arrayOf("change-list", "glsl-shaders", "clr", ""))
    }
    fun setSuperResolution(index: Int) {
        if (index != 0) {
            val cmd = arrayOf("change-list", "glsl-shaders", "set", AnimeShaders.getShader(jzvd.context, index))
            MPVLib.command(cmd)
        } else {
            clearSuperResolution()
        }
    }

    private val mpvEventObserver = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) {
//            Log.d(TAG, "eventProperty: $property")
        }

        override fun eventProperty(property: String, value: Long) {

        }

        override fun eventProperty(property: String, value: Boolean) {
//            Log.d(TAG, "eventProperty: $property $value")
        }

        override fun eventProperty(property: String, value: String) {
//            Log.d(TAG, "eventProperty: $property $value")
        }

        override fun eventProperty(property: String, value: Double) {
//            Log.d(TAG, "eventProperty: $property $value")
            when (property) {
                "time-pos" -> mpvTimePos = value
                "demuxer-cache-duration" -> mpvCacheDuration = value
                "duration" -> mpvDuration = value
            }
            if (mpvDuration > 0) {
                val bufferedTime = mpvTimePos + mpvCacheDuration
                val safeBufferedTime = bufferedTime.coerceAtMost(mpvDuration)
                val bufferPercent = ((safeBufferedTime / mpvDuration) * 100).toInt()
                handler.post {
                    jzvd.setBufferProgress(bufferPercent)
                }
            }

        }

        override fun event(eventId: Int) {
            handler.post {
                when (eventId) {
                    MPVLib.mpvEventId.MPV_EVENT_START_FILE -> {
                        // 文件开始加载
                        diagLog("[M4] START_FILE")
                        mpvTimePos = 0.0
                        mpvCacheDuration = 0.0
                        mpvDuration = 0.0
                        jzvd.onStatePreparing()
                    }
                    MPVLib.mpvEventId.MPV_EVENT_FILE_LOADED -> {
                        // 文件加载成功
                        diagLog("[M4] FILE_LOADED")
                        jzvd.onPrepared()
                        MPVLib.setPropertyDouble("speed", defaultSpeed.toDouble())
                    }
                    MPVLib.mpvEventId.MPV_EVENT_PLAYBACK_RESTART -> {
                        // 播放重新开始
                        diagLog("[M4] PLAYBACK_RESTART")
                        jzvd.onStatePlaying()
                    }
                    MPVLib.mpvEventId.MPV_EVENT_END_FILE -> {
                        // 播放结束（正常播完与加载失败都走这里，elapsed 区分两者）
                        val elapsed = if (mpvLoadStartMs > 0) System.currentTimeMillis() - mpvLoadStartMs else -1
                        diagLog("[M4] END_FILE elapsedMs=$elapsed")
                        releaseCurrentPfd("MPV_EVENT_END_FILE")
                        jzvd.onCompletion()
                    }
                    MPVLib.mpvEventId.MPV_EVENT_SHUTDOWN -> {
                        diagLog("[M4] SHUTDOWN")
                        Log.e(TAG, "event: $eventId")
                    }
                }
            }
        }
    }
}