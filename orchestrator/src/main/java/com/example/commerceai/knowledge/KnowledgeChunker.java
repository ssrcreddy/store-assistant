package com.example.commerceai.knowledge;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Splits the markdown files in classpath:knowledge/ into chunks, one per "## " section.
 * Sections longer than {@link #MAX_CHARS} are split at paragraph boundaries so every chunk stays
 * focused (better retrieval) and small (fewer tokens per answer).
 */
public final class KnowledgeChunker {

    static final int MAX_CHARS = 1200;

    /** id is "&lt;file&gt;#&lt;section-slug&gt;", with "-2", "-3" for the parts of a long section. */
    public record Chunk(String id, String doc, String title, String content) {}

    private KnowledgeChunker() {}

    public static List<Chunk> loadClasspath() {
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:knowledge/*.md");
            List<Chunk> chunks = new ArrayList<>();
            Arrays.stream(files)
                    .sorted(Comparator.comparing(Resource::getFilename))
                    .forEach(r -> chunks.addAll(chunk(docName(r), read(r))));
            return chunks;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Chunk> chunk(String doc, String markdown) {
        List<Chunk> out = new ArrayList<>();
        for (String section : markdown.split("(?m)^## ")) {
            if (section.isBlank() || section.startsWith("#")) {
                continue; // text before the first "## " (the "# Title" line)
            }
            int nl = section.indexOf('\n');
            String title = (nl < 0 ? section : section.substring(0, nl)).trim();
            String body = nl < 0 ? "" : section.substring(nl + 1).trim();
            String id = doc + "#" + slug(title);
            List<String> parts = split(body);
            for (int i = 0; i < parts.size(); i++) {
                out.add(new Chunk(i == 0 ? id : id + "-" + (i + 1), doc, title, parts.get(i)));
            }
        }
        return out;
    }

    private static List<String> split(String body) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : body.split("\\n\\s*\\n")) {
            String p = paragraph.trim().replaceAll("\\s+", " ");
            if (!current.isEmpty() && current.length() + p.length() + 1 > MAX_CHARS) {
                parts.add(current.toString());
                current.setLength(0);
            }
            current.append(current.isEmpty() ? "" : " ").append(p);
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    static String slug(String title) {
        return title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static String docName(Resource r) {
        String name = r.getFilename();
        return name == null ? "doc" : name.replaceFirst("\\.md$", "");
    }

    private static String read(Resource r) {
        try {
            return r.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
