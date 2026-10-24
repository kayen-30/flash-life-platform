// 运行完整页面脚本，故意让 fetch 忽略 AbortSignal，验证迟到回复也不能串号或串店。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const sourcePath = path.resolve(process.argv[2] || path.join(__dirname, '../js/lifeflash.js'));
const code = fs.readFileSync(sourcePath, 'utf8');
const tick = async () => { for (let i = 0; i < 6; i++) await new Promise(setImmediate); };
function response(data, status = 200) {
  return { ok: status === 200, status, json: async () => ({ success: status === 200, data }) };
}
function app(token = 'synthetic-A', hash = '#/shop?id=21') {
  const dom = {}, win = {}, storage = new Map(token ? [['token', token]] : []), requests = [], chat = [];
  const markup = { value: '' };
  let locationHash = hash, holdShop = false, holdLogout = false;
  const location = { search: '', get hash() { return locationHash; }, set hash(value) {
    locationHash = value.startsWith('#') ? value : '#' + value;
    queueMicrotask(() => win.hashchange?.());
  } };
  const sessionStorage = { getItem: key => storage.get(key) || null, setItem: (k, v) => storage.set(k, String(v)), removeItem: key => storage.delete(key) };
  class FormData { constructor(form) { this.values = Object.entries(form.values || {}); } get(key) { return new Map(this.values).get(key); } entries() { return this.values[Symbol.iterator](); } }
  const document = { addEventListener: (name, fn) => { dom[name] = fn; }, getElementById: () => null };
  const window = { location, history: { back() {} }, addEventListener: (name, fn) => { win[name] = fn; }, setTimeout() {} };
  const context = { Vue: { reactive: value => value, ref: () => markup, nextTick() {}, createApp: () => ({ mount() {} }) }, window, document, sessionStorage, FormData, URLSearchParams, URL, AbortController, console,
    fetch: (url, opts) => {
      const request = { url, opts, body: opts.body ? JSON.parse(opts.body) : null };
      requests.push(request);
      if (url.endsWith('/chat')) return new Promise(resolve => { request.resolve = resolve; chat.push(request); });
      if (url.endsWith('/logout') && holdLogout) return new Promise(resolve => { request.resolve = resolve; });
      if (/\/shop\/\d+$/.test(url)) {
        if (holdShop) return new Promise(resolve => { request.resolve = resolve; });
        const id = Number(url.split('/').at(-1));
        return Promise.resolve(response({ id, name: '合成门店' + id }));
      }
      if (url.endsWith('/user/me')) return Promise.resolve(response({ id: token === 'synthetic-A' ? 1 : 2, nickName: '合成账号' }));
      return Promise.resolve(response([]));
    } };
  vm.runInNewContext(code, context, { filename: sourcePath });
  const click = action => dom.click({ target: { closest: () => ({ dataset: { action } }) }, preventDefault() {} });
  const submit = text => dom.submit({ target: { closest: () => ({ dataset: { form: 'ai' }, values: { question: text } }) }, preventDefault() {} });
  const reply = (index, answer, extra = {}) => chat[index].resolve(response({ conversationId: 'conversation-' + index, answer, ...extra }));
  return { markup, requests, chat, storage: sessionStorage, click, submit, reply, win, window, set holdShop(v) { holdShop = v; }, set holdLogout(v) { holdLogout = v; } };
}
const results = [];
async function check(name, run) {
  try { await run(); results.push({ name, passed: true }); }
  catch (error) { results.push({ name, passed: false, error: error.message }); }
}
(async () => {
  await check('anonymous-cannot-submit', async () => {
    const a = app(null); await tick(); a.click('ai-open'); a.submit('私有问题'); await tick();
    assert.equal(a.chat.length, 0);
    assert.ok(!a.markup.value.includes('私有问题'));
  });
  await check('loaded-page-shop-and-same-conversation', async () => {
    const a = app(); await tick(); a.click('ai-open'); a.submit('周日买券预算80元'); await tick();
    assert.equal(a.chat[0].body.shopId, 21);
    assert.equal(a.chat[0].body.conversationId, null);
    a.reply(0, '本轮结果'); await tick(); a.submit('改成周一'); await tick();
    assert.equal(a.chat[1].body.conversationId, 'conversation-0');
    assert.equal(a.chat[1].body.shopId, 21);
  });
  await check('status-evidence-version-time-and-html-escaping', async () => {
    const a = app(); await tick(); a.click('ai-open'); a.submit('核对'); await tick();
    a.reply(0, '<img src=x onerror=alert(1)>', { task: { status: 'PARTIAL', steps: [{ capability: 'SELECTION', status: 'COMPLETED' }, { capability: 'FAQ', status: 'UNKNOWN' }] },
      evidence: [{ documentId: 'demo', title: '<script>bad()</script>', documentVersion: 'v2', publicationVersion: 'v7@demo-hash' }],
      faqAnswers: [{ question: '退款多久？', status: 'UNKNOWN', missingDetails: ['到账时限'] }], selectionObservedAt: '2026-10-23T08:49:10Z' });
    await tick();
    for (const text of ['部分完成', '选券 · 已完成', '规则咨询 · 待核实', '文档版本：v2', '知识发布：v7', '16:49:10', '待核实：到账时限', '&lt;img', '&lt;script&gt;']) assert.ok(a.markup.value.includes(text), text);
    assert.ok(!a.markup.value.includes('<script>bad()'));
  });
  await check('logout-clears-before-remote-completion-and-drops-late-success', async () => {
    const a = app(); await tick(); a.click('ai-open'); a.submit('账号A的私有问题'); await tick();
    a.holdLogout = true; a.click('logout'); await tick();
    assert.equal(a.storage.getItem('token'), null); a.click('ai-open');
    assert.ok(!a.markup.value.includes('账号A的私有问题'));
    a.reply(0, '账号A的私有答案'); await tick();
    assert.ok(!a.markup.value.includes('账号A的私有答案'));
  });
  await check('business-failure-preserves-task-status', async () => {
    const a = app(); await tick(); a.click('ai-open'); a.submit('查券'); await tick();
    a.chat[0].resolve({ ok: true, status: 200, json: async () => ({ success: false, errorMsg: '暂时无法回答', data: { task: { status: 'FAILED', steps: [{ capability: 'SELECTION', status: 'FAILED' }] } } }) });
    await tick(); assert.ok(a.markup.value.includes('选券 · 查询失败')); assert.ok(a.markup.value.includes('暂时无法回答'));
  });
  await check('unverified-user-requirements-are-visible-and-escaped', async () => {
    const a = app(); await tick(); a.click('ai-open'); a.submit('周日晚上十点三个人用'); await tick();
    a.reply(0, '其他使用条件待确认', { task: { status: 'PARTIAL', steps: [{ capability: 'SELECTION', status: 'PARTIAL' }], constraints: { dayQuote: '周日晚上十点', usageQuotes: ['三个人用', '<img src=x>'] } } });
    await tick();
    for (const text of ['本次使用要求', '周日晚上十点；三个人用；&lt;img src=x&gt;', '是否适用，见下方逐券核对结果。']) assert.ok(a.markup.value.includes(text));
    assert.ok(!a.markup.value.includes('<img src=x>'));
  });
  await check('account-switch-discards-old-success-and-keeps-new-request-pending', async () => {
    const a = app(); await tick(); a.click('ai-open'); a.submit('账号A问题'); await tick();
    a.storage.setItem('token', 'synthetic-B'); a.win.focus?.(); a.click('ai-open'); a.submit('账号B问题'); await tick();
    assert.equal(a.chat.length, 2, '新账号应能开始独立请求');
    assert.equal(a.chat[1].body.conversationId, null);
    a.reply(0, 'A迟到答案'); await tick();
    assert.ok(!a.markup.value.includes('账号A问题')); assert.ok(!a.markup.value.includes('A迟到答案'));
    assert.ok(a.markup.value.includes('正在查询并核对依据'));
    a.reply(1, 'B正确答案'); await tick(); assert.ok(a.markup.value.includes('B正确答案'));
  });
  await check('old-401-does-not-logout-new-account', async () => {
    const a = app(); await tick(); a.click('ai-open'); a.submit('A问题'); await tick();
    a.storage.setItem('token', 'synthetic-B'); a.win.focus?.(); a.click('ai-open');
    a.chat[0].resolve({ status: 401, ok: false, json: async () => { throw Error('not JSON'); } }); await tick();
    assert.equal(a.storage.getItem('token'), 'synthetic-B'); assert.ok(!a.markup.value.includes('A问题'));
  });
  await check('current-401-without-json-clears-visible-history', async () => {
    const a = app(); await tick(); a.click('ai-open'); a.submit('已失效账号问题'); await tick();
    a.chat[0].resolve({ status: 401, ok: false, json: async () => { throw Error('not JSON'); } }); await tick(); a.click('ai-open');
    assert.equal(a.storage.getItem('token'), null); assert.ok(!a.markup.value.includes('已失效账号问题'));
  });
  await check('shop-switch-waits-for-loaded-matching-shop-and-clears-history', async () => {
    const a = app(); await tick(); a.click('ai-open'); a.submit('门店21问题'); await tick();
    a.holdShop = true; a.window.location.hash = '#/shop?id=22'; await tick(); a.submit('还没加载的新店'); await tick();
    assert.equal(a.chat.length, 1);
    a.requests.find(r => r.url.endsWith('/shop/22')).resolve(response({ id: 22, name: '合成门店22' })); await tick();
    a.submit('门店22问题'); await tick(); assert.equal(a.chat.length, 2, '新门店应能开始独立请求'); assert.equal(a.chat[1].body.shopId, 22); assert.equal(a.chat[1].body.conversationId, null);
    a.reply(0, '门店21迟到结果'); await tick();
    assert.ok(!a.markup.value.includes('门店21迟到结果')); assert.ok(!a.markup.value.includes('门店21问题'));
  });
  await check('new-consultation-invalidates-old-request-and-conversation', async () => {
    const a = app(); await tick(); a.click('ai-open'); a.submit('旧问题'); await tick(); a.click('ai-reset');
    a.submit('新问题'); await tick(); assert.equal(a.chat.length, 2, '重置后应能发起新请求'); assert.equal(a.chat[1].body.conversationId, null);
    a.reply(0, '旧答案'); await tick(); assert.ok(!a.markup.value.includes('旧答案'));
    assert.ok(a.markup.value.includes('正在查询并核对依据'));
  });
  const report = { source: sourcePath, source_sha256: crypto.createHash('sha256').update(code).digest('hex'), model_calls: 0,
    note: '合成DOM、受控网络；故意忽略取消信号。不是实际浏览器或真实模型验收。', results, passed: results.every(r => r.passed) };
  if (process.argv[3]) fs.writeFileSync(process.argv[3], JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report, null, 2));
  process.exitCode = report.passed ? 0 : 1;
})();
