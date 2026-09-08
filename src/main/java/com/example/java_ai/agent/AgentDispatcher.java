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

    public AgentDispatcher(List<Agent> agentBeans) {
        for (Agent agent : agentBeans) {
            agents.put(agent.supportIntent(), agent);
        }
        log.info("已注册 Agent: {}", agents.keySet());
    }

    public Agent dispatch(RouteDecision decision) {
        Agent agent = agents.get(decision.intent());
        if (agent == null) {
            // 双保险：ORDER/DEVICE Agent 未实现前，兜底到知识库 Agent（有 RAG + 来源引用）
            log.info("意图 {} 无对应 Agent，降级为知识库 Agent", decision.intent());
            agent = agents.get(RouteIntent.KNOWLEDGE);
        }
        return agent;
    }
}
