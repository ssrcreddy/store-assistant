package com.example.commerceai.llm;

import com.anthropic.models.messages.Usage;

import java.util.Map;

/**
 * List prices in USD per million tokens. Cache writes are billed at 1.25x input (5-minute TTL),
 * cache reads at 0.1x input. Check current pricing before quoting numbers externally.
 */
public final class ModelPricing {

    private record Price(double input, double output) {}

    private static final Map<String, Price> PRICES = Map.of(
            "claude-haiku-5-5", new Price(0.10, 0.50),
            "claude-sonnet-5-5", new Price(2.00, 10.00),
            "claude-opus-5-5", new Price(4.00, 20.00));

    private ModelPricing() {}

    public static double costUsd(String model, Usage usage) {
        return costUsd(model, usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens().orElse(0L), usage.cacheReadInputTokens().orElse(0L));
    }

    public static double costUsd(String model, long input, long output, long cacheWrite, long cacheRead) {
        Price p = PRICES.getOrDefault(baseId(model), new Price(2.00, 10.00));
        return (input * p.input()
                + cacheWrite * p.input() * 1.25
                + cacheRead * p.input() * 0.10
                + output * p.output()) / 1_000_000.0;
    }

    /** Bedrock IDs carry an "anthropic." prefix; pricing is keyed by the bare model ID. */
    static String baseId(String model) {
        return model.startsWith("anthropic.") ? model.substring("anthropic.".length()) : model;
    }
}
