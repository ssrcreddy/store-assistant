// k6 load test: 6 req/s design peak, then 15 req/s headroom check.
// Run: k6 run loadtest/chat.js   (BASE_URL defaults to http://localhost:8080)
// Most traffic hits the template path; keep LLM-heavy runs short, they cost real tokens.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

const MESSAGES = [
  ['u-1001', 'Where is my order ORD-1001?'],
  ['u-1001', 'status of ORD-1002'],
  ['u-1002', 'track ORD-2001'],
  ['u-1001', 'Is SKU-HEADPHONES in stock?'],
  ['u-1002', 'How long do refunds take?'],
];

export const options = {
  scenarios: {
    design_peak: { executor: 'constant-arrival-rate', rate: 6, timeUnit: '1s', duration: '2m', preAllocatedVUs: 30 },
    headroom: { executor: 'constant-arrival-rate', rate: 15, timeUnit: '1s', duration: '1m', startTime: '2m', preAllocatedVUs: 60 },
  },
  thresholds: {
    'http_req_duration{route:template}': ['p(95)<1500'],
    'http_req_duration{route:llm}': ['p(95)<4000'],
    checks: ['rate>0.99'],
  },
};

export default function () {
  const [userId, message] = MESSAGES[Math.floor(Math.random() * MESSAGES.length)];
  const route = /ORD-\d+/.test(message) ? 'template' : 'llm';
  const res = http.post(`${BASE}/api/chat`, JSON.stringify({ sessionId: `lt-${__VU}-${__ITER}`, message }), {
    headers: { 'Content-Type': 'application/json', 'X-User-Id': userId },
    tags: { route },
    timeout: '30s',
  });
  check(res, {
    'status 200': (r) => r.status === 200,
    'has answer': (r) => r.body.includes('event:answer'),
  });
}
