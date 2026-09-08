package com.example.java_ai.agent;

import com.example.java_ai.router.RouteIntent;
import reactor.core.publisher.Flux;

import java.util.List;

public interface Agent {
    /**
     * 声明本 Agent 处理的意图集合。
     * 支持多意图：OpsAgent 同时处理 DEVICE 与 ORDER —— 设备/告警/工单是同一条业务链，
     * 拆成两个 Agent 反而无法处理「先查告警、再建单」这类跨意图请求
     */
    List<RouteIntent> supportIntents();
    Flux<String> handle(AgentContext ctx);

    /** 是否使用 ToolCallTracker（决定 Controller 是否合并工具事件流） */
    default boolean usesToolTracker() {
        return false;
    }
}
