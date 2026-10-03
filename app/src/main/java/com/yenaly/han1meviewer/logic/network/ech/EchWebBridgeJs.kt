package com.yenaly.han1meviewer.logic.network.ech

/**
 * 注入到受保护域名页面的传输桥（JS 侧）。
 *
 * 与原生 [EchWebBridge] 配对：把**非 GET** 请求（form / fetch / XHR）转交原生
 * Conscrypt + ECH 通道代发。GET 不在这里处理 —— 它由 `shouldInterceptRequest`
 * 走 [HyWebViewHelper]，两条路各管一半。
 *
 * 三条自我约束：
 * 1. 只碰受保护域名；其余请求原样走浏览器
 * 2. 只碰非 GET；GET/HEAD 一律不碰
 * 3. 任何异常都不能把页面卡死：要么按浏览器原样继续，要么给一个**可读的失败响应**
 */
internal object EchWebBridgeJs {

    /** 把受保护域名名单注入脚本（名单只有一份：[EchHosts] 同源的 `HANIME_HOSTNAME`） */
    fun script(protectedHosts: Array<String>): String =
        TEMPLATE.replace(
            "__ECH_PROTECTED__",
            protectedHosts.joinToString(",", "[", "]") { "\"$it\"" }
        )

    private val TEMPLATE = """
(function () {
  if (window.__echBridgeInstalled) return;
  if (!window.EchBridge) return;                 // 原生桥没挂上：不接管，保持原行为
  window.__echBridgeInstalled = true;

  var PROTECTED = __ECH_PROTECTED__;
  var pending = {};
  var seq = 0;

  function log(msg) { try { window.EchBridge.log(String(msg).slice(0, 300)); } catch (e) {} }

  function hostOf(u) {
    try { return new URL(u, location.href).hostname.toLowerCase(); } catch (e) { return ''; }
  }
  function isProtected(u) {
    var h = hostOf(u);
    if (!h) return false;
    for (var i = 0; i < PROTECTED.length; i++) {
      var d = PROTECTED[i];
      if (h === d) return true;
      if (h.length > d.length + 1 && h.slice(-(d.length + 1)) === '.' + d) return true;
    }
    return false;
  }

  function b64ToBytes(b64) {
    var bin = atob(b64 || '');
    var out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }
  function bytesToB64(buf) {
    var bytes = new Uint8Array(buf);
    var s = '';
    for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return btoa(s);
  }
  function textToB64(str) { return bytesToB64(new TextEncoder().encode(str).buffer); }
  function b64ToText(b64) {
    var bytes = b64ToBytes(b64);
    try { return new TextDecoder('utf-8').decode(bytes); }
    catch (e) { return ''; }
  }

  /** 原生侧回调用 */
  window.__echBridgeResolve = function (id, payloadJson) {
    var p = pending[id];
    if (!p) return;
    delete pending[id];
    var payload;
    try { payload = JSON.parse(payloadJson); }
    catch (e) { payload = { ok: false, status: 502, error: '桥回调解析失败' }; }
    p(payload);
  };

  function nativeSend(method, url, headers, bodyB64) {
    return new Promise(function (resolve) {
      var id = String(++seq);
      pending[id] = resolve;
      try {
        window.EchBridge.send(id, method, url, JSON.stringify(headers || {}), bodyB64 || '', location.href);
      } catch (e) {
        delete pending[id];
        resolve({ ok: false, status: 502, error: '桥调用失败: ' + e });
        return;
      }
      setTimeout(function () {
        if (pending[id]) { delete pending[id]; resolve({ ok: false, status: 502, error: '桥超时（30s）' }); }
      }, 30000);
    });
  }

  /** 把 body 搬成 base64 并推断 Content-Type；搬不了返回 null，让调用方保持原行为 */
  function serializeBody(body) {
    try {
      if (body === null || body === undefined) return { b64: '', ctype: null };
      if (typeof body === 'string') return { b64: textToB64(body), ctype: 'text/plain;charset=UTF-8' };
      if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) {
        return { b64: textToB64(body.toString()), ctype: 'application/x-www-form-urlencoded;charset=UTF-8' };
      }
      if (typeof FormData !== 'undefined' && body instanceof FormData) {
        var hasFile = false;
        body.forEach(function (v) { if (typeof File !== 'undefined' && v instanceof File) hasFile = true; });
        if (hasFile) return null;
        return { b64: textToB64(new URLSearchParams(body).toString()), ctype: 'application/x-www-form-urlencoded;charset=UTF-8' };
      }
      if (body instanceof ArrayBuffer) return { b64: bytesToB64(body), ctype: null };
      if (body && typeof body.byteLength === 'number' && body.buffer instanceof ArrayBuffer) {
        return { b64: bytesToB64(body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength)), ctype: null };
      }
      return null;
    } catch (e) { return null; }
  }

  /** 按浏览器语义补 Content-Type（页面自己设过就不动） */
  function applyCtype(headers, ctype) {
    if (!ctype) return;
    for (var k in headers) if (headers.hasOwnProperty(k) && k.toLowerCase() === 'content-type') return;
    headers['Content-Type'] = ctype;
  }

  function headersToObject(h) {
    var out = {};
    try {
      if (!h) return out;
      if (typeof h.forEach === 'function') h.forEach(function (v, k) { out[k] = v; });
      else if (typeof Headers !== 'undefined' && h instanceof Headers) h.forEach(function (v, k) { out[k] = v; });
      else if (typeof h === 'object') { for (var k in h) if (h.hasOwnProperty(k)) out[k] = h[k]; }
    } catch (e) {}
    return out;
  }

  // ---------------------------------------------------------------- fetch
  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function (input, init) {
      var url = null, method = 'GET', headers = {};
      try {
        if (typeof input === 'string') url = input;
        else if (input && input.url) { url = input.url; method = String(input.method || 'GET').toUpperCase(); headers = headersToObject(input.headers); }
        if (init) {
          if (init.method) method = String(init.method).toUpperCase();
          if (init.headers) { var ih = headersToObject(init.headers); for (var k in ih) if (ih.hasOwnProperty(k)) headers[k] = ih[k]; }
        }
        if (url && method !== 'GET' && method !== 'HEAD' && isProtected(url)) {
          var ser = serializeBody(init ? init.body : null);
          if (ser === null) {
            log('fetch 保持原样（body 类型不可序列化）: ' + method + ' ' + url);
          } else {
            applyCtype(headers, ser.ctype);
            return nativeSend(method, url, headers, ser.b64).then(function (r) {
              var text = r.ok ? b64ToText(r.bodyB64) : (r.error || 'ECH 桥失败');
              var hdrs = r.headers || {};
              if (!r.ok) hdrs = { 'Content-Type': 'text/plain; charset=utf-8' };
              return new Response(r.ok ? b64ToBytes(r.bodyB64) : new TextEncoder().encode(text).buffer, {
                status: r.ok ? r.status : (r.status || 502),
                statusText: r.statusText || '',
                headers: hdrs
              });
            });
          }
        }
      } catch (e) { log('fetch hook 异常，保持原行为: ' + e); }
      return origFetch.apply(this, arguments);
    };
  }

  // ---------------------------------------------------------------- XHR
  var OrigXHR = window.XMLHttpRequest;
  if (OrigXHR && OrigXHR.prototype && OrigXHR.prototype.open) {
    var origOpen = OrigXHR.prototype.open;
    var origSetHeader = OrigXHR.prototype.setRequestHeader;
    var origSend = OrigXHR.prototype.send;
    var origAbort = OrigXHR.prototype.abort;

    OrigXHR.prototype.open = function (method, url, async) {
      this.__ech = {
        method: String(method || 'GET').toUpperCase(),
        url: String(url),
        headers: {},
        async: async !== false,
        aborted: false
      };
      return origOpen.apply(this, arguments);
    };
    OrigXHR.prototype.setRequestHeader = function (k, v) {
      try { if (this.__ech) this.__ech.headers[k] = v; } catch (e) {}
      return origSetHeader.apply(this, arguments);
    };
    OrigXHR.prototype.abort = function () {
      try { if (this.__ech) this.__ech.aborted = true; } catch (e) {}
      return origAbort.apply(this, arguments);
    };
    OrigXHR.prototype.send = function (body) {
      var st = this.__ech;
      try {
        if (st && st.async && st.method !== 'GET' && st.method !== 'HEAD' && isProtected(st.url)) {
          var ser = serializeBody(body);
          if (ser === null) {
            log('XHR 保持原样（body 类型不可序列化）: ' + st.method + ' ' + st.url);
          } else {
            var xhr = this;
            applyCtype(st.headers, ser.ctype);
            nativeSend(st.method, st.url, st.headers, ser.b64).then(function (r) { fakeRespond(xhr, r, st); });
            return;
          }
        }
      } catch (e) { log('XHR hook 异常，保持原行为: ' + e); }
      return origSend.apply(this, arguments);
    };
  }

  function define(obj, name, value) {
    try { Object.defineProperty(obj, name, { configurable: true, get: function () { return value; } }); }
    catch (e) {}
  }

  /** 把原生代发的结果伪装成一个已完成的 XHR（调用方完全无感） */
  function fakeRespond(xhr, r, st) {
    if (st.aborted) return;
    var text = r.ok ? b64ToText(r.bodyB64) : (r.error || 'ECH 桥失败');
    var status = r.ok ? r.status : (r.status || 502);
    var hdrs = r.headers || {};
    var flat = '';
    for (var k in hdrs) if (hdrs.hasOwnProperty(k)) flat += k + ': ' + hdrs[k] + '\r\n';

    var type = '';
    try { type = xhr.responseType || ''; } catch (e) {}
    var responseValue = text;
    try {
      if (type === 'json') responseValue = text ? JSON.parse(text) : null;
      else if (type === 'arraybuffer') responseValue = b64ToBytes(r.bodyB64 || '').buffer;
      else if (type === 'blob') responseValue = new Blob([b64ToBytes(r.bodyB64 || '')]);
    } catch (e) { responseValue = text; }

    define(xhr, 'readyState', 4);
    define(xhr, 'status', status);
    define(xhr, 'statusText', r.statusText || '');
    define(xhr, 'responseURL', r.url || st.url);
    define(xhr, 'responseText', text);
    define(xhr, 'response', responseValue);
    define(xhr, 'getAllResponseHeaders', function () { return flat; });
    define(xhr, 'getResponseHeader', function (name) {
      if (!name) return null;
      for (var k in hdrs) if (hdrs.hasOwnProperty(k) && k.toLowerCase() === String(name).toLowerCase()) return hdrs[k];
      return null;
    });
    try { xhr.dispatchEvent(makeEvent('readystatechange')); } catch (e) {}
    fire(xhr, 'load');
    fire(xhr, 'loadend');
  }

  /** 某些实现没有 ProgressEvent —— 退化成 Event，别让事件发不出去 */
  function makeEvent(type) {
    try { return new ProgressEvent(type); } catch (e) { return new Event(type); }
  }
  function fire(xhr, type) {
    try { xhr.dispatchEvent(makeEvent(type)); } catch (e) {}
  }

  // ---------------------------------------------------------------- 表单提交（登录等）
  document.addEventListener('submit', function (e) {
    try {
      var f = e.target;
      if (!f || f.tagName !== 'FORM') return;
      var action = f.action || location.href;
      if (!isProtected(action)) return;
      if (!f.querySelector('input[type=password]')) return;     // 只接管登录类表单，别误伤搜索
      if (f.querySelector('input[type=file]')) return;
      var enc = String(f.getAttribute('enctype') || '').toLowerCase();
      if (enc.indexOf('multipart') >= 0) return;
      e.preventDefault();
      e.stopPropagation();
      var body = new URLSearchParams(new FormData(f)).toString();
      log('form → 桥: ' + action + ' (' + body.length + 'B)');
      window.EchBridge.postForm(action, textToB64(body), location.href);
    } catch (err) { log('form hook 异常: ' + err); }
  }, true);

  log('传输桥已装载: ' + PROTECTED.join(','));
})();
""".trimIndent()
}
