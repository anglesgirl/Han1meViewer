//! ECH over HTTP/3 客户端 —— Han1meViewer 的 H3 传输（移植自已验证的 CO3 native-h3）
//!
//! 背景：quiche 本身没有 ECH，但它 vendor 的 BoringSSL 有完整 ECH 客户端实现，
//! `tools/patch_quiche.py` 把入口暴露出来（`Config::set_ech_config_list`）。
//!
//! JNI 入口：`Java_com_yenaly_han1meviewer_logic_network_ech_HyEchH3_h3Fetch`

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::time::{Duration, Instant};

use quiche::h3::{Config as H3Config, Connection as H3Connection, Header, NameValue};

/// 一次 H3+ECH 请求的全部可观测结果。
pub struct H3Outcome {
    pub hs_ms: u128,
    pub established: bool,
    pub alpn: String,
    pub ech_override: Option<String>,
    pub ech_retry_len: usize,
    pub status: u16,
    pub first_byte_ms: u128,
    pub total_ms: u128,
    pub body_len: usize,
    /// 响应体字节（仅供 JNI 层落盘，不参与 JSON 输出）
    pub body: Vec<u8>,
    pub cf_ray: String,
    pub server: String,
    pub error: Option<String>,
    /// 诊断三件套：发了几个包、收了几个包、对端错误
    pub sent: u64,
    pub recv: u64,
    pub peer_err: String,
}

impl H3Outcome {
    fn err(msg: String) -> Self {
        H3Outcome {
            hs_ms: 0, established: false, alpn: String::new(), ech_override: None,
            ech_retry_len: 0, status: 0, first_byte_ms: 0, total_ms: 0, body_len: 0,
            body: Vec::new(),
            cf_ray: String::new(), server: String::new(), error: Some(msg),
            sent: 0, recv: 0, peer_err: String::new(),
        }
    }
}

