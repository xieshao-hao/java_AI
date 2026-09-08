package com.example.java_ai.agent;

/**
 * ReAct 循环控制强制终止异常。
 *
 * 在 LoopGuardToolCallingManager 中抛出（软拦截后模型仍坚持调工具 / 轮数超上限），
 * 由 OpsAgent 捕获后转为面向用户的兜底回复，保证循环必然终止。
 */
public class ReActTerminatedException extends RuntimeException {

    private final String conversationId;

    public ReActTerminatedException(String conversationId, String reason) {
        super("ReAct 循环已强制终止（conversation=" + conversationId + "）：" + reason);
        this.conversationId = conversationId;
    }

    public String conversationId() {
        return conversationId;
    }
}
