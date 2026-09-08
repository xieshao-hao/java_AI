package com.example.java_ai.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 路由三段式：规则快筛 → Redis 缓存 → LLM 改写+分类 → 置信度兜底。
 * 任何一步失败都不抛异常：路由永不失败，宁可多检索不可漏召回
 */
@Component
public class IntentRouter {

    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);
    private static final float CONFIDENCE_THRESHOLD = 0.8f;
    private static final String CACHE_KEY_PREFIX = "smartdesk:route:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final ChatClient routerChatClient;
    private final RuleIntentMatcher ruleMatcher;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ChatMemory chatMemory;

    public IntentRouter(ChatClient routerChatClient, RuleIntentMatcher ruleMatcher,
                        StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                        ChatMemory chatMemory) {
        this.routerChatClient = routerChatClient;
        this.ruleMatcher = ruleMatcher;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.chatMemory = chatMemory;
    }

    public RouteDecision route(String userMessage, String conversationId) {
        long start = System.currentTimeMillis();

        // 第一段：规则快筛（零成本）
        Optional<RouteIntent> ruleHit = ruleMatcher.match(userMessage);
        if (ruleHit.isPresent()) {
            return finish(new RouteDecision(ruleHit.get(), 1.0f, userMessage,
                    RouteDecision.RouteSource.RULE, elapsed(start)), userMessage);
        }

        // 第二段：缓存命中（相同问题不重复调分类器）
        Optional<RouteDecision> cached = readCache(userMessage);
        if (cached.isPresent()) {
            return finish(new RouteDecision(cached.get().intent(), cached.get().confidence(),
                    cached.get().rewrittenQuery(), RouteDecision.RouteSource.CACHE, elapsed(start)), userMessage);
        }

        // 第三段：LLM 改写 + 分类（一次调用）
        try {
            LlmRouteResult result = routerChatClient.prompt()
                    .user(u -> u.text("""
                                    【对话历史】
                                    {history}
                                    【最新输入】
                                    {query}
                                    """)
                            .param("history", recentHistory(conversationId))
                            .param("query", userMessage))
                    .call()
                    .entity(LlmRouteResult.class);

            RouteIntent intent = RouteIntent.valueOf(result.intent().trim().toUpperCase());
            RouteDecision decision = new RouteDecision(intent, result.confidence(),
                    result.rewrittenQuery(), RouteDecision.RouteSource.LLM, elapsed(start));

            if (decision.confidence() >= CONFIDENCE_THRESHOLD) {
                return finish(decision, userMessage);
            }
            log.info("路由置信度不足 {}，降级为知识库兜底: query={}, confidence={}",
                    CONFIDENCE_THRESHOLD, userMessage, decision.confidence());
        } catch (Exception e) {
            // 分类失败（含非法 intent、JSON 解析失败、LLM 超时）一律兜底
            log.warn("LLM 路由失败，降级为知识库兜底: query={}, 原因: {}", userMessage, e.getMessage());
        }

        // 兜底：走知识库链路（有 RAG + 来源引用），保证用户总能得到回答
        return finish(new RouteDecision(RouteIntent.KNOWLEDGE, 0f, userMessage,
                RouteDecision.RouteSource.FALLBACK, elapsed(start)), userMessage);
    }

    /** 从共享 ChatMemory 读取最近几轮作为改写依据，三个 Agent 会话历史互通 */
    private String recentHistory(String conversationId) {
        try {
            List<Message> messages = chatMemory.get(conversationId);
            if (messages == null || messages.isEmpty()) {
                return "（无）";
            }
            StringBuilder sb = new StringBuilder();
            messages.stream()
                    .skip(Math.max(0, messages.size() - 6))
                    .forEach(m -> sb.append(m.getMessageType() == MessageType.USER ? "用户: " : "助手: ")
                            .append(m.getText()).append('\n'));
            return sb.toString();
        } catch (Exception e) {
            return "（无）";
        }
    }

    private Optional<RouteDecision> readCache(String userMessage) {
        try {
            String json = redisTemplate.opsForValue().get(cacheKey(userMessage));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, RouteDecision.class));
        } catch (Exception e) {
            return Optional.empty();   // 缓存异常不影响主流程
        }
    }

    private RouteDecision finish(RouteDecision decision, String original) {
        if (decision.source() == RouteDecision.RouteSource.LLM) {
            writeCache(original, decision);
        }
        log.info("路由决策: 输入[{}] -> 改写[{}] -> 意图[{}] 置信度[{}] 来源[{}] 耗时[{}ms]",
                original, decision.rewrittenQuery(), decision.intent(),
                decision.confidence(), decision.source(), decision.costMs());
        return decision;
    }

    private void writeCache(String original, RouteDecision decision) {
        try {
            RouteDecision cached = new RouteDecision(decision.intent(), decision.confidence(),
                    decision.rewrittenQuery(), RouteDecision.RouteSource.CACHE, 0);
            redisTemplate.opsForValue().set(cacheKey(original),
                    objectMapper.writeValueAsString(cached), CACHE_TTL);
        } catch (Exception e) {
            log.debug("路由缓存写入失败: {}", e.getMessage());
        }
    }

    private String cacheKey(String userMessage) {
        return CACHE_KEY_PREFIX + DigestUtils.md5DigestAsHex(
                userMessage.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
