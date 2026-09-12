# Han1meViewer ECH 改造：当前状态交接

> 给新会话看的。读完这份就不用翻旧对话。

## 一、基线（已定，别再变）

**以 `misaka10032w/Han1meViewer` 的 PR #461 合并提交 `039c9889` 为干净基线，我们自己的改动往上加。**

- 我们的分支：`anglesgirl/Han1meViewer` 的 **`ech-conscrypt`**
- Go 版完整源码在 **`ech-pr456` @ `356fe3f9`**（最后一个能出完整包的 Go 版本），
  **只作「我们自己做了什么的清单」用**，代码本身不再复用（除了与 ECH 无关的那几项，已迁）
- `go-misaka` / `ech-pr456` 分支保留不动（里面有 `ech/echproxy/echproxy.go`，iOS 侧还要用）

## 二、传输层（用户定调，别再改）

| 平台 | 传输层 | 状态 |
|---|---|---|
| Android | **OkHttp + Conscrypt 2.7.0 in-process** | ✅ 已实现（`logic/network/ech/` 5 个文件） |
| iOS | Go 代理（gomobile → xcframework） | 仍是 Go（Conscrypt 是 Java 库，iOS 没运行时） |

Go 本地反代（`EchProxyManager` / `EchInterceptor` / gomobile AAR / `libgojni.so`）在 Android 上
**已整个删除**：外挂线程 + 本地端口的形态会被系统回收/卡死。

⚠️ **Conscrypt 的总开关是 `PolicyTrustManager.getNetworkSecurityPolicy()`**（Conscrypt 用反射取它）。
不写它 = 日志全绿但一个字节 ECH 都不发。**不能删、不能改 private**，且 release 包必须在
`proguard-rules.pro` 里 keep 住这个类（R8 一改名就静默失效）。

## 三、当前分支上「我们的东西」全清单

### Conscrypt ECH（新增，`logic/network/ech/`）
| 文件 | 作用 |
|---|---|
| `EchHosts.kt` | 受保护域名判定，与 `HanimeConstants.HANIME_HOSTNAME` 同源 |
| `EchDoh.kt` | DoH 取真实 IP + ECHConfigList（端点/bootstrap/超时走 `DohConfig`） |
| `ConscryptEch.kt` | provider + **PolicyTrustManager（ECH 总开关）** + 注入 ECHConfigList 的 SSLSocketFactory |
| `EchHttp.kt` | `echTransport()` 扩展 + `loginClient`（`followRedirects(false)`） |
| `HyWebViewHelper.kt` | WebView 子请求接管（`shouldInterceptRequest`），失败返回 502 页，**绝不返回 null** |

接线点：`ServiceCreator`（3 个站点客户端挂 `.echTransport(dns)`，GitHub 那个不动）、
`LoginActivity` / `CloudflareActivity` 的 `shouldInterceptRequest`、`HanimeApplication` 预热。

### 从 Go 版甄别后迁入的自有改动（上游确实没有）
- `DohConfig.kt`：`gateway` 预设（`tgxjjdszvu.cloudflare-gateway.com` + CF 边缘 IP）
- `Preferences.kt`：默认 `useDoH=true`、`dohPreset=gateway`
- `HanimeResolution.kt`：`normalizeCdnHost`（坏 CDN 节点 → `t33`）
- `HMediaKernel.kt`：Exo 与 MPV 两个内核的播放地址都过 `normalizeCdnHost`
- `HCookieJar.kt`：`saveFromResponse` 按 name 合并（修 302 分两次回写丢 cookie）
- `HUpdater.kt`：release 包国内镜像优先（`gh-proxy`）、`DEFAULT_BRANCH = ech-conscrypt`
- `Constants.kt`：GitHub URL 指向本 fork
- `util/EchStats.kt`、`util/LogExporter.kt`（无 ADB 时导日志）
- `.github/scripts/notify-build-result.sh` + CI 通知（成败都回传）
- `ERRORS_LEARNED.md`
- 15 张 PNG 无损重压（与 Go 版逐文件 sha256 一致）
- `applicationId = com.yenaly.han1meviewer.ech`（与官方版共存）+ `resConfigs("en","zh-rCN","ja")`

### 保留上游 Firebase（用户要求：有真实统计数据）
Analytics / Crashlytics / Remote Config / RealtimeDatabase 全部保留。
`app/google-services.json` **不进 git**（`.gitignore` 本来就排除），走 CI secret
`GOOGLE_SERVICES_JSON_BASE64`（已设置，包名 `com.yenaly.han1meviewer.ech`）。

### 不迁
- `Parser.kt` 的 CDN 修正 —— 上游自己有 `avCdnHost`（只对 AV 站生效），结果一致、并存不冲突
- Go 本地反代整套、登录流程改造 —— 见下

## 四、⚠️ 已知缺口：WebView 的登录 POST（必须用户拍板）

`shouldInterceptRequest` **拿不到 POST body**，所以 `HyWebViewHelper.intercept()` 对非 GET
**返回 null 放行** —— 也就是登录表单提交会走 WebView 自己的 TLS 栈，**明文 SNI**。

- Go 时代没有这个问题：整个 WebView 都在本地反代后面，POST 也走代理
- 换成 Conscrypt 后这条缺口冒出来，而它恰恰是**唯一一条含凭据**的流量
- 受保护域名（`hanime1.me` / `hanime1.com` / `hanimeone.me` / `javchu.com`）都被 SNI 阻断，
  所以**在被墙站点上登录会失败**（连接被 RST），不只是"泄露"而已

三条出路（用户曾明确否决过 ②，换 Conscrypt 后它的必要性变了，需重新拍板）：
1. **保持现状**：登录 POST 走 WebView 明文 SNI（被墙站点登录会失败）
2. **JS 拦表单 → 原生代发**（CO3 的做法）：只注入登录页，用「含 `input[type=password]` 的表单」
   作判据、`MutationObserver` 兜异步插入、成功用凭据 cookie 名判定、成功后 `loadUrl` 真实地址
3. **只给登录这一条链路留窄通道**（登录时临时起本地反代，用完即停）

## 五、验证节奏

- CI：`gh run watch <id> --exit-status` 后台等；核对 `headSha` 与本地 `git rev-parse HEAD` 一致
- 交付校验（CI 里已自动跑）：APK 内**无** `libgojni.so`、**有** `libconscrypt_jni.so`
- **CI 绿灯 ≠ 功能可用**：ECH 是否真生效必须真机验证，判据见 `ech-probe-verification`
  （不能用证书匹配判断）
