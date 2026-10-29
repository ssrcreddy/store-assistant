package com.example.commerceai.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.example.commerceai.config.AppProperties;
import com.example.commerceai.routing.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * The only path to the model. Owns model choice per route, budget enforcement, refusal fallback
 * and metering, so no other class needs to know which provider or model is in use.
 */
@Component
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    private final AnthropicClient client;
    private final AppProperties props;
    private final UsageLedger ledger;

    public LlmGateway(@Lazy AnthropicClient client, AppProperties props, UsageLedger ledger) {
        this.client = client;
        this.props = props;
        this.ledger = ledger;
    }

    /** Applies the budget policy: above the degrade threshold, everything runs on the fast model. */
    public Route effectiveRoute(Route requested) {
        double spend = ledger.monthSpendUsd();
        double cap = props.budget().monthlyUsd();
        if (spend >= cap) {
            throw new BudgetExceededException("Monthly LLM budget of $" + cap + " reached");
        }
        if (requested == Route.SMART && spend >= cap * props.budget().degradeAtFraction()) {
            log.warn("Budget at {}% of cap; downgrading SMART route to FAST", Math.round(100 * spend / cap));
            return Route.FAST;
        }
        return requested;
    }

    public String modelFor(Route route) {
        return route == Route.SMART ? props.llm().smartModel() : props.llm().fastModel();
    }

    public long maxTokensFor(Route route) {
        return route == Route.SMART ? props.agent().smartMaxTokens() : props.agent().fastMaxTokens();
    }

    /**
     * Streams the response: each text delta goes to {@code onText} as it arrives, and the
     * accumulated message is returned for tool handling and metering. Separate text blocks are
     * joined with a newline, the same as the non-streaming answer.
     */
    public Message stream(MessageCreateParams.Builder params, String model, Consumer<String> onText) {
        addFallback(params, model);
        MessageAccumulator accumulator = MessageAccumulator.create();
        try (StreamResponse<RawMessageStreamEvent> response = client.messages().createStreaming(params.build())) {
            boolean[] sawText = {false};
            response.stream().forEach(event -> {
                accumulator.accumulate(event);
                event.contentBlockStart()
                        .filter(start -> start.contentBlock().isText())
                        .ifPresent(start -> {
                            if (sawText[0]) {
                                onText.accept("\n");
                            }
                            sawText[0] = true;
                        });
                event.contentBlockDelta()
                        .flatMap(delta -> delta.delta().text())
                        .ifPresent(text -> onText.accept(text.text()));
            });
        }
        return meter(model, accumulator.message());
    }

    private void addFallback(MessageCreateParams.Builder params, String model) {
        if (props.llm().fallbacksEnabled()
                && "anthropic".equalsIgnoreCase(props.llm().provider())
                && model.contains("sonnet-5-5")) {
            // Server-side refusal fallback: if a safety classifier declines, the API retries on a
            // suitable model instead of returning a bare refusal. Claude API only.
            params.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
    }

    private Message meter(String model, Message response) {
        var usage = response.usage();
        double cost = ModelPricing.costUsd(model, usage);
        long cacheRead = usage.cacheReadInputTokens().orElse(0L);
        ledger.recordLlmCall(usage.inputTokens(), usage.outputTokens(), cacheRead, cost);
        log.debug("model={} in={} out={} cacheRead={} cost=${}", model, usage.inputTokens(),
                usage.outputTokens(), cacheRead, cost);
        return response;
    }
}