/// 向 host 发起一次 HTTP/3 GET（QUIC 连到 peer_ip，SNI/证书用 host）。
pub fn h3_fetch(
    host: &str,
    peer_ip: &str,
    ech: Option<&[u8]>,
    path: &str,
    referer: Option<&str>,
    ca_path: &str,
    connect_timeout: Duration,
    request_timeout: Duration,
) -> H3Outcome {
    let peer: SocketAddr = match format!("{}:443", peer_ip).parse() {
        Ok(a) => a,
        Err(e) => return H3Outcome::err(format!("peer_ip 解析失败: {}", e)),
    };
    let local = if peer.is_ipv4() {
        SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0)
    } else {
        SocketAddr::new(IpAddr::V6(Ipv6Addr::UNSPECIFIED), 0)
    };

    let mut config = match quiche::Config::new(quiche::PROTOCOL_VERSION) {
        Ok(c) => c,
        Err(e) => return H3Outcome::err(format!("Config::new 失败: {:?}", e)),
    };
    config.verify_peer(true);
    if let Err(e) = config.load_verify_locations_from_file(ca_path) {
        return H3Outcome::err(format!("加载 CA 失败({}): {:?}", ca_path, e));
    }
    // ALPN 必须传裸名 h3
    if let Err(e) = config.set_application_protos(&[b"h3"]) {
        return H3Outcome::err(format!("set_alpn 失败: {:?}", e));
    }
    config.set_max_idle_timeout(30_000);
    config.set_max_recv_udp_payload_size(1350);
    config.set_initial_max_data(10_000_000);
    config.set_initial_max_stream_data_bidi_local(1_000_000);
    config.set_initial_max_stream_data_bidi_remote(1_000_000);
    config.set_initial_max_stream_data_uni(1_000_000);
    config.set_initial_max_streams_bidi(100);
    config.set_initial_max_streams_uni(100);
    config.set_disable_active_migration(true);
    config.set_cc_algorithm(quiche::CongestionControlAlgorithm::CUBIC);
    if let Some(ech_bytes) = ech {
        config.set_ech_config_list(ech_bytes);
    }

    let socket = match std::net::UdpSocket::bind(local) {
        Ok(s) => s,
        Err(e) => return H3Outcome::err(format!("绑定 UDP 失败: {}", e)),
    };
    if let Err(e) = socket.connect(peer) {
        return H3Outcome::err(format!("UDP connect 失败: {}", e));
    }
    let local_addr = socket.local_addr().unwrap();

    let scid_bytes: [u8; 16] = rand::random();
    let scid = quiche::ConnectionId::from_vec(scid_bytes.to_vec());
    let mut conn = match quiche::connect(Some(host), &scid, local_addr, peer, &mut config) {
        Ok(c) => c,
        Err(e) => return H3Outcome::err(format!("建立 QUIC 连接失败: {:?}", e)),
    };

    // ---- 握手 ----
    let hs_start = Instant::now();
    let mut buf = [0u8; 65535];
    let mut sent: u64 = 0;
    let mut recv: u64 = 0;
    let mut hs_err: Option<String> = None;

    loop {
        while let Ok((len, info)) = conn.send(&mut buf) {
            if socket.send_to(&buf[..len], info.to).is_ok() {
                sent += 1;
            }
        }
        if conn.is_established() {
            break;
        }
        if let Some(e) = conn.peer_error() {
            hs_err = Some(format!("对端错误: {:?}", e));
            break;
        }
        if hs_start.elapsed() >= connect_timeout {
            hs_err = Some("握手超时".to_string());
            break;
        }
        if conn.is_closed() {
            hs_err = Some("连接在握手期间被关闭".to_string());
            break;
        }
        let remain = connect_timeout.saturating_sub(hs_start.elapsed());
        let _ = socket.set_read_timeout(Some(remain.min(Duration::from_millis(500))));
        match socket.recv_from(&mut buf) {
            Ok((len, from)) => {
                recv += 1;
                let info = quiche::RecvInfo { from, to: local_addr };
                if let Err(e) = conn.recv(&mut buf[..len], info) {
                    hs_err = Some(format!("QUIC recv 出错: {:?}", e));
                    break;
                }
            }
            Err(ref e)
                if e.kind() == std::io::ErrorKind::WouldBlock
                    || e.kind() == std::io::ErrorKind::TimedOut => {}
            Err(e) => {
                hs_err = Some(format!("UDP recv 出错: {}", e));
                break;
            }
        }
        conn.on_timeout();
    }

    let hs_ms = hs_start.elapsed().as_millis();
    if let Some(err) = hs_err {
        let mut o = H3Outcome::err(format!("{}（握手 {}ms）", err, hs_ms));
        o.hs_ms = hs_ms;
        o.sent = sent;
        o.recv = recv;
        o.peer_err = conn.peer_error().map(|e| format!("{:?}", e)).unwrap_or_default();
        return o;
    }

    let alpn = String::from_utf8_lossy(conn.application_proto()).to_string();
    let ech_override = conn.ech_name_override();
    let ech_retry_len = conn.ech_retry_configs().len();

    // ---- HTTP/3 请求 ----
    let h3_config = match H3Config::new() {
        Ok(c) => c,
        Err(e) => return H3Outcome::err(format!("H3Config 失败: {:?}", e)),
    };
    let mut h3 = match H3Connection::with_transport(&mut conn, &h3_config) {
        Ok(h) => h,
        Err(e) => return H3Outcome::err(format!("H3 连接失败: {:?}", e)),
    };

    let mut hdrs = vec![
        Header::new(b":method", b"GET"),
        Header::new(b":scheme", b"https"),
        Header::new(b":authority", host.as_bytes()),
        Header::new(b":path", path.as_bytes()),
        Header::new(
            b"user-agent",
            b"Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36",
        ),
        Header::new(b"accept", b"image/avif,image/webp,*/*"),
    ];
    if let Some(r) = referer {
        hdrs.push(Header::new(b"referer", r.as_bytes()));
    }

    let req_start = Instant::now();
    let _stream_id = match h3.send_request(&mut conn, &hdrs, true) {
        Ok(id) => id,
        Err(e) => return H3Outcome::err(format!("H3 send_request 失败: {:?}", e)),
    };

    let mut status: u16 = 0;
    let mut body_len = 0usize;
    let mut body: Vec<u8> = Vec::new();
    let mut finished = false;
    let mut first_byte_ms: u128 = 0;
    let mut cf_ray = String::new();
    let mut server = String::new();

    while !finished {
        while let Ok((len, info)) = conn.send(&mut buf) {
            if socket.send_to(&buf[..len], info.to).is_ok() {
                sent += 1;
            }
        }
        loop {
            match h3.poll(&mut conn) {
                Ok((sid, ev)) => match ev {
                    quiche::h3::Event::Headers { list, .. } => {
                        for h in list.iter() {
                            let k = String::from_utf8_lossy(NameValue::name(h)).to_lowercase();
                            let v = String::from_utf8_lossy(NameValue::value(h)).to_string();
                            if k == ":status" {
                                status = v.parse().unwrap_or(0);
                            } else if k == "cf-ray" {
                                cf_ray = v;
                            } else if k == "server" {
                                server = v;
                            }
                        }
                    }
                    quiche::h3::Event::Data => {
                        let mut d = vec![0u8; 65535];
                        match h3.recv_body(&mut conn, sid, &mut d) {
                            Ok(n) => {
                                if first_byte_ms == 0 {
                                    first_byte_ms = req_start.elapsed().as_millis();
                                }
                                body_len += n;
                                body.extend_from_slice(&d[..n]);
                            }
                            Err(quiche::h3::Error::Done) => {}
                            Err(e) => {
                                let mut o = H3Outcome::err(format!("读 body 出错: {:?}", e));
                                o.hs_ms = hs_ms;
                                o.sent = sent;
                                o.recv = recv;
                                return o;
                            }
                        }
                    }
                    quiche::h3::Event::Finished => {
                        finished = true;
                        break;
                    }
                    quiche::h3::Event::Reset(e) => {
                        let mut o = H3Outcome::err(format!("流被重置: {}", e));
                        o.hs_ms = hs_ms;
                        o.sent = sent;
                        o.recv = recv;
                        return o;
                    }
                    quiche::h3::Event::PriorityUpdate => {}
                    quiche::h3::Event::GoAway => {}
                },
                Err(quiche::h3::Error::Done) => break,
                Err(e) => {
                    let mut o = H3Outcome::err(format!("H3 poll 出错: {:?}", e));
                    o.hs_ms = hs_ms;
                    o.sent = sent;
                    o.recv = recv;
                    return o;
                }
            }
        }
        if finished {
            break;
        }
        if req_start.elapsed() >= request_timeout {
            let mut o = H3Outcome::err("请求超时".to_string());
            o.hs_ms = hs_ms;
            o.sent = sent;
            o.recv = recv;
            return o;
        }
        let remain = request_timeout.saturating_sub(req_start.elapsed());
        let _ = socket.set_read_timeout(Some(remain.min(Duration::from_millis(500))));
        match socket.recv_from(&mut buf) {
            Ok((len, from)) => {
                recv += 1;
                let info = quiche::RecvInfo { from, to: local_addr };
                let _ = conn.recv(&mut buf[..len], info);
            }
            Err(_) => {}
        }
        conn.on_timeout();
    }

    H3Outcome {
        hs_ms,
        established: true,
        alpn,
        ech_override,
        ech_retry_len,
        status,
        first_byte_ms,
        total_ms: req_start.elapsed().as_millis(),
        body_len,
        body,
        cf_ray,
        server,
        error: None,
        sent,
        recv,
        peer_err: conn.peer_error().map(|e| format!("{:?}", e)).unwrap_or_default(),
    }
}

