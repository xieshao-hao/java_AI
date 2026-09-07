package com.example.java_ai;

import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ChatController {

    private final ChatClient chatClient;
    private final ToolCallTracker toolCallTracker;

    public ChatController(ChatClient chatClient, ToolCallTracker toolCallTracker) {
        this.chatClient = chatClient;
        this.toolCallTracker = toolCallTracker;
    }

    @GetMapping("/chat")
    public String chat(@RequestParam("msg") String msg,
                       @RequestParam(value = "conversationId", defaultValue = "default") String conversationId) {
        return chatClient.prompt()
                .user(msg)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestParam("msg") String msg,
                                   @RequestParam(value = "conversationId", defaultValue = "default") String conversationId) {
        Flux<String> content = chatClient.prompt()
                .user(msg)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .toolContext(Map.of("conversationId", conversationId))
                .stream()
                .content()
                .cache();
        Flux<String> toolEvents = toolCallTracker.open(conversationId)
                .takeUntilOther(content.ignoreElements())
                // 无论正常完成、出错还是用户中断，都回收 sink，防止内存泄漏
                .doFinally(signal -> toolCallTracker.close(conversationId));
        return Flux.merge(content, toolEvents);
    }
}