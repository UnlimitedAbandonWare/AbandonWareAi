const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const { spawnSync } = require('node:child_process');

const ROOT = path.resolve(__dirname, '..', '..', '..');
const LINT = path.join(ROOT, 'tools', 'agents', 'skill-lint.mjs');

function fixture() {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'skill-lint-'));
  const mk = (dir, body) => {
    fs.mkdirSync(path.join(tmp, dir), { recursive: true });
    fs.writeFileSync(path.join(tmp, dir, 'SKILL.md'), body);
  };
  mk('good-skill', '---\nname: good-skill\ndescription: Use when testing the lint happy path\n---\n\n# Good\n');
  mk('no-front', '# No frontmatter\n\nbody only\n');
  mk('plain-colon', '---\nname: plain-colon\ndescription: Use when X happens: rails only\n---\n\n# Bad\n');
  mk('name-mismatch', '---\nname: other.name\ndescription: Use when the name differs from folder\n---\n');
  mk('dup-a', '---\nname: dup-name\ndescription: Use when dup A\n---\n');
  mk('dup-b', '---\nname: dup-name\ndescription: Use when dup B\n---\n');
  const index = path.join(tmp, 'index.yaml');
  fs.writeFileSync(index, 'intents:\n  - intent: x\n    primary_skill: good-skill\n    optional_skill: ghost-skill\nfamilies:\n  f:\n    skills:\n      - dup-name\n');
  return { tmp, index };
}

function run(tmp, index, extra = []) {
  return spawnSync(process.execPath, [LINT, '--skills-dir', tmp, '--index', index, ...extra], { encoding: 'utf8', timeout: 30000 });
}

test('skill-lint reports parseable/missing/colon/mismatch/dup issues', () => {
  const { tmp, index } = fixture();
  const r = run(tmp, index, ['--json']);
  assert.equal(r.status, 0, `stderr: ${r.stderr}`);
  const j = JSON.parse(r.stdout);
  assert.equal(j.summary.total, 6);
  const byDir = Object.fromEntries(j.rows.map((x) => [x.dir, x]));
  assert.ok(byDir['good-skill'].issues.length === 0);
  assert.ok(byDir['no-front'].issues.some((i) => i.code === 'no-frontmatter'));
  assert.ok(byDir['plain-colon'].issues.some((i) => i.code === 'yaml-plain-colon'));
  assert.ok(byDir['name-mismatch'].issues.some((i) => i.code === 'name-mismatch'));
  assert.ok(byDir['dup-a'].issues.some((i) => i.code === 'duplicate-name'));
  assert.ok(byDir['dup-b'].issues.some((i) => i.code === 'duplicate-name'));
  assert.deepEqual(j.index.ghost, ['ghost-skill']);
  assert.ok(j.index.unregistered.includes('no-front'), 'unregistered info lists non-indexed dirs');
  assert.equal(j.summary.ok, 1);
  assert.equal(j.summary.warn, 5);
});

test('skill-lint --doctor-line prints exact summary line', () => {
  const { tmp, index } = fixture();
  const r = run(tmp, index, ['--doctor-line']);
  assert.equal(r.status, 0);
  assert.match(r.stdout.trim(), /^skills: 1 ok \/ 5 warn$/);
});

test('skill-lint real repo scan runs and exits 0', () => {
  const r = spawnSync(process.execPath, [LINT], { cwd: ROOT, encoding: 'utf8', timeout: 60000 });
  assert.equal(r.status, 0, `stderr: ${r.stderr}`);
  assert.match(r.stdout, /skills: \d+ ok \/ \d+ warn/);
});
