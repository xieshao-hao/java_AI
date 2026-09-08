package com.example.java_ai.agent;

import com.example.java_ai.router.RouteIntent;
import com.example.java_ai.tools.OpsTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

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
 * 4. ReAct 循环控制 —— Spring AI 1.0.x 内部工具执行无轮数上限，通过自定义
 *    LoopGuardToolCallingManager 做「轮数上限 + 死循环检测 + 强制终止」两级拦截：
 *    每次请求创建 ReActGuardState 经 toolContext 传入守卫（按请求 opt-in，其它链路透传），
 *    强制终止异常在此转为兜底回复，保证循环必然终止
 */
@Component
public class OpsAgent implements Agent {

    /** 强制终止后的兜底回复：不再调模型，直接给用户明确的引导话术 */
    private static final String GUARD_FALLBACK_REPLY = """
            抱歉，本次任务的工具调用连续多轮未能完成，系统已强制终止工具执行以保障服务安全。
            请换个问法，或补充更明确的设备编码、告警编号后再试。""";

    private final ChatClient chatClient;

    public OpsAgent(ChatClient.Builder builder, ChatMemory chatMemory, OpsTools opsTools) {
        this.chatClient = builder
                .defaultSystem("""
                        你是 IoT 运维助手，通过调用工具帮运维人员完成设备查询、告警检索与工单处理。
                        工作规范：
                        1. 回答必须基于工具返回的真实数据，严禁编造设备状态、告警内容或工单编号；
                        2. 创建工单时：若用户消息中已包含设备编码、标题与优先级三项信息，直接调用工具创建，无需再次确认；仅当信息缺失时，一次性追问缺失项；用户明确确认后必须立即调用工具，严禁再次追问确认；编号由系统生成，你无需提供；
                        3. 只有当 createWorkOrder 工具返回了真实工单编号，才能告知用户"创建成功"并展示该编号；严禁在未调用工具的情况下声称已创建、已提交或展示任何工单详情；
                        4. 工具返回"查询失败/创建失败"时，把原因和引导建议转述给用户，帮助用户补充信息；
                        5. 用中文简洁回答，多条数据分点列出，关键信息（编码、状态、优先级）完整呈现。
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
                // 请求级循环控制状态：随 toolContext 进入每轮工具执行链路，请求结束即回收
                .toolContext(Map.of(ReActGuardState.TOOL_CONTEXT_KEY,
                        ReActGuardState.create(ctx.conversationId())))
                .stream()
                .content()
                // 强制终止兜底：不再调模型，直接输出固定引导话术
                .onErrorResume(ReActTerminatedException.class,
                        ex -> Flux.just(GUARD_FALLBACK_REPLY));
    }
}
