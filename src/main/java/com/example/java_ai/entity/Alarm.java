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
 * 告警表实体。与设备是弱关联（device_code 字符串，无外键）。
 * resolveTime 为 null 表示未解决 —— "未处理告警"是最高频查询维度。
 */
@Entity
@Table(name = "alarm")
@Getter
@Setter
@NoArgsConstructor
public class Alarm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务编码：AL-yyyyMMdd-NNN */
    @Column(name = "alarm_code", nullable = false, unique = true, length = 32)
    private String alarmCode;

    @Column(name = "device_code", nullable = false, length = 32)
    private String deviceCode;

    /** 等级：P1(紧急)/P2(严重)/P3(一般)/P4(提示) */
    @Column(name = "level", nullable = false, length = 8)
    private String level;

    @Column(name = "content", nullable = false, length = 255)
    private String content;

    /** 状态：ACTIVE(待处理)/PROCESSING(处理中)/RESOLVED(已解决) */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "resolve_time")
    private LocalDateTime resolveTime;
}
