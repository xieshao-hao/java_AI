package com.example.java_ai.config;

import com.example.java_ai.entity.Alarm;
import com.example.java_ai.entity.Device;
import com.example.java_ai.entity.WorkOrder;
import com.example.java_ai.repository.AlarmRepository;
import com.example.java_ai.repository.DeviceRepository;
import com.example.java_ai.repository.WorkOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV 种子数据导入器：应用启动时执行一次，表非空则跳过（幂等）。
 *
 * 设计要点：
 * 1. 不用 spring.sql.init 的 data.sql —— CSV 逐行解析走 JPA，字段类型由 Java 代码
 *    显式控制（时间解析、空值处理），比 SQL 方式可调试性好
 * 2. ONLINE 设备的 last_online_time 统一取导入时刻 —— 种子数据是静态的，
 *    但"在线设备"的最后在线时间必须始终新鲜，否则演示时"3小时未上报"变成"20天未上报"
 * 3. 种子数据是演示与评测的"剧本"：刻意包含离线设备、P1 告警、待处理工单等边界数据
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final DeviceRepository deviceRepository;
    private final AlarmRepository alarmRepository;
    private final WorkOrderRepository workOrderRepository;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (deviceRepository.count() > 0) {
            log.info("业务数据已存在，跳过种子数据导入");
            return;
        }
        int devices = importDevices();
        int alarms = importAlarms();
        int orders = importWorkOrders();
        log.info("种子数据导入完成：设备 {} 台，告警 {} 条，工单 {} 单", devices, alarms, orders);
    }

    private int importDevices() throws IOException {
        LocalDateTime now = LocalDateTime.now();
        List<Device> devices = new ArrayList<>();
        for (String[] row : readCsv("data/device.csv")) {
            Device d = new Device();
            d.setDeviceCode(row[0]);
            d.setName(row[1]);
            d.setType(row[2]);
            d.setLocation(row[3]);
            d.setStatus(row[4]);
            // 在线设备的最后在线时间取导入时刻；离线/故障/维护中保留 CSV 中的历史时间
            d.setLastOnlineTime("ONLINE".equals(row[4]) ? now : LocalDateTime.parse(row[5]));
            d.setCreatedAt(now);
            devices.add(d);
        }
        deviceRepository.saveAll(devices);
        return devices.size();
    }

    private int importAlarms() throws IOException {
        List<Alarm> alarms = new ArrayList<>();
        for (String[] row : readCsv("data/alarm.csv")) {
            Alarm a = new Alarm();
            a.setAlarmCode(row[0]);
            a.setDeviceCode(row[1]);
            a.setLevel(row[2]);
            a.setContent(row[3]);
            a.setStatus(row[4]);
            a.setCreateTime(LocalDateTime.parse(row[5]));
            a.setResolveTime(row[6].isBlank() ? null : LocalDateTime.parse(row[6]));
            alarms.add(a);
        }
        alarmRepository.saveAll(alarms);
        return alarms.size();
    }

    private int importWorkOrders() throws IOException {
        List<WorkOrder> orders = new ArrayList<>();
        for (String[] row : readCsv("data/work_order.csv")) {
            WorkOrder w = new WorkOrder();
            w.setOrderCode(row[0]);
            w.setDeviceCode(row[1]);
            w.setTitle(row[2]);
            w.setDescription(row[3]);
            w.setStatus(row[4]);
            w.setPriority(row[5]);
            w.setCreator(row[6]);
            w.setCreateTime(LocalDateTime.parse(row[7]));
            w.setUpdateTime(row[8].isBlank() ? LocalDateTime.parse(row[7]) : LocalDateTime.parse(row[8]));
            orders.add(w);
        }
        workOrderRepository.saveAll(orders);
        return orders.size();
    }

    /** 读取 classpath 下的 CSV：跳过表头，逗号分隔（种子数据约定字段内不使用英文逗号） */
    private List<String[]> readCsv(String location) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(location).getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines()
                    .skip(1)
                    .filter(line -> !line.isBlank())
                    .map(line -> line.split(",", -1))
                    .toList();
        }
    }
}
