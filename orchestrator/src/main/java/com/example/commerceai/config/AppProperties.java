package com.example.commerceai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All tunables in one place; bound from the {@code app.*} keys in application.yml. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Llm llm, Budget budget, Agent agent, Tools tools, boolean debugEndpoints) {

    /** provider: "anthropic" (Claude API) or "bedrock" (Claude on Amazon Bedrock). */
    public record Llm(String provider, String fastModel, String smartModel, boolean fallbacksEnabled, int timeoutSeconds) {}

    /** monthlyUsd: hard cap. degradeAtFraction: above this share of the cap, route everything to the fast model. */
    public record Budget(double monthlyUsd, double degradeAtFraction, long maxTokensPerSession) {}

    public record Agent(int maxToolRounds, long fastMaxTokens, long smartMaxTokens, int historyTurns) {}

    public record Tools(String baseUrl, int timeoutMs) {}
}
