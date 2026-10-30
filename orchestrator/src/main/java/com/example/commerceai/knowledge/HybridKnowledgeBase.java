package com.example.commerceai.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Retrieval over Postgres: full-text search (exact words, SKUs, policy terms) and pgvector
 * similarity (paraphrases: "send it back" finds "Return window"), merged with reciprocal rank
 * fusion (RRF). RRF uses only ranks, so the two very different scores never need calibrating.
 *
 * Full-text matching requires every query word (plainto_tsquery, AND). Matching any word (OR)
 * was tried and measured worse: generic words like "order" or "add" pulled unrelated chunks above
 * good vector matches (hybrid recall@3 0.79 with OR vs 1.00 with AND on evals/retrieval.jsonl).
 *
 * At startup it syncs classpath:knowledge/*.md into kb_chunk: new or edited chunks are embedded,
 * removed ones are deleted, unchanged ones are skipped. Startup ordering puts this before the
 * warm-up and readiness, so the service never answers from a half-loaded index.
 */
@Component
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true")
public class HybridKnowledgeBase implements KnowledgeBase, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HybridKnowledgeBase.class);

    /** RRF constant from the original paper; damps the advantage of rank 1 over rank 2. */
    private static final int RRF_K = 60;
    /** Candidates taken from each search before fusion. */
    private static final int CANDIDATES = 20;

    public enum Mode { KEYWORD, VECTOR, HYBRID }

    /** A search result with the rank it had in each list (null if absent), for evals and debugging. */
    public record Hit(String id, String title, String text, double score, Integer vectorRank, Integer keywordRank,
                      Double vectorDistance) {}

    private static final String SEARCH_SQL = """
            WITH v AS (
                SELECT id, d, row_number() OVER (ORDER BY d) AS r
                FROM (SELECT id, embedding <=> CAST(? AS vector) AS d
                      FROM kb_chunk ORDER BY d LIMIT %1$d) nearest
                WHERE d < ?
            ),
            k AS (
                SELECT id, row_number() OVER (ORDER BY rank DESC) AS r
                FROM (SELECT id, ts_rank_cd(tsv, q) AS rank
                      FROM kb_chunk,
                           plainto_tsquery('english', ?) q
                      WHERE tsv @@ q ORDER BY rank DESC LIMIT %1$d) matched
            )
            SELECT * FROM (
                SELECT c.id, c.title, c.content, v.r AS vr, k.r AS kr, v.d AS vd,
                       CASE WHEN ? THEN COALESCE(1.0 / (%2$d + v.r), 0) ELSE 0 END
                     + CASE WHEN ? THEN ? * COALESCE(1.0 / (%2$d + k.r), 0) ELSE 0 END AS score
                FROM kb_chunk c
                LEFT JOIN v ON v.id = c.id
                LEFT JOIN k ON k.id = c.id
            ) scored
            WHERE score > 0
            ORDER BY score DESC, id
            LIMIT ?
            """.formatted(CANDIDATES, RRF_K);

    private final JdbcTemplate jdbc;
    private final Embedder embedder;
    private final double maxDistance;
    private final double keywordWeight;

    public HybridKnowledgeBase(JdbcTemplate jdbc, Embedder embedder,
                               @Value("${app.rag.max-vector-distance:0.85}") double maxDistance,
                               @Value("${app.rag.keyword-weight:1.0}") double keywordWeight) {
        this.jdbc = jdbc;
        this.embedder = embedder;
        this.maxDistance = maxDistance;
        this.keywordWeight = keywordWeight;
    }

    @Override
    public void run(ApplicationArguments args) {
        sync(KnowledgeChunker.loadClasspath());
    }

    void sync(List<KnowledgeChunker.Chunk> chunks) {
        long start = System.nanoTime();
        Map<String, String> stored = new HashMap<>();
        jdbc.query("SELECT id, content_hash FROM kb_chunk", rs -> {
            stored.put(rs.getString(1), rs.getString(2));
        });

        int embedded = 0;
        for (KnowledgeChunker.Chunk c : chunks) {
            String hash = hash(embedder.modelId() + "\n" + c.title() + "\n" + c.content());
            if (hash.equals(stored.get(c.id()))) {
                continue;
            }
            jdbc.update("""
                    INSERT INTO kb_chunk (id, doc, title, content, content_hash, embedding_model, embedding, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS vector), now())
                    ON CONFLICT (id) DO UPDATE SET doc = EXCLUDED.doc, title = EXCLUDED.title,
                        content = EXCLUDED.content, content_hash = EXCLUDED.content_hash,
                        embedding_model = EXCLUDED.embedding_model, embedding = EXCLUDED.embedding, updated_at = now()
                    """,
                    c.id(), c.doc(), c.title(), c.content(), hash, embedder.modelId(),
                    literal(embedder.embed(c.title() + ". " + c.content())));
            embedded++;
        }

        Set<String> current = chunks.stream().map(KnowledgeChunker.Chunk::id).collect(Collectors.toSet());
        List<String> removed = stored.keySet().stream().filter(id -> !current.contains(id)).toList();
        removed.forEach(id -> jdbc.update("DELETE FROM kb_chunk WHERE id = ?", id));

        log.info("Knowledge base synced in {} ms: {} chunks, {} embedded, {} removed",
                (System.nanoTime() - start) / 1_000_000, chunks.size(), embedded, removed.size());
    }

    @Override
    public List<Passage> search(String query, int limit) {
        return search(query, limit, Mode.HYBRID).stream()
                .map(h -> new Passage(h.id(), h.title(), h.text()))
                .toList();
    }

    public List<Hit> search(String query, int limit, Mode mode) {
        String vector = mode == Mode.KEYWORD ? literal(new float[embedder.dimensions()]) : literal(embedder.embed(query));
        return jdbc.query(SEARCH_SQL,
                (rs, i) -> new Hit(rs.getString("id"), rs.getString("title"), rs.getString("content"),
                        rs.getDouble("score"), rank(rs.getObject("vr")), rank(rs.getObject("kr")),
                        (Double) rs.getObject("vd")),
                vector, maxDistance, query, mode != Mode.KEYWORD, mode != Mode.VECTOR,
                mode == Mode.KEYWORD ? 1.0 : keywordWeight, limit);
    }

    /** row_number() is a bigint; null means the chunk wasn't in that result list. */
    private static Integer rank(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    /** pgvector's text form, "[0.1,0.2,...]"; cast to vector in SQL, so no extra JDBC type is needed. */
    static String literal(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            sb.append(i == 0 ? "" : ",").append(v[i]);
        }
        return sb.append(']').toString();
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
