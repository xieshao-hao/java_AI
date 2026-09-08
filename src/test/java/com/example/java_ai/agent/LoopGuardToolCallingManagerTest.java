package com.example.java_ai.agent;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * ReAct 循环控制守卫单元测试：纯 Mockito 验证拦截逻辑，不依赖 LLM 与 Spring 容器。
 *
 * 覆盖五条控制路径：
 * 1. 非受控链路（无守卫状态）透传 —— 知识库/闲聊行为不变
 * 2. 首轮正常放行 —— 合法工具链不受影响
 * 3. 相同指纹重复 → 软拦截（注入终止指令，不执行工具）
 * 4. 软拦截后仍调工具 → 硬终止异常
 * 5. 轮数超上限 → 硬终止异常
 */
@ExtendWith(MockitoExtension.class)
class LoopGuardToolCallingManagerTest {

    @Mock
    private ToolCallingManager delegate;

    private LoopGuardToolCallingManager guard;

    @BeforeEach
    void setUp() {
        guard = new LoopGuardToolCallingManager(delegate);
    }

    // ==================== 构造辅助 ====================

    private Prompt promptWithState(ReActGuardState state) {
        ToolCallingChatOptions options = DefaultToolCallingChatOptions.builder()
                .toolContext(ReActGuardState.TOOL_CONTEXT_KEY, state)
                .build();
        return new Prompt(List.of(new UserMessage("查一下 GW-10086")), options);
    }

    private Prompt promptWithoutState() {
        return new Prompt(List.of(new UserMessage("随便聊聊")),
                DefaultToolCallingChatOptions.builder().build());
    }

    private ChatResponse chatResponse(AssistantMessage.ToolCall... toolCalls) {
        AssistantMessage message = new AssistantMessage("", Map.of(), List.of(toolCalls));
        return new ChatResponse(List.of(new Generation(message)));
    }

    private AssistantMessage.ToolCall toolCall(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }

