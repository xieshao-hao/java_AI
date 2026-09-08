package com.example.java_ai.agent;

import com.example.java_ai.router.RouteIntent;
import reactor.core.publisher.Flux;

public interface Agent {
    RouteIntent supportIntent();
    Flux<String> handle(AgentContext ctx);

    /** 是否使用 ToolCallTracker（决定 Controller 是否合并工具事件流） */
    default boolean usesToolTracker() {
        return false;
    }
}
