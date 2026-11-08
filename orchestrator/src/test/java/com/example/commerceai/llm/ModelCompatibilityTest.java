package com.example.commerceai.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ModelCompatibilityTest {

    private final LlmGateway gateway = new LlmGateway(null, null, null);

    @Test
    void baseIdStripsBedrockPrefixesAndVersionSuffixes() {
        assertThat(ModelPricing.baseId("claude-haiku-5-5")).isEqualTo("claude-haiku-5-5");
        assertThat(ModelPricing.baseId("anthropic.claude-sonnet-5-5")).isEqualTo("claude-sonnet-5-5");
        assertThat(ModelPricing.baseId("global.anthropic.claude-sonnet-4-6")).isEqualTo("claude-sonnet-4-6");
        assertThat(ModelPricing.baseId("anthropic.claude-haiku-4-5-20251001-v1:0")).isEqualTo("claude-haiku-4-5");
    }

    @Test
    void effortIsSentOnlyToModelsThatAcceptIt() {
        assertThat(gateway.supportsEffort("anthropic.claude-haiku-4-5")).isFalse();
        assertThat(gateway.supportsEffort("anthropic.claude-haiku-4-5-20251001-v1:0")).isFalse();
        assertThat(gateway.supportsEffort("anthropic.claude-sonnet-4-6")).isTrue();
        assertThat(gateway.supportsEffort("anthropic.claude-sonnet-5")).isTrue();
        assertThat(gateway.supportsEffort("claude-haiku-5-5")).isTrue();
        assertThat(gateway.supportsEffort("claude-sonnet-5-5")).isTrue();
    }

    @Test
    void olderModelsArePricedAtTheirOwnRates() {
        // 1M input + 1M output tokens
        assertThat(ModelPricing.costUsd("anthropic.claude-haiku-4-5", 1_000_000, 1_000_000, 0, 0)).isCloseTo(6.0, within(1e-9));
        assertThat(ModelPricing.costUsd("anthropic.claude-sonnet-4-6", 1_000_000, 1_000_000, 0, 0)).isCloseTo(18.0, within(1e-9));
    }
}
