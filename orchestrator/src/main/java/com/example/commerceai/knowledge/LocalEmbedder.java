package com.example.commerceai.knowledge;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * all-MiniLM-L6-v2 (384 dimensions) via ONNX inside the JVM: no API key, no network, no cost,
 * a few milliseconds per query. Good enough for short English support content; a larger model
 * (Titan, Voyage) would retrieve better on long or specialised documents.
 */
@Component
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true")
public class LocalEmbedder implements Embedder {

    private final EmbeddingModel model = new AllMiniLmL6V2EmbeddingModel();

    @Override
    public float[] embed(String text) {
        return model.embed(text).content().vector();
    }

    @Override
    public String modelId() {
        return "all-minilm-l6-v2";
    }

    @Override
    public int dimensions() {
        return 384;
    }
}
