package com.example.java_ai;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    ChatMemory chatMemory(RedisChatMemoryRepository redisChatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .maxMessages(20)
                // 对话记忆持久化到 Redis，应用重启后会话可恢复
                .chatMemoryRepository(redisChatMemoryRepository)
                .build();
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder, VectorStore vectorStore, ChatMemory chatMemory,
                          KnowledgeTools knowledgeTools, RerankService rerankService) {
        return builder
                .defaultSystem("你是一个友好、严谨的中文 AI 助手。请使用简洁的中文回答用户的问题，"
                        + "如果不确定答案请明确说明，不要编造内容。回答时优先依据知识库中检索到的上下文。"
                        + "当用户询问时间、日期、数学计算、知识库统计信息时，请主动调用对应的工具获取准确结果。")
                .defaultTools(knowledgeTools)
                .defaultAdvisors(
                        // PromptChatMemoryAdvisor：把对话历史拼进 system 消息文本。
                        // 不能用 MessageChatMemoryAdvisor（消息列表注入），会被 RAG advisor 的
                        // 消息重建丢弃导致记忆失效；system 文本不受 RAG 增强影响
                        PromptChatMemoryAdvisor.builder(chatMemory).build(),
                        // 两阶段 RAG 检索：向量召回 Top-20 → Rerank 精排 Top-4
                        RetrievalAugmentationAdvisor.builder()
                                // 自定义增强模板：默认模板强制"只准依据上下文、否则说不知道"，
                                // 会压制对话历史，导致记忆失效；这里明确允许结合历史回答
                                .queryAugmenter(ContextualQueryAugmenter.builder()
                                        .promptTemplate(new PromptTemplate("""
                                                用户问题：
                                                {query}

                                                知识库上下文：
                                                ---------------------
                                                {context}
                                                ---------------------

                                                回答规则：
                                                1. 优先依据上面的知识库上下文回答；
                                                2. 如果上下文中没有，就结合对话历史回答；
                                                3. 两者都没有时才坦率说明不知道，不要编造。
                                                """))
                                        .allowEmptyContext(true)
                                        .build())
                                .documentRetriever((Query query) -> {
                                    String searchText = query.text();
                                    List<Document> candidates = vectorStore.similaritySearch(
                                            SearchRequest.builder()
                                                    .query(searchText)
                                                    .topK(20)
                                                    .similarityThreshold(0.3)
                                                    .build());
                                    if (candidates == null || candidates.isEmpty()) {
                                        return List.of();
                                    }
                                    return rerankService.rerank(searchText, candidates, 4);
                                })
                                .build())
                .build();
    }
}