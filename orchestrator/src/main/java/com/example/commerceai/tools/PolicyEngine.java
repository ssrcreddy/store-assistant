package com.example.commerceai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Business rules for write actions, enforced in code rather than in the prompt.
 * The model can only propose; execution needs the owner's explicit confirmation.
 */
@Component
public class PolicyEngine {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");
    private static final Duration CONFIRM_WINDOW = Duration.ofMinutes(10);
    private static final Set<String> CANCELLABLE = Set.of("placed", "packed");

    private final CommerceApiClient api;
    private final Map<String, PendingAction> pending = new ConcurrentHashMap<>();

    public PolicyEngine(CommerceApiClient api) {
        this.api = api;
    }

    public sealed interface Proposal permits Proposal.Accepted, Proposal.Rejected {
        record Accepted(PendingAction action) implements Proposal {}
        record Rejected(String reason) implements Proposal {}
    }

    public Proposal proposeCancel(String userId, String sessionId, String orderId, String reason) {
        JsonNode order;
        try {
            order = api.getOrder(userId, orderId);
        } catch (HttpClientErrorException.NotFound e) {
            return new Proposal.Rejected("Order " + orderId + " was not found on this account.");
        }
        String status = order.path("status").asText();
        if (!CANCELLABLE.contains(status)) {
            return new Proposal.Rejected("Order " + orderId + " is " + status
                    + " and can no longer be cancelled. A return can be requested after delivery.");
        }
        PendingAction action = new PendingAction(UUID.randomUUID().toString(), "CANCEL_ORDER", userId, sessionId,
                orderId, reason, UUID.randomUUID().toString(), Instant.now().plus(CONFIRM_WINDOW));
        pending.put(action.id(), action);
        audit.info("action=propose type={} user={} order={} id={}", action.type(), userId, orderId, action.id());
        return new Proposal.Accepted(action);
    }

    public Optional<PendingAction> find(String actionId, String userId) {
        PendingAction a = pending.get(actionId);
        if (a == null || !a.userId().equals(userId) || a.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(a);
    }

    /** Executes a confirmed action once. The idempotency key makes a retried confirm safe downstream. */
    public JsonNode confirm(String actionId, String userId) {
        PendingAction a = find(actionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("This action has expired or doesn't exist."));
        pending.remove(actionId);
        JsonNode result = api.cancelOrder(userId, a.orderId(), a.reason(), a.idempotencyKey());
        audit.info("action=confirm type={} user={} order={} id={} result={}", a.type(), userId, a.orderId(),
                a.id(), result.path("status").asText());
        return result;
    }

    public boolean reject(String actionId, String userId) {
        Optional<PendingAction> a = find(actionId, userId);
        a.ifPresent(x -> {
            pending.remove(actionId);
            audit.info("action=reject type={} user={} order={} id={}", x.type(), userId, x.orderId(), x.id());
        });
        return a.isPresent();
    }

    @Scheduled(fixedDelay = 60_000)
    void evictExpired() {
        Instant now = Instant.now();
        pending.values().removeIf(a -> a.expiresAt().isBefore(now));
    }
}
