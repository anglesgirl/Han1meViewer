package io.github.daisukikaffuchino.han1meviewer.logic.ech

import android.content.Context
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import echproxy.Echproxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ServerSocket

/** 统一管理本地 Go ECH 代理。代理未就绪时不允许目标站点明文直连。 */
object EchProxyManager {
    const val DEFAULT_DOH = "https://tgxjjdszvu.cloudflare-gateway.com/dns-query"

    @Volatile
    var port: Int = -1
        private set

    val isRunning: Boolean get() = port > 0

    private val targetHost: String
        get() = java.net.URI(SettingsRepository.baseUrl).host ?: "hanime1.me"

    suspend fun ensureStarted(context: Context): Boolean {
        if (isRunning) return true
        return start(context)
    }

    suspend fun start(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext true
        val chosen = ServerSocket(0).use { it.localPort }
        runCatching {
            Echproxy.start(
                "127.0.0.1:$chosen",
                targetHost,
                "",
                DEFAULT_DOH,
                "",
                File(context.filesDir, "ech-public-config.json").absolutePath,
                false,
            )
            port = chosen
            HProxySelector.rebuildNetwork()
        }.isSuccess
    }

    fun proxyUrl(httpsUrl: String): String? {
        val p = port
        if (p <= 0) return null
        val source = java.net.URI(httpsUrl)
        val path = source.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val query = source.rawQuery?.let { "?$it" }.orEmpty()
        return "http://127.0.0.1:$p$path$query"
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        runCatching { Echproxy.stop() }
        port = -1
        HProxySelector.rebuildNetwork()
    }
}