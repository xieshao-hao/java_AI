package com.example.java_ai;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Component
public class ToolCallTracker {

    private final Map<String, Sinks.Many<String>> sinks = new ConcurrentHashMap<>();

    public Flux<String> open(String conversationId) {
        Sinks.Many<String> sink = Sinks.many().replay().all();
        sinks.put(conversationId, sink);
        return sink.asFlux();
    }

    public void emit(String conversationId, String toolName, String args) {
        Sinks.Many<String> sink = sinks.get(conversationId);
        if (sink != null) {
            sink.tryEmitNext("🔧 正在调用工具：" + toolName
                    + (args == null || args.isEmpty() ? "" : "（" + args + "）"));
        }
    }

    /** 来源收集 Advisor 检索到知识库文档时，把去重后的文件名作为来源事件推送给前端 */
    public void emitSources(String conversationId, List<String> fileNames) {
        Sinks.Many<String> sink = sinks.get(conversationId);
        if (sink != null) {
            sink.tryEmitNext("📚 参考来源：" + String.join("、", fileNames));
        }
    }

    /** 对话流结束时回收 sink，防止 sinks Map 无限增长导致内存泄漏 */
    public void close(String conversationId) {
        Sinks.Many<String> sink = sinks.remove(conversationId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }
}