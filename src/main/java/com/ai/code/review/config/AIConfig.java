package com.ai.code.review.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * AI configuration providing ChatClient beans for agent use.
 *
 * Configures model options such as temperature for consistent
 * code review output across all agents.
 */
@Configuration
@EnableAsync
public class AIConfig {

    /**
     * Provides the base ChatClient.Builder, auto-configured by Spring AI
     * with the OpenAI model (gpt-4o) and API key from environment.
     *
     * @param chatModel the auto-configured ChatModel bean
     * @return configured ChatClient.Builder instance
     */
    @Bean
    public ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }

    /**
     * Provides a pre-configured ChatClient with temperature 0.1
     * for consistent, deterministic code review results.
     *
     * @param builder the auto-configured ChatClient.Builder
     * @return configured ChatClient instance
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultOptions(OpenAiChatOptions.builder()
                        .temperature(0.1)
                        .build())
                .build();
    }
}
