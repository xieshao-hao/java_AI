package com.example.java_ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 工单表实体。
 * orderCode 由系统生成（WO-yyyyMMdd-NNNN），LLM 创建工单时只提供业务字段，
 * 编号生成权收归服务端，避免模型编造编号。
 */
@Entity
@Table(name = "work_order")
@Getter
@Setter
@NoArgsConstructor
public class WorkOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务编码：WO-yyyyMMdd-NNNN（服务端生成） */
    @Column(name = "order_code", nullable = false, unique = true, length = 32)
    private String orderCode;

    @Column(name = "device_code", nullable = false, length = 32)
    private String deviceCode;

    @Column(name = "title", nullable = false, length = 64)
    private String title;

    @Column(name = "description", length = 500)
    private String description;

    /** 状态：OPEN(待处理)/IN_PROGRESS(进行中)/COMPLETED(已完成)/CLOSED(已关闭) */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 优先级：P1/P2/P3/P4（与告警等级对齐） */
    @Column(name = "priority", nullable = false, length = 8)
    private String priority;

    /** 创建人（权限拦截的预留字段） */
    @Column(name = "creator", nullable = false, length = 32)
    private String creator;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;
}
