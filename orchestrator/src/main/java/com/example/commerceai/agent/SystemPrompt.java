package com.example.commerceai.agent;

import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.TextBlockParam;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Versioned system prompt, loaded once. Keep it byte-identical between requests (no dates, user
 * data or request IDs) so it and the tool definitions are served from the prompt cache.
 */
@Component
public class SystemPrompt {

    public static final String VERSION = "system-v1";

    private final TextBlockParam block;

    public SystemPrompt() {
        String text;
        try {
            text = new ClassPathResource("prompts/" + VERSION + ".md").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.block = TextBlockParam.builder()
                .text(text)
                .cacheControl(CacheControlEphemeral.builder().build())
                .build();
    }

    public TextBlockParam cachedBlock() {
        return block;
    }
}
