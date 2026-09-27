// Local Chromium + bundled DOMPurify. NODE_PATH points to an existing Playwright.
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require('playwright');

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || undefined });
  const results = [];
  let network = 0;
  try {
    const page = await browser.newPage();
    await page.route('**/*', route => { network++; return route.abort(); });
    await page.setContent('<div data-admin-diagnostics></div><input data-chat-trace-toggle type="checkbox" checked><main><div id="answer"></div></main>');
    await page.addScriptTag({ path: path.resolve('main/resources/static/js/vendor/chat/dompurify-3.4.15.min.js') });
    await page.addScriptTag({ path: path.resolve('main/resources/static/js/chat-trace-ui.js') });
    const render = html => page.evaluate(html => {
      window.AwxChatTraceUi.upsert(document.querySelector('#answer'), { html });
      const body = document.querySelector('.awx-trace-body');
      return {
        text: body.textContent,
        length: body.innerHTML.length,
        dataRows: [...body.querySelectorAll('table.trace-data')].map(t =>
          [...t.querySelectorAll('tr')].filter(r => r.closest('table') === t && r.querySelector('td')).length),
        headers: body.querySelectorAll('table.trace-data thead').length,
        notices: [...body.querySelectorAll('.trace-row-limit')].map(n => n.textContent),
        unsafe: body.querySelectorAll('script,style,img,svg,iframe,[onclick],[onerror],[href],[src],[id],[name]').length,
        slots: document.querySelectorAll('.awx-trace-panel').length,
        aria: document.querySelector('.awx-trace-panel').getAttribute('aria-live')
      };
    }, html);
    const table = (count, padding = '') => '<table class="trace-data"><thead><tr><th>#</th></tr></thead><tbody>' +
      Array.from({ length: count }, (_, i) => '<tr><td>한글😀 ' + i + padding + '</td></tr>').join('') + '</tbody></table>';
    const fixture = inner => '<details class="search-trace"><summary>trace</summary><table class="trace-kv">' +
      '<tr><th>events</th><td>' + inner + '</td></tr>' +
      '<tr class="trace-kv-group"><th colspan="2">TERMINAL_GROUP</th></tr><tr><th>failure</th><td>FINAL_OK</td></tr>' +
      '</table></details>';
    async function check(name, action) {
      try { await action(); results.push({ name, status: 'PASS' }); }
      catch (e) { results.push({ name, status: 'FAIL', reason: String(e.message).slice(0, 200) }); }
    }
    await check('nested120_keeps_terminal_and_counts', async () => {
      const r = await render(fixture(table(120)));
      assert.ok(r.text.includes('TERMINAL_GROUP') && r.text.includes('FINAL_OK'));
      assert.deepEqual(r.dataRows, [100]);
      assert.ok(r.notices.includes('total=120 displayed=100 omitted=20'));
      assert.equal(r.headers, 1);
    });
    for (const n of [0, 1, 200]) await check('rows_' + n, async () => {
      const r = await render(fixture(table(n)));
      assert.deepEqual(r.dataRows, [Math.min(n, 100)]);
      assert.ok(r.notices.includes('total=' + n + ' displayed=' + Math.min(n, 100) + ' omitted=' + Math.max(0, n - 100)));
      assert.ok(r.text.includes('FINAL_OK'));
    });
    await check('independent_tables_and_layout_rows', async () => {
      const r = await render(fixture(table(120) + table(200)));
      assert.deepEqual(r.dataRows, [100, 100]);
      assert.equal(r.notices.length, 2);
      const layout = '<table class="trace-kv">' + '<tr><th>key</th><td>value</td></tr>'.repeat(200) + '</table>';
      const k = await render(fixture(layout));
      assert.ok(k.text.includes('FINAL_OK'));
      assert.equal((k.text.match(/value/g) || []).length, 200);
    });
    for (const length of [59999, 60000]) await check('html_boundary_' + length, async () => {
      const base = fixture(table(1));
      const html = fixture(table(1, '가'.repeat(length - base.length)));
      assert.equal(html.length, length);
      const r = await render(html);
      assert.ok(r.text.includes('FINAL_OK'));
      assert.ok(r.length <= 60000);
      assert.ok(!r.text.includes('�'));
    });
    for (const length of [60001, 70000]) await check('input_ceiling_' + length, async () => {
      const r = await page.evaluate(length => {
        const original = window.DOMPurify.sanitize;
        let parses = 0;
        window.DOMPurify.sanitize = (...args) => { parses++; return original(...args); };
        try {
          window.AwxChatTraceUi.upsert(document.querySelector('#answer'), { html: '가'.repeat(length) });
          const body = document.querySelector('.awx-trace-body');
          return { parses, text: body.textContent, rows: body.querySelectorAll('tr').length };
        } finally { window.DOMPurify.sanitize = original; }
      }, length);
      assert.equal(r.parses, 0);
      assert.ok(r.text.includes('60,000자 초과'));
      assert.equal(r.rows, 0);
    });
    await check('sanitization_and_dedup', async () => {
      const html = fixture(table(1)) + '<script>window.attack=1</script><img src=x onerror="window.attack=1">' +
        '<svg onload="window.attack=1"></svg><div id="location" name="cookie" onclick="window.attack=1">safe</div>';
      const r = await render(html); await render(html);
      assert.equal(r.unsafe, 0); assert.equal(r.slots, 1); assert.equal(r.aria, 'off');
      assert.equal(await page.evaluate(() => window.attack), undefined);
      const count = await page.evaluate(() => {
        const a = document.querySelector('#answer');
        for (let i = 0; i < 3; i++) {
          const detail = document.createElement('span'); detail.textContent = 'same score';
          window.AwxChatTraceUi.upsertDiagnostic(a, 'score', detail, 'same-event');
        }
        return document.querySelector('.awx-trace-signals').children.length;
      });
      assert.equal(count, 1);
    });
    await check('step_sort_and_filter', async () => {
      const h = '<details class="search-trace"><summary>steps</summary><details class="trace-steps-panel">' +
        '<div class="trace-steps-controls"></div><table class="trace-steps-table"><thead><tr><th>#</th><th>query</th><th>returned</th><th>kept</th><th>ms</th></tr></thead><tbody>' +
        '<tr><td>2</td><td>q2</td><td>0</td><td>0</td><td>2ms</td></tr>' +
        '<tr><td>1</td><td>q1</td><td>3</td><td>2</td><td>1ms</td></tr></tbody></table></details></details>';
      await render(h);
      const r = await page.evaluate(() => {
        const controls = document.querySelector('.trace-steps-controls');
        const selects = [...controls.querySelectorAll('select')];
        for (const s of selects) s.dispatchEvent(new Event('change'));
        const order = [...document.querySelectorAll('.trace-steps-table tbody tr')].map(r => r.cells[0].textContent);
        const toggle = controls.querySelector('input[type=checkbox]');
        toggle.checked = true; toggle.dispatchEvent(new Event('change'));
        return { order, visible: [...document.querySelectorAll('.trace-steps-table tbody tr')].filter(r => !r.hidden).length };
      });
      assert.deepEqual(r.order, ['1', '2']); assert.equal(r.visible, 1);
    });
    await check('metadata_only_restore', async () => {
      const result = await page.evaluate(async () => {
        const a = document.createElement('div'); document.querySelector('main').append(a);
        let calls = 0;
        window.fetch = async url => {
          calls++;
          if (!String(url).endsWith('/synthetic-snapshot/html')) throw new Error('unexpected URL');
          return { ok: true, text: async () => '<section data-trace="trace-memory" data-kind="metadata-only"><h3>Trace Memory Checkpoint</h3><dl><dt>stage</dt><dd>한글😀</dd></dl></section>' };
        };
        const p = window.AwxChatTraceUi.restore(a, { snapshotId: 'synthetic-snapshot', fields: {} });
        p.open = true; p.dispatchEvent(new Event('toggle'));
        await new Promise(resolve => setTimeout(resolve, 30));
        return { text: p.textContent, calls };
      });
      assert.ok(result.text.includes('진단 요약만 저장됨') && result.text.includes('한글😀'));
      assert.equal(result.calls, 1);
    });
    assert.equal(network, 0);
    const summary = { browser: 'Chromium', tests: results.length,
      passed: results.filter(r => r.status === 'PASS').length,
      failed: results.filter(r => r.status === 'FAIL').length, networkCalls: network,
      generationCalls: 0, results };
    console.log(JSON.stringify(summary));
    process.exitCode = process.argv.includes('--expect-red')
      ? (results.some(r => r.name === 'nested120_keeps_terminal_and_counts' && r.status === 'FAIL') ? 0 : 1)
      : (summary.failed ? 1 : 0);
  } finally { await browser.close(); }
}
main().catch(e => { console.error(e.message); process.exitCode = 1; });
