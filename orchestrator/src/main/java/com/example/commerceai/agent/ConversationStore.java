package com.example.commerceai.agent;

import com.anthropic.models.messages.MessageParam;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-term memory per session: the last N user/assistant text turns, already redacted.
 * Earlier turns are kept as plain text only (no tool calls or thinking blocks), which keeps the
 * per-request token count low. In production this moves to Redis, with Postgres as the audit copy.
 */
@Component
public class ConversationStore {

    private static final Duration TTL = Duration.ofMinutes(30);

    private record Turn(String user, String assistant) {}

    private static final class Session {
        final Deque<Turn> turns = new ArrayDeque<>();
        long tokensUsed;
        Instant lastAccess = Instant.now();
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public List<MessageParam> history(String sessionId) {
        Session s = sessions.get(sessionId);
        List<MessageParam> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        synchronized (s) {
            for (Turn t : s.turns) {
                out.add(MessageParam.builder().role(MessageParam.Role.USER).content(t.user()).build());
                out.add(MessageParam.builder().role(MessageParam.Role.ASSISTANT).content(t.assistant()).build());
            }
        }
        return out;
    }

    public void append(String sessionId, String redactedUser, String redactedAssistant, long tokens, int maxTurns) {
        Session s = sessions.computeIfAbsent(sessionId, id -> new Session());
        synchronized (s) {
            s.turns.addLast(new Turn(redactedUser, redactedAssistant));
            while (s.turns.size() > maxTurns) {
                s.turns.removeFirst();
            }
            s.tokensUsed += tokens;
            s.lastAccess = Instant.now();
        }
    }

    public long tokensUsed(String sessionId) {
        Session s = sessions.get(sessionId);
        return s == null ? 0 : s.tokensUsed;
    }

    @Scheduled(fixedDelay = 60_000)
    void evictExpired() {
        Instant cutoff = Instant.now().minus(TTL);
        sessions.values().removeIf(s -> s.lastAccess.isBefore(cutoff));
    }
}
