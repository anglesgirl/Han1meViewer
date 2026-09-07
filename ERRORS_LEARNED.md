# Han1meViewer ECH 開發錯誤記錄 (2026-09)

## 核心架構錯誤

### 1. 代理格式選擇錯誤
- **錯誤**：使用內嵌格式 `http://127.0.0.1:port/https://javchu.com/login`
- **後果**：HTML 相對路徑 (css/js/images) 解析錯誤，需要複雜 HTML 重寫
- **正確**：系統代理模式，WebView 載入直連 URL `https://javchu.com/login`，Go proxy 根據 Host header 動態路由
- **參考**：CO3、舊版 Go proxy 都是這樣做的

### 2. 啟動順序錯誤
- **錯誤**：`HProxySelector.rebuildNetwork()` 在 ECH proxy 啟動**前**調用
- **後果**：系統代理未設置，WebView 直連被 GFW 重置
- **正確**：先 `ProxySelector.setDefault()`，再 `EchProxyManager.startAsync()`，內部啟動成功後調用 `rebuildNetwork()`

### 3. 登錄頁首次請求直連
- **錯誤**：Coroutine 等待有競態，`loginPageUrl()` 在代理就緒前返回直連 URL
- **後果**：登錄頁直連 HTTPS → 被重置/419
- **正確**：`while (!isRunning) delay()` 確保代理就緒，且 `loginPageUrl()` 始終返回直連 URL（系統代理自動處理）

### 4. 過度攔截邏輯
- **錯誤**：在 `shouldOverrideUrlLoading` 裡手動將所有請求轉為代理 URL
- **後果**：複雜、易錯、HTML 相對路徑失效
- **正確**：系統代理自動路由，WebView 直接加載，**無需攔截**

## 代碼質量錯誤

### 5. 缺失 Import
- 多次推送缺 `HProxySelector`、`Preferences` 等 import
- **對策**：推送前本地檢查編譯（雖然本機無 SDK，但可用 `kotlinc` 語法檢查）

### 6. 空安全錯誤
- `u.host?.contains()` 需要 `?.` 或 `?: false`
- Kotlin 編譯器嚴格檢查空安全

### 7. 邏輯重複提交
- 多次提交同類修復（Kotlin 空安全、import 缺失）
- **對策**：一次性修復所有相關問題再推送

## 架構決策記錄

### 系統代理 vs 內嵌代理
| 維度 | 內嵌格式 | 系統代理 (正確) |
|------|----------|-----------------|
| URL 格式 | `http://127.0.0.1:port/https://host/path` | `https://host/path` |
| HTML 重寫 | 需要 (css/js 相對路徑) | 不需要 |
| 表單提交 | POST 給 127.0.0.1 內嵌路徑 | POST 直連，系統代理自動轉發 |
| 代理複雜度 | 需解析 path 獲取 target | 讀 Host header / X-Ech-Target |
| 參考實現 | 無 | CO3、舊版 Go proxy、userfork/main |

### Go Proxy 多主機路由
- `hostRouter.RoundTrip()` 根據 `req.URL.Hostname()` 動態創建 transport
- 支援 `X-Ech-Target` header 覆蓋（OkHttp 用）
- 系統代理模式下自動工作，無需 Android 端特殊處理

## 關鍵文件修改歷史

| 文件 | 關鍵修正 |
|------|----------|
| `HanimeApplication.kt` | 啟動順序：先 setDefault 再 startAsync |
| `EchProxyManager.kt` | start() 內部調用 rebuildNetwork()；proxyUrl() 簡化 |
| `HProxySelector.kt` | rebuildNetwork() 檢測 EchProxyManager.port；select() 返回 NO_PROXY |
| `LoginActivity.kt` | loginPageUrl() 返回直連 URL；shouldOverrideUrlLoading 不攔截；等待代理就緒 |
| `ech/echproxy/echproxy.go` | hostRouter 多主機路由（已就緒） |

## 避坑指南

1. **推送前必看 CI 歷史** - 不要重複已失敗的模式
2. **系統代理生效時機** - 必須在 ECH proxy 啟動**後**設置
3. **WebView 載入 URL** - 始終用直連 URL，系統代理自動處理
4. **不要手動重寫請求** - 系統代理 + Go proxy 動態路由已足夠
4. **Kotlin 空安全** - 所有 `?.` 和 `?:` 要寫對
5. **Import 完整性** - 新用到的類必須 import
6. **參考 CO3/userfork/main** - 它們是已驗證工作的實現

## 當前狀態 (a828c351)

已推送系統代理模式實現，等待 CI 構建。
核心邏輯對齊 CO3：
- 系統代理自動路由 ✓
- WebView 直連 URL ✓
- Go proxy 多主機動態路由 ✓
- 啟動順序正確 ✓