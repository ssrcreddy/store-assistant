package com.example.commerceai.api;

import com.example.commerceai.config.AppProperties;
import com.example.commerceai.llm.UsageLedger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class MetricsController {

    private final UsageLedger ledger;
    private final AppProperties props;

    public MetricsController(UsageLedger ledger, AppProperties props) {
        this.ledger = ledger;
        this.props = props;
    }

    /** Spend, tokens, routes, p50/p95 latency and PII counters since startup. */
    @GetMapping("/api/metrics")
    public Map<String, Object> metrics() {
        return ledger.snapshot(props.budget().monthlyUsd());
    }

    /**
     * Dev only: the exact text sent to the LLM, so the PII eval can assert that no raw PII left
     * the system. Disabled unless app.debug-endpoints=true.
     */
    @RestController
    @ConditionalOnProperty(name = "app.debug-endpoints", havingValue = "true")
    static class DebugController {
        private final UsageLedger ledger;

        DebugController(UsageLedger ledger) {
            this.ledger = ledger;
        }

        @GetMapping("/api/debug/llm-inputs")
        public List<String> llmInputs() {
            return ledger.recentLlmInputs();
        }
    }
}
