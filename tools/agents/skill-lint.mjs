#!/usr/bin/env node
// tools/agents/skill-lint.mjs
// $0-cost SKILL.md lint for .agents/skills/<name>/SKILL.md.
// Checks, per skill: frontmatter present + parseable, name/description fields,
// name == folder name, description length, duplicate names; plus
// skills-intent-index.yaml ghost entries (referenced but absent) and
// unregistered skills (present but never referenced).
// Mirrors agy's strict YAML rule: a plain (unquoted) scalar may not contain
// ": " -- agy rejects those files outright ("mapping values not allowed").
// Read-only. Usage:
//   node skill-lint.mjs [--skills-dir <dir>] [--index <file>] [--json]
//   node skill-lint.mjs --doctor-line        -> "skills: N ok / M warn"
// Exit 0 always (advisory); counts carry the signal, never the exit code.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..');
const DESC_WARN_CHARS = 500;

// --- minimal frontmatter parser (no deps) ---------------------------------
// Returns { fields, errors } where fields.name / fields.description may be
// null. errors[] carry codes that map to agy's observed reject reasons.
function parseFrontmatter(body) {
  const errors = [];
  const lines = body.replace(/\r\n?/g, '\n').split('\n');
  if (lines[0].trim() !== '---') {
    return { fields: {}, errors: [{ code: 'no-frontmatter', msg: 'file does not start with ---' }] };
  }
  let end = -1;
  for (let i = 1; i < lines.length; i++) {
    if (lines[i].trim() === '---') { end = i; break; }
  }
  if (end < 0) {
    return { fields: {}, errors: [{ code: 'bad-frontmatter', msg: 'no closing ---' }] };
  }
  const fields = {};
  let curKey = null, curBlock = null;
  const blockRe = /^[>|][+-]?$/;
  for (let i = 1; i < end; i++) {
    const line = lines[i];
    const kv = /^([A-Za-z0-9_.-]+):\s*(.*)$/.exec(line);
    if (kv && !/^\s/.test(line)) {
      if (curKey) fields[curKey] = (curBlock || []).join(' ').trim();
      curKey = kv[1]; curBlock = null;
      const rest = kv[2].trim();
      if (blockRe.test(rest) || rest === '') { curBlock = []; continue; }
      // plain scalar containing ": " is rejected by strict YAML parsers (agy)
      if (!/^["']/.test(rest) && /:\s/.test(rest)) {
        errors.push({ code: 'yaml-plain-colon', msg: `${curKey}: unquoted ": " in plain scalar (agy: mapping values not allowed)` });
      }
      const q = rest.match(/^(["'])([\s\S]*)\1$/);
      fields[curKey] = q ? q[2] : rest;
      curKey = null; curBlock = null;
      continue;
    }
    if (curBlock) curBlock.push(line.trim());
  }
  if (curKey) fields[curKey] = (curBlock || []).join(' ').trim();
  return { fields, errors };
}

// --- skills-intent-index.yaml skill-name extraction ------------------------
// Collects names from `primary_skill:` / `optional_skill:` keys and `skills:`
// list blocks (families). No full YAML parse needed for this file shape.
function referencedSkillNames(indexPath) {
  if (!indexPath || !fs.existsSync(indexPath)) return { names: new Set(), present: false };
  const names = new Set();
  let inSkillsList = false;
  for (const raw of fs.readFileSync(indexPath, 'utf8').split(/\r?\n/)) {
    const kv = /^\s*(primary_skill|optional_skill):\s*(\S+)/.exec(raw);
    if (kv && kv[2] !== 'null') { names.add(kv[2]); inSkillsList = false; continue; }
    if (/^\s*skills:\s*$/.test(raw)) { inSkillsList = true; continue; }
    if (inSkillsList) {
      const item = /^\s*-\s*(\S+)/.exec(raw);
      if (item) { names.add(item[1]); continue; }
      if (/\S/.test(raw)) inSkillsList = false; // left the list block
    }
  }
  return { names, present: true };
}

function lint(skillsDir, indexPath) {
  const rows = [];
  const dirs = fs.readdirSync(skillsDir, { withFileTypes: true })
    .filter((e) => e.isDirectory()).map((e) => e.name).sort();
  const byName = new Map(); // frontmatter name -> [dirs]
  for (const dir of dirs) {
    const file = path.join(skillsDir, dir, 'SKILL.md');
    const issues = [];
    if (!fs.existsSync(file)) {
      issues.push({ severity: 'error', code: 'missing-file', msg: 'SKILL.md absent' });
      rows.push({ dir, name: null, descLen: 0, issues });
      continue;
    }
    const { fields, errors } = parseFrontmatter(fs.readFileSync(file, 'utf8'));
    for (const e of errors) issues.push({ severity: 'error', ...e });
    const name = fields.name || null;
    const desc = fields.description || null;
    if (!name) issues.push({ severity: 'error', code: 'name-missing', msg: 'frontmatter name missing' });
    if (!desc) issues.push({ severity: 'error', code: 'description-missing', msg: 'frontmatter description missing' });
    if (name && name !== dir) {
      issues.push({ severity: 'warn', code: 'name-mismatch', msg: `name "${name}" != folder "${dir}"` });
    }
    if (name) {
      if (!byName.has(name)) byName.set(name, []);
      byName.get(name).push(dir);
    }
    const descLen = desc ? desc.length : 0;
    if (desc && descLen > DESC_WARN_CHARS) {
      issues.push({ severity: 'warn', code: 'description-long', msg: `description ${descLen} chars > ${DESC_WARN_CHARS}` });
    }
    rows.push({ dir, name, descLen, issues });
  }
  for (const [name, ds] of byName) {
    if (ds.length > 1) {
      for (const r of rows.filter((x) => x.name === name)) {
        r.issues.push({ severity: 'warn', code: 'duplicate-name', msg: `name "${name}" used by ${ds.join(', ')}` });
      }
    }
  }
  const idx = referencedSkillNames(indexPath);
  const folders = new Set(dirs);
  const fmNames = new Set(rows.map((r) => r.name).filter(Boolean));
  const ghost = [...idx.names].filter((n) => !folders.has(n) && !fmNames.has(n)).sort();
  const unregistered = dirs.filter((d) => !idx.names.has(d) && !(rows.find((r) => r.dir === d)?.name && idx.names.has(rows.find((r) => r.dir === d).name))).sort();
  const warnCount = rows.filter((r) => r.issues.some((i) => i.severity === 'error' || i.severity === 'warn')).length;
  const okCount = rows.length - warnCount;
  return { rows, index: { path: indexPath, present: idx.present, ghost, unregistered }, summary: { total: rows.length, ok: okCount, warn: warnCount } };
}

function main() {
  const { values: v } = parseArgs({
    options: {
      'skills-dir': { type: 'string' }, index: { type: 'string' },
      json: { type: 'boolean' }, 'doctor-line': { type: 'boolean' }, help: { type: 'boolean' },
    }, strict: true,
  });
  if (v.help) {
    console.log('usage: node skill-lint.mjs [--skills-dir d] [--index f] [--json] [--doctor-line]');
    process.exit(0);
  }
  const skillsDir = path.resolve(v['skills-dir'] || path.join(ROOT, '.agents', 'skills'));
  const indexPath = v.index ? path.resolve(v.index) : path.join(ROOT, '.agents', 'skills-intent-index.yaml');
  const result = lint(skillsDir, indexPath);
  const { ok, warn, total } = result.summary;
  if (v['doctor-line']) { console.log(`skills: ${ok} ok / ${warn} warn`); process.exit(0); }
  if (v.json) { console.log(JSON.stringify(result, null, 2)); process.exit(0); }
  console.log('=== skill-lint ($0, read-only) ===');
  console.log(`${'skill'.padEnd(46)}${'status'.padEnd(6)}issues`);
  console.log('-'.repeat(100));
  for (const r of result.rows) {
    const bad = r.issues.some((i) => i.severity === 'error' || i.severity === 'warn');
    const text = r.issues.map((i) => `${i.severity}:${i.code} ${i.msg}`).join('; ') || '-';
    console.log(`${(r.name || r.dir).padEnd(46)}${(bad ? 'warn' : 'ok').padEnd(6)}${text}`);
  }
  console.log(`\nindex   : ${result.index.present ? result.index.path : 'absent'}`);
  console.log(`ghost   : ${result.index.ghost.length ? result.index.ghost.join(', ') : 'none'}`);
  console.log(`unreg.  : ${result.index.unregistered.length} skill dirs not referenced by the index (info)`);
  console.log(`\nskills: ${ok} ok / ${warn} warn (total ${total})`);
  process.exit(0);
}

main();
