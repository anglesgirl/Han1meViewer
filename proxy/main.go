// ECH (Encrypted Client Hello) local proxy for Android.
//
// This is a general-purpose local proxy that provides:
//  1. Standard HTTP proxy (CONNECT tunneling + HTTP forward) — any app can use it.
//     DNS resolution goes through DoH to bypass poisoning.
//  2. ECH URL-rewriting endpoint (/proxy?url=...) — for apps that need ECH.
//     The proxy performs TLS 1.3 handshake with EncryptedClientHelloConfigList,
//     encrypting the real SNI.
//
// Usage:
//   libechproxy.so -port 18423 -doh https://0kbpekmcr1.cloudflare-gateway.com/dns-query \
//     -ech-domains hanime1.me,hanime1.com,hanimeone.me
//
// Build: CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -ldflags="-s -w" -trimpath -o libechproxy.so .
package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// ---------------------------------------------------------------------------
// global config
// ---------------------------------------------------------------------------

const DefaultPort = 18423

var (
	dohEndpoint = "https://0kbpekmcr1.cloudflare-gateway.com/dns-query"
	hardcodedECH []byte // set via -ech flag (base64 ECHConfigList)

	// fallback DNS server for resolving the DoH endpoint's own domain.
	// On Android, system DNS ([::1]:53) is often unavailable to Go binaries.
	fallbackDNS = "223.5.5.5:53"

	// custom resolver using fallbackDNS
	customResolver *net.Resolver

	// per-domain ECH config cache: domain -> {config, expiry}
	echCache    = sync.Map{}
	echCacheTTL = 30 * time.Minute

	// ECH-enabled domains for pre-fetching at startup.
	// At runtime, ANY domain going through /proxy will try ECH automatically.
	// This list is just for pre-fetching to warm the cache.
	echDomains = map[string]bool{}

	// sharedECHConfig stores the first successfully fetched ECH config.
	// Used as fallback for domains that don't have their own ECH config.
	// Cloudflare shares ECH keys across all domains on the same edge.
	sharedECHConfig     []byte
	sharedECHConfigTime time.Time

	// shared DoH HTTP client (reuses TLS connections)
	dohClient *http.Client
)

type cachedECH struct {
	config  []byte
	expires time.Time
}

// ---------------------------------------------------------------------------
// CA certificate loading (Android compatibility)
// ---------------------------------------------------------------------------

// systemCertPool loads CA certificates from Android's system cert stores.
// CGO_ENABLED=0 Go binaries don't use the system trust store automatically.
var systemCertPool *x509.CertPool

func loadSystemCerts() *x509.CertPool {
	pool := x509.NewCertPool()

	// Android CA cert directories (try all known locations)
	certDirs := []string{
		"/system/etc/security/cacerts",
		"/apex/com.android.conscrypt/cacerts",
		"/system/etc/security/cacerts_google",
		"/data/misc/user/0/cacerts-added",
	}

	totalLoaded := 0
	for _, dir := range certDirs {
		entries, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		for _, entry := range entries {
			if entry.IsDir() {
				continue
			}
			data, err := os.ReadFile(filepath.Join(dir, entry.Name()))
			if err != nil {
				continue
			}
			if pool.AppendCertsFromPEM(data) {
				totalLoaded++
			}
		}
	}

	if totalLoaded > 0 {
		log.Printf("Loaded %d CA certificates from Android system", totalLoaded)
	} else {
		log.Printf("WARNING: No CA certificates found in Android system dirs")
	}

	return pool
}

// ---------------------------------------------------------------------------
// DoH helpers
// ---------------------------------------------------------------------------

type dohAnswer struct {
	Name string `json:"name"`
	Type int    `json:"type"`
	TTL  int    `json:"TTL"`
	Data string `json:"data"`
}

type dohResponse struct {
	Status int         `json:"Status"`
	Answer []dohAnswer `json:"Answer"`
}

