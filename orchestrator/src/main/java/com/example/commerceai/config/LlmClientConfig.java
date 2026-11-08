package com.example.commerceai.config;

import com.anthropic.bedrock.backends.BedrockBackend;
import com.anthropic.bedrock.backends.BedrockMantleBackend;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class LlmClientConfig {

    /**
     * Lazy so the app (template path, mock tools, tests) starts without credentials.
     * <ul>
     *   <li>anthropic: Claude API, reads ANTHROPIC_API_KEY.</li>
     *   <li>bedrock: Claude in Amazon Bedrock on the Messages API endpoint (bedrock-mantle), for Claude
     *       4.7 and later. Model IDs look like "anthropic.claude-haiku-5-5".</li>
     *   <li>bedrock-runtime: Bedrock's InvokeModel endpoint, for Claude 4.6 and earlier, and for
     *       accounts where only that endpoint is enabled. Model IDs are inference profiles, like
     *       "global.anthropic.claude-sonnet-4-6".</li>
     * </ul>
     * Both Bedrock options read AWS_REGION and the default AWS credential chain (the ECS task role on AWS).
     */
    @Bean
    @Lazy
    public AnthropicClient anthropicClient(AppProperties props) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .timeout(Duration.ofSeconds(props.llm().timeoutSeconds()))
                .maxRetries(2);
        switch (props.llm().provider().toLowerCase(Locale.ROOT)) {
            case "bedrock" -> builder.backend(BedrockMantleBackend.fromEnv());
            case "bedrock-runtime" -> builder.backend(BedrockBackend.fromEnv());
            default -> builder.fromEnv();
        }
        return builder.build();
    }

    /** Runs agent turns off the servlet thread so SSE can stream progress events. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService agentExecutor() {
        return Executors.newFixedThreadPool(64);
    }
}
