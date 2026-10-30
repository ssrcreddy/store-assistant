package com.example.commerceai.knowledge;

/**
 * Turns text into a vector. The local implementation runs in the JVM; a Bedrock implementation
 * (Amazon Titan Text Embeddings) can replace it for AWS without touching the search code.
 * Anthropic has no embeddings model, so this is always a separate model from Claude.
 */
public interface Embedder {

    float[] embed(String text);

    /** Stored with each chunk, so changing the model re-embeds everything on the next start. */
    String modelId();

    int dimensions();
}