// initDoHClient creates a shared HTTP client for DoH queries.
// Reusing the client avoids creating a new TLS connection for each query.
func initDoHClient() {
	tlsCfg := &tls.Config{}
	if systemCertPool != nil {
		tlsCfg.RootCAs = systemCertPool
	}
	dohClient = &http.Client{
		Timeout: 10 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig:   tlsCfg,
			MaxIdleConns:      5,
			MaxIdleConnsPerHost: 2,
			IdleConnTimeout:   90 * time.Second,
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				host, port, _ := net.SplitHostPort(addr)
				// Resolve using our fallback DNS server
				ips, err := customResolver.LookupIPAddr(ctx, host)
				if err != nil || len(ips) == 0 {
					return nil, fmt.Errorf("fallback DNS resolve %s: %w", host, err)
				}
				dialAddr := net.JoinHostPort(ips[0].IP.String(), port)
				dialer := &net.Dialer{Timeout: 10 * time.Second}
				return dialer.DialContext(ctx, network, dialAddr)
			},
		},
	}
}

func fetchDoH(domain string, qtype int) (*dohResponse, error) {
	reqURL := fmt.Sprintf("%s?name=%s&type=%d", dohEndpoint, domain, qtype)
	req, err := http.NewRequest("GET", reqURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/dns-json")

	resp, err := dohClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	var result dohResponse
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, err
	}
	return &result, nil
}

// resolveA resolves A records via DoH, returns a list of IPs.
func resolveA(domain string) ([]string, error) {
	resp, err := fetchDoH(domain, 1) // type A
	if err != nil {
		return nil, err
	}
	var ips []string
	for _, a := range resp.Answer {
		if a.Type == 1 {
			ips = append(ips, a.Data)
		}
	}
	return ips, nil
}

// fetchECHConfig fetches the ECHConfigList from the domain's HTTPS RR (type 65).
// Supports two DoH response formats:
//  1. Text format: "1 . alpn=h2 ech=ABCD... ipv4hint=..."
//  2. Wire format (RFC 3597): "\# <length> <hex>" — requires SVCB binary parsing
func fetchECHConfig(domain string) ([]byte, error) {
	if hardcodedECH != nil {
		return hardcodedECH, nil
	}

	resp, err := fetchDoH(domain, 65) // type HTTPS
	if err != nil {
		return nil, fmt.Errorf("DoH HTTPS query failed: %w", err)
	}

	for _, a := range resp.Answer {
		if a.Type != 65 {
			continue
		}
		data := a.Data

		// Format 1: Text format "1 . alpn=h2 ech=ABCD..."
		if idx := strings.Index(data, "ech="); idx >= 0 {
			b64 := data[idx+4:]
			if sp := strings.IndexAny(b64, " "); sp >= 0 {
				b64 = b64[:sp]
			}
			b64 = strings.Trim(b64, `"`)
			decoded, err := base64.StdEncoding.DecodeString(b64)
			if err != nil {
				return nil, fmt.Errorf("ECH base64 decode: %w", err)
			}
			log.Printf("ECH config loaded for %s (%d bytes, text format)", domain, len(decoded))
			return decoded, nil
		}

		// Format 2: Wire format "\# <length> <hex>"
		if echConfig, err := parseSVCBWireFormat(data); err == nil && echConfig != nil {
			log.Printf("ECH config loaded for %s (%d bytes, wire format)", domain, len(echConfig))
			return echConfig, nil
		}
	}
	return nil, fmt.Errorf("no ech in HTTPS record for %s", domain)
}

