package com.example.commerceai.tools;

import java.time.Instant;

/** A write action the model proposed. It runs only after the user confirms it in the UI. */
public record PendingAction(
        String id,
        String type,
        String userId,
        String sessionId,
        String orderId,
        String reason,
        String idempotencyKey,
        Instant expiresAt) {

    public String summary() {
        return switch (type) {
            case "CANCEL_ORDER" -> "Cancel order " + orderId;
            default -> type + " " + orderId;
        };
    }
}
