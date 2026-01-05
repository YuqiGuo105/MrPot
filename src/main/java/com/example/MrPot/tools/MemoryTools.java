package com.example.MrPot.tools;

import com.example.MrPot.service.RedisChatMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class MemoryTools {

    private static final int DEFAULT_LAST_N = 12;

    private final RedisChatMemoryService chatMemoryService;

    @Tool(name = "memory_recent", description = "Get recent chat history for a session in a rendered text form.")
    public String recent(String sessionId, Integer lastN) {
        int n = (lastN == null || lastN <= 0) ? DEFAULT_LAST_N : lastN;
        List<RedisChatMemoryService.StoredMessage> history = chatMemoryService.loadHistory(sessionId);
        if (history == null || history.isEmpty()) return "";

        int start = Math.max(0, history.size() - n);
        List<RedisChatMemoryService.StoredMessage> recent = history.subList(start, history.size());
        return chatMemoryService.renderHistory(recent);
    }
}
