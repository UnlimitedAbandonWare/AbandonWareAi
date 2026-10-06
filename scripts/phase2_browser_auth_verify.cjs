// Real browser/production-DAO auth verification. Accept only synthetic credentials via stdin.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { chromium } = require('playwright');
const hash = value => value ? crypto.createHash('sha256').update(value).digest('hex').slice(0, 12) : null;
const failures = [];
function requireValue(value, reason) { if (!value) failures.push(reason); }
async function readInput() {
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}
async function main() {
  const input = await readInput();
  const url = new URL(input.base), root = path.resolve(__dirname, '..');
  const output = path.resolve(input.output);
  if (url.hostname !== '127.0.0.1' || url.protocol !== 'http:' || [18180,18181,18182].includes(Number(url.port))
      || !output.startsWith(path.join(root, 'data/agent-handoff/codex-autonomy') + path.sep)
      || path.basename(output) !== 'browser-auth-evidence.json') throw Error('fixture_scope_invalid');
  const protectedPath = '/admin/debug-events';
  const evidence = { scope: 'isolated-production-DAO-auth', protectedPath, freshContexts: 0,
    restoredAuthState: false, generationPosts: 0, requests: [], checks: {}, verdict: 'FAIL' };
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  async function context() {
    evidence.freshContexts++;
    const value = await browser.newContext();
    await value.route('**/api/chat/**', async route => {
      if (route.request().method() === 'POST' && /\/api\/chat\/(stream|sync)$/.test(new URL(route.request().url()).pathname)) {
        evidence.generationPosts++;
        return route.abort('blockedbyclient');
      }
      return route.continue();
    });
    const page = await value.newPage();
    page.on('response', response => {
      const endpoint = new URL(response.url()).pathname;
      if (!['/login','/logout',protectedPath].includes(endpoint)) return;
      const headers = response.headers();
      const location = headers.location ? new URL(headers.location, input.base) : null;
      const code = headers['x-reason-code'];
      evidence.requests.push({ endpoint, method: response.request().method(), status: response.status(),
        requestHash: hash(headers['x-request-id'] || headers['x-trace-id'] || ''),
        locationPath: location?.pathname || null, loginError: location?.searchParams.has('error') || false,
        logoutRedirect: location?.searchParams.has('logout') || false,
        serverReasonCode: typeof code === 'string' && /^[a-zA-Z0-9_.:-]{1,80}$/.test(code) ? code : null });
    });
    return { value, page };
  }
  async function protectedResponse(page) {
    const pending = page.waitForResponse(r => new URL(r.url()).pathname === protectedPath && r.request().method() === 'GET');
    await page.goto(input.base + protectedPath);
    return pending;
  }
  async function submit(page, username, password) {
    await page.goto(input.base + '/login');
    await page.locator('[name="username"]').fill(username);
    await page.locator('[name="password"]').fill(password);
    const pending = page.waitForResponse(r => new URL(r.url()).pathname === '/login' && r.request().method() === 'POST');
    await page.locator('form').filter({ has: page.locator('[name="password"]') }).locator('[type="submit"]').click();
    return pending;
  }
  try {
    const invalid = await context();
    try {
      const anonymous = await protectedResponse(invalid.page);
      requireValue(anonymous.status() === 302 && new URL(invalid.page.url()).pathname === '/login', 'anonymous_protected_not_blocked');
      evidence.checks.anonymousProtected = anonymous.status();
      const rejected = await submit(invalid.page, input.username + '-invalid', 'synthetic-invalid');
      requireValue(rejected.status() === 302 && (rejected.headers().location || '').includes('/login?error'), 'invalid_login_not_rejected');
      evidence.checks.invalidLogin = rejected.status();
      const afterInvalid = await protectedResponse(invalid.page);
      requireValue(afterInvalid.status() === 302, 'invalid_account_protected_not_blocked');
      evidence.checks.invalidAccountProtected = afterInvalid.status();
    } finally { await invalid.value.close(); }

    const valid = await context();
    try {
      const loggedIn = await submit(valid.page, input.username, input.password);
      requireValue(loggedIn.status() === 302 && (loggedIn.headers().location || '').endsWith('/index'), 'fresh_login_not_successful');
      evidence.checks.freshLogin = loggedIn.status();
      const protectedResource = await protectedResponse(valid.page);
      requireValue(protectedResource.status() === 200, 'authenticated_admin_resource_unavailable');
      evidence.checks.authenticatedProtected = protectedResource.status();
      await valid.page.goto(input.base + '/index');
      const logoutResponse = valid.page.waitForResponse(r => new URL(r.url()).pathname === '/logout' && r.request().method() === 'POST');
      await valid.page.locator('form[action="/logout"]').locator('button, [type="submit"]').first().click();
      const loggedOut = await logoutResponse;
      requireValue(loggedOut.status() === 302 && (loggedOut.headers().location || '').includes('/login?logout'), 'logout_not_observed');
      evidence.checks.logout = loggedOut.status();
      const afterLogout = await protectedResponse(valid.page);
      requireValue(afterLogout.status() === 302 && new URL(valid.page.url()).pathname === '/login', 'post_logout_protected_not_blocked');
      evidence.checks.postLogoutProtected = afterLogout.status();
    } finally { await valid.value.close(); }
    requireValue(evidence.generationPosts === 0, 'unexpected_generation_request');
    evidence.verdict = failures.length ? 'FAIL' : 'PASS';
  } catch (error) {
    failures.push('browser_scenario_failed');
    evidence.errorClass = error.name;
  } finally {
    await browser.close();
    evidence.failures = failures;
    fs.writeFileSync(output, JSON.stringify(evidence, null, 2));
    console.log(JSON.stringify({ verdict: evidence.verdict, checks: evidence.checks, failureCount: failures.length }));
    process.exitCode = evidence.verdict === 'PASS' ? 0 : 1;
  }
}
main().catch(error => { console.log(JSON.stringify({ verdict: 'FAIL', reason: 'fixture_execution_failed', errorClass: error.name })); process.exitCode = 1; });
