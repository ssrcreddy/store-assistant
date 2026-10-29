import { useEffect, useRef, useState } from 'react';
import { decideAction, fetchMetrics, sendChat, type Meta, type Metrics } from './api';

type Msg =
  | { id: string; role: 'user'; text: string }
  | { id: string; role: 'assistant'; text: string; meta?: Meta; error?: boolean }
  | { id: string; role: 'action'; actionId: string; summary: string; state: 'pending' | 'done'; result?: string };

const DEMO_USERS = [
  { id: 'u-1001', label: 'Asha (4 orders)' },
  { id: 'u-1002', label: 'Daniel (1 order)' },
];

const SAMPLES = [
  'Where is my order ORD-1001?',
  'Show my recent orders',
  'Is the mechanical keyboard SKU-KEYBOARD in stock?',
  'Please cancel ORD-1002, I ordered it by mistake',
  'My payment failed for ORD-1004, can I pay again?',
  'What is the return window for electronics? My email is asha.verma@example.com',
];

const newSession = () => crypto.randomUUID();
let seq = 0;
const nextId = () => `m${++seq}`;

export default function App() {
  const [userId, setUserId] = useState(DEMO_USERS[0].id);
  const [sessionId, setSessionId] = useState(newSession);
  const [messages, setMessages] = useState<Msg[]>([]);
  const [input, setInput] = useState('');
  const [status, setStatus] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [metrics, setMetrics] = useState<Metrics | null>(null);
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, status]);

  useEffect(() => {
    const load = () => fetchMetrics().then(setMetrics);
    load();
    const t = setInterval(load, 4000);
    return () => clearInterval(t);
  }, []);

  const switchUser = (id: string) => {
    setUserId(id);
    setSessionId(newSession());
    setMessages([]);
  };

  const send = async (text: string) => {
    const trimmed = text.trim();
    if (!trimmed || busy) return;
    setInput('');
    setBusy(true);
    setStatus('Thinking');
    setMessages((m) => [...m, { id: nextId(), role: 'user', text: trimmed }]);
    try {
      await sendChat(userId, sessionId, trimmed, (e) => {
        if (e.type === 'status') setStatus(e.message);
        if (e.type === 'action')
          setMessages((m) => [...m, { id: nextId(), role: 'action', actionId: e.actionId, summary: e.summary, state: 'pending' }]);
        if (e.type === 'answer') setMessages((m) => [...m, { id: nextId(), role: 'assistant', text: e.text, meta: e.meta }]);
        if (e.type === 'error') setMessages((m) => [...m, { id: nextId(), role: 'assistant', text: e.message, error: true }]);
      });
    } catch {
      setMessages((m) => [...m, { id: nextId(), role: 'assistant', text: 'Could not reach the assistant. Is the orchestrator running on port 8080?', error: true }]);
    } finally {
      setBusy(false);
      setStatus(null);
      fetchMetrics().then(setMetrics);
    }
  };

  const decide = async (msg: Extract<Msg, { role: 'action' }>, decision: 'confirm' | 'reject') => {
    const res = await decideAction(userId, msg.actionId, decision);
    setMessages((m) => m.map((x) => (x.id === msg.id ? { ...msg, state: 'done', result: res.message } : x)));
  };

  return (
    <div className="layout">
      <main className="chat">
        <header className="chat-head">
          <div>
            <h1>Store Assistant</h1>
            <p className="sub">Portfolio POC · Spring Boot + Claude · mock legacy APIs</p>
          </div>
          <div className="controls">
            <label htmlFor="user">Signed in as</label>
            <select id="user" value={userId} onChange={(e) => switchUser(e.target.value)}>
              {DEMO_USERS.map((u) => (
                <option key={u.id} value={u.id}>{u.label}</option>
              ))}
            </select>
            <button type="button" className="ghost" onClick={() => switchUser(userId)}>New chat</button>
          </div>
        </header>

        <section className="messages" aria-live="polite">
          {messages.length === 0 && (
            <div className="empty">
              <p>Try one of these:</p>
              <div className="samples">
                {SAMPLES.map((s) => (
                  <button key={s} type="button" className="chip" onClick={() => send(s)}>{s}</button>
                ))}
              </div>
            </div>
          )}
          {messages.map((m) => {
            if (m.role === 'user') return <div key={m.id} className="bubble user">{m.text}</div>;
            if (m.role === 'action')
              return (
                <div key={m.id} className="action-card">
                  <strong>{m.summary}?</strong>
                  {m.state === 'pending' ? (
                    <div className="action-buttons">
                      <button type="button" onClick={() => decide(m, 'confirm')}>Confirm cancellation</button>
                      <button type="button" className="ghost" onClick={() => decide(m, 'reject')}>Keep my order</button>
                    </div>
                  ) : (
                    <p>{m.result}</p>
                  )}
                </div>
              );
            return (
              <div key={m.id} className={`bubble assistant${m.error ? ' error' : ''}`}>
                <p>{m.text}</p>
                {m.meta && <MetaLine meta={m.meta} />}
              </div>
            );
          })}
          {status && <div className="status">{status}…</div>}
          <div ref={endRef} />
        </section>

        <form
          className="composer"
          onSubmit={(e) => {
            e.preventDefault();
            send(input);
          }}
        >
          <label htmlFor="msg" className="visually-hidden">Message</label>
          <input
            id="msg"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="Ask about an order, stock or a policy"
            maxLength={2000}
            disabled={busy}
          />
          <button type="submit" disabled={busy || !input.trim()}>Send</button>
        </form>
      </main>

      <aside className="panel">
        <h2>Live metrics</h2>
        {metrics ? <MetricsView m={metrics} /> : <p className="sub">Waiting for the orchestrator…</p>}
      </aside>
    </div>
  );
}

function MetaLine({ meta }: { meta: Meta }) {
  const parts = [
    meta.route,
    meta.model !== 'none' ? meta.model : null,
    `${meta.latencyMs} ms`,
    meta.costUsd > 0 ? `$${meta.costUsd.toFixed(5)}` : '$0',
    meta.tools.length ? `tools: ${meta.tools.join(', ')}` : null,
    meta.piiRedactions ? `${meta.piiRedactions} PII redacted` : null,
  ].filter(Boolean);
  return <div className="meta" title={meta.reason}>{parts.join(' · ')}</div>;
}

function MetricsView({ m }: { m: Metrics }) {
  const pct = m.budgetUsd ? (100 * m.spendUsd) / m.budgetUsd : 0;
  return (
    <dl className="metrics">
      <div><dt>Requests</dt><dd>{m.requests}</dd></div>
      <div><dt>p95 latency</dt><dd>{m.latencyP95Ms} ms</dd></div>
      <div><dt>p50 latency</dt><dd>{m.latencyP50Ms} ms</dd></div>
      <div><dt>Spend</dt><dd>${m.spendUsd.toFixed(4)}</dd></div>
      <div><dt>Budget used</dt><dd>{pct.toFixed(3)}%</dd></div>
      <div><dt>Cost / request</dt><dd>${m.costPerRequestUsd.toFixed(5)}</dd></div>
      <div><dt>Cache hits</dt><dd>{m.cacheHits}</dd></div>
      {Object.entries(m.byRoute).map(([k, v]) => (
        <div key={k}><dt>Route {k}</dt><dd>{v}</dd></div>
      ))}
      <div><dt>PII redacted</dt><dd>{m.piiRedactions}</dd></div>
      <div><dt>Output leaks blocked</dt><dd>{m.outputLeaksBlocked}</dd></div>
    </dl>
  );
}
