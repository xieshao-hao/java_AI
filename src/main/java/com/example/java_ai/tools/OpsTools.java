package com.example.java_ai.tools;

import com.example.java_ai.entity.Alarm;
import com.example.java_ai.entity.Device;
import com.example.java_ai.entity.WorkOrder;
import com.example.java_ai.repository.AlarmRepository;
import com.example.java_ai.repository.DeviceRepository;
import com.example.java_ai.repository.WorkOrderRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 运维工具集：设备 / 告警 / 工单的结构化查询与写操作，供 LLM Function-Calling 使用。
 *
 * 设计要点（面试可讲）：
 * 1. 返回值是给 LLM 读的中文摘要文本，不是原始 JSON —— 控制上下文体积、
 *    主动附带关联信息（如设备状态带未处理告警数），让模型下一轮可直接接话
 * 2. 所有入参校验失败都返回"错误描述 + 合法值 + 下一步建议"的文本，而不是抛异常
 *    —— 异常会中断 Agent 执行，而描述性错误让模型有能力自纠（ReAct 自纠的基础）
 * 3. 工单编号由服务端生成（WO-yyyyMMdd-NNNN），编号生成权不交给模型，
 *    避免模型编造编号；creator 固定 "AI助手" 标记写操作来源（权限拦截的预留字段）
 */
@Component
public class OpsTools {

    private static final Set<String> ALARM_LEVELS = Set.of("P1", "P2", "P3", "P4");
    private static final Set<String> ALARM_STATUSES = Set.of("ACTIVE", "PROCESSING", "RESOLVED");
    /** "未处理"的定义：待处理 + 处理中 */
    private static final List<String> UNRESOLVED_STATUSES = List.of("ACTIVE", "PROCESSING");
    /** 返回给 LLM 的明细条数上限，防止长列表撑爆上下文 */
    private static final int MAX_DETAIL_ROWS = 20;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final DeviceRepository deviceRepository;
    private final AlarmRepository alarmRepository;
    private final WorkOrderRepository workOrderRepository;

    public OpsTools(DeviceRepository deviceRepository,
                    AlarmRepository alarmRepository,
                    WorkOrderRepository workOrderRepository) {
        this.deviceRepository = deviceRepository;
        this.alarmRepository = alarmRepository;
        this.workOrderRepository = workOrderRepository;
    }

    // ==================== 设备 ====================

