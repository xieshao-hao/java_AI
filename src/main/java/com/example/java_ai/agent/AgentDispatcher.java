package com.example.java_ai.agent;

import com.example.java_ai.router.RouteDecision;
import com.example.java_ai.router.RouteIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AgentDispatcher.class);
    private final Map<RouteIntent, Agent> agents = new EnumMap<>(RouteIntent.class);

    /**
     * 注册所有 Agent 声明的意图。开闭原则的体现：新增 Agent 只需实现 Agent 接口并
     * 标注 @Component，本类零修改（Spring 自动注入容器中所有 Agent 实现为 List）。
     *
     * 意图冲突快速失败：两个 Agent 声明同一意图时立即抛异常终止启动，
     * 而不是静默覆盖 —— 编译器拦不住这种冲突，必须靠启动期校验兜住
     */
    public AgentDispatcher(List<Agent> agentBeans) {
        for (Agent agent : agentBeans) {
            for (RouteIntent intent : agent.supportIntents()) {
                Agent previous = agents.put(intent, agent);
                if (previous != null) {
                    throw new IllegalStateException("意图 " + intent + " 被多个 Agent 声明："
                            + previous.getClass().getSimpleName() + " 与 " + agent.getClass().getSimpleName());
                }
            }
        }
        log.info("已注册 Agent: {}", agents.entrySet().stream()
                .map(e -> e.getKey() + "->" + e.getValue().getClass().getSimpleName())
                .toList());
    }

    public Agent dispatch(RouteDecision decision) {
        Agent agent = agents.get(decision.intent());
        if (agent == null) {
            // 双保险兜底：理论上路由层已保证意图合法，此分支仅在"枚举新增意图但漏实现 Agent"时触发
            log.info("意图 {} 无对应 Agent，降级为知识库 Agent", decision.intent());
            agent = agents.get(RouteIntent.KNOWLEDGE);
        }
        return agent;
    }
}
