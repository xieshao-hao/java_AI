package com.example.java_ai.router;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RouterConfig {

    /** 意图分类器独立成链：与业务 Agent 隔离，温度 0 保证分类稳定 */
    @Bean
    ChatClient routerChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("""
                        你是物联网运维系统的意图分类器。结合对话历史，完成两件事：
                        1. 将用户最新输入改写为不依赖上下文的独立完整查询（消解"它/这个/上面说的"等指代）
                        2. 将改写后的查询分类为以下意图之一：
                           CHITCHAT  - 闲聊问候，与设备、工单、运维知识无关
                           DEVICE    - 设备状态查询、告警检索
                           ORDER     - 工单创建、工单查询、售后处理
                           KNOWLEDGE - 运维知识、故障处理方案、文档内容咨询
                        confidence 为你对分类结果的置信度（0.0~1.0），不确定时给出低分。
                        只输出 JSON，格式：{"intent":"...","confidence":0.0,"rewrittenQuery":"..."}
                        """)
                .defaultOptions(ChatOptions.builder().temperature(0.0).build())
                .build();
    }
}
