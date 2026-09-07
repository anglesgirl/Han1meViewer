// Package echproxy provides a reusable on-device DoH/ECH HTTP proxy for Android.
// Every valid target is resolved through DoH. Only targets that resolve entirely
// to Cloudflare AS13335 and publish their own HTTPS ECH record use ECH; all
// other targets use ordinary TLS over the DoH-resolved addresses.
//
// gomobile-exported surface (basic types only):
//
//	IsAs13335(doh, host) bool
//	Start(listen, target, echB64, doh, ipList, cachePath string, insecure bool) error
//	Stop() error
//	LastStatus() string
//	FetchTxt(doh, name) (string, error)
package echproxy

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

var androidCertPool *x509.CertPool
var androidCertPoolOnce sync.Once

const (
	// 2026-08-15: 20s→5s。移动宽带下不可达 CF IP 的 TCP connect 内核级
	// 超时通常 ~10s，20s 是二次重试的累积；串行试多个候选时 40s+ 才轮到
	// 可用 IP（用户实测每次请求卡 40s+）。5s 足够 TCP 往返判定失败。
	dialTimeout = 5 * time.Second
	// DoH 查询超时独立控制：网络差时 20s 太慢，5s 失败后走缓存或种子 IP 兜底。
	dohTimeout = 5 * time.Second
	// ECH 公钥配置缓存 5 小时:公钥轮换频率远低于此,期间连接直接用缓存握手,
	// 避免每次启动/换 host 都实时查 DoH。兜底配置(server retry_configs / 目标
	// 自身 ech= / operator fallback)同样缓存,失败后降级普通 TLS。
	publicECHCacheTTL = 5 * time.Hour
)

var (
	mu          sync.Mutex
	server      *http.Server
	lastStatus  = "not started"
	configInfo  string   // where the ECH config came from
	dnsInfo     string   // how the upstream IPs were resolved
	shakeInfo   string   // last TLS handshake result (ECHAccepted=…)
	upstreamIPs []string // DoH-resolved upstream addresses, IPv4 first
	customIPs   []string // user-supplied edge IPs, tried before everything else
	fallbackECH []byte   // operator-published ECHConfigList for AS13335 targets

	// Per-host state for secondary targets (translation API, mirrors, …) reached
	// through the same proxy via the X-Ech-Target header.
	hostsMu    sync.Mutex
	hostConfs  = map[string]*hostConf{}
	activeDoH  string
	activeInse bool

	// cachePath 是 ECHConfigList 的磁盘缓存位置(5h TTL),由 Start 传入。
	// 首次从 cloudflare-ech.com / 目标 HTTPS ech= / server retry_configs 获取后
	// 落盘,后续连接直接读缓存握手,避免每次启动都实时查 DoH。
	cachePathMu sync.RWMutex
	cachePath   string
)

// cookieJar 是代理的会话 cookie 容器(每个 Start 重建)。提升为包级变量,
// 以便 ClearSessionCookies 在不重启代理的前提下清掉 AO3 会话 cookie、
// 保留 cf_clearance —— 重启整个代理会连 cf_clearance 一起丢,导致
// Cloudflare 验证无限循环。
//
// 2026-08-13 实测(JarInfo):AO3 经代理存的 session cookie 形状是
// host-only + path=""(空路径)。Go cookiejar 的删除标记(SetCookies +
// MaxAge=-1)会算默认 path="/",与存的 path="" 形成不同 key,永远匹配
// 不上 → session 删不掉 → AO3 一直 302 到 /users/xxx。修复:不再用
// 删除标记,直接**整体替换 jar**(newCookieJar)清空一切 cookie,并同步
// 更新 proxyClient.Jar 的引用。
var (
	cookieJarMu sync.Mutex
	cookieJar   *cookiejar.Jar
	proxyClient *http.Client
)

// newCookieJar 新建 jar 并替换包级引用(Start 时调用)。
func newCookieJar() *cookiejar.Jar {
	jar, _ := cookiejar.New(nil)
	cookieJarMu.Lock()
	cookieJar = jar
	cookieJarMu.Unlock()
	return jar
}

// ClearSessionCookies 只清除 AO3 的会话 cookie(_otwarchive_session /
// user_credentials),保留 cf_clearance。登录重试时 AO3 不再 302 到
// 用户主页(否则 WebView 验证窗口永不弹出),且不会把用户刚完成的
// Cloudflare 验证作废。
//
// 注意(2026-08-13 实测):AO3 的 session cookie 是 Domain=.archiveofourown.org
// 的 domain cookie。Go cookiejar 删除时 domain 必须精确匹配——只传
// host-only 删除 cookie(无 Domain 属性)匹配不上,删不掉,导致 AO3 一直
// 判定"已登录"302 到 /users/xxx。因此对每个域名同时用 host-only 和
// domain(带前导点)两种形式发删除标记。
//
// 2026-08-13 二次加强:http.Client 把响应 Set-Cookie 存进 jar 时,host-only
// cookie 的域 = 请求 URL 的 host。代理转发一律用 archiveofourown.org,
// 但为绝对稳妥,删除标记覆盖 http/https × 4 个 host(含 127.0.0.1/localhost)
// × host-only/domain 两种形式,穷尽所有可能存法。
//
// 2026-08-13 三次加强:cf_clearance 也必须删。实测发现 ky.get 首次命中
// CF challenge 时,CF 返回 challenge 页会 Set-Cookie 一个**未完成**的
// cf_clearance;WebView 再加载时带着它 → CF 判定"已给过验证"直接放行 →
// 返回登录表单而非验证界面 → 验证窗口永不弹出,POST 却仍被拦 → 死循环。
// 删掉 cf_clearance 让 WebView 从零开始,CF 才会真正弹验证;完成后新的
// cf_clearance 写入 jar 才对后续 POST 有效。
//
// 2026-08-13 四次加强(最终形态):不再逐个发删除标记 —— JarInfo 实测
// 显示 session cookie 是 path=""(空路径)的 host-only cookie,删除标记的
// 默认 path="/" 匹配不上,删不掉。直接整体替换 jar 清空**一切** cookie
// (session + cf_clearance + __cf_bm 等),并同步 proxyClient.Jar 引用,
// 保证后续请求用新 jar。WebView 从零开始加载,CF 才会真正渲染验证界面。
func ClearSessionCookies() {
	cookieJarMu.Lock()
	defer cookieJarMu.Unlock()
	if cookieJar == nil {
		return
	}
	// 整体替换 jar:清空所有 cookie,不依赖任何删除标记的 key 匹配。
	newJar, _ := cookiejar.New(nil)
	cookieJar = newJar
	if proxyClient != nil {
		proxyClient.Jar = newJar
	}
}

// JarInfo 返回代理 cookie jar 当前内容的摘要(诊断用,通过 JS bridge 打到
// 调试日志,方便定位"jar 里 session 清不掉"这类问题)。
func JarInfo() string {
	cookieJarMu.Lock()
	defer cookieJarMu.Unlock()
	if cookieJar == nil {
		return "jar: nil"
	}
	var b strings.Builder
	hosts := []string{"archiveofourown.org", "www.archiveofourown.org", "127.0.0.1", "localhost"}
	schemes := []string{"https", "http"}
	for _, scheme := range schemes {
		for _, host := range hosts {
			u := &url.URL{Scheme: scheme, Host: host}
			cks := cookieJar.Cookies(u)
			if len(cks) == 0 {
				continue
			}
			fmt.Fprintf(&b, "[%s://%s] %d cookie(s)\n", scheme, host, len(cks))
			for _, c := range cks {
				v := c.Value
				// session cookie 值必须完整打印：交互式登录从 jarInfo 文本
				// 提取 _otwarchive_session 作为登录态；截断 24 字符会存进
				// 无效 session → validateCookie 失败 → 无限重新登录。
				if len(v) > 24 && c.Name != "_otwarchive_session" {
					v = v[:24] + "..."
				}
				fmt.Fprintf(&b, "  %s=%q domain=%q path=%q secure=%v maxAge=%d\n",
					c.Name, v, c.Domain, c.Path, c.Secure, c.MaxAge)
			}
		}
	}
	if b.Len() == 0 {
		return "jar: empty"
	}
	return strings.TrimSuffix(b.String(), "\n")
}