    private ToolExecutionResult delegateResult() {
        return ToolExecutionResult.builder()
                .conversationHistory(List.of(new UserMessage("工具已执行")))
                .build();
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("非受控链路（toolContext 无守卫状态）直接透传给默认实现")
    void passthroughWhenNoGuardState() {
        ToolExecutionResult stubbed = delegateResult(); 
        when(delegate.executeToolCalls(any(), any())).thenReturn(stubbed);

        ToolExecutionResult result = guard.executeToolCalls(
                promptWithoutState(), chatResponse(toolCall("t1", "queryAlarms", "{\"level\":\"P1\"}")));

        assertThat(result).isSameAs(stubbed);
        verify(delegate, times(1)).executeToolCalls(any(), any());
    }

    @Test
    @DisplayName("首轮工具调用正常放行并记录指纹")
    void firstRoundPassesThrough() {
        when(delegate.executeToolCalls(any(), any())).thenReturn(delegateResult());
        ReActGuardState state = ReActGuardState.create("conv-1");

        guard.executeToolCalls(promptWithState(state),
                chatResponse(toolCall("t1", "queryAlarms", "{\"level\":\"P1\"}")));

        verify(delegate, times(1)).executeToolCalls(any(), any());
    }

    @Test
    @DisplayName("不同参数的调用不算死循环，连续两轮均放行")
    void differentFingerprintPassesRepeatedly() {
        when(delegate.executeToolCalls(any(), any())).thenReturn(delegateResult());
        ReActGuardState state = ReActGuardState.create("conv-2");

        guard.executeToolCalls(promptWithState(state),
                chatResponse(toolCall("t1", "queryAlarms", "{\"level\":\"P1\"}")));
        guard.executeToolCalls(promptWithState(state),
                chatResponse(toolCall("t2", "queryAlarms", "{\"level\":\"P2\"}")));

        verify(delegate, times(2)).executeToolCalls(any(), any());
    }

    @Test
    @DisplayName("相同工具+相同参数重复调用 → 软拦截：不执行工具，注入终止指令")
    void repeatedFingerprintSoftStops() {
        when(delegate.executeToolCalls(any(), any())).thenReturn(delegateResult());
        ReActGuardState state = ReActGuardState.create("conv-3");
        AssistantMessage.ToolCall repeatedCall =
                toolCall("t1", "createWorkOrder", "{\"deviceCode\":\"GW-10086\",\"title\":\"x\"}");

        // 第一轮放行
        guard.executeToolCalls(promptWithState(state), chatResponse(repeatedCall));
        verify(delegate, times(1)).executeToolCalls(any(), any());

        // 第二轮相同指纹 → 软拦截
        ToolExecutionResult result = guard.executeToolCalls(promptWithState(state), chatResponse(repeatedCall));

        // 委托只被调用过第一轮那次，重复调用未执行
        verify(delegate, times(1)).executeToolCalls(any(), any());
        verifyNoMoreInteractions(delegate);

        // 注入的历史：最后一条是 ToolResponseMessage，内容为终止指令
        List<Message> history = result.conversationHistory();
        Message last = history.get(history.size() - 1);
        assertThat(last).isInstanceOf(ToolResponseMessage.class);
        assertThat(((ToolResponseMessage) last).getResponses())
                .hasSize(1)
                .first()
                .satisfies(r -> {
                    assertThat(r.name()).isEqualTo("createWorkOrder");
                    assertThat(r.responseData()).contains("系统强制拦截");
                });
        assertThat(state.isStopInjected()).isTrue();
    }

    @Test
    @DisplayName("软拦截后模型仍请求工具 → 硬终止异常，循环必然结束")
    void toolCallAfterSoftStopHardTerminates() {
        when(delegate.executeToolCalls(any(), any())).thenReturn(delegateResult());
        ReActGuardState state = ReActGuardState.create("conv-4");

        guard.executeToolCalls(promptWithState(state),
                chatResponse(toolCall("t1", "queryAlarms", "{\"level\":\"P1\"}")));
        guard.executeToolCalls(promptWithState(state),
                chatResponse(toolCall("t1", "queryAlarms", "{\"level\":\"P1\"}"))); // 软拦截

        assertThatThrownBy(() -> guard.executeToolCalls(promptWithState(state),
                chatResponse(toolCall("t2", "queryDeviceAlarms", "{\"deviceCode\":\"GW-10086\"}"))))
                .isInstanceOf(ReActTerminatedException.class)
                .hasMessageContaining("conv-4")
                .hasMessageContaining("软拦截后");
    }

    @Test
    @DisplayName("轮数超过上限 → 硬终止异常")
    void exceedingMaxRoundsHardTerminates() {
        when(delegate.executeToolCalls(any(), any())).thenReturn(delegateResult());
        ReActGuardState state = ReActGuardState.create("conv-5");

        // 每轮参数不同，只触发轮数限制；MAX_ROUNDS 轮内全部放行
        for (int i = 1; i <= ReActGuardState.MAX_ROUNDS; i++) {
            guard.executeToolCalls(promptWithState(state),
                    chatResponse(toolCall("t" + i, "queryAlarms", "{\"level\":\"P" + i + "\"}")));
        }
        verify(delegate, times(ReActGuardState.MAX_ROUNDS)).executeToolCalls(any(), any());

        assertThatThrownBy(() -> guard.executeToolCalls(promptWithState(state),
                chatResponse(toolCall("t-x", "queryAlarms", "{\"level\":\"PX\"}"))))
                .isInstanceOf(ReActTerminatedException.class)
                .hasMessageContaining("上限")
                .hasMessageContaining(String.valueOf(ReActGuardState.MAX_ROUNDS));
    }

    @Test
    @DisplayName("chatResponse 未请求任何工具时透传（交给默认实现抛出既有异常语义）")
    void passthroughWhenNoToolCallRequested() {
        when(delegate.executeToolCalls(any(), any())).thenReturn(delegateResult());
        ReActGuardState state = ReActGuardState.create("conv-6");

        guard.executeToolCalls(promptWithState(state),
                new ChatResponse(List.of(new Generation(new AssistantMessage("不需要工具")))));

        verify(delegate, times(1)).executeToolCalls(any(), any());
    }
}
