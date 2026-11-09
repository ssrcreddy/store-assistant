package com.example.commerceai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** All tunables in one place; bound from the {@code app.*} keys in application.yml. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Llm llm, Budget budget, Agent agent, Tools tools, boolean debugEndpoints) {

    /**
     * provider: "anthropic" (Claude API) or "bedrock" (Claude on Amazon Bedrock).
     * warmup: send one tiny fast-model request at startup so the first user isn't served cold.
     */
    public record Llm(String provider, String fastModel, String smartModel, boolean fallbacksEnabled,
                      int timeoutSeconds, boolean warmup) {}

    /** monthlyUsd: hard cap. degradeAtFraction: above this share of the cap, route everything to the fast model. */
    public record Budget(double monthlyUsd, double degradeAtFraction, long maxTokensPerSession) {}

    public record Agent(int maxToolRounds, long fastMaxTokens, long smartMaxTokens, int historyTurns) {}

    public record Tools(String baseUrl, int timeoutMs, CircuitBreaker circuitBreaker) {

        /**
         * One breaker per legacy API. It opens when failureRatePercent of the last windowSize calls
         * failed (once minimumCalls have been made), rejects calls for openWait, then lets
         * halfOpenCalls test calls through to decide whether to close again.
         */
        public record CircuitBreaker(float failureRatePercent, int windowSize, int minimumCalls,
                                     Duration openWait, int halfOpenCalls) {}
    }
}
