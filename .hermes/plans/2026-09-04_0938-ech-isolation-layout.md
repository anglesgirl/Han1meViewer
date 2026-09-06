# Han1meViewer ECH 隔离架构 全面布局与坑位推演

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 按豆包三层隔离架构落地 ECH：底层 `curl+BoringSSL JNI` 基准层全日志、原生 `OkHttp` 业务层 ECH、`WebView` 纯渲染 + Cookie 外灌，彻底规避 WebView 网络栈坑。

**Architecture:** `EchHttpClient(libhan1me_ech.so)` 作为唯一可信网络出口 → `OkHttp: EchInterceptor` 薄封装（不做 SSLSocketFactory 轮子）→ `Cookie` 三同步 → `WebView` 拦截 POST 抛原生。

**Tech Stack:** `libhan1me_ech.so` (curl 8.x + BoringSSL ECH) + `OkHttp 4.x` + `WebView` + `CookieManager` + `GitHub Actions` 单仓编译

---

## 一、现状与约束

- 已验证：`main d58dfa2c` 的 `EchHttpClient` 对 `hanime1/javchu` GET 全通，仅 `POST 419` 待查
- 已踩坑：`GeckoView SDK36 libxul崩`、`Go代理 POST body丢`、`SSLSocketFactory 四缺陷`、`t27→t33 CDN` 已修
- 约束：`GitHub Actions 唯一出包`、`fail-closed`、全链路日志、`arm64-v8a` 单架构
- 用户环境：大陆 + GFW，`api.github.com` 裸连可用，`CF` 慢不墙

## 二、豆包架构采纳与微调

| 层 | 豆包原案 | 微调后 |
|---|---|---|
| 底层 | `curl+BoringSSL JNI` 全日志 | 保留 `EchHttpClient.request()` 不改，新增 `verbose` 日志透出到 `logcat+Diagnostics` |
| 原生 | `OkHttp+自定义SSLSocketFactory` | **不造轮子**：`OkHttp` 直接走 `EchInterceptor` 薄封装调 `EchHttpClient`，规避四缺陷，复用连接池/拦截器 |
| WebView | 纯渲染+Cookie灌入 | 同意，`POST` 表单用 `JS桥接` 白名单仅拦 `hanime1/javchu /login` |
| Cookie | 三同步 | 加 `读写锁 + 持久化 + 域名归一` |

**为什么不做 SSLSocketFactory：** 证链导出/`100-Continue`/layered socket 三坑补完需改 `ech_socket.cpp` 并引 `X509TrustManager`，工期 2-3 天且无收益，不如薄封装 `EchHttpClient`。

## 三、全量坑位推演（按阶段）

### 阶段0：底层基准层 (EchHttpClient)

| 坑 | 现象 | 规避 |
|---|---|---|
| DoH 取不到 ECHConfig | `javchu` 直连 RST | 已做：`DohConfig` 主 `han1me_app` + `cloudflare-ech.com` 回退 + 定时 `warm` |
| `ECHConfig` 缓存过期 5h | `ECH rejected` 403 | `ech-sync` 每 5h 推，App 失败时 `暖 CF` + `通知 Worker` 已实现 |
| `POST 419` 仍现 | `laravel_session` 对不上 | `Verifier` 二分：先确认是否 `Expect` 头，再查 `Cookie/_token` |
| `.so` 架构缺失 | `x86` 模拟器崩 | 仅 `arm64-v8a`，`CI` 加 `abiFilters` 校验 |
| `verbose` 日志过大 | `logcat` 刷屏 | `Diagnostics` 脱敏 + 采样，`echLogs` 只留最后一条 |

### 阶段1：OkHttp 封装层

| 坑 | 现象 | 规避 |
|---|---|---|
| `Expect:100-Continue` 空 body | `POST 419` | `Interceptor` 首行 `removeHeader("Expect")` 已做，`curl` 层不支持 `100` |
| `CookieJar` 域名不匹配 | `POST` 无 `laravel_session` | `HCookieJar` 统一 `host` 归一（去 `www.`），`Secure` 标志校验打印 |
| `_token` 未带 | `POST 419` | `GET html` 正则取 `name="_token"`，`FormBody` 必带，日志打印 `token len` |
| 连接池复用错乱 | `429/403` | `EchInterceptor` 内 `curl` 短链不进 `OkHttp` 池，不影响 |
| 重试风暴 | `ECH` 过期反复重试 | 已做 `repeat(2)` + `sleep 300ms`，失败直接 `fail-open` 回落（热修复期）后切 `fail-closed` |

### 阶段2：Cookie 三同步

| 坑 | 现象 | 规避 |
|---|---|---|
| 三方各存一份割裂 | `WebView` 登录后原生 `419` | 单源：`HCookieJar` 内存主库，`curl` 读写前同步，`WebView` `CookieManager.setCookie` 同步 |
| `CookieManager` 异步 | `setCookie` 后立刻 `load` 拿不到 | `CookieManager.flush()` + `CountDownLatch` 等 200ms |
| `HttpOnly` 读不出 | `JS` 拿不到 `laravel_session` | 不经 `JS`，全部原生层 `CookieJar` 读写 |
| 持久化丢失 | 杀进程后掉登录 | `SharedPreferences` 存 `Cookie` 持久化，`HanimeApplication` 启动恢复 |

### 阶段3：WebView 隔离

