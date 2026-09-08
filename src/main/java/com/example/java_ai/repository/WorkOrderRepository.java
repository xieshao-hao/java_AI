package com.example.java_ai.repository;

import com.example.java_ai.entity.WorkOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {

    /** 按业务编码查工单 */
    Optional<WorkOrder> findByOrderCode(String orderCode);

    /** 按状态查工单列表（如所有待处理工单） */
    List<WorkOrder> findByStatusOrderByCreateTimeDesc(String status);

    /** 查某设备的工单历史 */
    List<WorkOrder> findByDeviceCodeOrderByCreateTimeDesc(String deviceCode);

    /**
     * 生成新工单编号用：取今天前缀下最大的一个编号。
     * findFirstBy...StartingWith = WHERE order_code LIKE 'prefix%' ORDER BY ... LIMIT 1
     */
    Optional<WorkOrder> findFirstByOrderCodeStartingWithOrderByOrderCodeDesc(String prefix);
}
