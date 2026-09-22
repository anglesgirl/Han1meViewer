package com.yenaly.han1meviewer.logic.network.ech

import java.net.InetAddress

/**
 * Cloudflare 边缘 IP 段判定 —— 用于**提前判断某个域名吃不吃得到 ECH**。
 *
 * ## 为什么必须有它（实测教训）
 *
 * ECH 是 Cloudflare 的机制（`cloudflare-ech.com` 就是它的 ECH 前端）。
 * 给**不在 CF** 的域名注入 ECHConfigList，服务器会**静默忽略**这个扩展：
 * 不报错、不返回 retry_configs —— 于是：
 *   · 上层靠捕获 `ECH_REJECTED` 的降级逻辑**永远不触发**
 *   · **每个新连接都白注入一次** ECH 扩展
 *
 * 实测后果：播放页几十张新图，40 秒内刷了几十次
 * `ECH 已注入 host=1497203185.rsc.cdn77.org cfg=71B 核心=false`，
 * 而诊断字段 `echDegraded(明文)` **始终为空**（从未降级）—— 纯白费，还拖慢加载。
 * 首页看着正常只是因为图都在缓存里、压根不发请求。
 *
 * ## 判据
 *
 * 把域名解析结果与 CF 官方公布的边缘段比对，落在段内才注入 ECH。
 * 段表取自官方公开列表（`https://www.cloudflare.com/ips-v4` 与 `/ips-v6`），
 * 实测核对过：
 *   · `hanime1.me` → `104.26.x` / `172.67.x` → **True**（核心域名不会被误伤）
 *   · `javchu.com` → `188.114.x` → **True**（188.114.96.0/20 在表内）
 *   · `1497203185.rsc.cdn77.org` → `195.181.x` / `37.19.x` → **False**
 *   · `vdownload-8.hembed.com` → `216.227.173.26` → **False**
 *
 * ⚠️ 段表是官方公开且稳定的，但**不是长期不变的**。升级时若发现某些 CF 站点
 * 突然不走 ECH 了，第一个要复查的就是这里有没有漏段。
 */
object CloudflareEdge {

    private val V4 = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
        "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
        "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
        "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
    ).map { Cidr(it) }

    private val V6 = listOf(
        "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32",
        "2405:8100::/32", "2a06:98c0::/29", "2c0f:f248::/32",
    ).map { Cidr(it) }

    /** 地址是否落在 Cloudflare 边缘段内。 */
    fun contains(addr: InetAddress): Boolean {
        val bytes = addr.address
        val table = if (bytes.size == 4) V4 else V6
        return table.any { it.contains(bytes) }
    }

    private class Cidr(cidr: String) {

        private val prefix: ByteArray
        private val bits: Int

        init {
            val (ipPart, lenPart) = cidr.split("/")
            prefix = InetAddress.getByName(ipPart).address
            bits = lenPart.toInt()
        }

        fun contains(addr: ByteArray): Boolean {
            if (addr.size != prefix.size) return false
            val fullBytes = bits / 8
            for (i in 0 until fullBytes) {
                if (addr[i] != prefix[i]) return false
            }
            val rem = bits % 8
            if (rem == 0) return true
            val mask = (0xFF shl (8 - rem)) and 0xFF
            return (addr[fullBytes].toInt() and mask) == (prefix[fullBytes].toInt() and mask)
        }
    }
}