| 坑 | 现象 | 规避 |
|---|---|---|
| 表单 `POST` 被拦截不到 | 点登录无反应 | `WebViewClient.shouldInterceptRequest` 只拦 `GET`，`POST` 用 `JS` `addEventListener('submit')` + `Android Bridge` 抛原生 |
| `JS` 桥全站注入误伤 `CF` 验证 | 人机验证崩 | 白名单：仅 `javchu.com/login`、`hanime1.me/login` 注入，其余不注入 |
| `loadDataWithBaseURL` 相对路径错 | `css/js` `404` | `baseUrl` 用真实域名 `https://javchu.com/`，`curl` 已取 `html` 重组后灌入 |
| `302` 跳转丢失 | 登录后白屏 | 原生 `curl` 跟 `302` 后，把最终 `url+cookie` 一并灌 `WebView` `loadUrl` |
| `Cookie` 灌入时机晚 | 灌前 `WebView` 已发请求 | 先 `CookieManager.removeAllCookies` 再批量 `setCookie` 最后 `loadUrl` |

### 阶段4：CI/构建/发布

| 坑 | 现象 | 规避 |
|---|---|---|
| 新依赖拉不到 | `plugins.gradle.org` 超时 | 单仓复用 `han1meviewer` `libs.versions.toml`，不新建仓（`javchu-verifier` 已踩） |
| `verifier` 单独仓 `CI` 失败 | `alias` 找不到 | 直接 `VerifierActivity` 放主仓，复用现有 `CI` |
| 产物过大 | `>150M` | 单 `arm64-v8a`，`isMinifyEnabled=true`，`so` 不重复打包 |

## 四、分步实施计划（咬碎到 2-5 分钟）

### Task 0: 固化 Verifier 基准
- **Files:** `ui/activity/VerifierActivity.kt` (已推 `d1fe9f03`，待 `CI` 绿)
- **验证:** 真机点 `测试` → 分享日志含 `echStatus accepted` + `token len>0` + `CookieJar`
- **Commit:** 已推，待 `CI 338589...` 重跑

### Task 1: OkHttp 薄封装定版
- **Files:** `logic/network/EchInterceptor.kt:1-172` 保持 `EchHttpClient` 直调，不切 `SSLSocketFactory`
- **改动:** 确认 `removeHeader("Expect")` + `Cookie` 手动注入 + `DoH` 双源 已齐
- **验证:** `Verifier` `POST` 假账号返回 `419` 还是 `302`，日志看是否带 `Cookie`
- **Commit:** `fix: EchInterceptor fail-closed 日志补齐`

### Task 2: Cookie 三同步
- **Files:** 新建 `logic/network/CookieSync.kt`，改 `HCookieJar.kt`、`EchHttpClient`、`WebViewEchHelper.kt`
- **代码:** `CookieSync.syncToWebView(url)` 遍历 `HCookieJar` → `CookieManager.setCookie` → `flush`
- **验证:** 原生登录后 `WebView` 打开 `/user` 不跳登录
- **Commit:** `feat: Cookie 三同步`

### Task 3: WebView 隔离 + JS 桥
- **Files:** `ui/activity/LoginActivity.kt`、`assets/js_bridge.js`
- **代码:** 白名单注入 `JS` 拦 `form[action*="login"]` → `window.Android.postLogin(JSON.stringify(formData))`
- **验证:** `WebView` 点登录走原生 `curl`，返回后 `WebView` 刷新已登录态
- **Commit:** `feat: WebView POST 抛原生`

### Task 4: 诊断与发布
- **Files:** `diagnostics/Diagnostics.kt`、`NetworkDiagnosticsInterceptor.kt`
- **改动:** `ech_intercept` 事件补 `hasCookie/_token_len/expect_removed` 三字段
- **验证:** 远程日志 `log.anglesgirl.eu.org` 可检索 `verifier` 全链路
- **Commit:** `chore: 诊断补全 + CI 出包`

## 五、验证清单

- [ ] `Verifier` 真机 `GET` `200` + `echStatus accepted`
- [ ] `Verifier` `POST` 日志含 `laravel_session` + `_token len>0` + 无 `Expect`
- [ ] 浏览器 `HAR` vs `Verifier` 日志 `Cookie` 一致时 `POST` 仍 `419` → 定位 `Expect/_token` 二分
- [ ] 原生登录后 `WebView` 免登
- [ ] `WebView` 表单 `POST` 走原生后回显成功
- [ ] `t33 CDN` 自动切仍生效
- [ ] `CI` 单 `arm64-v8a` 产物 `<110M`

## 六、风险与取舍

- **不做 SSLSocketFactory：** 放弃 `OkHttp` 连接池复用 `ECH` 连接，代价是每次 `curl` 短链，收益是省 2 天 `JNI` 坑
- **WebView 不 ECH：** `WebView` 流量走明文 `SNI`（仅 `GET` 浏览），但 `POST` 已隔离到 `ECH`，权衡后接受，`fail-closed` 仅对原生 `POST` 强制
- **JS 桥白名单：** 新增 `JS` 注入需防 `XSS`，仅信任 `hanime1/javchu` 域名

## 七、开放问题

- `SSLSocketFactory` 是否彻底废弃？建议保留分支 `gecko-lite-ech-v2` 存档，不删 `echtls` 目录
- `Cookie` 持久化是否要加密？`Laravel session` 敏感，暂明文 `SP` + `MODE_PRIVATE`，后续可加 `EncryptedSharedPreferences`

---
保存于 `.hermes/plans`，下游用 `subagent-driven-development` 逐任务派发。
