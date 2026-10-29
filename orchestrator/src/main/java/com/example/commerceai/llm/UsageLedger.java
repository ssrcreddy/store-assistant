package com.example.commerceai.llm;

import com.example.commerceai.routing.Route;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * In-memory metrics for the POC: spend, tokens, routes, latency percentiles, PII counters.
 * In production these become Micrometer metrics (CloudWatch) and a Postgres ledger for spend.
 */
@Component
public class UsageLedger {

    private static final int WINDOW = 2_000;
    private static final int LLM_INPUT_LOG = 500;

    private final DoubleAdder monthSpendUsd = new DoubleAdder();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong llmCalls = new AtomicLong();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong cacheReadTokens = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong piiRedactions = new AtomicLong();
    private final AtomicLong leaksBlocked = new AtomicLong();
    private final Map<Route, AtomicLong> byRoute = new EnumMap<>(Route.class);
    private final Deque<Long> latenciesMs = new ArrayDeque<>();
    private final Deque<String> llmInputs = new ArrayDeque<>();

    public UsageLedger() {
        for (Route r : Route.values()) {
            byRoute.put(r, new AtomicLong());
        }
    }

    public double monthSpendUsd() {
        return monthSpendUsd.sum();
    }

    public void recordLlmCall(long in, long out, long cacheRead, double costUsd) {
        llmCalls.incrementAndGet();
        inputTokens.addAndGet(in);
        outputTokens.addAndGet(out);
        cacheReadTokens.addAndGet(cacheRead);
        monthSpendUsd.add(costUsd);
    }

    public void recordRequest(Route route, long latencyMs, boolean fromCache) {
        requests.incrementAndGet();
        if (fromCache) {
            cacheHits.incrementAndGet();
        } else {
            byRoute.get(route).incrementAndGet();
        }
        synchronized (latenciesMs) {
            latenciesMs.addLast(latencyMs);
            if (latenciesMs.size() > WINDOW) {
                latenciesMs.removeFirst();
            }
        }
    }

    public void recordPii(int redactions, boolean leakBlocked) {
        piiRedactions.addAndGet(redactions);
        if (leakBlocked) {
            leaksBlocked.incrementAndGet();
        }
    }

    /** Keeps the text actually sent to the LLM so the PII eval can prove no raw PII left the system. */
    public void recordLlmInput(String text) {
        synchronized (llmInputs) {
            llmInputs.addLast(text);
            if (llmInputs.size() > LLM_INPUT_LOG) {
                llmInputs.removeFirst();
            }
        }
    }

    public List<String> recentLlmInputs() {
        synchronized (llmInputs) {
            return List.copyOf(llmInputs);
        }
    }

    public Map<String, Object> snapshot(double budgetUsd) {
        List<Long> sorted;
        synchronized (latenciesMs) {
            sorted = new ArrayList<>(latenciesMs);
        }
        Collections.sort(sorted);
        Map<String, Long> routes = new LinkedHashMap<>();
        byRoute.forEach((k, v) -> routes.put(k.name(), v.get()));
        double spend = monthSpendUsd.sum();
        long reqs = requests.get();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requests", reqs);
        out.put("cacheHits", cacheHits.get());
        out.put("byRoute", routes);
        out.put("llmCalls", llmCalls.get());
        out.put("inputTokens", inputTokens.get());
        out.put("outputTokens", outputTokens.get());
        out.put("cacheReadTokens", cacheReadTokens.get());
        out.put("spendUsd", round(spend));
        out.put("budgetUsd", budgetUsd);
        out.put("costPerRequestUsd", reqs == 0 ? 0 : round(spend / reqs));
        out.put("latencyP50Ms", percentile(sorted, 0.50));
        out.put("latencyP95Ms", percentile(sorted, 0.95));
        out.put("piiRedactions", piiRedactions.get());
        out.put("outputLeaksBlocked", leaksBlocked.get());
        return out;
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static double round(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }
}
