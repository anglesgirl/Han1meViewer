// Package echproxy provides a simple on-device DoH/ECH HTTP proxy for Android.
// Simple transparent forwarding: WebView -> 127.0.0.1:port/path -> target via ECH.
// No cookie jar, no HTML rewriting, no multi-host routing.
// Cookie header is preserved and forwarded as-is.
package echproxy

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

var (
	mu          sync.Mutex
	server      *http.Server
	lastStatus  = "not started"
	configInfo  string   // where the ECH config came from
	dnsInfo     string   // how the upstream IPs were resolved
	shakeInfo   string   // last TLS handshake result (ECHAccepted=…)
	fallbackECH []byte   // operator-published ECHConfigList for AS13335 targets

	cachePathMu sync.RWMutex
	cachePath   string

	dnsCacheMu   sync.RWMutex
	dnsCache     = map[string]dnsCacheEntry{}
	dnsCachePath string
)

const (
	dialTimeout        = 20 * time.Second
	publicECHCacheTTL  = 5 * time.Hour
	dnsCacheTTL        = time.Hour
)

type dnsCacheEntry struct {
	IPs    []string `json:"ips"`
	Expire int64    `json:"expire"`
}

// LastStatus returns status summary.
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

// Start binds a reverse proxy on `listen` that forwards to https://`target` over ECH.
func Start(listen, target, echB64, doh, ipList, cpArg string, insecure bool) error {
	mu.Lock()
	if server != nil {
		mu.Unlock()
		return errors.New("echproxy already running")
	}
	mu.Unlock()

	_ = target // target stored in handler
	cachePathMu.Lock()
	cachePath = cpArg
	dnsCachePath = filepath.Join(filepath.Dir(cpArg), "ech-dns-cache.json")
	cachePathMu.Unlock()
	flushDnsCacheIfEndpointChanged(filepath.Join(filepath.Dir(cpArg), "ech-doh-endpoint.txt"), doh)
	loadDnsCache()

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
	fallbackECH = fallback
	customIPs = custom
	mu.Unlock()
	setDNSInfo("per-host DoH; ECH only for AS13335-qualified hosts")

	// Simple client: no cookie jar, transparent forwarding
	client := &http.Client{
		Transport: &hostRouter{},
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
		_, _ = transportFor(target)
	}()
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

// Stop shuts the proxy down.
func Stop() error {
	mu.Lock()
	defer mu.Unlock()
	if server == nil {
		return errors.New("echproxy not running")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	err := server.Shutdown(ctx)
	server = nil
	log.Println("echproxy stopped")
	return err
}

// IsAs13335 resolves host through the configured DoH endpoints and returns true
// only when it has at least one answer and every returned IP is in Cloudflare AS13335.
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
func HasECH(doh, host string) bool {
	if !isTargetHost(host) {
		return false
	}
	config, err := fetchECHViaDoH(host, doh)
	return err == nil && len(config) > 0
}

// Resolve returns DoH-resolved A/AAAA addresses as a comma-separated string.
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

// ---------- internal ----------

var hopByHop = map[string]bool{
	"Connection": true, "Proxy-Connection": true, "Keep-Alive": true,
	"Proxy-Authenticate": true, "Proxy-Authorization": true, "Te": true,
	"Trailer": true, "Transfer-Encoding": true, "Upgrade": true,
}

type proxyHandler struct {
	target string
	client *http.Client
}

func (h *proxyHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodConnect {
		h.handleConnect(w, r)
		return
	}

	// Simple: use configured target, preserve Host header from request
	target := h.target

	outURL := &url.URL{Scheme: "https", Host: target, Path: r.URL.Path, RawQuery: r.URL.RawQuery}
	req, err := http.NewRequestWithContext(r.Context(), r.Method, outURL.String(), r.Body)
	if err != nil {
		http.Error(w, "echproxy: bad request: "+err.Error(), http.StatusBadGateway)
		return
	}

	// Copy headers, skip hop-by-hop, Host (will be set), Accept-Encoding (we handle decoding)
	for k, vv := range r.Header {
		if hopByHop[k] || k == "Host" {
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

	if resp.StatusCode >= 400 {
		setStatus("HTTP %d for %s (upstream %s)", resp.StatusCode, r.URL.Path, target)
	}

	// Rewrite Location header for redirects
	if loc := resp.Header.Get("Location"); loc != "" {
		resp.Header.Set("Location", rewriteLocation(loc, target))
	}

	// Copy response headers
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

func rewriteLocation(loc, target string) string {
	u, err := url.Parse(loc)
	if err != nil {
		return loc
	}
	if u.Host == target || u.Host == "www."+target {
		nu := &url.URL{Scheme: u.Scheme, Host: u.Host, Path: u.Path, RawQuery: u.RawQuery}
		if nu.Path == "" {
			nu.Path = "/"
		}
		return "/" + nu.String()
	}
	return loc
}

func (h *proxyHandler) handleConnect(w http.ResponseWriter, r *http.Request) {
	hostport := r.Host
	if hostport == "" {
		http.Error(w, "echproxy: missing host in CONNECT", http.StatusBadRequest)
		return
	}
	if !isTargetHost(hostport) {
		http.Error(w, "echproxy: invalid CONNECT target", http.StatusBadRequest)
		return
	}

	hijacker, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "echproxy: hijack not supported", http.StatusInternalServerError)
		return
	}
	clientConn, _, err := hijacker.Hijack()
	if err != nil {
		log.Printf("echproxy: CONNECT hijack failed: %v", err)
		return
	}
	defer clientConn.Close()

	// Build TLS config for upstream
	cfg := &tls.Config{
		ServerName:         hostport,
		MinVersion:         tls.VersionTLS12,
		NextProtos:         []string{"h2", "http/1.1"},
		InsecureSkipVerify: false,
	}

	doh := ""
	_ = doh
	target := strings.Split(hostport, ":")[0]
	_ = target

	// Dial upstream with ECH via transportFor
	tr, err := transportFor(strings.Split(hostport, ":")[0])
	if err != nil {
		log.Printf("echproxy: CONNECT transportFor failed: %v", err)
		return
	}

	dialer := &net.Dialer{Timeout: dialTimeout}
	conn, err := tr.DialContext(r.Context(), "tcp", hostport)
	if err != nil {
		log.Printf("echproxy: CONNECT dial failed: %v", err)
		return
	}
	defer conn.Close()

	// Send 200 Connection Established
	_, _ = clientConn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))

	// Pipe
	go func() { _, _ = io.Copy(conn, clientConn) }()
	_, _ = io.Copy(clientConn, conn)
}

// isTargetHost accepts DNS host names only.
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

var (
	customIPs   []string
	fallbackECH2 []byte // renamed to avoid conflict with package var
)

func loadAndroidCertPool() *x509.CertPool {
	// ... (keep existing implementation)
	return nil
}

func isCloudflareAS13335(value string) bool {
	// ... (keep existing)
	return false
}

func allCloudflareAS13335(ips []string) bool {
	// ...
	return false
}

var cloudflareAS13335CIDRs = []string{
	"173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
	"141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
	"197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
	"104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
	"2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32",
	"2405:8100::/32", "2a06:98c0::/29", "2c0f:f248::/32",
}

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

// ... (keep existing DNS/ECH resolution functions: transportFor, resolveViaDoH, fetchECHViaDoH, etc.)

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

func flushDnsCacheIfEndpointChanged(markerPath, doh string) {
	// ... keep existing
}

func loadDnsCache() {
	// ... keep existing
}

func resolveViaDoH(host, doh string) ([]string, error) {
	// ... keep existing
	return nil, nil
}

func fetchECHViaDoH(host, doh string) ([]byte, error) {
	// ... keep existing
	return nil, nil
}

func transportFor(host string) (*http.Transport, error) {
	// ... keep existing
	return nil, nil
}

func loadAndroidCertPool() *x509.CertPool {
	return nil
}
