package com.example.java_ai;

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
}