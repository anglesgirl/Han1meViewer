package com.yenaly.han1meviewer.ui.player.kernel

import android.content.Context
import android.view.Surface

// 单播放器：只保留 Media3 ExoPlayer。MPV 内核已移除（2026-11-16，
// 上游分支跟随单播放器定案），不再提供内核切换。

/**
 * 一次播放请求。
 *
 * @param url 视频地址，支持网络 URL 与本地文件 URI。
 * @param title 视频标题（仅供展示）。
 * @param headers 请求 headers（ExoPlayer 内核使用）。
 * @param looping 是否单视频循环。
 * @param startPositionMs 起播位置，内核准备完成后 seek 到该位置。
 * @param speed 起播倍速。
 */
data class MediaRequest(
    val url: String,
    val title: String = "",
    val headers: Map<String, String> = emptyMap(),
    val looping: Boolean = false,
    val startPositionMs: Long = 0L,
    val speed: Float = 1.0f,
)

/**
 * 内核事件回调。
 *
 * 所有回调均已切换到主线程；带默认空实现，实现方按需覆盖关心的事件。
 */
interface KernelListener {

    /** 开始准备（加载媒体）。 */
    fun onPreparing() {}

    /** 准备完成，可以开始播放。 */
    fun onPrepared() {}

    /** 播放中缓冲（显示 loading）。 */
    fun onBuffering() {}

    /** 已经开始播放。 */
    fun onPlaying() {}

    /** 已暂停。 */
    fun onPaused() {}

    /** 播放到结尾。 */
    fun onCompleted() {}

    /** seek 完成。 */
    fun onSeekComplete() {}

    /** 缓冲百分比变化（0..100）。 */
    fun onBufferedUpdate(percent: Int) {}

    /** 视频尺寸变化（像素宽高，已含像素宽高比换算）。 */
    fun onVideoSizeChanged(width: Int, height: Int) {}

    /** 播放出错。 */
    fun onError(error: Throwable? = null) {}
}

/**
 * 播放内核抽象。
 *
 * 一个实例负责一路媒体的完整生命周期：[prepare] → [start]/[pause]/[seekTo] → [release]。
 * 换源（例如切换清晰度）通过释放旧实例、以新的 [MediaRequest] 创建新实例完成，
 * 由上层 [com.yenaly.han1meviewer.ui.player.HanimePlayerController] 编排。
 *
 * ### 线程约定
 * - 所有方法都在主线程调用；
 * - ExoPlayer 实现内部自行切换到播放器线程执行，回调通过 [listener] 切回主线程。
 */
interface PlayerKernel {

    /** 事件回调，内核持有弱引用语义由实现决定，调用方负责置空避免泄漏。 */
    var listener: KernelListener?

    /** 视频真实像素宽，未就绪时为 0。 */
    val videoWidth: Int

    /** 视频真实像素高，未就绪时为 0。 */
    val videoHeight: Int

    /** 当前是否正在播放。 */
    val isPlaying: Boolean

    /** 当前播放位置（ms）。 */
    val currentPositionMs: Long

    /** 总时长（ms），未知时为 0。 */
    val durationMs: Long

    /**
     * 准备并开始加载媒体。
     *
     * 加载完成、可以播放时会回调 [KernelListener.onPrepared]。
     * [MediaRequest.startPositionMs] 由内核在就绪后自行 seek。
     */
    fun prepare(request: MediaRequest)

    /** 开始播放。 */
    fun start()

    /** 暂停播放。 */
    fun pause()

    /** seek 到指定位置（ms）。 */
    fun seekTo(positionMs: Long)

    /** 设置播放倍速。 */
    fun setSpeed(speed: Float)

    /** 设置单视频循环。 */
    fun setLooping(looping: Boolean)

    /**
     * 附加或替换视频输出 [Surface]；传 null 表示分离。
     *
     * 在 [prepare] 之前调用时，内核会缓存 surface，就绪后再挂载。
     */
    fun setSurface(surface: Surface?)

    /** 释放内核资源，幂等。 */
    fun release()
}

/**
 * 内核工厂。
 *
 * 单播放器：固定创建 ExoPlayer 内核。
 */
object PlayerKernelFactory {

    /**
     * 创建 ExoPlayer 播放内核。
     */
    fun create(context: Context): PlayerKernel {
        return ExoPlayerKernel(context.applicationContext)
    }
}