// ---------------------------------------------------------------- JNI

fn json_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 8);
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out
}

impl H3Outcome {
    /// 诊断三件套（sent/recv/peer_err）+ 结果，全部回给 Kotlin。
    pub fn to_json(&self) -> String {
        format!(
            "{{\"ok\":{},\"hs_ms\":{},\"established\":{},\"alpn\":\"{}\",\"ech_override\":{},\"ech_retry_len\":{},\"status\":{},\"first_byte_ms\":{},\"total_ms\":{},\"body_len\":{},\"cf_ray\":\"{}\",\"server\":\"{}\",\"sent\":{},\"recv\":{},\"peer_err\":\"{}\",\"error\":{}}}",
            self.error.is_none(),
            self.hs_ms,
            self.established,
            json_escape(&self.alpn),
            match &self.ech_override {
                Some(n) => format!("\"{}\"", json_escape(n)),
                None => "null".to_string(),
            },
            self.ech_retry_len,
            self.status,
            self.first_byte_ms,
            self.total_ms,
            self.body_len,
            json_escape(&self.cf_ray),
            json_escape(&self.server),
            self.sent,
            self.recv,
            json_escape(&self.peer_err),
            match &self.error {
                Some(e) => format!("\"{}\"", json_escape(e)),
                None => "null".to_string(),
            }
        )
    }
}

