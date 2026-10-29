package com.example.commerceai.api;

import com.example.commerceai.tools.PolicyEngine;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/** The user's explicit confirmation for a write action the assistant proposed. No LLM involved. */
@RestController
public class ActionController {

    private final PolicyEngine policy;

    public ActionController(PolicyEngine policy) {
        this.policy = policy;
    }

    @PostMapping("/api/actions/{actionId}/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@RequestHeader("X-User-Id") String userId,
                                                       @PathVariable String actionId) {
        try {
            JsonNode result = policy.confirm(actionId, userId);
            return ResponseEntity.ok(Map.of(
                    "status", result.path("status").asText(),
                    "message", "Order " + result.path("id").asText() + " is cancelled. Your refund is on its way."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("status", "expired", "message", e.getMessage()));
        } catch (RestClientException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("status", "failed", "message", "We couldn't cancel the order right now. Please try again."));
        }
    }

    @PostMapping("/api/actions/{actionId}/reject")
    public Map<String, Object> reject(@RequestHeader("X-User-Id") String userId, @PathVariable String actionId) {
        boolean removed = policy.reject(actionId, userId);
        return Map.of("status", removed ? "rejected" : "expired", "message", "Okay, nothing was changed.");
    }
}
