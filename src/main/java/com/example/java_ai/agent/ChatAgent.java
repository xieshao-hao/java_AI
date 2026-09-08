package com.example.java_ai.agent;

import com.example.java_ai.router.RouteIntent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/** 闲聊 Agent：无 RAG 无工具，最短链路，省检索成本与延迟 */
@Component
public class ChatAgent implements Agent {

    private final ChatClient chatClient;

    public ChatAgent(ChatClient.Builder builder, ChatMemory chatMemory) {
        this.chatClient = builder
                .defaultSystem("你是一个友好的中文 AI 助手，负责日常寒暄和简单问答。"
                        + "回答保持简洁，不涉及设备、工单、运维知识等专业内容。")
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    @Override
    public List<RouteIntent> supportIntents() {
        return List.of(RouteIntent.CHITCHAT);
    }

    @Override
    public Flux<String> handle(AgentContext ctx) {
        return chatClient.prompt()
                .user(ctx.effectiveQuery())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, ctx.conversationId()))
                .stream()
                .content();
    }
}
