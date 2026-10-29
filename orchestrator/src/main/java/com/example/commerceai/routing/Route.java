package com.example.commerceai.routing;

public enum Route {
    /** Deterministic answer from an API call plus a template; no LLM. */
    TEMPLATE,
    /** Fast, cheap model (Haiku) for lookups and FAQs. */
    FAST,
    /** Stronger model (Sonnet) for multi-step work and write actions. */
    SMART
}
