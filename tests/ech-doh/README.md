# Restore-bootstrap JVM 回归测试

这是独立 JVM 回归测试目录，只编译、不修改生产源码。测试直接使用仓库中的生产 `DohConfig.kt`、`EchDoh.kt`、`EchHosts.kt` 和 `HDns.kt`；偏好项与 Android API 仅由此目录下的 stub 提供。ECH wire 响应来自本地捕获 fixture。网关 pin 探测用本地 `SocketFactory` 在 connect 前抛错，不会连接互联网；其余 HTTP 均由本地 OkHttp interceptor 返回 fixture。

覆盖：

- 生产 DoH 预设网关 URL 与 `172.64.229.69` bootstrap 配置；对真实 OkHttp DNS/connect 路径做离线 pin 断言，ECH HTTP 请求仅发往该网关域名。
- 捕获 wire 应答可解析；ECH 配置缓存约 4 小时、跨 hostname 命中，且显式 `invalidateEch` 后重新查询。
- 核心与非核心域名均走配置 resolver；解析失败时 `EchDns` 抛 `UnknownHostException`，不调用提供的 system-DNS fallback。
- 两个媒体 CDN 域名的自定义 IP 绕过 resolver。
- `HDns` 通过生产配置的 DoH URL/bootstrap resolver 路径工作，不采用公共 resolver。

在测试目录放入仓库 `tests/ech-doh` 后，脚本默认从目录位置推导仓库根目录；也可通过 `REPO_ROOT` 覆盖。运行：

```bash
./run-tests.sh
```

若依赖 jar 位于非默认位置，可设 `ECH_DOH_REFERENCE_DIR`（其下应有 `lib/okhttp.jar`、`lib/okio.jar`、`lib/doh.jar`），Kotlin Gradle compiler 可用 `KOTLIN_GRADLE_LIB` 指定。脚本依赖缺失时会尝试下载固定版本依赖；本次验证使用了本地已有的依赖 bundle，没有联网。

期望末行：`PASS: 27 assertions; ...`。这是隔离 JVM 回归，不是 Android instrumentation 或真实网路连通性测试。