// parseSVCBWireFormat parses RFC 3597 wire format SVCB/HTTPS record to extract ech param.
// Input: "\# <length> <hex bytes>"
// SVCB structure:
//   - 2 bytes: priority
//   - N bytes: target domain (DNS wire format, 0-terminated)
//   - SvcParams: 2-byte key + 2-byte len + value (repeated)
//     key=5 is ech, value is raw ECHConfigList bytes
func parseSVCBWireFormat(data string) ([]byte, error) {
	if !strings.HasPrefix(data, "\\# ") {
		return nil, fmt.Errorf("not wire format")
	}

	rest := data[3:]
	sp := strings.Index(rest, " ")
	if sp < 0 {
		return nil, fmt.Errorf("wire format invalid: missing hex data")
	}

	hexStr := strings.ReplaceAll(rest[sp+1:], " ", "")
	wireData, err := hexDecode(hexStr)
	if err != nil {
		return nil, fmt.Errorf("hex decode: %w", err)
	}

	if len(wireData) < 3 {
		return nil, fmt.Errorf("wire data too short")
	}

	pos := 2 // skip priority (2 bytes)

	// Skip target domain (DNS wire format, 0-terminated)
	for pos < len(wireData) && wireData[pos] != 0 {
		labelLen := int(wireData[pos])
		pos += 1 + labelLen
	}
	pos++ // skip terminating 0

	// Parse SvcParams
	for pos+4 <= len(wireData) {
		key := int(wireData[pos])<<8 | int(wireData[pos+1])
		valLen := int(wireData[pos+2])<<8 | int(wireData[pos+3])
		pos += 4

		if pos+valLen > len(wireData) {
			break
		}

		// key=5 is ech
		if key == 5 {
			return wireData[pos : pos+valLen], nil
		}

		pos += valLen
	}

	return nil, fmt.Errorf("no ech (key=5) in SVCB record")
}

func hexDecode(s string) ([]byte, error) {
	if len(s)%2 != 0 {
		return nil, fmt.Errorf("odd hex length")
	}
	result := make([]byte, len(s)/2)
	for i := 0; i < len(s); i += 2 {
		hi, ok1 := hexCharToByte(s[i])
		lo, ok2 := hexCharToByte(s[i+1])
		if !ok1 || !ok2 {
			return nil, fmt.Errorf("invalid hex char")
		}
		result[i/2] = hi<<4 | lo
	}
	return result, nil
}

func hexCharToByte(c byte) (byte, bool) {
	switch {
	case c >= '0' && c <= '9':
		return c - '0', true
	case c >= 'a' && c <= 'f':
		return c - 'a' + 10, true
	case c >= 'A' && c <= 'F':
		return c - 'A' + 10, true
	default:
		return 0, false
	}
}

// getECHConfig returns a cached ECH config or fetches a fresh one.
//
// ALL domains going through /proxy will try ECH. If a domain has its own
// ECH config in DNS HTTPS records, that is used. If not, the shared ECH
// config (from any previously successful fetch) is used as fallback.
// This works because Cloudflare shares ECH keys across all domains on
// the same edge network.
func getECHConfig(domain string) []byte {
	if hardcodedECH != nil {
		return hardcodedECH
	}

	// Check per-domain cache first
	if v, ok := echCache.Load(domain); ok {
		c := v.(cachedECH)
		if time.Now().Before(c.expires) {
			return c.config
		}
	}

	cfg, err := fetchECHConfig(domain)
	if err != nil {
		// Domain doesn't have its own ECH config.
		// Try shared ECH config (from any previously successful fetch).
		if sharedECHConfig != nil && time.Since(sharedECHConfigTime) < echCacheTTL {
			log.Printf("ECH config not found for %s, using shared ECH config (%d bytes)", domain, len(sharedECHConfig))
			echCache.Store(domain, cachedECH{
				config:  sharedECHConfig,
				expires: time.Now().Add(echCacheTTL),
			})
			return sharedECHConfig
		}

		// No shared config yet — try fetching from a known Cloudflare domain
		log.Printf("ECH config not found for %s, trying shared ECH config from store.ubisoft.com", domain)
		cfg, err = fetchECHConfig("store.ubisoft.com")
		if err != nil {
			log.Printf("ECH config fetch FAILED for %s (fallback also failed): %v", domain, err)
			// return stale cache if available
			if v, ok := echCache.Load(domain); ok {
				return v.(cachedECH).config
			}
			return nil
		}
		log.Printf("Using shared ECH config from store.ubisoft.com for %s (%d bytes)", domain, len(cfg))
		// Save as shared config for future fallbacks
		sharedECHConfig = cfg
		sharedECHConfigTime = time.Now()
	} else {
		log.Printf("ECH config loaded for %s (%d bytes)", domain, len(cfg))
		// Save as shared config if we don't have one yet
		if sharedECHConfig == nil || time.Since(sharedECHConfigTime) >= echCacheTTL {
			sharedECHConfig = cfg
			sharedECHConfigTime = time.Now()
			log.Printf("Saved shared ECH config from %s (%d bytes)", domain, len(cfg))
		}
	}

	echCache.Store(domain, cachedECH{
		config:  cfg,
		expires: time.Now().Add(echCacheTTL),
	})
	return cfg
}

