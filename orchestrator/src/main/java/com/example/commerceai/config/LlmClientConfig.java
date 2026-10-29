package com.example.commerceai.config;

import com.anthropic.bedrock.backends.BedrockMantleBackend;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class LlmClientConfig {

    /**
     * Lazy so the app (template path, mock tools, tests) starts without credentials.
     * anthropic: reads ANTHROPIC_API_KEY. bedrock: reads AWS_REGION + the default AWS credential chain.
     */
    @Bean
    @Lazy
    public AnthropicClient anthropicClient(AppProperties props) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .timeout(Duration.ofSeconds(props.llm().timeoutSeconds()))
                .maxRetries(2);
        if ("bedrock".equalsIgnoreCase(props.llm().provider())) {
            builder.backend(BedrockMantleBackend.fromEnv());
        } else {
            builder.fromEnv();
        }
        return builder.build();
    }

    /** Runs agent turns off the servlet thread so SSE can stream progress events. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService agentExecutor() {
        return Executors.newFixedThreadPool(64);
    }
}
