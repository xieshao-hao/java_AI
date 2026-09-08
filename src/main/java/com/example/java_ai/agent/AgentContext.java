package com.example.java_ai.agent;

import com.example.java_ai.router.RouteDecision;

public record AgentContext(String conversationId, RouteDecision decision) {
    /** Agent 内部应使用改写后的查询，而非用户原文 */
    public String effectiveQuery() {
        return decision.rewrittenQuery();
    }
}
