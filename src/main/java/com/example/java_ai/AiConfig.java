package com.example.java_ai;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(20)
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
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        // 两阶段 RAG 检索：向量召回 Top-20 → Rerank 精排 Top-4
                        RetrievalAugmentationAdvisor.builder()
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