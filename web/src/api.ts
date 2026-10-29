export type Meta = {
  route: string;
  reason: string;
  model: string;
  latencyMs: number;
  firstTokenMs: number;
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  costUsd: number;
  tools: string[];
  piiRedactions: number;
  outputLeakBlocked: boolean;
};

export type ChatEvent =
  | { type: 'status'; message: string }
  | { type: 'action'; actionId: string; actionType: string; orderId: string; summary: string }
  | { type: 'delta'; text: string }
  | { type: 'reset' }
  | { type: 'answer'; text: string; meta: Meta }
  | { type: 'error'; message: string };

/**
 * POST /api/chat and read the Server-Sent Events stream. EventSource only supports GET,
 * so this parses the stream from fetch directly.
 */
export async function sendChat(
  userId: string,
  sessionId: string,
  message: string,
  onEvent: (e: ChatEvent) => void,
): Promise<void> {
  const res = await fetch('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-User-Id': userId },
    body: JSON.stringify({ sessionId, message }),
  });
  if (!res.ok || !res.body) {
    onEvent({ type: 'error', message: `The assistant is unavailable (HTTP ${res.status}).` });
    return;
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let match: RegExpExecArray | null;
    while ((match = /\r?\n\r?\n/.exec(buffer)) !== null) {
      const raw = buffer.slice(0, match.index);
      buffer = buffer.slice(match.index + match[0].length);
      const parsed = parseEvent(raw);
      if (parsed) onEvent(parsed);
    }
  }
}

function parseEvent(raw: string): ChatEvent | null {
  let name = 'message';
  const data: string[] = [];
  for (const line of raw.split(/\r?\n/)) {
    if (line.startsWith('event:')) name = line.slice(6).trim();
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
  }
  if (data.length === 0) return null;
  const payload = JSON.parse(data.join('\n'));
  switch (name) {
    case 'status':
      return { type: 'status', message: payload.message };
    case 'action':
      return { type: 'action', actionId: payload.actionId, actionType: payload.type, orderId: payload.orderId, summary: payload.summary };
    case 'delta':
      return { type: 'delta', text: payload.text };
    case 'reset':
      return { type: 'reset' };
    case 'answer':
      return { type: 'answer', text: payload.text, meta: payload.meta };
    case 'error':
      return { type: 'error', message: payload.message };
    default:
      return null;
  }
}

export async function decideAction(userId: string, actionId: string, decision: 'confirm' | 'reject') {
  const res = await fetch(`/api/actions/${actionId}/${decision}`, {
    method: 'POST',
    headers: { 'X-User-Id': userId },
  });
  return (await res.json()) as { status: string; message: string };
}

export type Metrics = {
  requests: number;
  cacheHits: number;
  byRoute: Record<string, number>;
  spendUsd: number;
  budgetUsd: number;
  costPerRequestUsd: number;
  latencyP50Ms: number;
  latencyP95Ms: number;
  piiRedactions: number;
  outputLeaksBlocked: number;
};

export async function fetchMetrics(): Promise<Metrics | null> {
  try {
    const res = await fetch('/api/metrics');
    return res.ok ? ((await res.json()) as Metrics) : null;
  } catch {
    return null;
  }
}
