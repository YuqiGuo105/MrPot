package com.example.MrPot.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AiConfig {

    @Bean
    @Primary
    public EmbeddingModel embeddingModel(@Qualifier("openAiEmbeddingModel") EmbeddingModel openAi) {
        return openAi;
    }

    /**
     * DeepSeek is the default ChatClient.
     * This bean is only created when a DeepSeekChatModel bean exists
     * (so the app won't fail to start if the DeepSeek API key is missing in some envs).
     */
    @Bean
    @Primary
    @ConditionalOnBean(DeepSeekChatModel.class)
    public ChatClient deepseekChatClient(DeepSeekChatModel model) {
        return ChatClient.builder(model)
                .defaultSystem("You're Mr Pot, Yuqi's LLM Agent")
                .build();
    }

    /**
     * OpenAI ChatClient as an alternative.
     * This bean is only created when an OpenAiChatModel bean exists
     * (so missing OpenAI API key will not break the app).
     */
    @Bean
    @ConditionalOnBean(OpenAiChatModel.class)
    public ChatClient openaiChatClient(OpenAiChatModel model) {
        return ChatClient.builder(model)
                .defaultSystem("You're Mr Pot, Yuqi's LLM Agent")
                .build();
    }

    @Bean
    @ConditionalOnBean(DashScopeChatModel.class)
    public ChatClient qwenChatClient(DashScopeChatModel model) {
        return ChatClient.builder(model)
                .defaultSystem("You're Mr Pot, Yuqi's LLM Agent")
                .defaultOptions(DashScopeChatOptions.builder()
                        .model("qwen-vl-plus")
                        .build())
                .build();
    }

    /**
     * If no ChatClient beans are registered (e.g. missing @ConditionalOnBean matches),
     * build a default client using DeepSeek when available, otherwise fall back to OpenAI.
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(ChatClient.class)
    public ChatClient defaultChatClient(
            ObjectProvider<DeepSeekChatModel> deepSeekProvider,
            ObjectProvider<OpenAiChatModel> openAiProvider
    ) {
        DeepSeekChatModel deepseekModel = deepSeekProvider.getIfAvailable();
        if (deepseekModel != null) {
            return ChatClient.builder(deepseekModel)
                    .defaultSystem("You're Mr Pot, Yuqi's LLM Agent")
                    .build();
        }

        OpenAiChatModel openAiModel = openAiProvider.getIfAvailable();
        if (openAiModel != null) {
            return ChatClient.builder(openAiModel)
                    .defaultSystem("You're Mr Pot, Yuqi's LLM Agent")
                    .build();
        }

        throw new IllegalStateException("No ChatModel beans are available to build a ChatClient");
    }
}
