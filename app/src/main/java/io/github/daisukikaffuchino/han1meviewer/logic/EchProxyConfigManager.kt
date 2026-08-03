package io.github.daisukikaffuchino.han1meviewer.logic

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ECH 代理配置和缓存管理器
 *
 * 缓存策略（与 CF 密钥轮换同步）：
 * - CF 密钥：1 小时轮换，旧密钥保留 5 小时（双 key 宽限期）
 * - DNS TTL：固定 5 分钟（300s）
 * - ECH 配置缓存：300s（与 DNS TTL 同步，不超过 300s）
 *   理由：CF 密钥 1h 轮换 + 5h 旧密钥保留 = 6h 有效期
 *        但 ECH 配置应与 DNS TTL 同步（300s）以快速响应密钥更新
 *        现代 ECH 双 key 宽限期降低握手失败风险
 *
 * 优化策略：
 * 1. 从公共域名获取共享 ECH 公钥，缓存本地（300s）
 * 2. 所有域名连接复用此公钥，避免重复获取
 * 3. 公钥匹配失败时重试一次（使用 DNS 返回的最新公钥）
 * 4. 再次失败则降级为普通 TLS 连接（不使用 ECH）
 */
class EchProxyConfigManager(private val context: Context) {

    private val tag = "EchProxyConfig"
    private val configDir = context.filesDir
    private val cacheFile = File(configDir, "ech_proxy_config.yaml")
    private val cacheLockFile = File(configDir, ".ech_cache_lock")
    
    companion object {
        // ECH 配置缓存 TTL：300 秒（与 DNS TTL 同步）
        const val CACHE_TTL_SECONDS = 300L
        
        // CF 密钥轮换周期：1 小时
        const val CF_KEY_ROTATION_HOURS = 1
        
        // CF 旧密钥保留期：5 小时
        const val CF_OLD_KEY_RETENTION_HOURS = 5
    }
    
    // 公共域名（用于获取共享 ECH 公钥）
    private var publicDomain = ""
    
    // 代理配置 DNS 源域名
    private var configDomain = "ech-config.anglesgirl.eu.org"

    /**
     * 初始化配置管理器
     * @param publicDomain 公共域名（用于获取共享 ECH 公钥）
     * @param configDomain 代理配置源域名
     */
    fun initialize(publicDomain: String = "", configDomain: String = "ech-config.anglesgirl.eu.org") {
        this.publicDomain = publicDomain
        this.configDomain = configDomain
        Log.d(tag, "Initialized with publicDomain=$publicDomain, configDomain=$configDomain")
    }

    /**
     * 获取代理配置文件路径
     */
    fun getConfigPath(): String = cacheFile.absolutePath

    /**
     * 检查配置文件是否存在且有效
     */
    fun isConfigValid(): Boolean {
        return cacheFile.exists() && cacheFile.length() > 100 // 最少有内容
    }

    /**
     * 从 DNS TXT 记录获取配置并缓存
     * 适用于首次启动或强制刷新配置
     */
    suspend fun fetchAndCacheConfig(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            acquireLock()
            
            Log.i(tag, "Fetching config from DNS: $configDomain")
            
            // 此处应调用 Go 库的 FetchConfigFromDNS 函数
            // 由于跨语言调用的复杂性，这里使用占位符
            // 实际应用中应通过 JNI 或 gomobile 调用
            val configYaml = fetchConfigFromDNSViaGo(configDomain)
            
            if (configYaml.isNotEmpty()) {
                // 原子性写入，先写临时文件再重命名
                val tempFile = File(configDir, "ech_proxy_config.yaml.tmp")
                tempFile.writeText(configYaml)
                tempFile.renameTo(cacheFile)
                
                Log.i(tag, "Config cached successfully (${cacheFile.length()} bytes)")
                true
            } else {
                Log.e(tag, "Failed to fetch config from DNS")
                false
            }
        } catch (e: Exception) {
            Log.e(tag, "Error fetching config", e)
            false
        } finally {
            releaseLock()
        }
    }

    /**
     * 获取共享 ECH 公钥缓存文件路径
     * ECH 代理会在此路径缓存共享公钥
     */
    fun getSharedEchKeyPath(): String = File(configDir, "ech_public_key.pem").absolutePath

    /**
     * 检查共享 ECH 公钥是否已缓存
     */
    fun hasSharedEchKey(): Boolean {
        return File(getSharedEchKeyPath()).exists()
    }

    /**
     * 清除 ECH 公钥缓存（强制重新获取）
     */
    fun clearEchKeyCache() {
        File(getSharedEchKeyPath()).delete()
        Log.i(tag, "ECH key cache cleared")
    }

    /**
     * 获取缓存统计信息
     */
    suspend fun getCacheStats(): Map<String, String> = withContext(Dispatchers.IO) {
        return@withContext mapOf(
            "config_file_size" to "${cacheFile.length()} bytes",
            "config_exists" to cacheFile.exists().toString(),
            "ech_key_cached" to hasSharedEchKey().toString(),
            "config_path" to cacheFile.absolutePath,
            "public_domain" to publicDomain
        )
    }

    /**
     * 清除所有缓存
     */
    fun clearAllCache() {
        try {
            cacheFile.delete()
            File(getSharedEchKeyPath()).delete()
            Log.i(tag, "All caches cleared")
        } catch (e: Exception) {
            Log.e(tag, "Error clearing cache", e)
        }
    }

    // --- 私有方法 ---

    /**
     * 获取文件锁（防止并发写入）
     */
    private fun acquireLock() {
        var retries = 10
        while (retries > 0) {
            if (cacheLockFile.createNewFile()) {
                return
            }
            retries--
            Thread.sleep(100)
        }
        throw RuntimeException("Failed to acquire cache lock after 10 attempts")
    }

    /**
     * 释放文件锁
     */
    private fun releaseLock() {
        cacheLockFile.delete()
    }

    /**
     * 通过 Go 库从 DNS 获取配置
     * 这里是占位符，实际应通过 gomobile JNI 调用
     */
    private fun fetchConfigFromDNSViaGo(domain: String): String {
        // 实际应该调用：
        // return GoDnsConfigFetcher.fetchFromDNS(domain)
        
        // 目前返回空字符串，表示需要 Go 库支持
        Log.w(tag, "Go library integration needed for DNS config fetch")
        return ""
    }
}
