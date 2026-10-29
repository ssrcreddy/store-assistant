// Runs the golden set against a running orchestrator and reports pass rate, latency, cost and PII leaks.
// Usage: node evals/run-evals.mjs [--base http://localhost:8080] [--min-pass 0.9]
// Note: cases that reach the model spend real tokens (roughly $0.01-0.05 for the whole set).
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const arg = (name, fallback) => {
  const i = process.argv.indexOf(name);
  return i > -1 ? process.argv[i + 1] : fallback;
};
const BASE = arg('--base', process.env.EVAL_BASE_URL ?? 'http://localhost:8080');
const MIN_PASS = Number(arg('--min-pass', '0.9'));

const cases = readFileSync(join(here, 'golden.jsonl'), 'utf8')
  .split('\n')
  .filter((l) => l.trim())
  .map((l) => JSON.parse(l));

async function chat(userId, message) {
  const res = await fetch(`${BASE}/api/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-User-Id': userId },
    body: JSON.stringify({ sessionId: randomUUID(), message }),
  });
  const body = await res.text();
  const events = body
    .split(/\r?\n\r?\n/)
    .map((block) => {
      const name = block.match(/^event:\s*(.+)$/m)?.[1]?.trim();
      const data = [...block.matchAll(/^data:\s?(.*)$/gm)].map((m) => m[1]).join('\n');
      return name && data ? { name, data: JSON.parse(data) } : null;
    })
    .filter(Boolean);
  return {
    answer: events.find((e) => e.name === 'answer')?.data,
    actions: events.filter((e) => e.name === 'action').map((e) => e.data),
    error: events.find((e) => e.name === 'error')?.data?.message,
  };
}

function check(c, r) {
  const failures = [];
  if (r.error) return [`error event: ${r.error}`];
  if (!r.answer) return ['no answer event'];
  const text = r.answer.text.toLowerCase();
  const meta = r.answer.meta;
  const e = c.expect ?? {};
  if (e.route && meta.route !== e.route && meta.route !== 'CACHE') failures.push(`route ${meta.route} != ${e.route}`);
  for (const s of e.containsAll ?? []) if (!text.includes(s.toLowerCase())) failures.push(`missing "${s}"`);
  // containsAny is one list of alternatives, or a list of such lists that must each match.
  const anyGroups = !e.containsAny ? [] : Array.isArray(e.containsAny[0]) ? e.containsAny : [e.containsAny];
  for (const group of anyGroups)
    if (!group.some((s) => text.includes(s.toLowerCase()))) failures.push(`none of [${group.join(' | ')}]`);
  for (const s of e.notContains ?? []) if (text.includes(s.toLowerCase())) failures.push(`must not contain "${s}"`);
  if (e.action === true && r.actions.length === 0) failures.push('expected a confirmation action');
  if (e.action === false && r.actions.length > 0) failures.push('unexpected confirmation action');
  for (const t of e.tools ?? []) if (!meta.tools.includes(t)) failures.push(`tool ${t} not used`);
  return failures;
}

const results = [];
for (const c of cases) {
  const started = Date.now();
  let r;
  try {
    r = await chat(c.userId, c.input);
  } catch (err) {
    r = { error: `request failed: ${err.message}`, actions: [] };
  }
  const failures = c.type === 'pii' ? (r.answer ? [] : ['no answer event']) : check(c, r);
  results.push({
    id: c.id,
    pass: failures.length === 0,
    failures,
    latencyMs: r.answer?.meta?.latencyMs ?? Date.now() - started,
    firstTokenMs: r.answer?.meta?.firstTokenMs,
    costUsd: r.answer?.meta?.costUsd ?? 0,
    route: r.answer?.meta?.route,
    answer: r.answer?.text,
  });
  console.log(`${failures.length === 0 ? 'PASS' : 'FAIL'}  ${c.id}${failures.length ? '  -> ' + failures.join('; ') : ''}`);
}

// PII check: nothing the orchestrator sent to the LLM may contain a raw PII value.
let leaks = [];
try {
  const inputs = await (await fetch(`${BASE}/api/debug/llm-inputs`)).json();
  for (const c of cases.filter((x) => x.type === 'pii'))
    for (const v of c.piiValues) if (inputs.some((i) => i.includes(v))) leaks.push(`${c.id}: "${v}" reached the LLM`);
} catch {
  leaks.push('could not read /api/debug/llm-inputs (set DEBUG_ENDPOINTS=true)');
}

const pctOf = (values) => {
  const sorted = [...values].sort((a, b) => a - b);
  return (p) => sorted[Math.max(0, Math.ceil(p * sorted.length) - 1)] ?? 0;
};
const pct = pctOf(results.map((r) => r.latencyMs));
const llm = results.filter((r) => r.route === 'FAST' || r.route === 'SMART');
const llmPct = pctOf(llm.map((r) => r.latencyMs));
const firstTokenPct = pctOf(llm.map((r) => r.firstTokenMs ?? r.latencyMs));
const passRate = results.filter((r) => r.pass).length / results.length;
const summary = {
  ranAt: new Date().toISOString(),
  cases: results.length,
  passRate: Number(passRate.toFixed(3)),
  latencyP50Ms: pct(0.5),
  latencyP95Ms: pct(0.95),
  llmLatencyP95Ms: llmPct(0.95),
  llmFirstTokenP50Ms: firstTokenPct(0.5),
  llmFirstTokenP95Ms: firstTokenPct(0.95),
  totalCostUsd: Number(results.reduce((s, r) => s + r.costUsd, 0).toFixed(6)),
  piiLeaks: leaks.length,
};

console.log('\n' + JSON.stringify(summary, null, 2));
for (const l of leaks) console.log('LEAK ' + l);

mkdirSync(join(here, 'results'), { recursive: true });
writeFileSync(join(here, 'results', 'latest.json'), JSON.stringify({ summary, leaks, results }, null, 2));

process.exit(passRate >= MIN_PASS && leaks.length === 0 ? 0 : 1);
