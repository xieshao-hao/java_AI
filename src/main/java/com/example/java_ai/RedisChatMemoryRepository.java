package com.example.java_ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis 的对话记忆持久化实现（替换默认的 InMemoryChatMemoryRepository）。
 *
 * 存储结构：key = chat:mem:{conversationId}，value = JSON 数组 [{"type":"USER","text":"..."}, ...]
 * 只持久化 USER / ASSISTANT / SYSTEM 三类消息文本；工具调用的中间消息不跨会话保留。
 * TTL 7 天，每次写入刷新，活跃会话不会过期。
 */
@Component
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryRepository.class);

    private static final String KEY_PREFIX = "chat:mem:";
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisChatMemoryRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /** 消息的持久化载体：类型 + 文本 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StoredMessage(String type, String text) {
    }

    @Override
    public List<String> findConversationIds() {
        Set<String> keys = redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Set<String>>) connection -> {
            Set<String> result = new HashSet<>();
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(
                    ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(100).build())) {
                while (cursor.hasNext()) {
                    result.add(new String(cursor.next()));
                }
            }
            return result;
        });
        List<String> ids = new ArrayList<>();
        if (keys != null) {
            for (String key : keys) {
                ids.add(key.substring(KEY_PREFIX.length()));
            }
        }
        return ids;
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + conversationId);
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<StoredMessage> stored = objectMapper.readValue(json, new TypeReference<List<StoredMessage>>() {
            });
            List<Message> messages = new ArrayList<>(stored.size());
            for (StoredMessage sm : stored) {
                Message msg = deserialize(sm);
                if (msg != null) {
                    messages.add(msg);
                }
            }
            return messages;
        } catch (Exception e) {
            // 记忆数据损坏时不应阻断对话，降级为空记忆并告警
            log.error("会话 {} 的记忆数据反序列化失败，已降级为空记忆：{}", conversationId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        try {
            List<StoredMessage> stored = new ArrayList<>(messages.size());
            for (Message msg : messages) {
                StoredMessage sm = serialize(msg);
                if (sm != null) {
                    stored.add(sm);
                }
            }
            String json = objectMapper.writeValueAsString(stored);
            redisTemplate.opsForValue().set(KEY_PREFIX + conversationId, json, TTL);
        } catch (Exception e) {
            log.error("会话 {} 的记忆写入 Redis 失败：{}", conversationId, e.getMessage());
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        redisTemplate.delete(KEY_PREFIX + conversationId);
    }

    /** 消息 → 持久化载体；工具类消息（TOOL 等）不持久化，历史重放只需用户/助手/系统文本 */
    private StoredMessage serialize(Message message) {
        return switch (message.getMessageType()) {
            case USER -> new StoredMessage("USER", message.getText());
            case ASSISTANT -> new StoredMessage("ASSISTANT", message.getText());
            case SYSTEM -> new StoredMessage("SYSTEM", message.getText());
            default -> null;
        };
    }

    /** 持久化载体 → 消息实例；无法识别的类型跳过 */
    private Message deserialize(StoredMessage sm) {
        if (sm == null || sm.text() == null) {
            return null;
        }
        return switch (sm.type()) {
            case "USER" -> new UserMessage(sm.text());
            case "ASSISTANT" -> new AssistantMessage(sm.text());
            case "SYSTEM" -> new SystemMessage(sm.text());
            default -> null;
        };
    }
}