// ---------------------------------------------------------------------------
// transport with ECH-enabled TLS
// ---------------------------------------------------------------------------

var sharedTransport *http.Transport

func initTransport() {
	sharedTransport = &http.Transport{
		// Custom TLS dialer: resolve via DoH + ECH handshake (for ECH domains only)
		DialTLSContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			host, port, _ := net.SplitHostPort(addr)

			// 1. Resolve DNS via DoH (bypass system DNS poisoning)
			var dialAddr string
			ips, err := resolveA(host)
			if err != nil || len(ips) == 0 {
				// Fallback: resolve via custom resolver (223.5.5.5)
				ips2, err2 := customResolver.LookupIPAddr(ctx, host)
				if err2 != nil || len(ips2) == 0 {
					dialAddr = addr // last resort: let Go try system resolver
					if err != nil {
						log.Printf("DNS failed for %s: DoH=%v, fallback=%v", host, err, err2)
					}
				} else {
					dialAddr = net.JoinHostPort(ips2[0].IP.String(), port)
					log.Printf("DNS: %s -> %s (via fallback DNS)", host, ips2[0].IP.String())
				}
			} else {
				dialAddr = net.JoinHostPort(ips[0], port)
				log.Printf("DNS: %s -> %s (via DoH)", host, ips[0])
			}

			// 2. TCP connect
			dialer := &net.Dialer{Timeout: 30 * time.Second}
			conn, err := dialer.DialContext(ctx, network, dialAddr)
			if err != nil {
				return nil, fmt.Errorf("dial %s: %w", dialAddr, err)
			}

			// 3. TLS handshake — ECH only for ECH-enabled domains
			echConfig := getECHConfig(host)
			tlsCfg := &tls.Config{
				ServerName: host, // SNI is always the real domain — NOT hardcoded
				MinVersion: tls.VersionTLS13, // ECH requires TLS 1.3
			}
			if systemCertPool != nil {
				tlsCfg.RootCAs = systemCertPool
			}
			if echConfig != nil {
				tlsCfg.EncryptedClientHelloConfigList = echConfig
			}

			tlsConn := tls.Client(conn, tlsCfg)
			if err := tlsConn.HandshakeContext(ctx); err != nil {
				conn.Close()
				return nil, fmt.Errorf("TLS handshake %s: %w", host, err)
			}

			// 4. Verify ECH acceptance (for ECH domains only)
			st := tlsConn.ConnectionState()
			if echConfig != nil {
				if st.ECHAccepted {
					log.Printf("ECH ACCEPTED for %s (SNI encrypted)", host)
				} else {
					log.Printf("ECH GREASE only for %s (server did not accept ECH)", host)
				}
			}

			return tlsConn, nil
		},
		Proxy:               nil, // no upstream proxy
		MaxIdleConns:        10,
		MaxIdleConnsPerHost: 5,
		IdleConnTimeout:     90 * time.Second,
	}
}

// ---------------------------------------------------------------------------
// Plain TCP dialer with DoH DNS resolution (for CONNECT tunneling)
// ---------------------------------------------------------------------------

func dialWithDoH(ctx context.Context, network, addr string) (net.Conn, error) {
	host, port, _ := net.SplitHostPort(addr)

	var dialAddr string
	ips, err := resolveA(host)
	if err != nil || len(ips) == 0 {
		// Fallback: resolve via custom resolver (223.5.5.5)
		ips2, err2 := customResolver.LookupIPAddr(ctx, host)
		if err2 != nil || len(ips2) == 0 {
			dialAddr = addr
			if err != nil {
				log.Printf("CONNECT DNS failed for %s: DoH=%v, fallback=%v", host, err, err2)
			}
		} else {
			dialAddr = net.JoinHostPort(ips2[0].IP.String(), port)
			log.Printf("CONNECT DNS: %s -> %s (via fallback DNS)", host, ips2[0].IP.String())
		}
	} else {
		dialAddr = net.JoinHostPort(ips[0], port)
		log.Printf("CONNECT DNS: %s -> %s (via DoH)", host, ips[0])
	}

	dialer := &net.Dialer{Timeout: 30 * time.Second}
	return dialer.DialContext(ctx, network, dialAddr)
}

