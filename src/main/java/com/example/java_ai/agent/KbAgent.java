package com.example.java_ai.agent;

import com.example.java_ai.router.RouteIntent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/** 知识库 Agent：复用 AiConfig 中原有的完整 RAG 链路（两阶段检索 + rerank + 来源推送） */
@Component
public class KbAgent implements Agent {

    private final ChatClient chatClient;

    public KbAgent(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public List<RouteIntent> supportIntents() {
        return List.of(RouteIntent.KNOWLEDGE);
    }

    @Override
    public boolean usesToolTracker() {
        return true;
    }

    @Override
    public Flux<String> handle(AgentContext ctx) {
        return chatClient.prompt()
                .user(ctx.effectiveQuery())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, ctx.conversationId()))
                .toolContext(Map.of("conversationId", ctx.conversationId()))
                .stream()
                .content();
    }
}
