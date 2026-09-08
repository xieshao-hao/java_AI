package com.example.java_ai.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * ReAct 循环控制的请求级状态：跟踪一次对话请求内的工具执行轮数与调用指纹。
 *
 * 生命周期：OpsAgent 发起请求时创建，经 ChatClient.toolContext 传入，
 * 由 LoopGuardToolCallingManager 在每轮工具执行前检查更新，请求结束随对象被 GC 回收。
 * 不落 Map/Redis，无跨请求共享状态 —— 从根上避免 ToolCallTracker 那类
 * 因状态残留导致的内存泄漏（React 链路中每轮调用的线程由 Reactor 保证顺序可见）。
 */
public final class ReActGuardState {

    /** toolContext 中的状态 key，LoopGuardToolCallingManager 据此识别受控链路 */
    public static final String TOOL_CONTEXT_KEY = "reActGuard";

    /**
     * 最大工具执行轮数。
     * 依据：业务上最长的合法链路是「查告警 → 查设备 → 创建工单」约 3 轮，
     * 取 6 = 2 倍冗余（待评测集跑出真实轮数分布后回填数据校准）。
     */
    public static final int MAX_ROUNDS = 6;

    private final String conversationId;
    /** 历史调用指纹（工具名 + 入参原文），用于死循环检测 */
    private final List<String> fingerprints = new ArrayList<>();
    private int rounds;
    /** 软拦截（终止指令注入）是否已执行过：之后模型再请求任何工具都视为失控 */
    private boolean stopInjected;

    private ReActGuardState(String conversationId) {
        this.conversationId = conversationId;
    }

    public static ReActGuardState create(String conversationId) {
        return new ReActGuardState(conversationId);
    }

    public String conversationId() {
        return conversationId;
    }

    /** 记录一轮并返回轮次（从 1 开始） */
    int nextRound() {
        return ++rounds;
    }

    int rounds() {
        return rounds;
    }

    boolean isStopInjected() {
        return stopInjected;
    }

    void markStopInjected() {
        this.stopInjected = true;
    }

    /** 在历史指纹中查找重复项，返回第一条重复指纹；无重复返回 null */
    String findRepeated(List<String> fps) {
        for (String fp : fps) {
            if (fingerprints.contains(fp)) {
                return fp;
            }
        }
        return null;
    }

    void record(List<String> fps) {
        fingerprints.addAll(fps);
    }
}
