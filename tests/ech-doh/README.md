# Restore-bootstrap JVM 回归测试

这是独立 JVM 回归测试目录，只编译、不修改生产源码。测试直接使用仓库中的生产 `DohConfig.kt`、`EchDoh.kt`、`EchHosts.kt` 和 `HDns.kt`；偏好项与 Android API 仅由此目录下的 stub 提供。DoH HTTPS(65) JSON 响应由本地 OkHttp interceptor 按请求 hostname 构造；网关 pin 探测用本地 `SocketFactory` 在 connect 前抛错，不会连接互联网。

覆盖：

- 生产 DoH 预设网关 URL 与 `172.64.229.69` bootstrap 配置；对真实 OkHttp DNS/connect 路径做离线 pin 断言，HTTPS 记录查询只发往固定 Gateway endpoint。
- 核心 Hanime host 通过固定 Gateway/bootstrap 读取 `cloudflare-ech.com` 官方共享 ECH 活值，缓存遵守 HTTPS 记录 TTL；ECH_REJECTED 后失效该来源并可重新拉取。非核心 host 仍只接受其自身可达的 type-65 Answer，缓存按来源隔离；覆盖 CNAME owner/type、ECHConfigList framing、缺失记录、TTL=0 与按 host invalidation。
- 核心站点域维持静态受保护策略；普通媒体 CDN 可以查询自身 ECH，但没有有效配置时不被误标成必需 ECH。
- 核心与非核心域名均走配置 resolver；解析失败时 `EchDns` 抛 `UnknownHostException`，不调用提供的 system-DNS fallback。
- 两个媒体 CDN 域名的自定义 IP 绕过 resolver。
- `HDns` 通过生产配置的 DoH URL/bootstrap resolver 路径工作，不采用公共 resolver。

在测试目录放入仓库 `tests/ech-doh` 后，脚本默认从目录位置推导仓库根目录；也可通过 `REPO_ROOT` 覆盖。运行：

```bash
./run-tests.sh
```

若依赖 jar 位于非默认位置，可设 `ECH_DOH_REFERENCE_DIR`（其下应有 `lib/okhttp.jar`、`lib/okio.jar`、`lib/doh.jar`、`lib/json.jar`），Kotlin Gradle compiler 可用 `KOTLIN_GRADLE_LIB` 指定。脚本依赖缺失时会尝试下载固定版本依赖；所有测试 HTTP 响应都在本地生成。

期望末行：`PASS: 53 assertions; ...`。这是隔离 JVM 回归，不是 Android instrumentation 或真实网络连通性测试。
