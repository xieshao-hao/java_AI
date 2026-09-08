package com.example.java_ai.router;

public enum RouteIntent {
    CHITCHAT,   // 闲聊问候，无业务语义
    DEVICE,     // 设备状态查询、告警检索
    ORDER,      // 工单创建、查询、售后
    KNOWLEDGE   // 运维知识、文档内容咨询
}
