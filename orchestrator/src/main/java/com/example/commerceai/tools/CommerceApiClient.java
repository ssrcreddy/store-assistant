package com.example.commerceai.tools;

import com.example.commerceai.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * Thin client over the existing Order, Inventory and Payment APIs (mocked in /mock-apis).
 * Every call carries the authenticated user's ID so the APIs enforce ownership; the LLM never
 * chooses whose data is read. Add Resilience4j circuit breakers here for production.
 */
@Component
public class CommerceApiClient {

    private final RestClient http;

    public CommerceApiClient(AppProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.tools().timeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.tools().timeoutMs()));
        this.http = RestClient.builder()
                .baseUrl(props.tools().baseUrl())
                .requestFactory(factory)
                .build();
    }

    public JsonNode getOrder(String userId, String orderId) {
        return http.get().uri("/orders/{id}", orderId)
                .header("X-User-Id", userId)
                .retrieve().body(JsonNode.class);
    }

    public JsonNode listOrders(String userId, String status) {
        return http.get().uri(b -> b.path("/orders").queryParam("status", status).build())
                .header("X-User-Id", userId)
                .retrieve().body(JsonNode.class);
    }

    public JsonNode getInventory(String sku) {
        return http.get().uri("/inventory/{sku}", sku)
                .retrieve().body(JsonNode.class);
    }

    public JsonNode getPayment(String userId, String orderId) {
        return http.get().uri("/payments/{orderId}", orderId)
                .header("X-User-Id", userId)
                .retrieve().body(JsonNode.class);
    }

    public JsonNode createPaymentLink(String userId, String orderId) {
        return http.post().uri("/payments/{orderId}/link", orderId)
                .header("X-User-Id", userId)
                .retrieve().body(JsonNode.class);
    }

    public JsonNode cancelOrder(String userId, String orderId, String reason, String idempotencyKey) {
        return http.post().uri("/orders/{id}/cancel", orderId)
                .header("X-User-Id", userId)
                .header("Idempotency-Key", idempotencyKey)
                .body(Map.of("reason", reason))
                .retrieve().body(JsonNode.class);
    }
}
