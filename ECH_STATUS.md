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

## 四、WebView 非 GET 传输桥（B 方案，已实现并入库）

### 为什么需要
`shouldInterceptRequest` **拿不到 POST body**（`WebResourceRequest` 没有 body 字段），
所以受保护域名上的表单提交 / 页面内 fetch、XHR 只能放行 = WebView 用自己的 TLS 栈
明文发 SNI。Go 反代时代看不到这个缺口（本地端口能读到明文 body），换进程内 Conscrypt
后才暴露。

**覆盖范围（别夸大）**：本 App 只有**登录页**与 **CF 挑战页**用 WebView；
点赞/收藏/评论/播放上报都是原生 UI 发 API，走 OkHttp + ECH，**不经 WebView**。

### 实现
- `ech/EchWebBridgeJs.kt`：注入受保护域名页面，只接管**非 GET**
  - `fetch` → 转原生代发，返回真实 `Response`
  - `XHR` → 包装 open/send/setRequestHeader，结果伪装成已完成 XHR（含 readystatechange/load/loadend）
  - 表单 submit → **只劫持含 `input[type=password]` 的表单**（CO3 用的是 AO3 专用 id，不能照抄）
  - GET/HEAD 一律不碰 —— 那条路归 `shouldInterceptRequest`，两条路不重叠
- `ech/EchWebBridge.kt`：原生落地端（Conscrypt + ECH）
  - Cookie：**网络拦截器逐跳**读写 CookieManager（重定向中间跳的 Set-Cookie 才不丢）
  - 表单：`followRedirects(false)` 读 302 的 Location → 再 `loadUrl` 真实地址
  - **fail-closed**：ECH 未就绪 / 网络失败 → 502，绝不回落明文
  - `onFormLoginSuccess` 回调：表单被代发后 WebView 不会自己跳转，原
    `shouldOverrideUrlLoading(isRedirect)` 判成功那条路永不触发 → 成功后必须调用 App
    原有的登录完成逻辑（两处调同一个 `login()`）
- 注入时机：`onPageCommitVisible` + `onPageFinished`（尽量早）
- `addJavascriptInterface` 必须在 `loadUrl` **之前**；原生侧对 URL 再判一次受保护域名

### 验证
- 本地（不用 Android）：`bash .github/scripts/verify-ech-bridge-js.sh` → node 断言 15 条，
  CI 编译前会自动跑（JS 写坏属于静默失效，最难查）
- 真机：`adb logcat | grep -E "HY-ECH-BRIDGE|HY-ECH-WEBVIEW|\[js\]"`
  - 登录成功：`表单提交成功 → 交回 App 的登录完成逻辑`
  - 页面 POST：`bridge POST xxx -> 200`
  - 出现 `保持原样（body 类型不可序列化）` = 该请求仍走明文（Blob/File body），需处理

## 五、更新检查（2026-09-12 修，全是实测出来的）

### 之前是坏的（不是"会更新到上游"，是"永远查不到更新"）
1. **401**：`buildGithubClient` 无条件加 `Authorization: Bearer <BuildConfig.HA_GITHUB_TOKEN>`，
   而这个 token 来自 CI 的临时 GITHUB_TOKEN，App 跑起来早过期/为空。
   实测：`Authorization: Bearer `（空值）→ HTTP 401；同一请求不带该头 → HTTP 200。
   → 现在 token 为空就不加这个头；CI 也不再注入 HA_GITHUB_TOKEN。
2. **workflow artifacts 匿名下不了**：实测匿名 GET artifact zip → 401「Requires authentication」。
   → CI 频道改读 `releases?per_page=10`（含预发布）；稳定通道读 `releases/latest`。
3. **tag 版本号解析不出**：`checkNeedUpdate` 原用 `substringAfter("+").toIntOrNull()`，
   tag 带 `-ech-conscrypt` 后缀时解析失败 → 回落 Int.MAX_VALUE → 永远提示"有更新"。
   → 换成 `Regex("\\+(\\d+)")`；CI 的 tag 也去掉了分支后缀。

### 通道设计（匿名可下，零 token）
| 通道 | 读什么 | 语义 |
|---|---|---|
| 稳定（默认，`useCIUpdateChannel=false`） | `releases/latest` | 只有**正式版**（非预发布） |
| CI（设置里那个开关） | `releases?per_page=10` 取最新 | 含预发布，每次构建都能拿到 |

- CI 每次成功构建自动发**预发布**（保留最近 5 个）；要发正式版就
  `gh release edit <tag> --prerelease=false --latest`
- 下载走 `gh-proxy.com` → `ghfast.top` → 直连，依次重试
- **曾经的坑**：仓库里长期存在的唯一"正式版"是 9/8 的 Go 构建（tag `v1.0.8-ech-final`），
  `releases/latest` 一直指着它 —— 已删除（包备份在 `/root/go-release-backup/`）

### 验证方式（不用真机，匿名请求即可）
```bash
curl -s https://api.github.com/repos/anglesgirl/Han1meViewer/releases/latest   # tag 应含 +<版本号>
curl -sL -o /dev/null -w '%{http_code}' -r 0-1023 \
  https://gh-proxy.com/https://github.com/anglesgirl/Han1meViewer/releases/download/<tag>/<apk>
```

## 六、验证节奏

- CI：`gh run watch <id> --exit-status` 后台等；核对 `headSha` 与本地 `git rev-parse HEAD` 一致
- 交付校验（CI 里已自动跑）：APK 内**无** `libgojni.so`、**有** `libconscrypt_jni.so`
- **CI 绿灯 ≠ 功能可用**：ECH 是否真生效必须真机验证，判据见 `ech-probe-verification`
  （不能用证书匹配判断）
