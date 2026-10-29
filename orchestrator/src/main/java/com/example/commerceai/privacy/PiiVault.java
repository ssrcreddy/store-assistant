package com.example.commerceai.privacy;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session mapping between placeholders (e.g. {@code <EMAIL_1>}) and real values.
 * In-memory with a TTL for the scaffold; swap for Redis (encrypted values, same TTL) in production.
 */
@Component
public class PiiVault {

    private static final Duration TTL = Duration.ofMinutes(30);

    private final Map<String, SessionVault> sessions = new ConcurrentHashMap<>();

    public String placeholderFor(String sessionId, String type, String value) {
        return session(sessionId).placeholderFor(type, value);
    }

    /** Replaces every known placeholder in {@code text} with its real value for this session only. */
    public String restore(String sessionId, String text) {
        SessionVault vault = sessions.get(sessionId);
        if (vault == null || text == null) {
            return text;
        }
        vault.touch();
        String result = text;
        for (Map.Entry<String, String> e : vault.byPlaceholder.entrySet()) {
            result = result.replace(e.getKey(), e.getValue());
        }
        return result;
    }

    private SessionVault session(String sessionId) {
        SessionVault vault = sessions.computeIfAbsent(sessionId, id -> new SessionVault());
        vault.touch();
        return vault;
    }

    @Scheduled(fixedDelay = 60_000)
    void evictExpired() {
        Instant cutoff = Instant.now().minus(TTL);
        sessions.entrySet().removeIf(e -> e.getValue().lastAccess.isBefore(cutoff));
    }

    private static final class SessionVault {
        final Map<String, String> byPlaceholder = new ConcurrentHashMap<>();
        final Map<String, String> byValue = new ConcurrentHashMap<>();
        final Map<String, Integer> counters = new ConcurrentHashMap<>();
        volatile Instant lastAccess = Instant.now();

        synchronized String placeholderFor(String type, String value) {
            return byValue.computeIfAbsent(type + ":" + value, k -> {
                int n = counters.merge(type, 1, Integer::sum);
                String placeholder = "<" + type + "_" + n + ">";
                byPlaceholder.put(placeholder, value);
                return placeholder;
            });
        }

        void touch() {
            lastAccess = Instant.now();
        }
    }
}