// hostConf caches what we learned about a secondary upstream: its DoH-resolved
// addresses and its ECH config (absent for servers that don't offer ECH).
type hostConf struct {
	ips       []string
	ech       []byte
	as13335   bool
	transport *http.Transport
}

func setStatus(format string, a ...any) {
	s := fmt.Sprintf(format, a...)
	mu.Lock()
	lastStatus = s
	mu.Unlock()
	log.Printf("echproxy: %s", s)
}

func setConfigInfo(format string, a ...any) {
	s := fmt.Sprintf(format, a...)
	mu.Lock()
	configInfo = s
	mu.Unlock()
	log.Printf("echproxy: config %s", s)
}

func setDNSInfo(format string, a ...any) {
	s := fmt.Sprintf(format, a...)
	mu.Lock()
	dnsInfo = s
	mu.Unlock()
	log.Printf("echproxy: dns %s", s)
}

func setShakeInfo(format string, a ...any) {
	s := fmt.Sprintf(format, a...)
	mu.Lock()
	shakeInfo = s
	mu.Unlock()
	log.Printf("echproxy: handshake %s", s)
}

// LastStatus returns a multi-line summary: ECH config source, DNS resolution,
// last handshake result (look for ECHAccepted=true), and the latest status line.
func LastStatus() string {
	mu.Lock()
	defer mu.Unlock()
	out := "config: " + orNone(configInfo) + "\n"
	out += "dns: " + orNone(dnsInfo) + "\n"
	if shakeInfo != "" {
		out += "handshake: " + shakeInfo + "\n"
	} else {
		out += "handshake: (none yet)\n"
	}
	out += "last: " + lastStatus
	return out
}

func orNone(s string) string {
	if s == "" {
		return "(none)"
	}
	return s
}

// Start binds a reverse proxy on `listen` that forwards to https://`target`
// over ECH, then serves in a background goroutine.
//
// ipList is an optional comma-separated list of upstream edge IPs to use
// instead of DNS (e.g. hand-picked fast Cloudflare IPs). Because Cloudflare is
// anycast, any edge IP serves the site — the SNI and the ECH config are
// unaffected, so a custom IP changes only the route, never the encryption.
// IsAs13335 resolves host through the configured DoH endpoints and returns true
// only when it has at least one answer and every returned IP is in Cloudflare
// AS13335. It performs no system-DNS fallback, so a failed/ambiguous lookup does
// not accidentally route a host through the ECH proxy.
// loadAndroidCertPool accepts both Android hashed DER certificates and PEM
// bundles. CGO-free Go binaries do not reliably inherit Android's trust store.
func loadAndroidCertPool() *x509.CertPool {
	androidCertPoolOnce.Do(func() {
		pool := x509.NewCertPool()
		loaded := 0
		for _, dir := range []string{
			"/system/etc/security/cacerts",
			"/apex/com.android.conscrypt/cacerts",
			"/system/etc/security/cacerts_google",
			"/data/misc/user/0/cacerts-added",
		} {
			entries, err := os.ReadDir(dir)
			if err != nil { continue }
			for _, entry := range entries {
				if entry.IsDir() { continue }
				data, err := os.ReadFile(filepath.Join(dir, entry.Name()))
				if err != nil { continue }
				if cert, err := x509.ParseCertificate(data); err == nil {
					pool.AddCert(cert); loaded++
				} else if pool.AppendCertsFromPEM(data) {
					loaded++
				}
			}
		}
		if loaded > 0 { androidCertPool = pool }
	})
	return androidCertPool
}

func IsAs13335(doh, host string) bool {
	if !isTargetHost(host) {
		return false
	}
	ips, err := resolveViaDoH(host, doh)
	if err != nil || len(ips) == 0 {
		return false
	}
	return allCloudflareAS13335(ips)
}

// HasECH reports whether the target publishes its own HTTPS ECH configuration.
// It is independent from AS qualification: callers use ordinary DoH resolution
// for hosts that are not ECH-capable.
func HasECH(doh, host string) bool {
	if !isTargetHost(host) {
		return false
	}
	config, err := fetchECHViaDoH(host, doh)
	return err == nil && len(config) > 0
}

// Resolve returns DoH-resolved A/AAAA addresses as a comma-separated string
// for gomobile callers. It never falls back to Android/system DNS.
func Resolve(doh, host string) (string, error) {
	if !isTargetHost(host) {
		return "", errors.New("invalid target host")
	}
	ips, err := resolveViaDoH(host, doh)
	if err != nil {
		return "", err
	}
	return strings.Join(ips, ","), nil
}

// isCloudflareAS13335 checks the published IPv4 and IPv6 CIDRs assigned to
// Cloudflare's AS13335. The list is intentionally conservative: unknown
// addresses are treated as non-AS13335 and stay on Mihon's regular route.
func isCloudflareAS13335(value string) bool {
	ip := net.ParseIP(value)
	if ip == nil {
		return false
	}
	for _, cidr := range cloudflareAS13335CIDRs {
		_, network, _ := net.ParseCIDR(cidr)
		if network.Contains(ip) {
			return true
		}
	}
	return false
}

func allCloudflareAS13335(ips []string) bool {
	if len(ips) == 0 {
		return false
	}
	for _, ip := range ips {
		if !isCloudflareAS13335(ip) {
			return false
		}
	}
	return true
}

var cloudflareAS13335CIDRs = []string{
	"173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
	"141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
	"197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
	"104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
	"2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32",
	"2405:8100::/32", "2a06:98c0::/29", "2c0f:f248::/32",
}

