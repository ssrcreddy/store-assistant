// Mock versions of the company's existing Order, Inventory and Payment APIs.
// Behaves like the real ones where it matters for the AI layer: ownership checks, 404s for
// other users' orders, idempotent cancels, optional latency and failure injection.
import express from 'express';
import { randomUUID } from 'node:crypto';
import { orders, inventory, payments } from './data.js';

const PORT = Number(process.env.PORT ?? 4000);
const LATENCY_MS = Number(process.env.MOCK_LATENCY_MS ?? 60);
const FAILURE_RATE = Number(process.env.MOCK_FAILURE_RATE ?? 0);

const app = express();
app.use(express.json());

// Simulated network latency and random 503s, for testing timeouts and degraded mode.
app.use((req, res, next) => {
  if (Math.random() < FAILURE_RATE) {
    return res.status(503).json({ error: 'service_unavailable' });
  }
  setTimeout(next, LATENCY_MS);
});

const requireUser = (req, res, next) => {
  const userId = req.get('X-User-Id');
  if (!userId) return res.status(401).json({ error: 'missing_user' });
  req.userId = userId;
  next();
};

// Another customer's order looks exactly like a missing one, so IDs can't be probed.
const findOwnedOrder = (req, res) => {
  const order = orders.find((o) => o.id === req.params.id.toUpperCase() && o.userId === req.userId);
  if (!order) res.status(404).json({ error: 'order_not_found' });
  return order;
};

app.get('/health', (_req, res) => res.json({ status: 'ok' }));

// ---- Order API ----
app.get('/orders', requireUser, (req, res) => {
  const status = req.query.status ?? 'any';
  const mine = orders
    .filter((o) => o.userId === req.userId && (status === 'any' || o.status === status))
    .sort((a, b) => b.placedAt.localeCompare(a.placedAt));
  res.json(mine);
});

app.get('/orders/:id', requireUser, (req, res) => {
  const order = findOwnedOrder(req, res);
  if (order) res.json(order);
});

const cancelResults = new Map(); // Idempotency-Key -> response body

app.post('/orders/:id/cancel', requireUser, (req, res) => {
  const key = req.get('Idempotency-Key');
  if (!key) return res.status(400).json({ error: 'missing_idempotency_key' });
  if (cancelResults.has(key)) return res.json(cancelResults.get(key));

  const order = findOwnedOrder(req, res);
  if (!order) return;
  if (!['placed', 'packed'].includes(order.status)) {
    return res.status(409).json({ error: 'not_cancellable', status: order.status });
  }
  order.status = 'cancelled';
  order.cancelReason = req.body?.reason ?? '';
  const pay = payments[order.id];
  if (pay && pay.status === 'captured') pay.status = 'refund_pending';

  const body = { id: order.id, status: order.status, refund: pay?.status ?? 'none' };
  cancelResults.set(key, body);
  console.log(`[order-api] cancelled ${order.id} for ${req.userId} (key ${key})`);
  res.json(body);
});

// ---- Inventory API ----
app.get('/inventory/:sku', (req, res) => {
  const item = inventory[req.params.sku.toUpperCase()];
  if (!item) return res.status(404).json({ error: 'sku_not_found' });
  res.json(item);
});

// ---- Payment API ----
app.get('/payments/:orderId', requireUser, (req, res) => {
  req.params.id = req.params.orderId;
  const order = findOwnedOrder(req, res);
  if (!order) return;
  res.json(payments[order.id] ?? { orderId: order.id, status: 'unknown' });
});

app.post('/payments/:orderId/link', requireUser, (req, res) => {
  req.params.id = req.params.orderId;
  const order = findOwnedOrder(req, res);
  if (!order) return;
  const pay = payments[order.id];
  if (!pay || !['failed', 'pending'].includes(pay.status)) {
    return res.status(409).json({ error: 'no_payment_due', status: pay?.status ?? 'unknown' });
  }
  res.json({ orderId: order.id, url: `https://pay.example.com/l/${randomUUID().slice(0, 8)}`, expiresInMinutes: 30 });
});

app.listen(PORT, () => console.log(`Mock legacy APIs on http://localhost:${PORT}`));