    @Tool(name = "queryDeviceStatus",
            description = "按设备编码查询设备当前状态、类型、位置、最后在线时间与未处理告警数量。设备编码形如 GW-10086")
    public String queryDeviceStatus(
            @ToolParam(description = "设备业务编码，如 GW-10086") String deviceCode) {
        String code = normalizeCode(deviceCode);
        if (code == null) {
            return "查询失败：设备编码不能为空，请向用户确认设备编码后重试";
        }
        Device device = deviceRepository.findByDeviceCode(code).orElse(null);
        if (device == null) {
            return "查询失败：设备 " + code + " 不存在。请确认编码是否正确"
                    + "（编码前缀：GW-网关、SN-传感器、CAM-摄像头、CTL-控制器）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("设备 ").append(device.getDeviceCode()).append("（").append(device.getName()).append("）\n")
          .append("类型：").append(device.getType()).append("，位置：").append(device.getLocation()).append("\n")
          .append("当前状态：").append(device.getStatus())
          .append("，最后在线：").append(TS.format(device.getLastOnlineTime()));
        if (!"ONLINE".equals(device.getStatus())) {
            sb.append("（").append(offlineDuration(device.getLastOnlineTime())).append("）");
        }
        sb.append("\n");
        List<Alarm> unresolved = alarmRepository
                .findByDeviceCodeAndStatusInOrderByCreateTimeDesc(code, UNRESOLVED_STATUSES);
        if (unresolved.isEmpty()) {
            sb.append("未处理告警：无");
        } else {
            sb.append("未处理告警：").append(unresolved.size()).append(" 条（").append(levelSummary(unresolved)).append("）");
        }
        return sb.toString();
    }

    // ==================== 告警 ====================

    @Tool(name = "queryAlarms",
            description = "按等级和/或状态查询告警列表。等级：P1紧急/P2严重/P3一般/P4提示；状态：ACTIVE待处理/PROCESSING处理中/RESOLVED已解决。两个条件至少提供一个")
    public String queryAlarms(
            @ToolParam(required = false, description = "告警等级：P1/P2/P3/P4") String level,
            @ToolParam(required = false, description = "告警状态：ACTIVE/PROCESSING/RESOLVED") String status) {
        String lv = normalizeCode(level);
        String st = normalizeCode(status);

        // 无任何条件：不返回全量列表（可能撑爆上下文），返回统计概况 + 使用引导
        if (lv == null && st == null) {
            List<Alarm> unresolved = alarmRepository.findByStatusInOrderByCreateTimeDesc(UNRESOLVED_STATUSES);
            return "查询失败：至少需要提供等级或状态之一。当前告警概况：\n"
                    + "未处理告警共 " + unresolved.size() + " 条"
                    + (unresolved.isEmpty() ? "" : "（按等级：" + levelSummary(unresolved) + "）") + "\n"
                    + "等级取值：P1/P2/P3/P4；状态取值：ACTIVE/PROCESSING/RESOLVED";
        }
        if (lv != null && !ALARM_LEVELS.contains(lv)) {
            return "查询失败：等级 " + lv + " 非法，合法值：P1/P2/P3/P4";
        }
        if (st != null && !ALARM_STATUSES.contains(st)) {
            return "查询失败：状态 " + st + " 非法，合法值：ACTIVE/PROCESSING/RESOLVED";
        }

        List<Alarm> alarms;
        if (lv != null && st != null) {
            alarms = alarmRepository.findByLevelAndStatusOrderByCreateTimeDesc(lv, st);
        } else if (lv != null) {
            alarms = alarmRepository.findByLevelOrderByCreateTimeDesc(lv);
        } else {
            alarms = alarmRepository.findByStatusOrderByCreateTimeDesc(st);
        }
        if (alarms.isEmpty()) {
            return "查询结果：无符合条件的告警（等级=" + orDash(lv) + "，状态=" + orDash(st) + "）";
        }
        return "查询结果：共 " + alarms.size() + " 条告警\n" + renderAlarms(alarms);
    }

    @Tool(name = "queryDeviceAlarms",
            description = "查询指定设备的全部告警历史（含已解决），返回未处理统计与告警明细")
    public String queryDeviceAlarms(
            @ToolParam(description = "设备业务编码，如 GW-10086") String deviceCode) {
        String code = normalizeCode(deviceCode);
        if (code == null) {
            return "查询失败：设备编码不能为空，请向用户确认设备编码后重试";
        }
        Device device = deviceRepository.findByDeviceCode(code).orElse(null);
        if (device == null) {
            return "查询失败：设备 " + code + " 不存在";
        }
        List<Alarm> all = alarmRepository.findByDeviceCodeOrderByCreateTimeDesc(code);
        if (all.isEmpty()) {
            return "设备 " + code + "（" + device.getName() + "）暂无告警记录";
        }
        long unresolved = all.stream().filter(a -> UNRESOLVED_STATUSES.contains(a.getStatus())).count();
        return "设备 " + code + "（" + device.getName() + "）告警历史：共 " + all.size()
                + " 条，其中未处理 " + unresolved + " 条\n" + renderAlarms(all);
    }

    // ==================== 工单 ====================

    @Tool(name = "queryWorkOrder",
            description = "按工单编号查询工单详情。工单编号形如 WO-20260908-0001，由系统生成")
    public String queryWorkOrder(
            @ToolParam(description = "工单编号，如 WO-20260908-0001") String orderCode) {
        String code = normalizeCode(orderCode);
        if (code == null) {
            return "查询失败：工单编号不能为空";
        }
        WorkOrder order = workOrderRepository.findByOrderCode(code).orElse(null);
        if (order == null) {
            return "查询失败：工单 " + code + " 不存在。可提示用户提供正确编号，或用 queryAlarms 查看是否有相关告警需要建单";
        }
        String deviceLine = deviceRepository.findByDeviceCode(order.getDeviceCode())
                .map(d -> order.getDeviceCode() + "（" + d.getName() + "）")
                .orElse(order.getDeviceCode());
        return "工单 " + order.getOrderCode() + "\n"
                + "设备：" + deviceLine + "\n"
                + "标题：" + order.getTitle() + "\n"
                + "描述：" + (order.getDescription() == null ? "无" : order.getDescription()) + "\n"
                + "状态：" + order.getStatus() + "，优先级：" + order.getPriority() + "，创建人：" + order.getCreator() + "\n"
                + "创建时间：" + TS.format(order.getCreateTime()) + "，最近更新：" + TS.format(order.getUpdateTime());
    }

    @Tool(name = "createWorkOrder",
            description = "为指定设备创建运维工单。设备编码必须已存在；优先级 P1/P2/P3/P4；工单编号由系统生成，无需提供。创建属于写操作，请在调用前与用户确认设备与标题信息")
    public String createWorkOrder(
            @ToolParam(description = "设备业务编码，必须已存在") String deviceCode,
            @ToolParam(description = "工单标题，简明扼要") String title,
            @ToolParam(required = false, description = "工单详细描述，可为空") String description,
            @ToolParam(description = "优先级：P1/P2/P3/P4") String priority) {
        String code = normalizeCode(deviceCode);
        String p = normalizeCode(priority);

        // 入参校验：每条失败都说明原因 + 合法值，让模型可自纠
        if (code == null) {
            return "创建失败：设备编码不能为空，请向用户确认要为哪台设备创建工单";
        }
        if (title == null || title.isBlank()) {
            return "创建失败：工单标题不能为空，请根据用户诉求拟定标题";
        }
        if (p == null || !ALARM_LEVELS.contains(p)) {
            return "创建失败：优先级 " + orDash(priority) + " 非法，合法值：P1/P2/P3/P4。可按告警等级对齐：紧急用 P1";
        }
        Device device = deviceRepository.findByDeviceCode(code).orElse(null);
        if (device == null) {
            return "创建失败：设备 " + code + " 不存在，不能为不存在的设备创建工单";
        }

        LocalDateTime now = LocalDateTime.now();
        WorkOrder order = new WorkOrder();
        order.setOrderCode(nextOrderCode());
        order.setDeviceCode(code);
        order.setTitle(title.trim());
        order.setDescription(description == null || description.isBlank() ? null : description.trim());
        order.setStatus("OPEN");
        order.setPriority(p);
        order.setCreator("AI助手");
        order.setCreateTime(now);
        order.setUpdateTime(now);
        workOrderRepository.save(order);

        return "工单创建成功\n"
                + "工单编号：" + order.getOrderCode() + "\n"
                + "设备：" + code + "（" + device.getName() + "）\n"
                + "标题：" + order.getTitle() + "\n"
                + "优先级：" + p + "，状态：OPEN\n"
                + "请将工单编号告知用户，便于后续查询";
    }

    /**
     * 生成当天下一个工单编号：WO-yyyyMMdd-NNNN。
     * 已知并发缺陷：findFirst + save 非原子，高并发会重号。
     * 单机演示可接受；生产应改用数据库序列或分布式锁（面试可讲）。
     */
    private String nextOrderCode() {
        String prefix = "WO-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        String seq = workOrderRepository.findFirstByOrderCodeStartingWithOrderByOrderCodeDesc(prefix)
                .map(w -> w.getOrderCode().substring(prefix.length()))
                .map(s -> String.format(Locale.ROOT, "%04d", Integer.parseInt(s) + 1))
                .orElse("0001");
        return prefix + seq;
    }

    // ==================== 渲染辅助 ====================

    /** 告警明细列表（超过 MAX_DETAIL_ROWS 条截断，防上下文溢出） */
    private String renderAlarms(List<Alarm> alarms) {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(alarms.size(), MAX_DETAIL_ROWS);
        for (int i = 0; i < shown; i++) {
            Alarm a = alarms.get(i);
            sb.append(i + 1).append(". [").append(a.getLevel()).append("][").append(a.getStatus()).append("] ")
              .append(a.getAlarmCode()).append(" ").append(a.getDeviceCode()).append("：")
              .append(a.getContent()).append("（").append(TS.format(a.getCreateTime())).append("）\n");
        }
        if (alarms.size() > shown) {
            sb.append("...（其余 ").append(alarms.size() - shown).append(" 条略）");
        }
        return sb.toString().stripTrailing();
    }

    /** 按等级聚合计数，输出形如 "P1×2、P2×1" */
    private String levelSummary(List<Alarm> alarms) {
        return alarms.stream()
                .collect(Collectors.groupingBy(Alarm::getLevel, Collectors.counting()))
                .entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(e -> e.getKey() + "×" + e.getValue())
                .collect(Collectors.joining("、"));
    }

    /** 离线时长的可读化：分钟 → 小时 → 天 */
    private String offlineDuration(LocalDateTime lastOnline) {
        long minutes = Duration.between(lastOnline, LocalDateTime.now()).toMinutes();
        if (minutes < 1) {
            return "刚刚在线";
        }
        if (minutes < 60) {
            return "已离线约 " + minutes + " 分钟";
        }
        if (minutes < 60L * 48) {
            return "已离线约 " + minutes / 60 + " 小时";
        }
        return "已离线约 " + minutes / (60L * 24) + " 天";
    }

    /** 编码归一：去空白、统一大写；空串返回 null */
    private String normalizeCode(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim().toUpperCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