mod android_jni {
    use super::*;
    use jni::objects::{JClass, JString};
    use jni::sys::jstring;
    use jni::JNIEnv;
    use std::time::Duration;

    fn jstr(env: &mut JNIEnv, s: JString) -> String {
        env.get_string(&s)
            .map(|v| v.to_string_lossy().into_owned())
            .unwrap_or_default()
    }

    #[no_mangle]
    pub extern "system" fn Java_com_yenaly_han1meviewer_logic_network_ech_HyEchH3_h3Fetch(
        mut env: JNIEnv,
        _class: JClass,
        host: JString,
        peer_ip: JString,
        ech_b64: JString,
        path: JString,
        referer: JString,
        ca_path: JString,
        out_file: JString,
    ) -> jstring {
        let host = jstr(&mut env, host);
        let peer_ip = jstr(&mut env, peer_ip);
        let ech_b64 = jstr(&mut env, ech_b64);
        let path = jstr(&mut env, path);
        let referer = jstr(&mut env, referer);
        let ca_path = jstr(&mut env, ca_path);
        let out_file = jstr(&mut env, out_file);

        let ech: Option<Vec<u8>> = if ech_b64.is_empty() {
            None
        } else {
            match base64_decode(&ech_b64) {
                Ok(v) => Some(v),
                Err(e) => {
                    let o = H3Outcome::err(format!("ECH base64 解码失败: {}", e));
                    let out = env.new_string(o.to_json()).unwrap();
                    return out.into_raw();
                }
            }
        };

        let outcome = h3_fetch(
            &host,
            &peer_ip,
            ech.as_deref(),
            if path.is_empty() { "/" } else { &path },
            if referer.is_empty() { None } else { Some(&referer) },
            if ca_path.is_empty() { "/system/etc/security/cacerts" } else { &ca_path },
            Duration::from_secs(8),
            Duration::from_secs(25),
        );

        // 响应体落盘给 Kotlin 读（避免走 base64 再解码，也不占双份内存）
        let mut saved = String::new();
        if outcome.status == 200 && !outcome.body.is_empty() && !out_file.is_empty() {
            match std::fs::write(&out_file, &outcome.body) {
                Ok(_) => saved = out_file.clone(),
                Err(e) => saved = format!("ERR:{}", e),
            }
        }
        let mut json = outcome.to_json();
        if !saved.is_empty() {
            json = json.trim_end_matches('}').to_string()
                + &format!(",\"saved_to\":\"{}\"}}", saved);
        }
        let out = env.new_string(json).unwrap();
        out.into_raw()
    }

    /// 极简 base64 解码（标准表 + padding），避免为一个探针再引入依赖。
    fn base64_decode(s: &str) -> Result<Vec<u8>, String> {
        const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        let mut lut = [255u8; 256];
        for (i, c) in T.iter().enumerate() {
            lut[*c as usize] = i as u8;
        }
        let mut out = Vec::new();
        let mut acc: u32 = 0;
        let mut bits = 0u32;
        for c in s.bytes() {
            if c == b'=' || c == b'\n' || c == b'\r' || c == b' ' {
                continue;
            }
            let v = lut[c as usize];
            if v == 255 {
                return Err(format!("非法字符 {}", c as char));
            }
            acc = (acc << 6) | v as u32;
            bits += 6;
            if bits >= 8 {
                bits -= 8;
                out.push(((acc >> bits) & 0xFF) as u8);
            }
        }
        Ok(out)
    }
}
