// k6 load test: 6 req/s design peak, then 15 req/s headroom check.
// Run: k6 run loadtest/chat.js                    (BASE_URL defaults to http://localhost:8080)
//      k6 run -e NO_CACHE=1 loadtest/chat.js      (unique questions, so every LLM-route request reaches the model)
// Latency is recorded per route the orchestrator actually took (TEMPLATE, CACHE, FAST, SMART), read
// from the answer metadata, so cache hits don't flatter the LLM numbers.
// With NO_CACHE=1 about 40% of requests call the model: roughly 650 Haiku calls over the 3 minutes.
import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const NO_CACHE = Boolean(__ENV.NO_CACHE);

const MESSAGES = [
  ['u-1001', 'Where is my order ORD-1001?'],
  ['u-1001', 'status of ORD-1002'],
  ['u-1002', 'track ORD-2001'],
  ['u-1001', 'Is SKU-HEADPHONES in stock?'],
  ['u-1002', 'How long do refunds take?'],
];

const latency = {
  TEMPLATE: new Trend('latency_template', true),
  CACHE: new Trend('latency_cache', true),
  FAST: new Trend('latency_fast', true),
  SMART: new Trend('latency_smart', true),
};
const firstText = new Trend('first_text_llm', true);
const noLlm = new Rate('no_llm_call');

export const options = {
  scenarios: {
    design_peak: { executor: 'constant-arrival-rate', rate: 6, timeUnit: '1s', duration: '2m', preAllocatedVUs: 30 },
    headroom: { executor: 'constant-arrival-rate', rate: 15, timeUnit: '1s', duration: '1m', startTime: '2m', preAllocatedVUs: 60 },
  },
  thresholds: {
    latency_template: ['p(95)<1500'],
    latency_fast: ['p(95)<4000'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'max', 'count'],
};

export default function () {
  const [userId, base] = MESSAGES[Math.floor(Math.random() * MESSAGES.length)];
  const message = NO_CACHE && !/ORD-\d+/.test(base) ? `${base} (ref ${__VU}-${__ITER})` : base;
  const res = http.post(`${BASE}/api/chat`, JSON.stringify({ sessionId: `lt-${__VU}-${__ITER}`, message }), {
    headers: { 'Content-Type': 'application/json', 'X-User-Id': userId },
    timeout: '30s',
  });
  const ok = check(res, {
    'status 200': (r) => r.status === 200,
    'has answer': (r) => r.body.includes('event:answer'),
  });
  if (!ok) return;

  const route = (res.body.match(/"route":"(\w+)"/) || [])[1];
  if (latency[route]) latency[route].add(res.timings.duration);
  noLlm.add(route === 'TEMPLATE' || route === 'CACHE');
  const ft = (res.body.match(/"firstTokenMs":(\d+)/) || [])[1];
  if (ft && (route === 'FAST' || route === 'SMART')) firstText.add(Number(ft));
}
