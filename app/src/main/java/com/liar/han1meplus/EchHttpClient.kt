package com.liar.han1meplus

import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import org.json.JSONObject
import java.io.File

/**
 * 可拆卸的 Han1mePlus BoringSSL+curl ECH bridge。
 * JNI 类名必须保持 com.liar.han1meplus.EchHttpClient，与已编译 .so 一致。
 */
@Keep
object EchHttpClient {
    @Volatile
    var isLoaded = false
        private set

    fun init(context: Context) {
        if (isLoaded) return
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" }
            ?: error("当前仅提供 arm64-v8a ECH 库")
        val target = File(context.filesDir, "han1me_ech_$abi.so")
        context.assets.open("han1meplus/$abi/libhan1me_ech.so").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        System.load(target.absolutePath)
        isLoaded = true
    }

    external fun request(
        method: String,
        url: String,
        headers: Array<String>,
        body: ByteArray?,
        dohUrl: String,
        dohResolve: String,
    ): String

    fun get(context: Context, url: String): JSONObject {
        init(context)
        return JSONObject(request(
            "GET", url, emptyArray(), null,
            "https://82sew1c85i.cloudflare-gateway.com/dns-query",
            "82sew1c85i.cloudflare-gateway.com:443:162.159.36.20,162.159.36.5",
        ))
    }
}
