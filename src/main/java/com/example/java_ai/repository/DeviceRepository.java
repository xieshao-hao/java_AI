package com.example.java_ai.repository;

import com.example.java_ai.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    /** 按业务编码查设备（工具层主查询路径） */
    Optional<Device> findByDeviceCode(String deviceCode);

    /** 按状态查设备列表，如查所有离线设备 */
    List<Device> findByStatus(String status);

    /** 按类型查设备列表 */
    List<Device> findByType(String type);

    boolean existsByDeviceCode(String deviceCode);
}