// ---------------------------------------------------------------------------
// hop-by-hop headers
// ---------------------------------------------------------------------------

var hopHeaders = map[string]bool{
	"connection":          true,
	"keep-alive":          true,
	"proxy-authenticate":  true,
	"proxy-authorization": true,
	"te":                  true,
	"trailers":            true,
	"transfer-encoding":   true,
	"upgrade":             true,
}

// ---------------------------------------------------------------------------
// Standard HTTP proxy: CONNECT tunneling (for HTTPS)
// ---------------------------------------------------------------------------

func handleCONNECT(w http.ResponseWriter, r *http.Request) {
	host, port, err := net.SplitHostPort(r.Host)
	if err != nil {
		host = r.Host
		port = "443"
	}

	// Resolve via DoH and connect
	ctx := r.Context()
	targetAddr := net.JoinHostPort(host, port)
	serverConn, err := dialWithDoH(ctx, "tcp", targetAddr)
	if err != nil {
		log.Printf("CONNECT FAIL %s: %v", targetAddr, err)
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}

	// Hijack the client connection FIRST, then manually write the response.
	// This avoids buffering issues with Go's ResponseWriter.
	hijacker, ok := w.(http.Hijacker)
	if !ok {
		log.Printf("CONNECT: hijacking not supported")
		serverConn.Close()
		http.Error(w, "hijacking not supported", http.StatusInternalServerError)
		return
	}

	clientConn, bufRw, err := hijacker.Hijack()
	if err != nil {
		log.Printf("CONNECT: hijack failed: %v", err)
		serverConn.Close()
		return
	}
	defer clientConn.Close()
	defer serverConn.Close()

	// Flush any buffered data from the client
	if bufRw != nil {
		bufRw.Flush()
	}

	// Manually write the 200 response directly on the raw connection
	// Using "200 Connection established" which is standard for CONNECT proxies
	_, err = clientConn.Write([]byte("HTTP/1.1 200 Connection established\r\n\r\n"))
	if err != nil {
		log.Printf("CONNECT: failed to write response: %v", err)
		return
	}

	log.Printf("CONNECT tunnel: %s", targetAddr)

	// Bidirectional relay
	done := make(chan struct{}, 2)
	go func() {
		defer func() { done <- struct{}{} }()
		io.Copy(serverConn, clientConn)
		serverConn.Close()
	}()
	go func() {
		defer func() { done <- struct{}{} }()
		io.Copy(clientConn, serverConn)
		clientConn.Close()
	}()

	// Wait for both directions to finish
	<-done
	<-done
}

// ---------------------------------------------------------------------------
// Standard HTTP proxy: HTTP forward (for plain HTTP)
// ---------------------------------------------------------------------------

// sharedForwardTransport is used for HTTP forward proxying (plain HTTP only).
// Reuses connections for efficiency.
var sharedForwardTransport *http.Transport

func initForwardTransport() {
	sharedForwardTransport = &http.Transport{
		DialContext:         dialWithDoH,
		MaxIdleConns:        10,
		MaxIdleConnsPerHost: 5,
		IdleConnTimeout:     90 * time.Second,
	}
}

func handleHTTPForward(w http.ResponseWriter, r *http.Request) {
	// r.URL is absolute (e.g., http://example.com/path)
	outReq, err := http.NewRequestWithContext(r.Context(), r.Method, r.URL.String(), r.Body)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	// Copy headers (skip hop-by-hop)
	for key, vals := range r.Header {
		if hopHeaders[strings.ToLower(key)] {
			continue
		}
		for _, v := range vals {
			outReq.Header.Add(key, v)
		}
	}
	outReq.Host = r.URL.Host

	client := &http.Client{
		Transport: sharedForwardTransport,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
		Timeout: 0,
	}

	resp, err := client.Do(outReq)
	if err != nil {
		log.Printf("HTTP FORWARD FAIL %s: %v", r.URL.String(), err)
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	for key, vals := range resp.Header {
		if hopHeaders[strings.ToLower(key)] {
			continue
		}
		for _, v := range vals {
			w.Header().Add(key, v)
		}
	}
	w.WriteHeader(resp.StatusCode)

	flusher, _ := w.(http.Flusher)
	buf := make([]byte, 32*1024)
	for {
		n, err := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := w.Write(buf[:n]); werr != nil {
				break
			}
			if flusher != nil {
				flusher.Flush()
			}
		}
		if err != nil {
			break
		}
	}
}