func Start(listen, target, echB64, doh, ipList, cpArg string, insecure bool) error {
	mu.Lock()
	if server != nil {
		mu.Unlock()
		return errors.New("echproxy already running")
	}
	mu.Unlock()

	// Generic mode: each requested host is resolved lazily. echB64 is an
	// operator-published fallback ECHConfigList for AS13335 targets whose own
	// HTTPS record has no ech= parameter.
	_ = target
	cachePathMu.Lock()
	cachePath = cpArg
	cachePathMu.Unlock()
	fallback := []byte(nil)
	if strings.TrimSpace(echB64) != "" {
		decoded, err := base64.StdEncoding.DecodeString(strings.TrimSpace(echB64))
		if err != nil || len(decoded) == 0 {
			return fmt.Errorf("invalid fallback ECHConfigList: %w", err)
		}
		fallback = decoded
	}

	custom := make([]string, 0)
	seenIP := make(map[string]bool)
	for _, ip := range parseIPList(ipList) {
		if isCloudflareAS13335(ip) {
			seenIP[ip] = true
			custom = append(custom, ip)
		}
	}
	// ⚠️ 2026-08-15 移除 DoH 端点 IP 并入：162.159.36.x(Gateway 段)不是
	// 目标域官方段。实测(05:24 日志): CO3 并发 dial 时 Gateway 段延迟低
	// 胜出 → AO3 返回 CF 1034(Edge IP Restricted)——同一天 Han1meViewer
	// 用同一官方 IP 104.17.48.226 稳定 200。结论：只连目标域官方段
	// (远程配置 IP + 优选同段), 不连 Gateway 段。
	mu.Lock()
	customIPs = custom
	fallbackECH = fallback
	upstreamIPs = nil
	mu.Unlock()
	if len(custom) > 0 {
		setDNSInfo("per-host DoH; ECH only for AS13335-qualified hosts; %d official-segment edge IP(s)", len(custom))
	} else {
		setDNSInfo("per-host DoH; ECH only for AS13335-qualified hosts")
	}

	// 2026-08-15 CF IP 三阶段优选（用户方案）：
	// 有缓存(12h)立即返回；无缓存 → 采样50不同网段 + TCP延迟排序2s
	// top10 → speed.cloudflare.com 下载测速8s top3 → 写缓存。
	// 同步执行：启动即绑定最快 IP（"不再乱跳"），总耗时 ≤10s。
	// ⚠️ 2026-08-15 实测修正：优选 IP 只能作兜底，不能前置！04:06 日志
	// 优选 IP 前置后 403 复现，而 00:52/01:26 用远程配置官方 IP 一直
	// 200 —— CF 风控信誉机制盯上大众优选段 IP（自选 IP 被标记的几率
	// 远高于官方解析 IP）。候选顺序：官方解析 IP 优先，优选 IP 殿后。
	fastStart := time.Now()
	mu.Lock()
	officialIPs := append([]string(nil), customIPs...)
	mu.Unlock()
	fastIPs := optimizeFastIPs(cpArg, officialIPs)
	if len(fastIPs) > 0 {
		mu.Lock()
		seen := make(map[string]bool, len(customIPs)+len(fastIPs))
		fresh := make([]string, 0, len(customIPs)+len(fastIPs))
		// 1) 官方解析 IP（远程配置）优先 —— 信誉高，403 概率低
		for _, ip := range customIPs {
			if !seen[ip] {
				seen[ip] = true
				fresh = append(fresh, ip)
			}
		}
		// 2) 优选 IP 殿后 —— 官方 IP 失败（被墙/超时）时兜底
		for _, ip := range fastIPs {
			if !seen[ip] && isCloudflareAS13335(ip) {
				seen[ip] = true
				fresh = append(fresh, ip)
			}
		}
		customIPs = fresh
		mu.Unlock()
		setDNSInfo("preferred IP scan: %d fallback edge IPs appended: %v (took %v)", len(fastIPs), fastIPs, time.Since(fastStart))
		log.Printf("echproxy: preferred IP scan done in %v: %v", time.Since(fastStart), fastIPs)
	} else {
		log.Printf("echproxy: preferred IP scan: none (took %v)", time.Since(fastStart))
	}

	// Remember the settings so every requested host can be resolved the same way.
	hostsMu.Lock()
	activeDoH, activeInse = doh, insecure
	hostConfs = map[string]*hostConf{}
	hostsMu.Unlock()

	jar := newCookieJar()
	client := &http.Client{
		Transport: &hostRouter{},
		Jar:       jar,
		Timeout:   60 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	cookieJarMu.Lock()
	proxyClient = client
	cookieJarMu.Unlock()

	ln, err := net.Listen("tcp", listen)
	if err != nil {
		return fmt.Errorf("listen %s: %w", listen, err)
	}

	srv := &http.Server{Handler: &proxyHandler{target: target, client: client}}
	mu.Lock()
	server = srv
	mu.Unlock()

	setStatus("generic ECH proxy listening on http://%s", listen)
	go func() {
		if err := srv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
			setStatus("server stopped: %v", err)
		}
		mu.Lock()
		server = nil
		mu.Unlock()
	}()
	return nil
}

