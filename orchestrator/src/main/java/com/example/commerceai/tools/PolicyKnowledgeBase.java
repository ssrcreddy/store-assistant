package com.example.commerceai.tools;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI layer 5 placeholder: keyword search over policy sections in policies.md.
 * Replace with embeddings in Postgres + pgvector (hybrid search) once the policy set grows.
 */
@Component
public class PolicyKnowledgeBase {

    private record Section(String title, String body, Set<String> terms) {}

    private final List<Section> sections;

    public PolicyKnowledgeBase() {
        this.sections = load();
    }

    public List<String> search(String query, int limit) {
        Set<String> q = terms(query);
        return sections.stream()
                .map(s -> new Object[] {s, s.terms().stream().filter(q::contains).count()})
                .filter(x -> (long) x[1] > 0)
                .sorted(Comparator.comparingLong(x -> -(long) x[1]))
                .limit(limit)
                .map(x -> {
                    Section s = (Section) x[0];
                    return s.title() + ": " + s.body();
                })
                .toList();
    }

    private static List<Section> load() {
        String text;
        try {
            text = new ClassPathResource("policies.md").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Section> out = new ArrayList<>();
        for (String chunk : text.split("(?m)^## ")) {
            if (chunk.isBlank() || chunk.startsWith("#")) {
                continue;
            }
            int nl = chunk.indexOf('\n');
            String title = chunk.substring(0, nl).trim();
            String body = chunk.substring(nl + 1).trim().replaceAll("\\s+", " ");
            out.add(new Section(title, body, terms(title + " " + body)));
        }
        return out;
    }

    private static Set<String> terms(String s) {
        return Arrays.stream(s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(t -> t.length() > 3)
                .map(t -> t.endsWith("s") ? t.substring(0, t.length() - 1) : t)
                .collect(Collectors.toSet());
    }
}
