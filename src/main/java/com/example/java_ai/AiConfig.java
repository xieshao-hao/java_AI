package com.example.java_ai;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    /** 来源展示的最低相关度阈值：低于此值的检索片段不作为参考来源推送 */
    private static final float SOURCE_MIN_SCORE = 0.5f;

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
                          KnowledgeTools knowledgeTools, RerankService rerankService,
                          ToolCallTracker toolCallTracker) {
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
                                // 查询改写已上移到 IntentRouter（改写+意图分类合并为一次 LLM 调用），
                                // RAG 层不再重复改写，直接使用路由层输出的独立完整查询
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
                                    List<Document> documents = rerankService.rerank(searchText, candidates, 4);
                                    emitSourceFiles(query, documents, toolCallTracker);
                                    return documents;
                                })
                                .build())
                .build();
    }

    /** 检索完成后提取去重文件名，通过 ToolCallTracker 推送给前端展示参考来源 */
    private void emitSourceFiles(Query query, List<Document> documents, ToolCallTracker toolCallTracker) {
        Set<String> fileNames = new LinkedHashSet<>();
        for (Document doc : documents) {
            // 相关度过低的片段不作为来源展示（如闲聊问题误召回的内容）
            if (doc.getScore() < SOURCE_MIN_SCORE) {
                continue;
            }
            Object fileName = doc.getMetadata().get("fileName");
            if (fileName != null && !fileName.toString().isBlank()) {
                fileNames.add(fileName.toString());
            }
        }
        if (fileNames.isEmpty()) {
            return;
        }
        Object conversationId = query.context().get(ChatMemory.CONVERSATION_ID);
        if (conversationId != null) {
            toolCallTracker.emitSources(conversationId.toString(), List.copyOf(fileNames));
        }
    }
}