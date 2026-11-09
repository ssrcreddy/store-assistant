package com.example.commerceai.tools;

import com.example.commerceai.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Thin client over the existing Order, Inventory and Payment APIs (mocked in /mock-apis).
 * Every call carries the authenticated user's ID so the APIs enforce ownership; the LLM never
 * chooses whose data is read.
 *
 * Each API has its own circuit breaker, so a Payment outage doesn't block order lookups. Only
 * timeouts, connection errors and 5xx count as failures: a 4xx means the API is healthy, and a 404
 * is how ownership checks answer. An open breaker throws ResourceAccessException, the same error
 * as an unreachable API, so callers handle both the same way.
 */
@Component
public class CommerceApiClient {

    private static final Logger log = LoggerFactory.getLogger(CommerceApiClient.class);

    private final RestClient http;
    private final CircuitBreaker orders;
    private final CircuitBreaker inventory;
    private final CircuitBreaker payments;

    public CommerceApiClient(AppProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.tools().timeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.tools().timeoutMs()));
        this.http = RestClient.builder()
                .baseUrl(props.tools().baseUrl())
                .requestFactory(factory)
                .build();

        CircuitBreakerConfig config = breakerConfig(props.tools().circuitBreaker());
        this.orders = breaker("orders", config);
        this.inventory = breaker("inventory", config);
        this.payments = breaker("payments", config);
    }

    private static CircuitBreakerConfig breakerConfig(AppProperties.Tools.CircuitBreaker settings) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.windowSize())
                .minimumNumberOfCalls(settings.minimumCalls())
                .failureRateThreshold(settings.failureRatePercent())
                .waitDurationInOpenState(settings.openWait())
                .permittedNumberOfCallsInHalfOpenState(settings.halfOpenCalls())
                .recordException(e -> e instanceof ResourceAccessException || e instanceof HttpServerErrorException)
                .build();
    }

    private static CircuitBreaker breaker(String name, CircuitBreakerConfig config) {
        CircuitBreaker breaker = CircuitBreaker.of(name, config);
        breaker.getEventPublisher().onStateTransition(e -> log.warn("Circuit breaker '{}': {} -> {}",
                name, e.getStateTransition().getFromState(), e.getStateTransition().getToState()));
        return breaker;
    }

    private static <T> T call(CircuitBreaker breaker, Supplier<T> request) {
        try {
            return breaker.executeSupplier(request);
        } catch (CallNotPermittedException e) {
            throw new ResourceAccessException("The " + breaker.getName() + " API circuit breaker is open");
        }
    }

    public JsonNode getOrder(String userId, String orderId) {
        return call(orders, () -> http.get().uri("/orders/{id}", orderId)
                .header("X-User-Id", userId)
                .retrieve().body(JsonNode.class));
    }

    public JsonNode listOrders(String userId, String status) {
        return call(orders, () -> http.get().uri(b -> b.path("/orders").queryParam("status", status).build())
                .header("X-User-Id", userId)
                .retrieve().body(JsonNode.class));
    }

    public JsonNode getInventory(String sku) {
        return call(inventory, () -> http.get().uri("/inventory/{sku}", sku)
                .retrieve().body(JsonNode.class));
    }

    public JsonNode getPayment(String userId, String orderId) {
        return call(payments, () -> http.get().uri("/payments/{orderId}", orderId)
                .header("X-User-Id", userId)
                .retrieve().body(JsonNode.class));
    }

    public JsonNode createPaymentLink(String userId, String orderId) {
        return call(payments, () -> http.post().uri("/payments/{orderId}/link", orderId)
                .header("X-User-Id", userId)
                .retrieve().body(JsonNode.class));
    }

    public JsonNode cancelOrder(String userId, String orderId, String reason, String idempotencyKey) {
        return call(orders, () -> http.post().uri("/orders/{id}/cancel", orderId)
                .header("X-User-Id", userId)
                .header("Idempotency-Key", idempotencyKey)
                .body(Map.of("reason", reason))
                .retrieve().body(JsonNode.class));
    }
}
