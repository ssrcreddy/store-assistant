-- Knowledge base for retrieval (app.rag.enabled=true). Applied by Flyway at startup.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE kb_chunk (
    id              text PRIMARY KEY,              -- "<doc>#<section-slug>", e.g. "returns#return-window"
    doc             text        NOT NULL,
    title           text        NOT NULL,
    content         text        NOT NULL,
    content_hash    text        NOT NULL,          -- sha-256 of model + title + content; unchanged chunks aren't re-embedded
    embedding_model text        NOT NULL,
    embedding       vector(384) NOT NULL,          -- all-MiniLM-L6-v2
    tsv             tsvector GENERATED ALWAYS AS (to_tsvector('english', title || ' ' || content)) STORED,
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- Approximate nearest neighbour search on cosine distance, and full-text search.
CREATE INDEX kb_chunk_embedding_idx ON kb_chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX kb_chunk_tsv_idx ON kb_chunk USING gin (tsv);
