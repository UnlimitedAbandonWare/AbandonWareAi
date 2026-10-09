'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('main/resources/templates/debug-events.html', 'utf8');
const start = source.indexOf('  function providerStatusLine(p) {');
const end = source.indexOf('  async function refreshApiFailures()', start);
assert(start >= 0 && end > start);
const context = { fmt: value => value == null ? '' : String(value) };
vm.createContext(context);
vm.runInContext(source.slice(start, end) + '\nglobalThis.render = providerStatusLine;', context);

test('stale failure retains historical timestamps without claiming current recovery', () => {
  const text = context.render({ provider: 'naver', model: 'webkr', state: 'STALE',
    category: 'authentication', consecutive: 2, lastSeen: 'old-failure', lastSuccessAt: 'old-success' });
  assert(text.includes('현재 상태 미확인'));
  assert(text.includes('old-failure'));
  assert(text.includes('old-success'));
  assert(!text.includes('— 정상'));
});

test('missing observation does not default to healthy', () => {
  assert(context.render({ provider: 'naver', model: 'webkr' }).includes('현재 상태 미확인'));
});

test('observed recent recovery preserves its normal label', () => {
  const text = context.render({ provider: 'naver', model: 'webkr', state: 'OK', recoveredAt: 'actual-success' });
  assert(text.includes('— 정상'));
  assert(text.includes('actual-success에 복구됨'));
});
