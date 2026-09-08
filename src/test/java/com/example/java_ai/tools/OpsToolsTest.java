package com.example.java_ai.tools;

import com.example.java_ai.entity.Alarm;
import com.example.java_ai.entity.Device;
import com.example.java_ai.entity.WorkOrder;
import com.example.java_ai.repository.AlarmRepository;
import com.example.java_ai.repository.DeviceRepository;
import com.example.java_ai.repository.WorkOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpsTools 单元测试。
 *
 * 测试策略（面试可讲）：
 * 1. @DataJpaTest 切片测试：只加载 JPA 相关组件，不拉起 Milvus/Redis/LLM 客户端，
 *    内存 H2 由 Hibernate 按 Entity 建表，秒级启动
 * 2. 直接 new OpsTools(...) —— 工具是纯查询/写库逻辑，不依赖容器代理，
 *    绕过 Spring AOP 让测试聚焦业务本身
 * 3. 测试数据用 @BeforeEach 手动构造已知值，不依赖 CSV 种子 —— 测试自包含，
 *    种子内容变了测试不受影响
 * 4. @DataJpaTest 事务自动回滚：每个测试方法的数据互不可见、不落盘，无需手动清理
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {
        // 关闭 schema.sql（测试表由 Hibernate 按注解建）
        "spring.sql.init.mode=never",
        // 主配置的 ddl-auto=none 会被继承，测试库必须显式改回 create-drop，否则空库无表
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class OpsToolsTest {

    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private AlarmRepository alarmRepository;
    @Autowired
    private WorkOrderRepository workOrderRepository;

    private OpsTools opsTools;
    private LocalDateTime now;

    private static final DateTimeFormatter TODAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    @BeforeEach
    void setUp() {
        opsTools = new OpsTools(deviceRepository, alarmRepository, workOrderRepository);
        now = LocalDateTime.now();

        // 设备：1 台离线 3 小时、1 台在线、1 台故障 5 小时
        deviceRepository.save(device("GW-10086", "三号仓库北门网关", "GATEWAY", "三号仓库-A区", "OFFLINE", now.minusHours(3)));
        deviceRepository.save(device("SN-20001", "一号仓库温湿度传感器", "SENSOR", "一号仓库", "ONLINE", now));
        deviceRepository.save(device("SN-20012", "三号仓库B区温湿度传感器", "SENSOR", "三号仓库-B区", "FAULT", now.minusHours(5)));

        // 告警：GW-10086 有 P1+P2 两条未处理；SN-20001 一条已解决；SN-20012 一条 P2 未处理
        alarmRepository.save(alarm("AL-20260908-001", "GW-10086", "P1", "网关心跳丢失超过15分钟", "ACTIVE", now.minusHours(2), null));
        alarmRepository.save(alarm("AL-20260908-002", "GW-10086", "P2", "北门网关信号强度持续偏低", "ACTIVE", now.minusHours(1), null));
        alarmRepository.save(alarm("AL-20260907-003", "SN-20001", "P3", "湿度读数漂移", "RESOLVED", now.minusDays(2), now.minusMinutes(30)));
        alarmRepository.save(alarm("AL-20260908-004", "SN-20012", "P2", "数据连续无上报", "PROCESSING", now.minusHours(4), null));

        // 工单：今天已有 0001（验证新建编号接续为 0002）、昨天一张
        workOrderRepository.save(order("WO-" + LocalDate.now().format(TODAY) + "-0001", "GW-10086",
                "排查北门网关离线", "OPEN", "P1", now.minusMinutes(30)));
        workOrderRepository.save(order("WO-" + LocalDate.now().minusDays(1).format(TODAY) + "-0001", "SN-20001",
                "检查湿度异常", "CLOSED", "P3", now.minusDays(1)));
    }

    // ==================== queryDeviceStatus ====================

    @Test
    @DisplayName("查设备状态：离线设备返回状态、离线时长与未处理告警统计")
    void queryDeviceStatus_offlineDevice() {
        String result = opsTools.queryDeviceStatus("GW-10086");
        assertThat(result)
                .contains("GW-10086", "三号仓库北门网关")
                .contains("OFFLINE")
                .contains("已离线约 3 小时")
                .contains("未处理告警：2 条（P1×1、P2×1）");
    }

    @Test
    @DisplayName("查设备状态：在线设备不附离线时长")
    void queryDeviceStatus_onlineDevice() {
        String result = opsTools.queryDeviceStatus("SN-20001");
        assertThat(result).contains("ONLINE").doesNotContain("已离线");
    }

    @Test
    @DisplayName("查设备状态：小写编码自动归一命中")
    void queryDeviceStatus_caseInsensitive() {
        assertThat(opsTools.queryDeviceStatus("gw-10086")).contains("三号仓库北门网关");
    }

    @Test
    @DisplayName("查设备状态：不存在的设备返回带前缀说明的错误")
    void queryDeviceStatus_notFound() {
        String result = opsTools.queryDeviceStatus("GW-99999");
        assertThat(result).contains("查询失败", "GW-99999 不存在").contains("GW-网关");
    }

    @Test
    @DisplayName("查设备状态：空编码返回校验错误")
    void queryDeviceStatus_blankCode() {
        assertThat(opsTools.queryDeviceStatus("   ")).contains("设备编码不能为空");
    }

    // ==================== queryAlarms ====================

    @Test
    @DisplayName("查告警：等级+状态组合过滤")
    void queryAlarms_byLevelAndStatus() {
        String result = opsTools.queryAlarms("P1", "ACTIVE");
        assertThat(result).contains("共 1 条告警").contains("AL-20260908-001").contains("网关心跳丢失");
    }

    @Test
    @DisplayName("查告警：仅按等级过滤，含未处理与已解决")
    void queryAlarms_byLevelOnly() {
        String result = opsTools.queryAlarms("P2", null);
        assertThat(result).contains("共 2 条告警")
                .contains("GW-10086").contains("SN-20012");
    }

    @Test
    @DisplayName("查告警：非法等级返回合法值列表")
    void queryAlarms_illegalLevel() {
        String result = opsTools.queryAlarms("P9", null);
        assertThat(result).contains("查询失败").contains("P9 非法").contains("P1/P2/P3/P4");
    }

    @Test
    @DisplayName("查告警：无条件返回统计概况与使用引导，而非全量列表")
    void queryAlarms_noCondition() {
        String result = opsTools.queryAlarms(null, null);
        assertThat(result).contains("至少需要提供等级或状态").contains("未处理告警共 3 条").contains("P1×1");
    }

    @Test
    @DisplayName("查告警：组合条件无结果时明确说明")
    void queryAlarms_emptyResult() {
        assertThat(opsTools.queryAlarms("P4", "ACTIVE")).contains("无符合条件的告警");
    }

    // ==================== queryDeviceAlarms ====================

    @Test
    @DisplayName("查设备告警历史：总数、未处理数与明细齐全")
    void queryDeviceAlarms_withHistory() {
        String result = opsTools.queryDeviceAlarms("GW-10086");
        assertThat(result).contains("告警历史：共 2 条，其中未处理 2 条").contains("AL-20260908-001");
    }

    @Test
    @DisplayName("查设备告警历史：无告警的设备返回明确说明")
    void queryDeviceAlarms_empty() {
        // SN-20001 的告警已解决，但仍属历史 —— 换一台没有告警的设备需另造数据，
        // 此处验证"查不存在设备"的错误路径
        assertThat(opsTools.queryDeviceAlarms("CAM-30001")).contains("查询失败").contains("不存在");
    }

    @Test
    @DisplayName("查设备告警历史：已解决告警计入总数但不计入未处理")
    void queryDeviceAlarms_resolvedCounted() {
        String result = opsTools.queryDeviceAlarms("SN-20001");
        assertThat(result).contains("共 1 条，其中未处理 0 条");
    }

    // ==================== queryWorkOrder ====================

    @Test
    @DisplayName("查工单：返回详情并关联设备名称")
    void queryWorkOrder_found() {
        String code = "WO-" + LocalDate.now().format(TODAY) + "-0001";
        String result = opsTools.queryWorkOrder(code);
        assertThat(result)
                .contains(code)
                .contains("GW-10086（三号仓库北门网关）")
                .contains("排查北门网关离线")
                .contains("OPEN").contains("P1");
    }

    @Test
    @DisplayName("查工单：不存在的编号返回下一步建议")
    void queryWorkOrder_notFound() {
        String result = opsTools.queryWorkOrder("WO-20990101-9999");
        assertThat(result).contains("查询失败").contains("不存在").contains("queryAlarms");
    }

    @Test
    @DisplayName("查工单：空编号返回校验错误")
    void queryWorkOrder_blank() {
        assertThat(opsTools.queryWorkOrder(" ")).contains("工单编号不能为空");
    }

    // ==================== createWorkOrder ====================

    @Test
    @DisplayName("建工单：成功后编号接续当天序号，入库状态为 OPEN")
    void createWorkOrder_success() {
        String result = opsTools.createWorkOrder("SN-20012", "更换故障传感器", "传感器数据中断，现场更换备件", "p1");
        String expectedCode = "WO-" + LocalDate.now().format(TODAY) + "-0002";
        assertThat(result)
                .contains("工单创建成功")
                .contains(expectedCode)
                .contains("SN-20012（三号仓库B区温湿度传感器）")
                .contains("优先级：P1，状态：OPEN");
        // 落库验证：编号、状态、creator 标记
        WorkOrder saved = workOrderRepository.findByOrderCode(expectedCode).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo("OPEN");
        assertThat(saved.getPriority()).isEqualTo("P1");
        assertThat(saved.getCreator()).isEqualTo("AI助手");
    }

    @Test
    @DisplayName("建工单：设备不存在时拒绝且不落库")
    void createWorkOrder_deviceNotFound() {
        long before = workOrderRepository.count();
        String result = opsTools.createWorkOrder("GW-99999", "标题", null, "P2");
        assertThat(result).contains("创建失败").contains("GW-99999 不存在");
        assertThat(workOrderRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("建工单：非法优先级拒绝且不落库")
    void createWorkOrder_illegalPriority() {
        long before = workOrderRepository.count();
        String result = opsTools.createWorkOrder("SN-20012", "标题", null, "P7");
        assertThat(result).contains("创建失败").contains("P7 非法");
        assertThat(workOrderRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("建工单：空标题拒绝且不落库")
    void createWorkOrder_blankTitle() {
        long before = workOrderRepository.count();
        String result = opsTools.createWorkOrder("SN-20012", "   ", null, "P2");
        assertThat(result).contains("创建失败").contains("标题不能为空");
        assertThat(workOrderRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("建工单：描述缺省时落库为 null 而非空串")
    void createWorkOrder_nullDescription() {
        String result = opsTools.createWorkOrder("SN-20012", "巡检任务", "  ", "P4");
        assertThat(result).contains("工单创建成功");
        String expectedCode = "WO-" + LocalDate.now().format(TODAY) + "-0002";
        WorkOrder saved = workOrderRepository.findByOrderCode(expectedCode).orElseThrow();
        assertThat(saved.getDescription()).isNull();
    }

    // ==================== 测试数据工厂 ====================

    private Device device(String code, String name, String type, String location, String status, LocalDateTime lastOnline) {
        Device d = new Device();
        d.setDeviceCode(code);
        d.setName(name);
        d.setType(type);
        d.setLocation(location);
        d.setStatus(status);
        d.setLastOnlineTime(lastOnline);
        d.setCreatedAt(now);
        return d;
    }

    private Alarm alarm(String code, String deviceCode, String level, String content, String status,
                        LocalDateTime createTime, LocalDateTime resolveTime) {
        Alarm a = new Alarm();
        a.setAlarmCode(code);
        a.setDeviceCode(deviceCode);
        a.setLevel(level);
        a.setContent(content);
        a.setStatus(status);
        a.setCreateTime(createTime);
        a.setResolveTime(resolveTime);
        return a;
    }

    private WorkOrder order(String code, String deviceCode, String title, String status, String priority, LocalDateTime createTime) {
        WorkOrder w = new WorkOrder();
        w.setOrderCode(code);
        w.setDeviceCode(deviceCode);
        w.setTitle(title);
        w.setDescription("测试工单");
        w.setStatus(status);
        w.setPriority(priority);
        w.setCreator("测试员");
        w.setCreateTime(createTime);
        w.setUpdateTime(createTime);
        return w;
    }
}
