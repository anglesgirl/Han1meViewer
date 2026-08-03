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
	dialTimeout = 20 * time.Second
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
	for _, ip := range parseIPList(ipList) {
		if isCloudflareAS13335(ip) {
			custom = append(custom, ip)
		}
	}
	mu.Lock()
	customIPs = custom
	fallbackECH = fallback
	upstreamIPs = nil
	mu.Unlock()
	setDNSInfo("per-host DoH; ECH only for AS13335-qualified hosts")

	// Remember the settings so every requested host can be resolved the same way.
	hostsMu.Lock()
	activeDoH, activeInse = doh, insecure
	hostConfs = map[string]*hostConf{}
	hostsMu.Unlock()

	jar, _ := cookiejar.New(nil)
	client := &http.Client{
		Transport: &hostRouter{},
		Jar:       jar,
		Timeout:   60 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

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
	req.Header.Del("Accept-Encoding")

	resp, err := h.client.Do(req)
	if err != nil {
		setStatus("upstream error %s %s: %v", r.Method, r.URL.Path, err)
		http.Error(w, "echproxy: upstream error: "+err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

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
		for _, v := range vv {
			w.Header().Add(k, v)
		}
	}
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, resp.Body)
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
		for _, c := range cands {
			raw, err = d.DialContext(ctx, "tcp", c)
			if err == nil {
				break
			}
		}
		if raw == nil {
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
			setShakeInfo("ok via DoH ECHAccepted=true source=%s", orNone(configInfo))
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
		return r == ',' || r == ' ' || r == '\n' || r == '\t' || r == ';'
	}) {
		f = strings.TrimSpace(f)
		if net.ParseIP(f) != nil {
			out = append(out, f)
		}
	}
	return out
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
		setShakeInfo("FAILED after %d ECH candidate(s): %v; downgrading to plain TLS", len(candidates), lastErr)
		// 所有 ECH 候选都失败 → 降级普通 TLS(保护性降级,保证连通)。
		return plainTLSHandshake(ctx, sni, d, candidates, insecure, true)
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
		resp, err := (&http.Client{Timeout: dialTimeout, Transport: transport}).Do(req)
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
	var v4, v6 []string
	var firstErr error

	if dr, err := dohQuery(endpoint, host, "A"); err == nil {
		for _, a := range dr.Answer {
			if a.Type == 1 && net.ParseIP(a.Data) != nil {
				v4 = append(v4, a.Data)
			}
		}
	} else {
		firstErr = err
	}

	if dr, err := dohQuery(endpoint, host, "AAAA"); err == nil {
		for _, a := range dr.Answer {
			if a.Type == 28 && net.ParseIP(a.Data) != nil {
				v6 = append(v6, a.Data)
			}
		}
	} else if firstErr == nil {
		firstErr = err
	}

	out := append(v4, v6...) // IPv4 first — broken/poisoned IPv6 is common
	if len(out) == 0 {
		if firstErr == nil {
			firstErr = errors.New("no A/AAAA records returned")
		}
		return nil, firstErr
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
