package com.example.java_ai.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

/**
 * ReAct 循环控制守卫：替换框架默认的 ToolCallingManager，在每轮工具执行前做拦截判断。
 *
 * 背景（面试可讲）：Spring AI 1.0.x 的内部工具执行是"模型请求工具 → 执行 → 结果回喂 → 再请求"
 * 的无限循环，没有轮数上限（maxIterations 是 1.1 才加入的能力）。模型一旦陷入
 * 重复调用/自我博弈，就会空转烧 Token 直至上下文爆炸。
 *
 * 两级拦截策略：
 * 1. 软拦截（死循环检测）：本轮请求中出现与历史完全相同的「工具名 + 入参」指纹，
 *    说明重复调用只会得到相同结果 —— 不执行工具，向模型注入终止指令，
 *    让模型基于已有结果自然收口（兜底回复的第一道防线，用户体验最好）。
 * 2. 硬终止（强制终止）：软拦截后模型仍请求工具（失控），或总轮数超过 MAX_ROUNDS，
 *    抛 ReActTerminatedException 中断执行，由 OpsAgent onErrorResume 输出兜底话术，
 *    保证循环必然终止。
 *
 * 状态传递：受控链路（OpsAgent）通过 ChatClient.toolContext 传入请求级 ReActGuardState；
 * 未携带状态的链路（知识库/闲聊）直接透传给默认实现，行为与框架完全一致 —— 守卫按请求 opt-in。
 */
@Component
public class LoopGuardToolCallingManager implements ToolCallingManager {

    private static final Logger log = LoggerFactory.getLogger(LoopGuardToolCallingManager.class);

    /** 软拦截时注入给模型的终止指令（代替真实工具结果） */
    static final String STOP_INSTRUCTION = """
            【系统强制拦截】检测到重复的工具调用，本轮调用未执行。
            重复调用相同工具、相同参数只会得到相同结果。请立即停止调用任何工具，
            直接基于已获取的工具结果回答用户；若关键信息缺失，用一句话向用户说明还需要什么信息。""";

    private final ToolCallingManager delegate;

    /** 生产装配：用框架默认实现做委托，保留 ToolCallbackResolver 与 Observation 语义 */
    @org.springframework.beans.factory.annotation.Autowired
    public LoopGuardToolCallingManager(ObjectProvider<ToolCallbackResolver> toolCallbackResolver,
                                       ObjectProvider<ObservationRegistry> observationRegistry) {
        DefaultToolCallingManager.Builder builder = DefaultToolCallingManager.builder();
        ToolCallbackResolver resolver = toolCallbackResolver.getIfAvailable();
        if (resolver != null) {
            builder.toolCallbackResolver(resolver);
        }
        ObservationRegistry registry = observationRegistry.getIfAvailable(() -> null);
        if (registry != null) {
            builder.observationRegistry(registry);
        }
        this.delegate = builder.build();
    }

    /** 测试装配：注入可 mock 的委托实现 */
    LoopGuardToolCallingManager(ToolCallingManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        ReActGuardState state = extractGuardState(prompt);
        // 未携带守卫状态的链路（知识库/闲聊等）直接透传，行为与框架默认实现一致
        if (state == null) {
            return delegate.executeToolCalls(prompt, chatResponse);
        }

        List<AssistantMessage.ToolCall> toolCalls = requestedToolCalls(chatResponse);
        if (toolCalls.isEmpty()) {
            return delegate.executeToolCalls(prompt, chatResponse);
        }

        // 硬终止其一：软拦截后模型仍请求工具 —— 失控，立即强杀
        if (state.isStopInjected()) {
            throw terminate(state, "软拦截后模型仍发起新的工具调用");
        }
        // 硬终止其二：总轮数超上限
        int round = state.nextRound();
        if (round > ReActGuardState.MAX_ROUNDS) {
            throw terminate(state, "工具执行轮数达到上限 " + ReActGuardState.MAX_ROUNDS);
        }

        // 软拦截：本轮出现与历史相同的调用指纹 → 注入终止指令，不执行工具
        String repeated = state.findRepeated(toolCalls.stream().map(this::fingerprint).toList());
        if (repeated != null) {
            state.markStopInjected();
            log.warn("ReAct 死循环软拦截 conversation={} round={} 指纹={}",
                    state.conversationId(), round, repeated);
            return stopResult(prompt, chatResponse, toolCalls);
        }

        state.record(toolCalls.stream().map(this::fingerprint).toList());
        log.info("ReAct 第 {}/{} 轮 conversation={} tools={}",
                round, ReActGuardState.MAX_ROUNDS, state.conversationId(),
                toolCalls.stream().map(AssistantMessage.ToolCall::name).toList());
        return delegate.executeToolCalls(prompt, chatResponse);
    }

    /** 从 prompt 选项的 toolContext 中提取守卫状态；不受控链路返回 null */
    private ReActGuardState extractGuardState(Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions options
                && options.getToolContext() != null
                && options.getToolContext().get(ReActGuardState.TOOL_CONTEXT_KEY)
                        instanceof ReActGuardState state) {
            return state;
        }
        return null;
    }

    private List<AssistantMessage.ToolCall> requestedToolCalls(ChatResponse chatResponse) {
        return chatResponse.getResults().stream()
                .filter(g -> g.getOutput().hasToolCalls())
                .findFirst()
                .map(g -> g.getOutput().getToolCalls())
                .orElse(List.of());
    }

    /** 调用指纹：工具名 + 入参原文（JSON 字符串 trim），同参数同工具即视为重复调用 */
    private String fingerprint(AssistantMessage.ToolCall toolCall) {
        return toolCall.name() + "?" + (toolCall.arguments() == null ? "" : toolCall.arguments().trim());
    }

    /**
     * 软拦截结果：不执行工具，构造「助手工具调用 + 合成终止指令」的消息历史回喂模型，
     * 让模型读到拦截说明后基于已有信息收口（结构与 DefaultToolCallingManager 的
     * 会话历史组装保持一致，确保后续轮次上下文合法）。
     */
    private ToolExecutionResult stopResult(Prompt prompt, ChatResponse chatResponse,
                                           List<AssistantMessage.ToolCall> toolCalls) {
        AssistantMessage assistantMessage = chatResponse.getResults().stream()
                .filter(g -> g.getOutput().hasToolCalls())
                .findFirst()
                .orElseThrow()
                .getOutput();
        List<ToolResponseMessage.ToolResponse> responses = toolCalls.stream()
                .map(tc -> new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), STOP_INSTRUCTION))
                .toList();
        List<Message> history = new ArrayList<>(prompt.getInstructions());
        history.add(assistantMessage);
        history.add(new ToolResponseMessage(responses, Map.of()));
        return ToolExecutionResult.builder().conversationHistory(history).build();
    }

    private ReActTerminatedException terminate(ReActGuardState state, String reason) {
        log.warn("ReAct 强制终止 conversation={} rounds={} 原因={}", state.conversationId(), state.rounds(), reason);
        return new ReActTerminatedException(state.conversationId(), reason);
    }
}
