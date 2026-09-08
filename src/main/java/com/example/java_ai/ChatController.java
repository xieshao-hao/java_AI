package com.example.java_ai;

import com.example.java_ai.agent.Agent;
import com.example.java_ai.agent.AgentContext;
import com.example.java_ai.agent.AgentDispatcher;
import com.example.java_ai.router.IntentRouter;
import com.example.java_ai.router.RouteDecision;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ChatController {

    private final IntentRouter intentRouter;
    private final AgentDispatcher agentDispatcher;
    private final ToolCallTracker toolCallTracker;

    public ChatController(IntentRouter intentRouter, AgentDispatcher agentDispatcher,
                          ToolCallTracker toolCallTracker) {
        this.intentRouter = intentRouter;
        this.agentDispatcher = agentDispatcher;
        this.toolCallTracker = toolCallTracker;
    }

    @GetMapping("/chat")
    public String chat(@RequestParam("msg") String msg,
                       @RequestParam(value = "conversationId", defaultValue = "default") String conversationId) {
        RouteDecision decision = intentRouter.route(msg, conversationId);
        Agent agent = agentDispatcher.dispatch(decision);
        return agent.handle(new AgentContext(conversationId, decision))
                .collectList()
                .map(chunks -> String.join("", chunks))
                .block();
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestParam("msg") String msg,
                                   @RequestParam(value = "conversationId", defaultValue = "default") String conversationId) {
        return Flux.defer(() -> {
            // 路由（同步：规则 → 缓存 → LLM 分类）→ 按意图分发 Agent
            RouteDecision decision = intentRouter.route(msg, conversationId);
            Agent agent = agentDispatcher.dispatch(decision);
            Flux<String> content = agent.handle(new AgentContext(conversationId, decision))
                    .cache();
            // 仅使用工具的 Agent 才需要合并工具事件流
            if (!agent.usesToolTracker()) {
                return content;
            }
            Flux<String> toolEvents = toolCallTracker.open(conversationId)
                    .takeUntilOther(content.ignoreElements())
                    // 无论正常完成、出错还是用户中断，都回收 sink，防止内存泄漏
                    .doFinally(signal -> toolCallTracker.close(conversationId));
            return Flux.merge(content, toolEvents);
        });
    }
}
