package com.example.commerceai.routing;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exact-match cache keyed by user and the redacted question. Short TTL because answers contain
 * live order data. Next step: Redis plus a semantic (embedding) lookup for FAQ-style questions.
 */
@Component
public class ResponseCache {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final int MAX_ENTRIES = 10_000;

    private record Entry(String answer, Instant expiresAt) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public Optional<String> get(String userId, String redactedQuestion) {
        Entry e = entries.get(key(userId, redactedQuestion));
        if (e == null || e.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(e.answer());
    }

    public void put(String userId, String redactedQuestion, String redactedAnswer) {
        if (entries.size() >= MAX_ENTRIES) {
            evictExpired();
            if (entries.size() >= MAX_ENTRIES) {
                return;
            }
        }
        entries.put(key(userId, redactedQuestion), new Entry(redactedAnswer, Instant.now().plus(TTL)));
    }

    @Scheduled(fixedDelay = 30_000)
    void evictExpired() {
        Instant now = Instant.now();
        entries.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }

    private static String key(String userId, String question) {
        String normalized = question.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9<>_-]+", " ").trim();
        return userId + "|" + normalized;
    }
}
