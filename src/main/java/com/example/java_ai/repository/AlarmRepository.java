package com.example.java_ai.repository;

import com.example.java_ai.entity.Alarm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlarmRepository extends JpaRepository<Alarm, Long> {

    /** 查某设备的告警历史（按时间倒序） */
    List<Alarm> findByDeviceCodeOrderByCreateTimeDesc(String deviceCode);

    /** 查某设备指定状态的告警（如未处理告警） */
    List<Alarm> findByDeviceCodeAndStatusOrderByCreateTimeDesc(String deviceCode, String status);

    /** 查某设备多个状态的告警（"未处理" = ACTIVE + PROCESSING） */
    List<Alarm> findByDeviceCodeAndStatusInOrderByCreateTimeDesc(String deviceCode, List<String> statuses);

    /** 按等级查告警（如所有 P1 告警） */
    List<Alarm> findByLevelOrderByCreateTimeDesc(String level);

    /** 按等级 + 状态查告警（如"未处理的 P1"，组合维度最高频） */
    List<Alarm> findByLevelAndStatusOrderByCreateTimeDesc(String level, String status);

    /** 按状态查告警（如所有待处理） */
    List<Alarm> findByStatusOrderByCreateTimeDesc(String status);

    /** 按多个状态查告警（全局统计未处理告警用） */
    List<Alarm> findByStatusInOrderByCreateTimeDesc(List<String> statuses);
}
