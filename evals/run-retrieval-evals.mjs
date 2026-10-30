// Retrieval evals: does the right knowledge-base chunk come back? Compares KEYWORD, VECTOR and
// HYBRID search on the same questions. No LLM calls, so it's free and deterministic.
// Needs the orchestrator running with RAG_ENABLED=true and DEBUG_ENDPOINTS=true.
// Usage: node evals/run-retrieval-evals.mjs [--base http://localhost:8080] [--min-recall3 0.9]
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const arg = (name, fallback) => {
  const i = process.argv.indexOf(name);
  return i > -1 ? process.argv[i + 1] : fallback;
};
const BASE = arg('--base', process.env.EVAL_BASE_URL ?? 'http://localhost:8080');
const MIN_RECALL3 = Number(arg('--min-recall3', '0.9'));
const MODES = ['KEYWORD', 'VECTOR', 'HYBRID'];

const cases = readFileSync(join(here, 'retrieval.jsonl'), 'utf8')
  .split('\n')
  .filter((l) => l.trim())
  .map((l) => JSON.parse(l));

async function search(query, mode) {
  const url = `${BASE}/api/debug/kb-search?mode=${mode}&limit=5&q=${encodeURIComponent(query)}`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
  return (await res.json()).map((h) => h.id);
}

const results = [];
for (const c of cases) {
  const row = { id: c.id, kind: c.kind, expect: c.expect, ranks: {} };
  for (const mode of MODES) {
    const ids = await search(c.query, mode);
    const pos = ids.indexOf(c.expect);
    row.ranks[mode] = pos === -1 ? null : pos + 1;
    row[`top_${mode}`] = ids[0] ?? null;
  }
  results.push(row);
  const cell = (m) => (row.ranks[m] ? `#${row.ranks[m]}` : 'miss').padEnd(6);
  console.log(`${c.id.padEnd(24)} ${c.kind.padEnd(10)} ${MODES.map(cell).join(' ')}`);
}

const metric = (rows, mode) => {
  const n = rows.length || 1;
  const hit = (k) => rows.filter((r) => r.ranks[mode] && r.ranks[mode] <= k).length / n;
  const mrr = rows.reduce((s, r) => s + (r.ranks[mode] ? 1 / r.ranks[mode] : 0), 0) / n;
  return { recall1: +hit(1).toFixed(3), recall3: +hit(3).toFixed(3), mrr: +mrr.toFixed(3) };
};

const summary = { ranAt: new Date().toISOString(), cases: results.length, byMode: {}, byKind: {} };
for (const mode of MODES) summary.byMode[mode] = metric(results, mode);
for (const kind of [...new Set(results.map((r) => r.kind))]) {
  const rows = results.filter((r) => r.kind === kind);
  summary.byKind[kind] = Object.fromEntries(MODES.map((m) => [m, metric(rows, m).recall3]));
}

console.log(`\n${''.padEnd(10)} ${'recall@1'.padEnd(9)} ${'recall@3'.padEnd(9)} MRR`);
for (const mode of MODES) {
  const m = summary.byMode[mode];
  console.log(`${mode.padEnd(10)} ${String(m.recall1).padEnd(9)} ${String(m.recall3).padEnd(9)} ${m.mrr}`);
}
console.log('\nrecall@3 by question kind:', JSON.stringify(summary.byKind));

mkdirSync(join(here, 'results'), { recursive: true });
writeFileSync(join(here, 'results', 'retrieval.json'), JSON.stringify({ summary, results }, null, 2));

process.exit(summary.byMode.HYBRID.recall3 >= MIN_RECALL3 ? 0 : 1);