// Stop shuts the proxy down. Safe to call when not running.
func Stop() error {
	mu.Lock()
	srv := server
	server = nil
	mu.Unlock()
	if srv == nil {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	setStatus("stopping")
	return srv.Shutdown(ctx)
}

// --- reverse proxy handler ------------------------------------------------

type proxyHandler struct {
	target string
	client *http.Client
}

var hopByHop = map[string]bool{
	"Connection": true, "Proxy-Connection": true, "Keep-Alive": true,
	"Proxy-Authenticate": true, "Proxy-Authorization": true, "Te": true,
	"Trailer": true, "Transfer-Encoding": true, "Upgrade": true,
}

func (h *proxyHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	// 请求耗时起点（诊断用：区分握手慢 vs 传输慢）
	reqStart := time.Now()
	// X-Ech-Target lets the app route other hosts (e.g. a translation API)
	// through the same DoH + ECH path instead of the poisoned system resolver.
	target := h.target
	if t := strings.TrimSpace(r.Header.Get("X-Ech-Target")); t != "" {
		if !isTargetHost(t) {
			http.Error(w, "echproxy: invalid target host", http.StatusBadRequest)
			return
		}
		target = strings.ToLower(t)
	}

	outURL := &url.URL{Scheme: "https", Host: target, Path: r.URL.Path, RawQuery: r.URL.RawQuery}
	req, err := http.NewRequestWithContext(r.Context(), r.Method, outURL.String(), r.Body)
	if err != nil {
		http.Error(w, "echproxy: bad request: "+err.Error(), http.StatusBadGateway)
		return
	}
	for k, vv := range r.Header {
		if hopByHop[k] || k == "Host" || k == "X-Ech-Target" {
			continue
		}
		for _, v := range vv {
			req.Header.Add(k, v)
		}
	}
	req.Host = target
	// 应用层域名改写: WebView/App 的请求 URL 是 127.0.0.1(本地代理地址),
	// Referer/Origin 头泄漏非官方域名 → AO3 服务端拒绝登录(auth_error,
	// 2026-08-13 playwright 实测: 表单提交 Referer=http://127.0.0.1:PORT)。
	// ECH 只保护网络层(TLS 隐藏 SNI),应用层来源必须与真实一致——重写为
	// 官方域名,服务端看到的来源与直连官方完全相同。
	if v := req.Header.Get("Referer"); v != "" {
		if u, err := url.Parse(v); err == nil && (u.Hostname() == "127.0.0.1" || u.Hostname() == "localhost") {
			u.Scheme = "https"
			u.Host = target
			req.Header.Set("Referer", u.String())
		}
	}
	if v := req.Header.Get("Origin"); v != "" {
		if u, err := url.Parse(v); err == nil && (u.Hostname() == "127.0.0.1" || u.Hostname() == "localhost") {
			u.Scheme = "https"
			u.Host = target
			req.Header.Set("Origin", u.String())
		}
	}
	req.Header.Del("Accept-Encoding")
	// CF 风控（2026-08-15 实测）：ECH 直连 + 无浏览器指纹时，CF Bot Fight
	// 对非浏览器 UA（Go-http-client/okhttp 默认）直接 403。RN fetch 请求
	// 不带 UA，WebView 带系统 UA —— 统一强制为 Chrome UA，让服务端看到
	// 的请求与真浏览器一致。UA 不是安全边界，AO3 只认"像浏览器"。
	req.Header.Set("User-Agent",
		"Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "+
			"(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36")

	resp, err := h.client.Do(req)
	if err != nil {
		setStatus("upstream error %s %s: %v", r.Method, r.URL.Path, err)
		http.Error(w, "echproxy: upstream error: "+err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	// 处理耗时诊断（2026-08-15）：移动宽带吞吐极低(实测 13.8KB/s)，
	// 大页面下载慢 → 打每请求总耗时到状态栏，日志定位是握手慢还是传输慢。
	setStatus("req %s %s → %d in %v", r.Method, r.URL.Path, resp.StatusCode, time.Since(reqStart))
	// 403 诊断：CF challenge / 风控 / 其他？把响应体前 500 字节打进状态，
	// 下次再 403 直接从日志看出是哪类拦截（challenge-platform=CF 验证页）。
	if resp.StatusCode == 403 {
		head := make([]byte, 500)
		n, _ := io.ReadFull(resp.Body, head)
		marker := ""
		body := string(head[:n])
		switch {
		case strings.Contains(body, "challenge-platform") || strings.Contains(body, "_cf_chl_opt"):
			marker = "CF_CHALLENGE"
		case strings.Contains(body, "cf-error-details") || strings.Contains(body, "cf-ray"):
			marker = "CF_ERROR_PAGE"
		default:
			marker = "PLAIN"
		}
		setStatus("403 %s %s (%s): %q", r.Method, r.URL.Path, marker, body)
	}

	if loc := resp.Header.Get("Location"); loc != "" {
		resp.Header.Set("Location", rewriteLocation(loc, target))
	}
	for k, vv := range resp.Header {
		if hopByHop[k] {
			continue
		}
		if resp.Uncompressed && (k == "Content-Encoding" || k == "Content-Length") {
			continue
		}
		if k == "Set-Cookie" {
			// WebView 页面 origin 是 http://127.0.0.1:<port>(代理地址),而
			// AO3/CF 的 Set-Cookie 带 Domain=archiveofourown.org + Secure
			// (+SameSite=None 必须配 Secure)→ 浏览器按跨域拒收 / Secure
			// cookie 不在 http 页面发送 → cf_clearance/session 进不了
			// WebView → 登录 POST 无验证凭据 → AO3 302 auth_error(2026-08-13
			// playwright 代理实测)。改写: 去掉 Domain(host-only 按 127.0.0.1
			// 存) + 去掉 Secure + SameSite=None→Lax。Go jar 不受影响: jar 在
			// client.Do 内部已按原始 Set-Cookie 存好(archiveofourown.org 域)。
			for _, v := range vv {
				w.Header().Add(k, rewriteCookieForWebView(v))
			}
			continue
		}
		for _, v := range vv {
			w.Header().Add(k, v)
		}
	}
	// AO3 前端反钓鱼 JS 检查 location.hostname(代理下是 127.0.0.1)会插入
	// proxy-notice 警告横幅。注入官方代理后门 cookie proxy_notice=0:
	// 前端检测到该 cookie 直接放行(playwright 实测: 有 cookie 无警告、
	// 表单不禁用)。host-only 无 Domain → 按 127.0.0.1 域存,WebView 可收。
	// 仅当上游响应没带该 cookie 时注入,避免覆盖用户已有值。
	if !cookieContains(w.Header().Values("Set-Cookie"), "proxy_notice") {
		w.Header().Add("Set-Cookie", "proxy_notice=0; Path=/")
	}
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, resp.Body)
}

func cookieContains(cookies []string, name string) bool {
	for _, c := range cookies {
		if strings.HasPrefix(strings.TrimSpace(c), name+"=") {
			return true
		}
	}
	return false
}

// rewriteCookieForWebView 把上游 Set-Cookie 改写成 WebView(127.0.0.1 页面)
// 能收能发的形式: 去掉 Domain(host-only,按页面域 127.0.0.1 存)、去掉
// Secure、SameSite=None→Lax(SameSite=None 强制要求 Secure,不改成 http
// 页面会拒收)。值/Expires/Max-Age/Path/HttpOnly 保留。
func rewriteCookieForWebView(sc string) string {
	reDomain := regexp.MustCompile(`(?i);\s*Domain=[^;]*`)
	reSecure := regexp.MustCompile(`(?i);\s*Secure\b`)
	reSameSite := regexp.MustCompile(`(?i);\s*SameSite=None`)
	sc = reDomain.ReplaceAllString(sc, "")
	sc = reSecure.ReplaceAllString(sc, "")
	sc = reSameSite.ReplaceAllString(sc, "; SameSite=Lax")
	return sc
}

// isTargetHost accepts DNS host names only. The header is intentionally not a
// general URL/authority escape hatch: ports, paths, userinfo, IP literals, and
// malformed names would bypass the per-host TLS/DNS routing assumptions.
func isTargetHost(value string) bool {
	if value == "" || len(value) > 253 || net.ParseIP(value) != nil || strings.ContainsAny(value, "/:@?#\\") {
		return false
	}
	for _, label := range strings.Split(strings.TrimSuffix(value, "."), ".") {
		if label == "" || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, r := range label {
			if !(r == '-' || r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9') {
				return false
			}
		}
	}
	return true
}

func rewriteLocation(loc, target string) string {
	u, err := url.Parse(loc)
	if err != nil {
		return loc
	}
	if u.Host == target || u.Host == "www."+target {
		u.Scheme, u.Host = "", ""
		return u.String()
	}
	return loc
}

// --- multi-host routing ---------------------------------------------------

// hostRouter sends each request through a transport built for its own hostname.
// The primary target keeps the transport built at Start(); any other host gets
// one created on demand (DoH-resolved addresses, ECH when the server offers it).
type hostRouter struct{}

func (hr *hostRouter) RoundTrip(req *http.Request) (*http.Response, error) {
	host := req.URL.Hostname()
	if host == "" {
		return nil, errors.New("missing target host")
	}
	t, err := transportFor(host)
	if err != nil {
		return nil, err
	}
	return t.RoundTrip(req)
}

// transportFor lazily builds (and caches) a transport for a secondary host.
func transportFor(host string) (*http.Transport, error) {
	hostsMu.Lock()
	if hc, ok := hostConfs[host]; ok && hc.transport != nil {
		hostsMu.Unlock()
		return hc.transport, nil
	}
	doh, insecure := activeDoH, activeInse
	hostsMu.Unlock()

	hc := &hostConf{}
	if ips, err := resolveViaDoH(host, doh); err == nil {
		hc.ips = ips
		hc.as13335 = allCloudflareAS13335(ips)
		setDNSInfo("%s: %d DoH address(es), AS13335=%v", host, len(ips), hc.as13335)
	} else {
		log.Printf("echproxy: DoH resolve for %s failed: %v", host, err)
		// DoH 失败时用种子 TXT 下发的优选 IP 兜底直连（不抛错断网）。
		// 与 Han1meViewer 已验证方案一致：宁可走种子 IP 直连 + ECH，
		// 也不要因为 DoH 被墙/抖动就完全断网。
		mu.Lock()
		fallbackIPs := append([]string(nil), customIPs...)
		mu.Unlock()
		if len(fallbackIPs) > 0 {
			hc.ips = fallbackIPs
			// 种子 IP 一般是 Cloudflare 边缘，按 AS13335 处理以启用 ECH。
			hc.as13335 = allCloudflareAS13335(fallbackIPs)
			setDNSInfo("%s: DoH failed, using %d seed IP(s) fallback, AS13335=%v",
				host, len(fallbackIPs), hc.as13335)
		} else {
			hc.ips = nil
			hc.as13335 = false
			setDNSInfo("%s: DoH failed and no seed IP fallback", host)
		}
	}
	// ECH 配置获取顺序(AS13335 主机):
	//   1. 本地缓存(5h TTL)—— 之前从 cloudflare-ech.com / 目标 ech= / retry 学到的
	//   2. cloudflare-ech.com 的 HTTPS ech= (Cloudflare 官方 ECH 公钥)
	//   3. 目标自身 HTTPS 记录的 ech=
	//   4. operator 下发的 fallback
	// 拿到后立即落盘,后续连接直接读缓存握手。
	if hc.as13335 {
		hc.ech, _ = loadECHConfigWithFallbacks(host, doh)
		if len(hc.ech) > 0 {
			setConfigInfo("%d bytes for %s", len(hc.ech), host)
		} else {
			setConfigInfo("no ECHConfigList for AS13335 host %s", host)
		}
	} else {
		setConfigInfo("%s is not AS13335; ordinary TLS via DoH", host)
	}
	hc.transport = &http.Transport{
		DialTLSContext:        hostDialContext(host, hc, insecure),
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          10,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   dialTimeout,
		ExpectContinueTimeout: 1 * time.Second,
	}

	hostsMu.Lock()
	hostConfs[host] = hc
	hostsMu.Unlock()
	log.Printf("echproxy: host %s ready (%d addr(s), ech=%v)", host, len(hc.ips), len(hc.ech) > 0)
	return hc.transport, nil
}

// hostDialContext dials a secondary host over its DoH-resolved addresses.
// AS13335 hosts always use ECH: their own HTTPS ech= is preferred, otherwise
// the operator-published fallback ConfigList is used.
func hostDialContext(host string, hc *hostConf, insecure bool) func(ctx context.Context, network, addr string) (net.Conn, error) {
	return func(ctx context.Context, network, addr string) (net.Conn, error) {
		_, port, err := net.SplitHostPort(addr)
		if err != nil || port == "" {
			port = "443"
		}
		mu.Lock()
		custom := append([]string(nil), customIPs...)
		mu.Unlock()
		// Prefer the addresses published for this host. A manually curated
		// Cloudflare edge can be useful as a fallback, but it is not guaranteed
		// to serve every Cloudflare customer or application endpoint reliably.
		cands := make([]string, 0, len(custom)+len(hc.ips))
		for _, ip := range hc.ips {
			cands = append(cands, net.JoinHostPort(ip, port))
		}
		if hc.as13335 {
			for _, ip := range custom {
				cands = append(cands, net.JoinHostPort(ip, port))
			}
		}

		d := &net.Dialer{Timeout: dialTimeout}
		var raw net.Conn
		dialedIP := ""
		// 并发尝试所有候选 IP（happy-eyeballs 风格，取第一个成功者）：
		// 2026-08-15 —— 移动宽带下部分 CF 优选 IP 不可达（connect 超时），
		// 串行逐个试 = 每个 5s+，最坏全试完才轮到可用 IP。并发让最快可达
		// 的 IP 直接胜出，失败候选的连接随即关闭，总耗时 ≈ 最优 IP RTT。
		if len(cands) == 1 {
			raw, err = d.DialContext(ctx, "tcp", cands[0])
			dialedIP = cands[0]
		} else {
			type dialRes struct {
				c  net.Conn
				e  error
				ip string
			}
			dctx, cancel := context.WithTimeout(ctx, dialTimeout)
			defer cancel()
			ch := make(chan dialRes, len(cands))
			for _, c := range cands {
				cc := c
				go func() {
					conn, derr := d.DialContext(dctx, "tcp", cc)
					ch <- dialRes{conn, derr, cc}
				}()
			}
			var firstErr error
			for range cands {
				r := <-ch
				switch {
				case r.e == nil && raw == nil:
					raw = r.c // 第一个成功者胜出
					dialedIP = r.ip
					cancel()
				case r.c != nil:
					r.c.Close() // 未选中的成功连接立即关闭
				case firstErr == nil:
					firstErr = r.e
				}
			}
			if raw == nil {
				err = firstErr
			}
		}
		if raw == nil {
			// 2026-08-15 失败重扫（用户方案："如果出现连接失败，重复这个模块"）：
			// 所有候选 dial 失败 = 优选 IP 失效（网络切换/IP 被墙）→
			// 清缓存 + 后台重跑三阶段优选，新 IP 前置到 customIPs。
			go func() {
				cachePathMu.RLock()
				cp := cachePath
				cachePathMu.RUnlock()
				mu.Lock()
				officialIPs := append([]string(nil), customIPs...)
				mu.Unlock()
				clearIPCache(cp)
				ips := optimizeFastIPs(cp, officialIPs)
				if len(ips) > 0 {
					mu.Lock()
					seen := make(map[string]bool, len(customIPs)+len(ips))
					var fresh []string
					// 官方解析 IP（远程配置）优先，优选 IP 殿后兜底
					for _, ip := range customIPs {
						if !seen[ip] {
							seen[ip] = true
							fresh = append(fresh, ip)
						}
					}
					for _, ip := range ips {
						if !seen[ip] && isCloudflareAS13335(ip) {
							seen[ip] = true
							fresh = append(fresh, ip)
						}
					}
					customIPs = fresh
					mu.Unlock()
					log.Printf("echproxy: re-scan after dial failure: %v", ips)
				} else {
					log.Printf("echproxy: re-scan after dial failure: no reachable IP")
				}
			}()
			return nil, fmt.Errorf("dial %s failed: %w", host, err)
		}

		cfg := &tls.Config{
			ServerName:         host,
			MinVersion:         tls.VersionTLS12,
			NextProtos:         []string{"h2", "http/1.1"},
			InsecureSkipVerify: insecure,
		}
		// ECH 可用则优先 ECH 握手;失败兜底一次(retry_configs)并缓存;
		// 再失败降级普通 TLS(保护性降级,至少保证连通性)。
		if hc.as13335 && len(hc.ech) > 0 {
			cfg.EncryptedClientHelloConfigList = hc.ech
			cfg.MinVersion = tls.VersionTLS13
		}
		hctx, cancel := context.WithTimeout(ctx, dialTimeout)
		defer cancel()

		tc := tls.Client(raw, cfg)
		err = tc.HandshakeContext(hctx)
		if err != nil {
			var rej *tls.ECHRejectionError
			// ECH 被拒且服务器给了 retry_configs:兜底一次,并缓存该配置。
			if hc.as13335 && errors.As(err, &rej) && len(rej.RetryConfigList) > 0 {
				raw.Close()
				setConfigInfo("%d bytes for %s, source: server retry_configs (cached)", len(rej.RetryConfigList), host)
				// 缓存兜底配置,下次直接用它握手。
				cachePathMu.RLock()
				cp := cachePath
				cachePathMu.RUnlock()
				storePublicECHCache(cp, host, rej.RetryConfigList)
				hc.ech = append([]byte(nil), rej.RetryConfigList...)
				raw, retryErr := d.DialContext(ctx, "tcp", cands[0])
				if retryErr != nil {
					return nil, fmt.Errorf("%s retry dial failed: %w", host, retryErr)
				}
				// 显式构造 retry 配置(不复制含锁的 cfg),仅替换 ECH 配置。
				retryConfig := &tls.Config{
					ServerName:                     host,
					MinVersion:                     tls.VersionTLS13,
					NextProtos:                     []string{"h2", "http/1.1"},
					InsecureSkipVerify:             insecure,
					EncryptedClientHelloConfigList: rej.RetryConfigList,
				}
				retryCtx, retryCancel := context.WithTimeout(ctx, dialTimeout)
				retryConn := tls.Client(raw, retryConfig)
				retryErr = retryConn.HandshakeContext(retryCtx)
				retryCancel()
				if retryErr == nil && retryConn.ConnectionState().ECHAccepted {
					hc.ech = append([]byte(nil), rej.RetryConfigList...)
					setShakeInfo("ok via %s ECHAccepted=true source=server retry_configs", cands[0])
					return retryConn, nil
				}
				raw.Close()
				// 兜底也失败 → 降级普通 TLS。
				setShakeInfo("ECH retry failed for %s; downgrading to plain TLS: %v", host, retryErr)
				return plainTLSHandshake(ctx, host, d, cands, insecure, hc.as13335 && len(hc.ech) > 0)
			}
			raw.Close()
			// ECH 握手失败(非 retry 场景)→ 降级普通 TLS。
			if hc.as13335 && len(hc.ech) > 0 {
				setShakeInfo("ECH handshake failed for %s; downgrading to plain TLS: %v", host, err)
				return plainTLSHandshake(ctx, host, d, cands, insecure, true)
			}
			return nil, fmt.Errorf("%s handshake failed: %w", host, err)
		}
		if hc.as13335 && !tc.ConnectionState().ECHAccepted {
			raw.Close()
			// ECH 配置被服务器忽略(未接受)→ 降级普通 TLS。
			setShakeInfo("ECH not accepted for %s; downgrading to plain TLS", host)
			return plainTLSHandshake(ctx, host, d, cands, insecure, true)
		}
		if hc.as13335 {
			// 2026-08-15: 记录实际连接的边缘 IP —— 1034(Edge IP Restricted)
			// 排查需要知道连了哪个候选(官方解析 vs DoH端点 162.159.36.x)。
			setShakeInfo("ok via DoH ECHAccepted=true source=%s dial=%s", orNone(configInfo), dialedIP)
		}
		return tc, nil
	}
}

// plainTLSHandshake dials each candidate and performs an ordinary TLS handshake
// (no ECH). Used as the last-resort fallback so a broken/rotated ECH config
// can never fully block access; the connection still goes through DoH-resolved
// addresses, so the poisoned system resolver is bypassed.
func plainTLSHandshake(ctx context.Context, host string, d *net.Dialer, cands []string, insecure bool, wasECH bool) (net.Conn, error) {
	var lastErr error
	for _, c := range cands {
		raw, err := d.DialContext(ctx, "tcp", c)
		if err != nil {
			lastErr = err
			continue
		}
		cfg := &tls.Config{
			ServerName:         host,
			MinVersion:         tls.VersionTLS12,
			NextProtos:         []string{"h2", "http/1.1"},
			InsecureSkipVerify: insecure,
		}
		tctx, cancel := context.WithTimeout(ctx, dialTimeout)
		tc := tls.Client(raw, cfg)
		err = tc.HandshakeContext(tctx)
		cancel()
		if err != nil {
			raw.Close()
			lastErr = err
			continue
		}
		if wasECH {
			setShakeInfo("ok via %s plain TLS (ECH downgraded)", c)
		}
		return tc, nil
	}
	if lastErr == nil {
		lastErr = errors.New("no dial candidates")
	}
	return nil, fmt.Errorf("plain TLS handshake failed: %w", lastErr)
}

// --- ECH transport --------------------------------------------------------

func newECHTransport(sni string, echList []byte, cachePath string, insecure bool) *http.Transport {
	return &http.Transport{
		DialTLSContext:        echDialContext(sni, echList, cachePath, insecure),
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          20,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   dialTimeout,
		ExpectContinueTimeout: 1 * time.Second,
	}
}

// parseIPList splits a comma/space separated list into valid IP literals.
func parseIPList(s string) []string {
	var out []string
	for _, f := range strings.FieldsFunc(s, func(r rune) bool {
		return r == ',' || r == ' ' || r == '\n' || r == '	' || r == ';'
	}) {
		f = strings.TrimSpace(f)
		if net.ParseIP(f) != nil {
			out = append(out, f)
		}
	}
	return out
}

// resolveDoHHostIPs 解析 DoH 端点域名(如 pieqllv9i7.cloudflare-gateway.com)
// 的 IP 列表,用于自动并入 ECH 握手候选。系统 DNS 解析失败时回退内置快照。
// doh 参数可能是逗号分隔的多个端点,取第一个能解析的即可。
func resolveDoHHostIPs(doh string) []string {
	for _, part := range strings.Split(doh, ",") {
		part = strings.TrimSpace(part)
		u, err := url.Parse(part)
		if err != nil || u.Hostname() == "" {
			continue
		}
		host := u.Hostname()
		var ips []string
		// ⚠️ net.LookupHost 无超时：移动宽带被污染的系统 DNS 能卡 30s+
		// （2026-08-15 实测：第二次冷启动 Start() 卡 30s，works 请求在
		// beforeRequest 等 getEchBase 干等）。3s 超时，失败走内置快照。
		type lookupRes struct {
			addrs []string
			err   error
		}
		lch := make(chan lookupRes, 1)
		go func() {
			addrs, err := net.LookupHost(host)
			lch <- lookupRes{addrs, err}
		}()
		select {
		case r := <-lch:
			if r.err == nil {
				for _, a := range r.addrs {
					if net.ParseIP(a) != nil {
						ips = append(ips, a)
					}
				}
			}
		case <-time.After(3 * time.Second):
			// 超时：跳过系统 DNS，直接用内置快照
		}
		// 内置快照兜底(系统 DNS 被污染时仍能用)。
		for _, b := range builtinDoHHostIPs {
			found := false
			for _, a := range ips {
				if a == b {
					found = true
					break
				}
			}
			if !found {
				ips = append(ips, b)
			}
		}
		if len(ips) > 0 {
			return ips
		}
	}
	return nil
}

// dialCandidates returns the addresses to try, in order:
// user-supplied IPs, then DoH-resolved (IPv4 first), then system DNS.
func dialCandidates(addr string) []string {
	_, port, err := net.SplitHostPort(addr)
	if err != nil || port == "" {
		port = "443"
	}
	mu.Lock()
	custom := append([]string(nil), customIPs...)
	ips := append([]string(nil), upstreamIPs...)
	mu.Unlock()

	out := make([]string, 0, len(custom)+len(ips)+1)
	for _, ip := range custom {
		out = append(out, net.JoinHostPort(ip, port))
	}
	for _, ip := range ips {
		out = append(out, net.JoinHostPort(ip, port))
	}
	return append(out, addr) // last resort: system DNS
}

// echDialContext dials the upstream and performs a TLS 1.3 handshake with ECH.
func echDialContext(sni string, echList []byte, cachePath string, insecure bool) func(ctx context.Context, network, addr string) (net.Conn, error) {
	var logged sync.Once
	return func(ctx context.Context, network, addr string) (net.Conn, error) {
		d := &net.Dialer{Timeout: dialTimeout}
		candidates := dialCandidates(addr)
		var lastErr error

		// An edge can reset or reject an otherwise valid ECH connection. Try every
		// configured edge before failing, but never downgrade this protected path to
		// ordinary TLS (which would expose the real AO3 SNI).
		for _, dialed := range candidates {
			raw, err := d.DialContext(ctx, "tcp", dialed)
			if err != nil {
				lastErr = err
				log.Printf("echproxy: dial %s failed: %v", dialed, err)
				continue
			}

			cfg := &tls.Config{
				ServerName:                     sni,
				MinVersion:                     tls.VersionTLS13, // ECH requires TLS 1.3
				NextProtos:                     []string{"h2", "http/1.1"},
				EncryptedClientHelloConfigList: echList,
				InsecureSkipVerify:             insecure,
			}
			hctx, cancel := context.WithTimeout(ctx, dialTimeout)
			tc := tls.Client(raw, cfg)
			err = tc.HandshakeContext(hctx)
			cancel()

			// Do not use server retry_configs. This proxy validates the ECH
			// configuration obtained for the target host itself, without silently
			// switching to a server-provided configuration.
			var rej *tls.ECHRejectionError
			if errors.As(err, &rej) && len(rej.RetryConfigList) > 0 {
				setStatus("ECH rejected via %s; server retry_configs ignored", dialed)
			}
			if err != nil {
				raw.Close()
				lastErr = err
				log.Printf("echproxy: ECH handshake via %s failed; trying next candidate: %v", dialed, err)
				continue
			}
			st := tc.ConnectionState()
			logged.Do(func() {
				setShakeInfo("ok via %s ECHAccepted=%v TLS=%s ALPN=%q",
					dialed, st.ECHAccepted, tlsVersionName(st.Version), st.NegotiatedProtocol)
			})
			return tc, nil
		}
		// 所有 ECH 候选都失败 → 直接报错，绝不降级到普通 TLS。
		// 降级会暴露真实 SNI (archiveofourown.org)，导致 Cloudflare 返回
		// Challenge 页面；WebView 无法在挑战窗口外完成验证，登录会陷入
		// 无限循环。宁可请求失败，也不降级暴露 SNI。
		setShakeInfo("FAILED after %d ECH candidate(s): %v; refusing plain TLS fallback to avoid Cloudflare challenge", len(candidates), lastErr)
		return nil, fmt.Errorf("all %d ECH candidates failed for %s; last error: %w (plain TLS fallback DISABLED to prevent Cloudflare challenge)", len(candidates), sni, lastErr)
	}
}

// --- DoH ------------------------------------------------------------------

type dohResp struct {
	Status int `json:"Status"`
	Answer []struct {
		Type int    `json:"type"`
		Data string `json:"data"`
	} `json:"Answer"`
}

// dohDialContext returns a DialContext that connects to the DoH endpoint via
// the user-supplied preferred IPs (SNI/domain stays the endpoint's hostname).
// Rationale: the DoH endpoint domain (e.g. cloudflare-gateway.com) can itself
// be DNS-poisoned / IP-blocked in some regions. Without this, dohQuery would
// fail before it ever gets to query cloudflare-ech.com's HTTPS ech= record,
// so the public ECH config could never be fetched or cached. The DoH endpoint
// is a Cloudflare domain, so the same preferred CF edge IPs apply.
// Returns nil when no preferred IPs are configured (use system DNS).
func dohDialContext() func(ctx context.Context, network, addr string) (net.Conn, error) {
	mu.Lock()
	custom := append([]string(nil), customIPs...)
	mu.Unlock()
	if len(custom) == 0 {
		return nil
	}
	return func(ctx context.Context, network, addr string) (net.Conn, error) {
		_, port, err := net.SplitHostPort(addr)
		if err != nil {
			port = "443"
		}
		d := &net.Dialer{Timeout: dohTimeout}
		var lastErr error
		for _, ip := range custom {
			conn, err := d.DialContext(ctx, network, net.JoinHostPort(ip, port))
			if err == nil {
				return conn, nil
			}
			lastErr = err
		}
		if lastErr != nil {
			// 优选 IP 全部失败时回退系统解析,避免 DoH 直接断网。
			setDNSInfo("DoH preferred IPs failed (%v), falling back to system DNS", lastErr)
			return d.DialContext(ctx, network, addr)
		}
		return nil, lastErr
	}
}

// dohQuery performs a DoH JSON query and returns the answer records.
func dohQuery(endpoint, name, qtype string) (*dohResp, error) {
	var lastErr error
	for _, base := range strings.Split(endpoint, ",") {
		base = strings.TrimSpace(base)
		if base == "" {
			continue
		}
		q := base
		if strings.Contains(q, "?") {
			q += "&"
		} else {
			q += "?"
		}
		q += "name=" + url.QueryEscape(name) + "&type=" + qtype
		req, err := http.NewRequest("GET", q, nil)
		if err != nil {
			lastErr = err
			continue
		}
		req.Header.Set("accept", "application/dns-json")
		transport := &http.Transport{}
		if pool := loadAndroidCertPool(); pool != nil { transport.TLSClientConfig = &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS12} }
		// 用优选 IP 直连 DoH 端点(SNI 保持端点域名),绕开 DoH 端点域名被
		// 污染/封 IP 的问题;未配置优选 IP 时走系统 DNS(原行为)。
		if dc := dohDialContext(); dc != nil {
			transport.DialContext = dc
		}
		resp, err := (&http.Client{Timeout: dohTimeout, Transport: transport}).Do(req)
		if err != nil {
			lastErr = err
			continue
		}
		if resp.StatusCode != 200 {
			lastErr = fmt.Errorf("DoH HTTP %d via %s", resp.StatusCode, base)
			resp.Body.Close()
			continue
		}
		var dr dohResp
		err = json.NewDecoder(resp.Body).Decode(&dr)
		resp.Body.Close()
		if err != nil {
			lastErr = err
			continue
		}
		if dr.Status != 0 {
			lastErr = fmt.Errorf("DoH DNS status %d via %s", dr.Status, base)
			continue
		}
		return &dr, nil
	}
	if lastErr == nil {
		lastErr = errors.New("no DoH endpoint configured")
	}
	return nil, lastErr
}

// resolveViaDoH returns the upstream IPs (IPv4 first) for host, using DoH so the
// poisoned system resolver is bypassed entirely.
func resolveViaDoH(host, endpoint string) ([]string, error) {
	if strings.TrimSpace(endpoint) == "" {
		return nil, errors.New("no DoH endpoint configured")
	}

	// A 和 AAAA 并行查询，缩短首次请求延迟。
	type result struct {
		ips []string
		err error
	}
	ch4 := make(chan result, 1)
	ch6 := make(chan result, 1)

	go func() {
		var ips []string
		if dr, err := dohQuery(endpoint, host, "A"); err == nil {
			for _, a := range dr.Answer {
				if a.Type == 1 && net.ParseIP(a.Data) != nil {
					ips = append(ips, a.Data)
				}
			}
			ch4 <- result{ips: ips}
		} else {
			ch4 <- result{err: err}
		}
	}()
	go func() {
		var ips []string
		if dr, err := dohQuery(endpoint, host, "AAAA"); err == nil {
			for _, a := range dr.Answer {
				if a.Type == 28 && net.ParseIP(a.Data) != nil {
					ips = append(ips, a.Data)
				}
			}
			ch6 <- result{ips: ips}
		} else {
			ch6 <- result{err: err}
		}
	}()

	r4, r6 := <-ch4, <-ch6
	out := append(r4.ips, r6.ips...) // IPv4 first — broken/poisoned IPv6 is common
	if len(out) == 0 {
		if r4.err != nil {
			return nil, r4.err
		}
		return nil, errors.New("no A/AAAA records returned")
	}
	return out, nil
}

// quotedRe extracts the quoted chunks of a TXT record. Long TXT values are
// split into multiple 255-byte strings, which DoH returns as "a" "b".
var quotedRe = regexp.MustCompile(`"([^"]*)"`)

// FetchTxt looks up the TXT records of `name` over DoH and returns them, one
// record per line, with quoting removed and split chunks re-joined.
//
// Used for remote configuration: the operator publishes a TXT record such as
//
//	v=co3ech1; doh=https://example.com/dns-query; ip=104.20.8.2,104.20.9.2
//
// so end users can pull working settings without knowing what DoH even is.
// The lookup itself goes over DoH, so a poisoned system resolver can't spoof it.
func FetchTxt(doh, name string) (string, error) {
	if strings.TrimSpace(doh) == "" {
		return "", errors.New("no DoH endpoint configured")
	}
	if strings.TrimSpace(name) == "" {
		return "", errors.New("no config domain given")
	}
	dr, err := dohQuery(doh, name, "TXT")
	if err != nil {
		return "", err
	}
	var lines []string
	for _, a := range dr.Answer {
		if a.Type != 16 { // TXT
			continue
		}
		s := a.Data
		if m := quotedRe.FindAllStringSubmatch(s, -1); len(m) > 0 {
			var b strings.Builder
			for _, g := range m {
				b.WriteString(g[1])
			}
			s = b.String()
		}
		s = strings.TrimSpace(s)
		if s != "" {
			lines = append(lines, s)
		}
	}
	if len(lines) == 0 {
		return "", errors.New("no TXT records found for " + name)
	}
	return strings.Join(lines, "\n"), nil
}

var echParamRe = regexp.MustCompile(`(?:^|\s)ech="?([A-Za-z0-9+/=]+)"?`)

// fetchECHViaDoH queries HTTPS (type 65) and accepts both textual SVCB output
// and RFC 3597 wire-format output returned by different DoH providers.
func fetchECHViaDoH(host, endpoint string) ([]byte, error) {
	dr, err := dohQuery(endpoint, host, "HTTPS")
	if err != nil { return nil, err }
	for _, a := range dr.Answer {
		if a.Type != 65 { continue }
		if match := echParamRe.FindStringSubmatch(a.Data); match != nil {
			value, err := base64.StdEncoding.DecodeString(match[1])
			if err != nil { return nil, fmt.Errorf("ECH base64: %w", err) }
			return value, nil
		}
		if value, err := parseSVCBWireECH(a.Data); err == nil { return value, nil }
	}
	return nil, errors.New("no ech= parameter in HTTPS record")
}

// parseSVCBWireECH extracts SvcParam key 5 (ech) from RFC 3597 form:
// "\\# <wire-length> <hex bytes>".
func parseSVCBWireECH(data string) ([]byte, error) {
	if !strings.HasPrefix(data, `\# `) { return nil, errors.New("not RFC3597 wire format") }
	parts := strings.Fields(data)
	if len(parts) < 3 { return nil, errors.New("malformed RFC3597 wire format") }
	hexBytes := strings.Join(parts[2:], "")
	wire, err := hex.DecodeString(hexBytes)
	if err != nil || len(wire) < 3 { return nil, errors.New("invalid SVCB wire data") }
	position := 2 // priority
	for position < len(wire) && wire[position] != 0 {
		length := int(wire[position]); position += 1 + length
		if position > len(wire) { return nil, errors.New("invalid SVCB target") }
	}
	position++
	for position+4 <= len(wire) {
		key := int(wire[position])<<8 | int(wire[position+1])
		length := int(wire[position+2])<<8 | int(wire[position+3])
		position += 4
		if position+length > len(wire) { return nil, errors.New("invalid SVCB parameter") }
		if key == 5 { return append([]byte(nil), wire[position:position+length]...), nil }
		position += length
	}
	return nil, errors.New("no ECH SvcParam")
}

type publicECHCache struct {
	Host      string `json:"host"`
	ConfigB64 string `json:"config_b64"`
	ExpiresAt int64  `json:"expires_at"`
}

// The cache is deliberately limited to a public ECHConfigList and expiry.
// It is shared by all proxy starts in this app installation; it never stores
// HTTP data, cookies, credentials, private keys, or a bypass decision.
func loadPublicECHCache(path, host string) ([]byte, bool) {
	if strings.TrimSpace(path) == "" { return nil, false }
	data, err := os.ReadFile(path)
	if err != nil { return nil, false }
	var record publicECHCache
	if json.Unmarshal(data, &record) != nil || !strings.EqualFold(record.Host, host) || record.ExpiresAt <= time.Now().Unix() { return nil, false }
	b, err := base64.StdEncoding.DecodeString(record.ConfigB64)
	if err != nil || len(b) == 0 { return nil, false }
	return b, true
}

func storePublicECHCache(path, host string, config []byte) {
	if strings.TrimSpace(path) == "" || len(config) == 0 { return }
	record, err := json.Marshal(publicECHCache{Host: strings.ToLower(host), ConfigB64: base64.StdEncoding.EncodeToString(config), ExpiresAt: time.Now().Add(publicECHCacheTTL).Unix()})
	if err != nil { return }
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil { return }
	tmp, err := os.CreateTemp(filepath.Dir(path), ".echconfig-")
	if err != nil { return }
	name := tmp.Name()
	defer os.Remove(name)
	if _, err = tmp.Write(record); err != nil { tmp.Close(); return }
	if err = tmp.Chmod(0600); err != nil { tmp.Close(); return }
	if err = tmp.Close(); err != nil { return }
	_ = os.Rename(name, path)
}

// cloudflareECHHost 是 Cloudflare 官方的 ECH 公钥发布点。它的 HTTPS 记录里
// 带 ech= 参数,代表 Cloudflare 边缘的当前 ECH 公钥,适用于所有 Cloudflare
// 托管的 AS13335 目标(archiveofourown.org 即其中之一)。
const cloudflareECHHost = "cloudflare-ech.com"

// builtinCFECHConfigB64 是 cloudflare-ech.com 当前 HTTPS 记录的 ech= 参数快照,
// 内置为最后兜底:部分区域(如福建)封禁 cloudflare-ech.com 的 IP 或干扰 DoH,
// 导致公共 ECH 公钥拉不到、缓存填充不了。内置后即使 DoH 全挂,AS13335 主机
// 仍能用这份公钥发起 ECH 握手;公钥轮换由服务器 retry_configs 兜底(握手被拒
// 时自动用新公钥重试),内置值过期无害。2026-08-13 抓取自 cloudflare-ech.com。
const builtinCFECHConfigB64 = "AEX+DQBBNAAgACCyup0GYiVj1Iph45mjgzNuuKu0qMra6LGPbZVfMTXgJwAEAAEAAQASY2xvdWRmbGFyZS1lY2guY29tAAA="

// builtinDoHHostIPs 是 Cloudflare Gateway DoH 端点域名当前解析的 IP 快照
// (2026-08-13 实测 pieqllv9i7.cloudflare-gateway.com → 162.159.36.5/20)。
// 属于 AS13335;部分区域(福建)封禁目标站点 CF 边缘 IP 时 DoH 端点 IP 仍可达。
// 作为 DoH 端点 IP 自动并入 ECH 候选的兜底(系统 DNS 被污染时仍能用)。
var builtinDoHHostIPs = []string{"162.159.36.5", "162.159.36.20"}

// loadECHConfigWithFallbacks returns the ECHConfigList for an AS13335 host,
// trying in order:
//  1. the local 5h disk cache (learned from a previous fetch or retry_configs)
//  2. cloudflare-ech.com's HTTPS ech= (Cloudflare's official ECH public key)
//  3. the target's own HTTPS ech= record
//  4. the operator-published fallback ECHConfigList
//
// The first successful source is persisted to the cache so subsequent
// connections handshake straight from cache without another DoH round-trip.
func loadECHConfigWithFallbacks(host, doh string) ([]byte, string) {
	cachePathMu.RLock()
	cp := cachePath
	cachePathMu.RUnlock()

	// 1. Cache first:握手用缓存配置,不发 DoH。
	if cp != "" {
		if b, ok := loadPublicECHCache(cp, host); ok && len(b) > 0 {
			return b, "cache"
		}
	}

	// 2. Cloudflare 官方 ECH 公钥(适用所有 CF 站点)。
	if doh != "" {
		if b, err := fetchECHViaDoH(cloudflareECHHost, doh); err == nil && len(b) > 0 {
			storePublicECHCache(cp, host, b)
			return b, "cloudflare-ech.com"
		}
	}

	// 3. 目标自身 HTTPS 记录的 ech=。
	if doh != "" {
		if b, err := fetchECHViaDoH(host, doh); err == nil && len(b) > 0 {
			storePublicECHCache(cp, host, b)
			return b, "target HTTPS ech="
		}
	}

	// 4. operator fallback。
	mu.Lock()
	defer mu.Unlock()
	if len(fallbackECH) > 0 {
		b := append([]byte(nil), fallbackECH...)
		storePublicECHCache(cp, host, b)
		return b, "operator fallback"
	}

	// 5. 内置 Cloudflare 公共公钥(最后兜底)。
	// 部分区域封禁 cloudflare-ech.com 的 IP / 干扰 DoH,导致上面 1-4 全失败。
	// 内置快照保证 AS13335 主机仍能发起 ECH 握手;公钥轮换由服务器
	// retry_configs 兜底(握手被拒时自动更新),无需网络拉取也能自愈。
	if b, err := base64.StdEncoding.DecodeString(builtinCFECHConfigB64); err == nil && len(b) > 0 {
		storePublicECHCache(cp, host, b)
		return b, "built-in cloudflare public key"
	}
	return nil, "no ECHConfigList available"
}

func loadECHConfig(host, echB64, doh, cachePath string) ([]byte, string, error) {
	if strings.TrimSpace(echB64) != "" {
		b, err := base64.StdEncoding.DecodeString(strings.TrimSpace(echB64))
		if err != nil { return nil, "", fmt.Errorf("ech base64: %w", err) }
		return b, "flag", nil
	}
	if strings.TrimSpace(doh) != "" {
		if b, err := fetchECHViaDoH(host, doh); err == nil && len(b) > 0 {
			if cachePath != "" { storePublicECHCache(cachePath, host, b) }
			return b, "DoH", nil
		} else if cachePath != "" {
			if cached, ok := loadPublicECHCache(cachePath, host); ok { return cached, fmt.Sprintf("public cache (DoH failed: %v)", err), nil }
		}
	}
	return nil, "", errors.New("no ECH HTTPS record available")
}

func tlsVersionName(v uint16) string {
	switch v {
	case tls.VersionTLS13:
		return "1.3"
	case tls.VersionTLS12:
		return "1.2"
	default:
		return fmt.Sprintf("0x%04x", v)
	}
}
