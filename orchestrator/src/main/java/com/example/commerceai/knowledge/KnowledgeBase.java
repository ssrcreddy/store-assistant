package com.example.commerceai.knowledge;

import java.util.List;

/**
 * Retrieval for the search_policy tool. Two implementations, picked by app.rag.enabled:
 * {@link KeywordKnowledgeBase} (in memory, no database) and {@link HybridKnowledgeBase}
 * (Postgres full-text + pgvector, merged with reciprocal rank fusion).
 */
public interface KnowledgeBase {

    /** One retrieved chunk. The id ("returns#return-window") lets answers and evals cite the source. */
    record Passage(String id, String title, String text) {}

    List<Passage> search(String query, int limit);
}
