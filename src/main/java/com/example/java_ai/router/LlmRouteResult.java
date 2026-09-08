package com.example.java_ai.router;

/**
 * LLM 分类器的结构化输出。
 * 关键设计：查询改写与意图分类合并为一次调用，延迟减半
 * （替代原 HistoryAwareQueryTransformer 的独立改写调用）
 */
public record LlmRouteResult(
        String intent,          // CHITCHAT / DEVICE / ORDER / KNOWLEDGE
        float confidence,
        String rewrittenQuery   // 结合历史改写后的独立查询
) {}
