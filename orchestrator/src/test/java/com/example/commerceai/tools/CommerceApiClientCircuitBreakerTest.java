package com.example.commerceai.tools;

import com.example.commerceai.config.AppProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against a real local HTTP server, so timeouts and status codes behave as in production. */
class CommerceApiClientCircuitBreakerTest {

    private static final int WINDOW = 4;
    private static final int TIMEOUT_MS = 10_000;
    private static final int SHORT_TIMEOUT_MS = 150;
    private static final Duration OPEN_WAIT = Duration.ofMillis(300);

    private HttpServer server;
    private final AtomicInteger orderStatus = new AtomicInteger(200);
    private final AtomicInteger orderDelayMs = new AtomicInteger(0);
    private final AtomicInteger orderHits = new AtomicInteger();
    private final AtomicInteger cancelStatus = new AtomicInteger(200);
    private final AtomicInteger cancelHits = new AtomicInteger();
    private CommerceApiClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/orders", ex -> {
            boolean cancel = ex.getRequestURI().getPath().endsWith("/cancel");
            (cancel ? cancelHits : orderHits).incrementAndGet();
            sleep(cancel ? 0 : orderDelayMs.get());
            respond(ex, cancel ? cancelStatus.get() : orderStatus.get(),
                    cancel ? "{\"id\":\"ORD-1\",\"status\":\"cancelled\"}" : "{\"id\":\"ORD-1\",\"status\":\"placed\"}");
        });
        server.createContext("/inventory", ex -> respond(ex, 200, "{\"sku\":\"SKU-1\",\"available\":3}"));
        server.start();

        client = clientWithTimeout(TIMEOUT_MS);
    }

    private CommerceApiClient clientWithTimeout(int timeoutMs) {
        var breaker = new AppProperties.Tools.CircuitBreaker(50f, WINDOW, WINDOW, OPEN_WAIT, 1);
        var tools = new AppProperties.Tools("http://localhost:" + server.getAddress().getPort(), timeoutMs, breaker);
        return new CommerceApiClient(new AppProperties(null, null, null, tools, false));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        try {
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
        } catch (IOException clientAlreadyTimedOut) {
            // Expected in the timeout test.
        } finally {
            ex.close();
        }
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void failOrdersUntilOpen() {
        orderStatus.set(503);
        for (int i = 0; i < WINDOW; i++) {
            assertThatThrownBy(() -> client.getOrder("u-1", "ORD-1")).isInstanceOf(HttpServerErrorException.class);
        }
    }

    @Test
    void notFoundNeverOpensTheBreaker() {
        orderStatus.set(404);

        for (int i = 0; i < WINDOW * 3; i++) {
            assertThatThrownBy(() -> client.getOrder("u-1", "ORD-1")).isInstanceOf(HttpClientErrorException.NotFound.class);
        }

        assertThat(orderHits).hasValue(WINDOW * 3);
    }

    @Test
    void serverErrorsOpenTheBreakerAndLaterCallsFailFast() {
        failOrdersUntilOpen();
        assertThat(orderHits).hasValue(WINDOW);

        assertThatThrownBy(() -> client.getOrder("u-1", "ORD-1"))
                .isInstanceOf(ResourceAccessException.class)
                .hasMessageContaining("orders API circuit breaker is open");

        assertThat(orderHits).as("an open breaker must not call the API").hasValue(WINDOW);
    }

    @Test
    void timeoutsOpenTheBreaker() {
        CommerceApiClient impatient = clientWithTimeout(SHORT_TIMEOUT_MS);
        orderDelayMs.set(SHORT_TIMEOUT_MS * 3);
        for (int i = 0; i < WINDOW; i++) {
            assertThatThrownBy(() -> impatient.getOrder("u-1", "ORD-1")).isInstanceOf(ResourceAccessException.class);
        }
        orderDelayMs.set(0);

        assertThatThrownBy(() -> impatient.getOrder("u-1", "ORD-1")).hasMessageContaining("circuit breaker is open");
        assertThat(orderHits).hasValue(WINDOW);
    }

    @Test
    void anOutageInOneApiDoesNotBlockAnother() {
        failOrdersUntilOpen();

        assertThat(client.getInventory("SKU-1").path("available").asInt()).isEqualTo(3);
    }

    @Test
    void breakerClosesAgainOnceTheApiRecovers() {
        failOrdersUntilOpen();
        orderStatus.set(200);
        sleep((int) OPEN_WAIT.toMillis() + 100);

        assertThat(client.getOrder("u-1", "ORD-1").path("status").asText()).isEqualTo("placed");
        assertThat(client.getOrder("u-1", "ORD-1").path("status").asText()).isEqualTo("placed");
    }

    @Test
    void aFailedConfirmCanBeRetried() {
        PolicyEngine policy = new PolicyEngine(client);
        var proposal = (PolicyEngine.Proposal.Accepted) policy.proposeCancel("u-1", "s-1", "ORD-1", "mistake");
        String actionId = proposal.action().id();

        cancelStatus.set(503);
        assertThatThrownBy(() -> policy.confirm(actionId, "u-1")).isInstanceOf(HttpServerErrorException.class);

        cancelStatus.set(200);
        assertThat(policy.confirm(actionId, "u-1").path("status").asText()).isEqualTo("cancelled");
        assertThat(cancelHits).hasValue(2);

        assertThatThrownBy(() -> policy.confirm(actionId, "u-1"))
                .as("a successful confirm consumes the action")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
