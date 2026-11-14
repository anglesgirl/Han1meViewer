package com.yenaly.han1meviewer.ui.player.surface

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.yenaly.han1meviewer.ui.player.HanimePlayerController

/**
 * 可移动的视频渲染 Surface。
 *
 * 返回一个可组合函数（movableContent），在「折叠头部小窗 / 全屏覆盖层 / 平板分栏左栏」
 * 之间移动时复用同一个 [TextureView] 实例，播放不中断——取代旧版 JZVD
 * 把 View 搬到 decorView 的方案。
 *
 * ### SurfaceTexture 保留技巧（自 JZVD 迁移）
 * - [TextureView.SurfaceTextureListener.onSurfaceTextureDestroyed] 返回 `false`，
 *   纹理由本层持有而不释放；
 * - 跨容器移动时系统会回调 `onSurfaceTextureAvailable` 提供新纹理，
 *   此时把旧纹理 `setSurfaceTexture` 塞回去，渲染管线无缝衔接。
 *
 * @param controller 播放器控制器，负责把纹理挂载到内核并同步尺寸
 */
@Composable
fun rememberMovablePlayerSurface(
    controller: HanimePlayerController,
): @Composable (Modifier) -> Unit {
    return remember(controller) {
        var savedSurfaceTexture: SurfaceTexture? = null
        movableContentOf { modifier: Modifier ->
            AndroidView(
                factory = { context ->
                    TextureView(context).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {

                            override fun onSurfaceTextureAvailable(
                                surfaceTexture: SurfaceTexture,
                                width: Int,
                                height: Int,
                            ) {
                                val saved = savedSurfaceTexture
                                if (saved == null) {
                                    savedSurfaceTexture = surfaceTexture
                                    controller.attachSurfaceTexture(surfaceTexture)
                                } else if (saved !== surfaceTexture) {
                                    // 跨布局移动后 TextureView 提供了新纹理，
                                    // 换回旧纹理避免画面重建、播放中断。
                                    setSurfaceTexture(saved)
                                    // 新纹理没有挂到播放内核，应用持有它时需显式释放。
                                    surfaceTexture.release()
                                }
                                controller.onSurfaceSizeChanged(width, height)
                            }

                            override fun onSurfaceTextureSizeChanged(
                                surfaceTexture: SurfaceTexture,
                                width: Int,
                                height: Int,
                            ) {
                                controller.onSurfaceSizeChanged(width, height)
                            }

                            override fun onSurfaceTextureDestroyed(
                                surfaceTexture: SurfaceTexture,
                            ): Boolean {
                                // 返回 false：纹理由本层持有，不随 View detach 释放，
                                // 重新挂载后可继续使用。
                                return false
                            }

                            override fun onSurfaceTextureUpdated(
                                surfaceTexture: SurfaceTexture,
                            ) = Unit
                        }
                    }
                },
                modifier = modifier,
            )
        }
    }
}
