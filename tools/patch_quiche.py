#!/usr/bin/env python3
"""给 quiche 的 vendored BoringSSL 暴露 ECH 客户端入口。

用法: patch_quiche.py <quiche 源码目录>
背景: quiche 0.22 整个 crate 没有任何 ECH 代码，但它 vendor 的 BoringSSL
      有完整 ECH 客户端实现（deps/boringssl/src/ssl/encrypted_client_hello.cc）。
      这里只做"暴露"，约 40 行。逐条断言命中次数，避免静默改坏。
"""
import sys, os

def apply(path, old, new, count=1):
    s = open(path, encoding="utf-8").read()
    n = s.count(old)
    if n != count:
        raise SystemExit(f"✗ 命中 {n} 次（期望 {count}）: {os.path.basename(path)} :: {old[:60]!r}")
    open(path, "w", encoding="utf-8").write(s.replace(old, new, 1))
    print(f"✓ {os.path.basename(path)}: {old.strip()[:56]}")

def main(root):
    tls = os.path.join(root, "src/tls/mod.rs")
    lib = os.path.join(root, "src/lib.rs")

    apply(tls,
        "    fn SSL_set_quic_use_legacy_codepoint(ssl: *mut SSL, use_legacy: c_int);",
        """    fn SSL_set_quic_use_legacy_codepoint(ssl: *mut SSL, use_legacy: c_int);

    // ---- ECH 补丁：暴露 BoringSSL 的 ECH 客户端入口 ----
    fn SSL_set1_ech_config_list(
        ssl: *mut SSL, ech_config_list: *const u8, ech_config_list_len: usize,
    ) -> c_int;

    fn SSL_get0_ech_name_override(
        ssl: *const SSL, out_name: *mut *const c_char, out_name_len: *mut usize,
    );

    fn SSL_get0_ech_retry_configs(
        ssl: *const SSL, out_retry_configs: *mut *const u8, out_retry_configs_len: *mut usize,
    );""")

    apply(tls,
        "    pub fn set_quic_transport_params(&mut self, buf: &[u8]) -> Result<()> {",
        """    /// ECH 补丁：交给 BoringSSL，握手即发送 ECH。
    pub fn set_ech_config_list(&mut self, ech_config_list: &[u8]) -> Result<()> {
        let rc = unsafe {
            SSL_set1_ech_config_list(
                self.as_mut_ptr(),
                ech_config_list.as_ptr(),
                ech_config_list.len(),
            )
        };
        self.map_result_ssl(rc)
    }

    /// ECH 补丁：ECH 被拒时返回 public name，否则为空。
    pub fn ech_name_override(&self) -> Option<String> {
        let mut ptr: *const c_char = ptr::null();
        let mut len: usize = 0;
        unsafe { SSL_get0_ech_name_override(self.as_ptr(), &mut ptr, &mut len) };
        if len == 0 || ptr.is_null() {
            return None;
        }
        let bytes = unsafe { std::slice::from_raw_parts(ptr as *const u8, len) };
        Some(String::from_utf8_lossy(bytes).to_string())
    }

    /// ECH 补丁：被拒时服务端给出的回退配置。
    pub fn ech_retry_configs(&self) -> Vec<u8> {
        let mut ptr: *const u8 = ptr::null();
        let mut len: usize = 0;
        unsafe { SSL_get0_ech_retry_configs(self.as_ptr(), &mut ptr, &mut len) };
        if len == 0 || ptr.is_null() {
            return Vec::new();
        }
        unsafe { std::slice::from_raw_parts(ptr, len).to_vec() }
    }

    pub fn set_quic_transport_params(&mut self, buf: &[u8]) -> Result<()> {""")

    apply(lib, "    tls_ctx: tls::Context,",
          "    tls_ctx: tls::Context,\n\n    ech_config_list: Option<Vec<u8>>,")
    apply(lib, "            tls_ctx,", "            tls_ctx,\n            ech_config_list: None,")
    apply(lib,
        "    fn with_tls_ctx(version: u32, tls_ctx: tls::Context) -> Result<Config> {",
        """    /// ECH 补丁：设置客户端 ECHConfigList。
    pub fn set_ech_config_list(&mut self, ech_config_list: &[u8]) {
        self.ech_config_list = Some(ech_config_list.to_vec());
    }

    fn with_tls_ctx(version: u32, tls_ctx: tls::Context) -> Result<Config> {""")
    apply(lib,
        "        let tls = config.tls_ctx.new_handshake()?;",
        """        let mut tls = config.tls_ctx.new_handshake()?;

        // ECH 补丁：握手前挂上 ECH 配置
        if let Some(ref ech) = config.ech_config_list {
            tls.set_ech_config_list(ech)?;
        }""")
    apply(lib,
        "    pub fn server_name(&self) -> Option<&str> {",
        """    /// ECH 补丁：握手后查看 ECH 结果。
    pub fn ech_name_override(&self) -> Option<String> {
        self.handshake.ech_name_override()
    }

    /// ECH 补丁：被拒时的回退配置。
    pub fn ech_retry_configs(&self) -> Vec<u8> {
        self.handshake.ech_retry_configs()
    }

    pub fn server_name(&self) -> Option<&str> {""")
    print("quiche ECH 补丁完成")

if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    main(sys.argv[1])
