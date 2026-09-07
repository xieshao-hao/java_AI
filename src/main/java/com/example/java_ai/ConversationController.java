package com.example.java_ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话管理接口：让持久化到 Redis 的对话记忆对用户可见、可管理。
 *
 * - GET    /conversations            会话列表（标题 + 消息数 + 最后一条预览）
 * - GET    /conversations/{id}/messages  历史消息回显（前端刷新后恢复聊天记录）
 * - DELETE /conversations/{id}       删除会话（同时清内存窗口与 Redis 持久化数据）
 */
@RestController
@RequestMapping("/conversations")
public class ConversationController {

    private final ChatMemory chatMemory;
    private final RedisChatMemoryRepository memoryRepository;

    public ConversationController(ChatMemory chatMemory,
                                  RedisChatMemoryRepository memoryRepository) {
        this.chatMemory = chatMemory;
        this.memoryRepository = memoryRepository;
    }

    /** 会话列表：标题取首条用户消息截断，预览取最后一条消息截断 */
    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : memoryRepository.findConversationIds()) {
            List<Message> messages = chatMemory.get(id);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("conversationId", id);
            item.put("title", extractTitle(messages));
            item.put("messageCount", messages.size());
            item.put("lastMessage", extractLast(messages));
            result.add(item);
        }
        return result;
    }

    /** 历史消息回显：只返回 USER / ASSISTANT；SYSTEM 是注入的提示词，不属于用户可见聊天记录 */
    @GetMapping("/{id}/messages")
    public ResponseEntity<List<Map<String, Object>>> messages(@PathVariable String id) {
        List<Message> messages = chatMemory.get(id);
        if (messages.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> result = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            if (msg instanceof UserMessage || msg instanceof AssistantMessage) {  
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", msg.getMessageType().name().toLowerCase());
                m.put("content", msg.getText());
                result.add(m);
            }
        }
        return ResponseEntity.ok(result);
    }

    /** 删除会话：chatMemory.clear 会同时清内存窗口与 Redis 中的持久化数据 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        chatMemory.clear(id);
        return ResponseEntity.ok(Map.of("message", "会话已删除"));
    }

    private String extractTitle(List<Message> messages) {
        return messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(Message::getText)
                .findFirst()
                .map(t -> t.length() > 20 ? t.substring(0, 20) + "…" : t)
                .orElse("新会话");
    }

    private String extractLast(List<Message> messages) {
        if (messages.isEmpty()) {
            return "";
        }
        String text = messages.get(messages.size() - 1).getText();
        return text == null ? "" : (text.length() > 50 ? text.substring(0, 50) + "…" : text);
    }
}
