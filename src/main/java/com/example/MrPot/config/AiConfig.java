package com.example.MrPot.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AiConfig {

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
}
