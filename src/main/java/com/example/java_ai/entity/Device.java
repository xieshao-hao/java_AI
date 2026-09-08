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
 * 设备表实体。
 * 注意：不使用 @Data —— 实体的 equals/hashCode 基于 ID 语义复杂，
 * Lombok @Data 会把所有字段纳入计算，在双向关联场景易出问题，@Getter/@Setter 足够。
 */
@Entity
@Table(name = "device")
@Getter
@Setter
@NoArgsConstructor
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务编码（GW-/SN-/CAM-/CTL- 前缀），用户与 LLM 直接引用 */
    @Column(name = "device_code", nullable = false, unique = true, length = 32)
    private String deviceCode;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /** 类型：GATEWAY/SENSOR/CAMERA/CONTROLLER */
    @Column(name = "type", nullable = false, length = 32)
    private String type;

    @Column(name = "location", nullable = false, length = 64)
    private String location;

    /** 状态：ONLINE/OFFLINE/FAULT/MAINTENANCE */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "last_online_time", nullable = false)
    private LocalDateTime lastOnlineTime;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
