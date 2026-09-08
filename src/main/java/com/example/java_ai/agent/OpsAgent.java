package com.example.java_ai.agent;

import com.example.java_ai.router.RouteIntent;
import com.example.java_ai.tools.OpsTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 运维 Agent：处理 DEVICE（设备/告警查询）与 ORDER（工单创建/查询）两个意图。
 *
 * 设计要点：
 * 1. 单 Agent 挂双意图 —— 设备、告警、工单是同一条业务链（看到告警 → 查设备 → 建工单），
 *    工具全挂在同一个 ChatClient 上，由模型通过 Function-Calling 自主选择，
 *    拆成两个 Agent 反而无法处理「先查告警、再建单」的跨意图请求
 * 2. 记忆用 MessageChatMemoryAdvisor（历史注入消息列表）—— 本链路无 RAG advisor，
 *    不存在 KbAgent 那样的消息重建问题；与 KbAgent 共享同一个 ChatMemory Bean，
 *    用户从闲聊/知识库切到查工单，会话历史互通
 * 3. 无 RAG —— 结构化数据查询走数据库，绝不进向量库
 */
@Component
public class OpsAgent implements Agent {

    private final ChatClient chatClient;

    public OpsAgent(ChatClient.Builder builder, ChatMemory chatMemory, OpsTools opsTools) {
        this.chatClient = builder
                .defaultSystem("""
                        你是 IoT 运维助手，通过调用工具帮运维人员完成设备查询、告警检索与工单处理。
                        工作规范：
                        1. 回答必须基于工具返回的真实数据，严禁编造设备状态、告警内容或工单编号；
                        2. 创建工单前先与用户确认设备编码、标题与优先级，编号由系统生成，你无需提供；
                        3. 工具返回"查询失败/创建失败"时，把原因和引导建议转述给用户，帮助用户补充信息；
                        4. 用中文简洁回答，多条数据分点列出，关键信息（编码、状态、优先级）完整呈现。
                        """)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(opsTools)
                .build();
    }

    @Override
    public List<RouteIntent> supportIntents() {
        return List.of(RouteIntent.DEVICE, RouteIntent.ORDER);
    }

    @Override
    public Flux<String> handle(AgentContext ctx) {
        return chatClient.prompt()
                .user(ctx.effectiveQuery())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, ctx.conversationId()))
                .stream()
                .content();
    }
}
