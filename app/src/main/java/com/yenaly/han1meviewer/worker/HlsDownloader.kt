package com.yenaly.han1meviewer.worker

import android.util.Log
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.network.ServiceCreator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HLS (m3u8) 下载器：解析播放列表 → 分片下载 → 合并为单个 .ts 文件。
 *
 * 背景：hanime1 是 mp4 直链（下载器现有 Range 断点续传即可），
 * 但 javchu 的视频源是 m3u8 分片（如 t33.cdn2020.com），原下载器会把
 * 播放列表文本直接存成 .mp4 导致无法播放。此文件补齐 m3u8 下载。
 *
 * 断点续传：每个分片解密后单独落盘为 part_XXXXX.ts，中断重试时
 * 已存在的 part 文件直接跳过；全部完成后按序合并为单个 .ts 并清理 part。
 *
 * 支持：
 * - master playlist（#EXT-X-STREAM-INF）自动选最高码率
 * - 相对路径分片（基于播放列表 URL 解析）
 * - AES-128 加密分片（#EXT-X-KEY，METHOD=AES-128）
 * - Referer 防盗链（用当前站点 baseUrl）
 */
object HlsDownloader {

    private const val TAG = "HlsDownloader"
    private const val BUFFER_SIZE = 64 * 1024

    /**
     * 下载 m3u8 分片到 partDir（可续传），全部完成后合并写入 output 并返回真实字节数。
     * onProgress: (doneSegments, totalSegments, downloadedBytes, estimatedTotalBytes)
     */
    suspend fun download(
        playlistUrl: String,
        partDir: File,
        output: java.io.OutputStream,
        onProgress: suspend (done: Int, total: Int, downloadedBytes: Long, estimatedTotalBytes: Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val referer = Preferences.baseUrl.removeSuffix("/")
        // 1. 解析出最终媒体播放列表（处理 master -> variant 跳转）
        val mediaUrl = resolveMediaPlaylist(playlistUrl, referer)
        Log.i(TAG, "media playlist: $mediaUrl")
        // 2. 拉取媒体播放列表
        val media = fetchText(mediaUrl, referer)
        // 3. 解析分片 + 加密信息
        val segments = parseSegments(mediaUrl, media)
        Log.i(TAG, "segments: ${segments.items.size}, encrypted=${segments.keyUri != null}")
        if (segments.items.isEmpty()) throw IOException("m3u8 无分片")

        // 4. 下载 key（若加密）
        val key: ByteArray? = segments.keyUri?.let { keyUri ->
            fetchBytes(keyUri, referer).also {
                if (it.size != 16) throw IOException("AES key 长度异常: ${it.size}")
            }
        }

        // 5. 逐个分片下载（已存在的 part 文件跳过 = 断点续传）
        partDir.mkdirs()
        var downloadedBytes = 0L
        try {
            for (index in segments.items.indices) {
                val partFile = File(partDir, "part_%05d.ts".format(index))
                if (partFile.exists() && partFile.length() > 0) {
                    // 断点续传：跳过已下载分片
                    downloadedBytes += partFile.length()
                    val estimated = if (index + 1 > 0 && downloadedBytes > 0) {
                        downloadedBytes / (index + 1) * segments.items.size
                    } else 0L
                    onProgress(index + 1, segments.items.size, downloadedBytes, estimated)
                    continue
                }
                val seg = segments.items[index]
                val data = fetchBytes(seg.uri, referer)
                val decrypted = if (key != null) {
                    decryptAes128(data, key, segments.ivFor(index))
                } else data
                partFile.writeBytes(decrypted)
                downloadedBytes += decrypted.size
                val estimated = downloadedBytes / (index + 1) * segments.items.size
                onProgress(index + 1, segments.items.size, downloadedBytes, estimated)
            }

            // 6. 全部下载完成：按序合并 part 为单个文件
            var totalBytes = 0L
            for (index in segments.items.indices) {
                val partFile = File(partDir, "part_%05d.ts".format(index))
                if (!partFile.exists()) throw IOException("缺少分片 $index，无法合并")
                partFile.inputStream().use { input ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        totalBytes += n
                    }
                }
            }
            // 清理 part 临时文件
            partDir.deleteRecursively()
            totalBytes
        } finally {
            output.close()
        }
    }