// ---------------------------------------------------------------------------
// ECH URL-rewriting endpoint: /proxy?url=<encoded_target_url>
// ---------------------------------------------------------------------------

func proxyHandler(w http.ResponseWriter, r *http.Request) {
	targetURL := r.URL.Query().Get("url")
	if targetURL == "" {
		http.Error(w, "missing url parameter", http.StatusBadRequest)
		return
	}

	parsed, err := url.Parse(targetURL)
	if err != nil {
		http.Error(w, "invalid url", http.StatusBadRequest)
		return
	}

	// Build outbound request
	outReq, err := http.NewRequestWithContext(r.Context(), r.Method, targetURL, r.Body)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	// Copy headers (skip hop-by-hop)
	for key, vals := range r.Header {
		if hopHeaders[strings.ToLower(key)] {
			continue
		}
		for _, v := range vals {
			outReq.Header.Add(key, v)
		}
	}
	outReq.Host = parsed.Host

	// Execute through ECH-enabled transport
	client := &http.Client{
		Transport: sharedTransport,
		// Don't auto-follow redirects; we handle them on the Android side
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
		Timeout: 0, // no timeout for streaming
	}

	resp, err := client.Do(outReq)
	if err != nil {
		log.Printf("ECH PROXY FAIL %s %s: %v", r.Method, targetURL, err)
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	// Copy response headers (skip hop-by-hop)
	for key, vals := range resp.Header {
		if hopHeaders[strings.ToLower(key)] {
			continue
		}
		for _, v := range vals {
			w.Header().Add(key, v)
		}
	}
	w.WriteHeader(resp.StatusCode)

	// Stream response body
	flusher, _ := w.(http.Flusher)
	buf := make([]byte, 32*1024)
	for {
		n, err := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := w.Write(buf[:n]); werr != nil {
				break
			}
			if flusher != nil {
				flusher.Flush()
			}
		}
		if err != nil {
			break
		}
	}
}

// ---------------------------------------------------------------------------
// Health check endpoint
// ---------------------------------------------------------------------------

func healthHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	echList := make([]string, 0, len(echDomains))
	for d := range echDomains {
		echList = append(echList, d)
	}
	fmt.Fprintf(w, `{"status":"ok","doh":"%s","ech_domains":["%s"]}`, dohEndpoint, strings.Join(echList, `","`))
}

// addECHDomainHandler dynamically adds a domain for ECH pre-fetching.
// POST /add-ech-domain?domain=example.com
// This allows the app to add new ECH domains at runtime without restarting the proxy.
func addECHDomainHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost && r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	domain := r.URL.Query().Get("domain")
	if domain == "" {
		// Try reading from body
		body, err := io.ReadAll(r.Body)
		if err == nil && len(body) > 0 {
			domain = strings.TrimSpace(string(body))
		}
	}
	if domain == "" {
		http.Error(w, `{"error":"missing domain parameter"}`, http.StatusBadRequest)
		return
	}

	// Add to ECH domains map
	echDomains[domain] = true
	log.Printf("Added ECH domain: %s", domain)

	// Pre-fetch ECH config in background
	go func() {
		cfg := getECHConfig(domain)
		if cfg != nil {
			log.Printf("pre-fetched ECH config for %s (%d bytes)", domain, len(cfg))
		}
	}()

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	fmt.Fprintf(w, `{"status":"ok","domain":"%s","message":"added and pre-fetching ECH config"}`, domain)
}

// ---------------------------------------------------------------------------
// Main router: dispatches to appropriate handler based on request type
// ---------------------------------------------------------------------------

