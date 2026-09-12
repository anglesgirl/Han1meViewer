// WebView 传输桥 JS 的本地验证（不需要 Android）：模拟浏览器环境跑三种接管路径
const fs = require('fs');
const js = fs.readFileSync(process.argv[2] || '/tmp/ech_bridge.js', 'utf8');

const sent = [], formCalls = [], logs = [], passthrough = [];
let submitHandler = null;

global.location = { href: 'https://hanime1.me/login' };
global.window = global;
global.document = { addEventListener: (t, fn) => { if (t === 'submit') submitHandler = fn; } };

class FakeXHR {
  constructor() { this.readyState = 0; this.status = 0; this.responseText = ''; this.responseType = ''; this._l = {}; }
  addEventListener(t, fn) { (this._l[t] = this._l[t] || []).push(fn); }
  dispatchEvent(e) { (this._l[e.type] || []).forEach(f => f.call(this, e)); const h = this['on' + e.type]; if (h) h.call(this, e); return true; }
  open(m, u, a) { this._open = { m, u, a }; this.readyState = 1; }
  setRequestHeader(k, v) { (this._h = this._h || {})[k] = v; }
  send(b) { this._native = b; }
  abort() { this._aborted = true; }
}
global.XMLHttpRequest = FakeXHR;
class FakeFormData { constructor(f) { this._f = f; } }
global.FormData = FakeFormData;

global.EchBridge = {
  send(id, method, url, headersJson, bodyB64, pageUrl) {
    sent.push({ id, method, url, headers: JSON.parse(headersJson), body: Buffer.from(bodyB64, 'base64').toString(), pageUrl });
    setTimeout(() => window.__echBridgeResolve(id, JSON.stringify({
      ok: true, status: 200, statusText: 'OK', url,
      headers: { 'Content-Type': 'application/json' },
      bodyB64: Buffer.from('{"ok":1}').toString('base64')
    })), 0);
  },
  postForm(url, bodyB64, pageUrl) { formCalls.push({ url, body: Buffer.from(bodyB64, 'base64').toString(), pageUrl }); },
  log(m) { logs.push(m); }
};
// 原生 fetch 桩：绝不真发网络，只记录"是否放行"
global.fetch = function (input) { passthrough.push(input); return Promise.resolve(new Response('orig', { status: 200 })); };

eval(js);

const results = [];
function check(name, cond, extra) { results.push({ name, ok: !!cond, extra }); }
const tick = () => new Promise(r => setTimeout(r, 20));

(async () => {
  // 1) fetch POST 到受保护域名 → 走桥
  const r1 = await fetch('https://hanime1.me/api/like', { method: 'POST', body: 'a=1' });
  check('fetch POST 受保护域名：被桥接管', sent.length === 1 && sent[0].method === 'POST');
  check('fetch POST：body 原样搬过去', sent[0] && sent[0].body === 'a=1', sent[0] && sent[0].body);
  check('fetch POST：按浏览器语义补 Content-Type', sent[0] && sent[0].headers['Content-Type'] === 'text/plain;charset=UTF-8', sent[0] && sent[0].headers['Content-Type']);
  check('fetch POST：pageUrl 传的是当前页', sent[0] && sent[0].pageUrl === 'https://hanime1.me/login');
  const t1 = await r1.text();
  check('fetch POST：返回真实 Response（状态/正文）', r1.status === 200 && t1 === '{"ok":1}', r1.status + ' ' + t1);

  // 2) fetch POST 到非保护域名 → 放行
  await fetch('https://other.com/api', { method: 'POST', body: 'a=1' });
  check('fetch POST 非保护域名：放行给原生', passthrough.length === 1);

  // 3) fetch GET 受保护域名 → 放行（GET 由 shouldInterceptRequest 管）
  await fetch('https://hanime1.me/page');
  check('fetch GET：不接管（交给 shouldInterceptRequest）', passthrough.length === 2);

  // 4) XHR POST → 接管并伪装响应
  const x = new XMLHttpRequest();
  const events = [];
  x.addEventListener('load', () => events.push('load'));
  x.addEventListener('readystatechange', () => events.push('rs:' + x.readyState));
  x.open('POST', 'https://hanime1.me/api/comment');
  x.setRequestHeader('Content-Type', 'application/json');
  x.send('{"a":1}');
  await tick();
  check('XHR POST：被桥接管', sent.length === 2 && sent[1].body === '{"a":1}', sent[1] && sent[1].body);
  check('XHR POST：页面设过的 Content-Type 不被覆盖', sent[1] && sent[1].headers['Content-Type'] === 'application/json', sent[1] && sent[1].headers['Content-Type']);
  check('XHR：status/readyState/responseText 伪装正确', x.status === 200 && x.readyState === 4 && x.responseText === '{"ok":1}', x.status + '/' + x.readyState + '/' + x.responseText);
  check('XHR：onload / readystatechange 事件都触发', events.includes('load') && events.includes('rs:4'), JSON.stringify(events));
  check('XHR：未走原生 send（没有明文外泄）', x._native === undefined, String(x._native));

  // 5) 登录表单提交 → 接管
  const mkForm = (hasPwd) => ({
    tagName: 'FORM', action: 'https://hanime1.me/login',
    getAttribute: () => 'application/x-www-form-urlencoded',
    querySelector: (sel) => (hasPwd && sel === 'input[type=password]') ? {} : null,
  });
  let prevented = false;
  submitHandler({ target: mkForm(true), preventDefault: () => { prevented = true; }, stopPropagation: () => {} });
  check('登录表单提交：被接管 + preventDefault', formCalls.length === 1 && prevented, JSON.stringify(formCalls));
  check('表单：action 原样传给原生', formCalls[0] && formCalls[0].url === 'https://hanime1.me/login');

  // 6) 非登录表单（无密码框）→ 不接管
  submitHandler({ target: mkForm(false), preventDefault: () => { prevented = true; }, stopPropagation: () => {} });
  check('无密码框的表单：不接管（别误伤搜索框）', formCalls.length === 1);

  // 输出
  let bad = 0;
  for (const r of results) { if (!r.ok) bad++; console.log((r.ok ? 'PASS  ' : 'FAIL  ') + r.name + (r.ok ? '' : '  → ' + r.extra)); }
  console.log('\n结果: ' + (results.length - bad) + '/' + results.length + ' 通过');
  if (logs.length) console.log('页面日志: ' + JSON.stringify(logs.slice(0, 5)));
  process.exit(bad ? 1 : 0);
})();
