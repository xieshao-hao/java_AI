package com.example.java_ai.router;

public record RouteDecision(
        RouteIntent intent,
        float confidence,
        String rewrittenQuery,   // 改写后的独立完整查询（消解指代后）
        RouteSource source,      // 决策来源，M4 接 Langfuse 时的 Trace 数据
        long costMs              // 路由耗时
) {
    public enum RouteSource { RULE, CACHE, LLM, FALLBACK }

    public static RouteDecision of(RouteIntent intent, float confidence,
                                   String rewrittenQuery, RouteSource source) {
        return new RouteDecision(intent, confidence, rewrittenQuery, source, 0);
    }
}