func mainHandler(w http.ResponseWriter, r *http.Request) {
	// CONNECT method → TCP tunnel with DoH DNS resolution
	if r.Method == http.MethodConnect {
		handleCONNECT(w, r)
		return
	}

	// Local endpoints
	if r.URL.Path == "/proxy" {
		proxyHandler(w, r)
		return
	}
	if r.URL.Path == "/health" {
		healthHandler(w, r)
		return
	}
	if r.URL.Path == "/add-ech-domain" {
		addECHDomainHandler(w, r)
		return
	}

	// Standard HTTP proxy forward (absolute URL in request line)
	if r.URL.IsAbs() {
		handleHTTPForward(w, r)
		return
	}

	// Unknown local request
	http.NotFound(w, r)
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------

func main() {
	port := flag.Int("port", DefaultPort, "listen port")
	doh := flag.String("doh", "https://0kbpekmcr1.cloudflare-gateway.com/dns-query", "DoH endpoint")
	ech := flag.String("ech", "", "hardcoded ECHConfigList (base64)")
	dns := flag.String("dns", "223.5.5.5:53", "fallback DNS server for resolving DoH endpoint")
	echDomainsFlag := flag.String("ech-domains", "", "comma-separated list of ECH-enabled domains (e.g. hanime1.me,hanime1.com)")
	flag.Parse()

	// Remove the -domain flag — domains are now passed via -ech-domains

	dohEndpoint = *doh

	// Parse ECH domains list
	if *echDomainsFlag != "" {
		for _, d := range strings.Split(*echDomainsFlag, ",") {
			d = strings.TrimSpace(d)
			if d != "" {
				echDomains[d] = true
			}
		}
		log.Printf("ECH domains: %v", echDomains)
	} else {
		log.Printf("No ECH domains specified — ECH will not be applied to any domain")
	}

	// Load Android system CA certificates (CGO_ENABLED=0 doesn't use system trust store)
	systemCertPool = loadSystemCerts()

	// Initialize custom DNS resolver using fallback DNS server.
	// This is critical on Android where system DNS ([::1]:53) is unavailable.
	customResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			dialer := net.Dialer{Timeout: 5 * time.Second}
			return dialer.DialContext(ctx, "udp", *dns)
		},
	}
	// Also set it as the default resolver so all Go DNS lookups use it
	net.DefaultResolver = customResolver
	log.Printf("Fallback DNS: %s", *dns)

	if *ech != "" {
		decoded, err := base64.StdEncoding.DecodeString(*ech)
		if err != nil {
			log.Fatalf("invalid -ech base64: %v", err)
		}
		hardcodedECH = decoded
		log.Printf("using hardcoded ECH config (%d bytes)", len(decoded))
	}

	// Initialize shared DoH client (reuses TLS connections)
	initDoHClient()

	// Initialize transports
	initTransport()
	initForwardTransport()

	// Pre-fetch ECH configs for all ECH domains (in background)
	go func() {
		for domain := range echDomains {
			go func(d string) {
				cfg := getECHConfig(d)
				if cfg != nil {
					log.Printf("pre-fetched ECH config for %s (%d bytes)", d, len(cfg))
				}
			}(domain)
		}
	}()

	// Listen on fixed port
	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", *port))
	if err != nil {
		log.Fatalf("listen failed on port %d: %v", *port, err)
	}

	actualPort := ln.Addr().(*net.TCPAddr).Port
	// Print port to stdout (for compatibility, though it's fixed now)
	fmt.Println(actualPort)
	os.Stdout.Sync()

	log.Printf("ECH proxy listening on 127.0.0.1:%d", actualPort)
	log.Printf("DoH endpoint: %s", dohEndpoint)
	log.Printf("Endpoints: /proxy (ECH), /health, /add-ech-domain, CONNECT (standard proxy), HTTP forward")

	// Use bufio.Reader for better connection handling
	srv := &http.Server{
		Handler: http.HandlerFunc(mainHandler),
		// No read/write timeout — proxy connections can be long-lived
	}
	if err := srv.Serve(ln); err != nil {
		log.Fatalf("server error: %v", err)
	}
}
