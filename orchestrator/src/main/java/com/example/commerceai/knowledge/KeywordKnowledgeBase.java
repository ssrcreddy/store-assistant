package com.example.commerceai.knowledge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Default retrieval when no database is configured (app.rag.enabled=false, as on the AWS demo):
 * counts shared words between the query and each chunk. Fast and dependency-free, but misses
 * paraphrases ("send it back" vs "return"); {@link HybridKnowledgeBase} fixes that.
 */
@Component
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "false", matchIfMissing = true)
public class KeywordKnowledgeBase implements KnowledgeBase {

    private record Indexed(KnowledgeChunker.Chunk chunk, Set<String> terms) {}

    private final List<Indexed> index;

    public KeywordKnowledgeBase() {
        this.index = KnowledgeChunker.loadClasspath().stream()
                .map(c -> new Indexed(c, terms(c.title() + " " + c.content())))
                .toList();
    }

    @Override
    public List<Passage> search(String query, int limit) {
        Set<String> q = terms(query);
        return index.stream()
                .map(i -> new Object[] {i, i.terms().stream().filter(q::contains).count()})
                .filter(x -> (long) x[1] > 0)
                .sorted(Comparator.comparingLong(x -> -(long) x[1]))
                .limit(limit)
                .map(x -> ((Indexed) x[0]).chunk())
                .map(c -> new Passage(c.id(), c.title(), c.content()))
                .toList();
    }

    private static Set<String> terms(String s) {
        return Arrays.stream(s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(t -> t.length() > 3)
                .map(t -> t.endsWith("s") ? t.substring(0, t.length() - 1) : t)
                .collect(Collectors.toSet());
    }
}
