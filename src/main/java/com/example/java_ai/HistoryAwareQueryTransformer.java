/**
 * 感知对话历史的检索查询改写器：将带指代的追问改写成独立完整的检索查询，提升多轮对话下的向量检索命中质量。
 * 例："那它的启动命令呢？" → "AI训练的启动命令是什么"
 * 改写失败时降级为原始查询，不阻断对话。
 */
package com.example.java_ai;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 感知对话历史的检索查询改写器：把带指代的追问改写成独立完整的查询。
 * 例："那它的启动命令呢？" → "AI训练的启动命令是什么"
 * 改写失败时降级为原始查询，不阻断对话。
 */
@Component
public class HistoryAwareQueryTransformer implements QueryTransformer {

    private static final Logger log = LoggerFactory.getLogger(HistoryAwareQueryTransformer.class);

    private static final int MAX_HISTORY_MESSAGES = 6;

    private final ChatClient rewriteClient;
    private final ChatMemory chatMemory;

    public HistoryAwareQueryTransformer(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory) {
        // ChatClient.Builder 是 prototype 作用域：注入到本类的实例与 AiConfig 中使用的是
        // 不同实例，且此处 build 时不带任何 advisor，改写调用不会触发 RAG 造成递归
        this.rewriteClient = chatClientBuilder.build();
        this.chatMemory = chatMemory;
    }

    @Override
    public Query transform(Query query) {
        String conversationId = query.context() == null ? null
                : String.valueOf(query.context().get(ChatMemory.CONVERSATION_ID));
        if (!StringUtils.hasText(conversationId) || "null".equals(conversationId)) {
            return query;
        }
        String historyText = renderHistory(conversationId);
        if (historyText.isEmpty()) {
            return query;
        }
        try {
            String prompt = "根据下面的对话历史，把用户的最新提问改写成一个独立、完整、不含指代词的检索查询。\n"
                    + "要求：只输出改写后的查询本身，不要解释、不要加引号。\n"
                    + "如果最新提问本身已经完整、无指代词，请原样输出。\n\n"
                    + "对话历史：\n" + historyText + "\n\n"
                    + "最新提问：" + query.text();
            String rewritten = rewriteClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            if (!StringUtils.hasText(rewritten)) {
                return query;
            }
            log.debug("查询改写：[{}] → [{}]", query.text(), rewritten.trim());
            return Query.builder()
                    .text(rewritten.trim())
                    .history(query.history())
                    .context(query.context())
                    .build();
        } catch (Exception e) {
            log.warn("查询改写失败，已降级为原始查询：{}", e.getMessage());
            return query;
        }
    }

    private String renderHistory(String conversationId) {
        List<Message> messages = chatMemory.get(conversationId);
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        List<Message> chat = messages.stream()
                .filter(m -> m instanceof UserMessage || m instanceof AssistantMessage)
                .toList();
        if (chat.isEmpty()) {
            return "";
        }
        int from = Math.max(0, chat.size() - MAX_HISTORY_MESSAGES);
        return chat.subList(from, chat.size()).stream()
                .map(m -> (m instanceof UserMessage ? "用户：" : "助手：") + m.getText())
                .collect(Collectors.joining("\n"));
    }
}