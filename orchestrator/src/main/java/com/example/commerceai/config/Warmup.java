package com.example.commerceai.config;

import com.example.commerceai.agent.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Warms the service at startup so the first real user doesn't pay for JIT, TLS handshakes and the
 * prompt-cache write (measured: 5-11 s for the first LLM answer after a restart, vs ~1.4 s warm).
 *
 * Runs on ApplicationReadyEvent, before Spring Boot reports readiness as ACCEPTING_TRAFFIC, so an
 * orchestrator using the readiness probe (ECS, Kubernetes) only sends traffic once it's warm.
 * A failure is logged and ignored: the service still starts, just cold.
 */
@Component
public class Warmup {

    private static final Logger log = LoggerFactory.getLogger(Warmup.class);

    private final AgentService agent;
    private final AppProperties props;

    public Warmup(AgentService agent, AppProperties props) {
        this.agent = agent;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!props.llm().warmup()) {
            return;
        }
        long start = System.nanoTime();
        try {
            agent.warmUp();
            log.info("Warm-up finished in {} ms", (System.nanoTime() - start) / 1_000_000);
        } catch (RuntimeException e) {
            log.warn("Warm-up failed; the first request will be slower: {}", e.getMessage());
        }
    }
}
