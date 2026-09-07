package com.example.java_ai;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 模拟电商业务数据层：内存订单表 + 物流轨迹。
 * 真实场景下这一层对接订单中台、物流网关等外部系统，工具层代码无需变化。
 */
@Component
public class MockOrderRepository {

    /** 订单信息 */
    public record OrderInfo(String orderNo, String userId, String product, double amount,
                            String status, String logisticsNo, LocalDateTime createdAt) {
    }

    /** 物流轨迹节点 */
    public record LogisticsNode(String time, String description) {
    }

    /** 订单状态常量 */
    public static final String STATUS_PENDING_SHIPMENT = "待发货";
    public static final String STATUS_SHIPPED = "已发货";
    public static final String STATUS_COMPLETED = "已完成";
    public static final String STATUS_REFUNDING = "退款中";
    public static final String STATUS_CANCELLED = "已取消";

    private final Map<String, OrderInfo> orders = new ConcurrentHashMap<>();
    private final Map<String, List<LogisticsNode>> logistics = new ConcurrentHashMap<>();

    public MockOrderRepository() {
        // 预置演示数据：覆盖不同订单状态，便于演示各类业务场景
        orders.put("A10001", new OrderInfo("A10001", "u001", "无线蓝牙耳机", 299.00,
                STATUS_SHIPPED, "SF1234567890", LocalDateTime.now().minusDays(3)));
        orders.put("A10002", new OrderInfo("A10002", "u001", "机械键盘", 459.00,
                STATUS_PENDING_SHIPMENT, null, LocalDateTime.now().minusDays(1)));
        orders.put("A10003", new OrderInfo("A10003", "u002", "27英寸4K显示器", 1899.00,
                STATUS_COMPLETED, "SF9876543210", LocalDateTime.now().minusDays(15)));
        orders.put("A10004", new OrderInfo("A10004", "u002", "USB-C 扩展坞", 129.00,
                STATUS_REFUNDING, null, LocalDateTime.now().minusDays(5)));

        logistics.put("SF1234567890", List.of(
                new LogisticsNode("两天前 09:12", "快件已揽收，发货地：广东深圳"),
                new LogisticsNode("两天前 21:40", "快件已到达深圳转运中心，即将发往杭州"),
                new LogisticsNode("昨天 15:05", "快件已到达杭州转运中心"),
                new LogisticsNode("今天 08:30", "快件正在派送途中，派送员：张师傅 138****5678")));
        logistics.put("SF9876543210", List.of(
                new LogisticsNode("14 天前 10:00", "快件已揽收，发货地：上海"),
                new LogisticsNode("13 天前 18:22", "快件运输中，已到达南京转运中心"),
                new LogisticsNode("12 天前 11:47", "快件派送中"),
                new LogisticsNode("12 天前 16:03", "快件已签收，签收人：本人")));
    }

    /** 按订单号查订单 */
    public OrderInfo findByOrderNo(String orderNo) {
        return orders.get(orderNo);
    }

    /** 按用户 ID 查订单列表（按下单时间倒序） */
    public List<OrderInfo> findByUserId(String userId) {
        return orders.values().stream()
                .filter(o -> o.userId().equals(userId))
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .toList();
    }

    /** 按物流单号查物流轨迹 */
    public List<LogisticsNode> findLogistics(String logisticsNo) {
        return logistics.get(logisticsNo);
    }

    /** 更新订单状态 */
    public void updateStatus(String orderNo, String newStatus) {
        OrderInfo o = orders.get(orderNo);
        if (o != null) {
            orders.put(orderNo, new OrderInfo(o.orderNo(), o.userId(), o.product(), o.amount(),
                    newStatus, o.logisticsNo(), o.createdAt()));
        }
    }
}