    /** 若为 master playlist，返回最高码率 variant 的播放列表 URL；否则原样返回 */
    private fun resolveMediaPlaylist(url: String, referer: String): String {
        val text = fetchText(url, referer)
        if (!text.contains("#EXT-X-STREAM-INF")) return url
        // 解析 variant：取 BANDWIDTH 最大的
        val variantLines = text.lines()
        var bestBandwidth = -1L
        var bestUri: String? = null
        var i = 0
        while (i < variantLines.size) {
            val line = variantLines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                // 下一行（或再下一行）是 URI
                var j = i + 1
                while (j < variantLines.size && variantLines[j].startsWith("#")) j++
                if (j < variantLines.size && !variantLines[j].startsWith("#")) {
                    val uri = resolveUri(url, variantLines[j].trim())
                    if (bandwidth > bestBandwidth) {
                        bestBandwidth = bandwidth
                        bestUri = uri
                    }
                }
                i = j
            } else i++
        }
        val target = bestUri ?: throw IOException("master playlist 无 variant")
        Log.i(TAG, "master -> variant(BW=$bestBandwidth): $target")
        return resolveMediaPlaylist(target, referer)
    }

    private class Segment(val uri: String)

    private class ParsedPlaylist(
        val items: List<Segment>,
        val keyUri: String?,
        val keyIv: ByteArray?,
    ) {
        fun ivFor(index: Int): ByteArray {
            keyIv?.let { return it }
            // 无 IV 时按规范用分片序号（0 起，16 字节大端）
            return ByteArray(16).also { b ->
                var v = index.toLong()
                for (k in 15 downTo 8) { b[k] = (v and 0xFF).toByte(); v = v shr 8 }
            }
        }
    }

    private fun parseSegments(playlistUrl: String, text: String): ParsedPlaylist {
        val items = mutableListOf<Segment>()
        var keyUri: String? = null
        var keyIv: ByteArray? = null
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            when {
                line.startsWith("#EXT-X-KEY") -> {
                    // #EXT-X-KEY:METHOD=AES-128,URI="...",IV=0x...
                    if (line.contains("METHOD=AES-128", ignoreCase = true)) {
                        Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)?.let {
                            keyUri = resolveUri(playlistUrl, it)
                        }
                        Regex("IV=0x([0-9A-Fa-f]+)").find(line)?.groupValues?.get(1)?.let { hex ->
                            val bytes = hex.chunked(2).mapNotNull { it.toInt(16).toByte() }.toByteArray()
                            if (bytes.size == 16) keyIv = bytes
                        }
                    } else {
                        // 其他 METHOD（如 NONE）视为无加密
                        keyUri = null
                    }
                }
                line.startsWith("#EXTINF") -> {
                    // 下一个非 # 行是分片 URI
                    var j = i + 1
                    while (j < lines.size && (lines[j].trim().startsWith("#") || lines[j].isBlank())) j++
                    if (j < lines.size) {
                        items.add(Segment(resolveUri(playlistUrl, lines[j].trim())))
                        i = j
                    }
                }
                line.startsWith("#EXT-X-MAP") -> {
                    // fMP4 变体（EXT-X-MAP 初始化段）：简化处理，忽略 init segment 会导致花屏。
                    // javchu/hanime 用的都是 TS 分片，这里若出现 MAP 则抛错提示不支持。
                    throw IOException("该 m3u8 使用 fMP4 (EXT-X-MAP)，暂不支持下载")
                }
            }
            i++
        }
        return ParsedPlaylist(items, keyUri, keyIv)
    }

    private fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return cipher.doFinal(data)
        } catch (e: Exception) {
            throw IOException("AES 解密失败: ${e.message}", e)
        }
    }

    private fun resolveUri(base: String, uri: String): String {
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri
        return try {
            URI(base).resolve(uri).toString()
        } catch (e: Exception) {
            val slash = if (base.endsWith("/")) "" else "/"
            base.substringBeforeLast("/") + "/" + uri.removePrefix("./")
        }
    }

    private fun fetchText(url: String, referer: String): String {
        return fetchBytes(url, referer).toString(Charsets.UTF_8)
    }

    private fun fetchBytes(url: String, referer: String): ByteArray {
        val request = Request.Builder().url(url).get()
            .header("Referer", referer)
            .build()
        ServiceCreator.downloadClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            return resp.body?.bytes() ?: throw IOException("empty body for $url")
        }
    }
}
